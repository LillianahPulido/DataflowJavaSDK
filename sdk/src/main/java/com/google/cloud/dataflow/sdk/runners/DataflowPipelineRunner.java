/*
 * Copyright (C) 2015 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.cloud.dataflow.sdk.runners;

import static com.google.cloud.dataflow.sdk.util.StringUtils.approximateSimpleName;

import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.util.Joiner;
import com.google.api.services.dataflow.Dataflow;
import com.google.api.services.dataflow.model.DataflowPackage;
import com.google.api.services.dataflow.model.Job;
import com.google.api.services.dataflow.model.ListJobsResponse;
import com.google.cloud.dataflow.sdk.Pipeline;
import com.google.cloud.dataflow.sdk.PipelineResult.State;
import com.google.cloud.dataflow.sdk.annotations.Experimental;
import com.google.cloud.dataflow.sdk.coders.CannotProvideCoderException;
import com.google.cloud.dataflow.sdk.coders.Coder;
import com.google.cloud.dataflow.sdk.coders.CoderException;
import com.google.cloud.dataflow.sdk.io.PubsubIO;
import com.google.cloud.dataflow.sdk.io.Read;
import com.google.cloud.dataflow.sdk.io.UnboundedSource;
import com.google.cloud.dataflow.sdk.options.DataflowPipelineOptions;
import com.google.cloud.dataflow.sdk.options.PipelineOptions;
import com.google.cloud.dataflow.sdk.options.PipelineOptionsValidator;
import com.google.cloud.dataflow.sdk.runners.DataflowPipelineTranslator.JobSpecification;
import com.google.cloud.dataflow.sdk.runners.dataflow.BasicSerializableSourceFormat;
import com.google.cloud.dataflow.sdk.runners.dataflow.DataflowAggregatorTransforms;
import com.google.cloud.dataflow.sdk.transforms.Aggregator;
import com.google.cloud.dataflow.sdk.transforms.Combine;
import com.google.cloud.dataflow.sdk.transforms.Create;
import com.google.cloud.dataflow.sdk.transforms.DoFn;
import com.google.cloud.dataflow.sdk.transforms.GroupByKey;
import com.google.cloud.dataflow.sdk.transforms.PTransform;
import com.google.cloud.dataflow.sdk.transforms.ParDo;
import com.google.cloud.dataflow.sdk.transforms.SerializableFunction;
import com.google.cloud.dataflow.sdk.transforms.View;
import com.google.cloud.dataflow.sdk.transforms.WithKeys;
import com.google.cloud.dataflow.sdk.transforms.Write;
import com.google.cloud.dataflow.sdk.transforms.windowing.AfterPane;
import com.google.cloud.dataflow.sdk.transforms.windowing.GlobalWindows;
import com.google.cloud.dataflow.sdk.transforms.windowing.Repeatedly;
import com.google.cloud.dataflow.sdk.transforms.windowing.Window;
import com.google.cloud.dataflow.sdk.util.CoderUtils;
import com.google.cloud.dataflow.sdk.util.DataflowReleaseInfo;
import com.google.cloud.dataflow.sdk.util.IOChannelUtils;
import com.google.cloud.dataflow.sdk.util.InstanceBuilder;
import com.google.cloud.dataflow.sdk.util.MonitoringUtil;
import com.google.cloud.dataflow.sdk.util.PCollectionViews;
import com.google.cloud.dataflow.sdk.util.PathValidator;
import com.google.cloud.dataflow.sdk.util.PropertyNames;
import com.google.cloud.dataflow.sdk.util.StreamingPCollectionViewWriterFn;
import com.google.cloud.dataflow.sdk.util.Transport;
import com.google.cloud.dataflow.sdk.util.ValueWithRecordId;
import com.google.cloud.dataflow.sdk.util.WindowingStrategy;
import com.google.cloud.dataflow.sdk.values.KV;
import com.google.cloud.dataflow.sdk.values.PCollection;
import com.google.cloud.dataflow.sdk.values.PCollection.IsBounded;
import com.google.cloud.dataflow.sdk.values.PCollectionView;
import com.google.cloud.dataflow.sdk.values.PDone;
import com.google.cloud.dataflow.sdk.values.PInput;
import com.google.cloud.dataflow.sdk.values.POutput;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;

import org.joda.time.DateTimeUtils;
import org.joda.time.DateTimeZone;
import org.joda.time.format.DateTimeFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * A {@link PipelineRunner} that executes the operations in the
 * pipeline by first translating them to the Dataflow representation
 * using the {@link DataflowPipelineTranslator} and then submitting
 * them to a Dataflow service for execution.
 */
public class DataflowPipelineRunner extends PipelineRunner<DataflowPipelineJob> {
  private static final Logger LOG = LoggerFactory.getLogger(DataflowPipelineRunner.class);

  /** Provided configuration options. */
  private final DataflowPipelineOptions options;

  /** Client for the Dataflow service. This is used to actually submit jobs. */
  private final Dataflow dataflowClient;

  /** Translator for this DataflowPipelineRunner, based on options. */
  private final DataflowPipelineTranslator translator;

  /** Custom transforms implementations for running in streaming mode. */
  private final Map<Class<?>, Class<?>> streamingOverrides;

  /** A set of user defined functions to invoke at different points in execution. */
  private DataflowPipelineRunnerHooks hooks;

  // Environment version information
  private static final String ENVIRONMENT_MAJOR_VERSION = "3";

  /**
   * Construct a runner from the provided options.
   *
   * @param options Properties that configure the runner.
   * @return The newly created runner.
   */
  public static DataflowPipelineRunner fromOptions(PipelineOptions options) {
    // (Re-)register standard IO factories. Clobbers any prior credentials.
    IOChannelUtils.registerStandardIOFactories(options);

    DataflowPipelineOptions dataflowOptions =
        PipelineOptionsValidator.validate(DataflowPipelineOptions.class, options);
    ArrayList<String> missing = new ArrayList<>();

    if (dataflowOptions.getAppName() == null) {
      missing.add("appName");
    }
    if (missing.size() > 0) {
      throw new IllegalArgumentException(
          "Missing required values: " + Joiner.on(',').join(missing));
    }

    PathValidator validator = dataflowOptions.getPathValidator();
    if (dataflowOptions.getStagingLocation() != null) {
      validator.verifyPath(dataflowOptions.getStagingLocation());
    }
    if (dataflowOptions.getTempLocation() != null) {
      validator.verifyPath(dataflowOptions.getTempLocation());
    }
    if (Strings.isNullOrEmpty(dataflowOptions.getTempLocation())) {
      dataflowOptions.setTempLocation(dataflowOptions.getStagingLocation());
    } else if (Strings.isNullOrEmpty(dataflowOptions.getStagingLocation())) {
      try {
        dataflowOptions.setStagingLocation(
            IOChannelUtils.resolve(dataflowOptions.getTempLocation(), "staging"));
      } catch (IOException e) {
        throw new IllegalArgumentException("Unable to resolve PipelineOptions.stagingLocation "
            + "from PipelineOptions.tempLocation. Please set the staging location explicitly.", e);
      }
    }

    if (dataflowOptions.getFilesToStage() == null) {
      dataflowOptions.setFilesToStage(detectClassPathResourcesToStage(
          DataflowPipelineRunner.class.getClassLoader()));
      LOG.info("PipelineOptions.filesToStage was not specified. "
          + "Defaulting to files from the classpath: will stage {} files. "
          + "Enable logging at DEBUG level to see which files will be staged.",
          dataflowOptions.getFilesToStage().size());
      LOG.debug("Classpath elements: {}", dataflowOptions.getFilesToStage());
    }

    // Verify jobName according to service requirements.
    String jobName = dataflowOptions.getJobName().toLowerCase();
    Preconditions.checkArgument(
        jobName.matches("[a-z]([-a-z0-9]*[a-z0-9])?"),
        "JobName invalid; the name must consist of only the characters "
            + "[-a-z0-9], starting with a letter and ending with a letter "
            + "or number");

    return new DataflowPipelineRunner(dataflowOptions);
  }

  private DataflowPipelineRunner(DataflowPipelineOptions options) {
    this.options = options;
    this.dataflowClient = options.getDataflowClient();
    this.translator = DataflowPipelineTranslator.fromOptions(options);

    this.streamingOverrides = ImmutableMap.<Class<?>, Class<?>>builder()
        .put(Create.Values.class, StreamingCreate.class)
        .put(View.AsMap.class, StreamingViewAsMap.class)
        .put(View.AsMultimap.class, StreamingViewAsMultimap.class)
        .put(View.AsSingleton.class, StreamingViewAsSingleton.class)
        .put(View.AsIterable.class, StreamingViewAsIterable.class)
        .put(Write.Bound.class, StreamingWrite.class)
        .put(PubsubIO.Write.Bound.class, StreamingPubsubIOWrite.class)
        .put(Read.Unbounded.class, StreamingUnboundedRead.class)
        .build();
  }

  /**
   * Applies the given transform to the input. For transforms with customized definitions
   * for the Dataflow pipeline runner, the application is intercepted and modified here.
   */
  @Override
  public <OutputT extends POutput, InputT extends PInput> OutputT apply(
      PTransform<InputT, OutputT> transform, InputT input) {

    if (Combine.GroupedValues.class.equals(transform.getClass())
        || GroupByKey.class.equals(transform.getClass())) {

      // For both Dataflow runners (streaming and batch), GroupByKey and GroupedValues are
      // primitives. Returning a primitive output instead of the expanded definition
      // signals to the translator that translation is necessary.
      @SuppressWarnings("unchecked")
      PCollection<?> pc = (PCollection<?>) input;
      @SuppressWarnings("unchecked")
      OutputT outputT = (OutputT) PCollection.createPrimitiveOutputInternal(
          pc.getPipeline(),
          pc.getWindowingStrategy(),
          pc.isBounded());
      return outputT;

    } else if (options.isStreaming() && streamingOverrides.containsKey(transform.getClass())) {
      // It is the responsibility of whoever constructs streamingOverrides
      // to ensure this is type safe.
      @SuppressWarnings("unchecked")
      Class<PTransform<InputT, OutputT>> transformClass =
          (Class<PTransform<InputT, OutputT>>) transform.getClass();

      @SuppressWarnings("unchecked")
      Class<PTransform<InputT, OutputT>> customTransformClass =
          (Class<PTransform<InputT, OutputT>>) streamingOverrides.get(transform.getClass());

      PTransform<InputT, OutputT> customTransform =
          InstanceBuilder.ofType(customTransformClass)
          .withArg(transformClass, transform)
          .build();

      return Pipeline.applyTransform(input, customTransform);
    } else {
      return super.apply(transform, input);
    }
  }

  @Override
  public DataflowPipelineJob run(Pipeline pipeline) {
    LOG.info("Executing pipeline on the Dataflow Service, which will have billing implications "
        + "related to Google Compute Engine usage and other Google Cloud Services.");

    List<DataflowPackage> packages = options.getStager().stageFiles();
    JobSpecification jobSpecification = translator.translate(pipeline, packages);
    Job newJob = jobSpecification.getJob();

    // Set a unique client_request_id in the CreateJob request.
    // This is used to ensure idempotence of job creation across retried
    // attempts to create a job. Specifically, if the service returns a job with
    // a different client_request_id, it means the returned one is a different
    // job previously created with the same job name, and that the job creation
    // has been effectively rejected. The SDK should return
    // Error::Already_Exists to user in that case.
    int randomNum = new Random().nextInt(9000) + 1000;
    String requestId = DateTimeFormat.forPattern("YYYYMMddHHmmssmmm").withZone(DateTimeZone.UTC)
        .print(DateTimeUtils.currentTimeMillis()) + "_" + randomNum;
    newJob.setClientRequestId(requestId);

    String version = DataflowReleaseInfo.getReleaseInfo().getVersion();
    System.out.println("Dataflow SDK version: " + version);

    newJob.getEnvironment().setUserAgent(DataflowReleaseInfo.getReleaseInfo());
    // The Dataflow Service may write to the temporary directory directly, so
    // must be verified.
    DataflowPipelineOptions dataflowOptions = options.as(DataflowPipelineOptions.class);
    if (!Strings.isNullOrEmpty(options.getTempLocation())) {
      newJob.getEnvironment().setTempStoragePrefix(
          dataflowOptions.getPathValidator().verifyPath(options.getTempLocation()));
    }
    newJob.getEnvironment().setDataset(options.getTempDatasetId());
    newJob.getEnvironment().setClusterManagerApiService(
        options.getClusterManagerApi().getApiServiceName());
    newJob.getEnvironment().setExperiments(options.getExperiments());

    // Requirements about the service.
    Map<String, Object> environmentVersion = new HashMap<>();
    environmentVersion.put(PropertyNames.ENVIRONMENT_VERSION_MAJOR_KEY, ENVIRONMENT_MAJOR_VERSION);
    newJob.getEnvironment().setVersion(environmentVersion);
    // Default jobType is DATA_PARALLEL, which is for java batch.
    String jobType = "DATA_PARALLEL";

    if (options.isStreaming()) {
      jobType = "STREAMING";
    }
    environmentVersion.put(PropertyNames.ENVIRONMENT_VERSION_JOB_TYPE_KEY, jobType);

    if (hooks != null) {
      hooks.modifyEnvironmentBeforeSubmission(newJob.getEnvironment());
    }

    if (!Strings.isNullOrEmpty(options.getDataflowJobFile())) {
      try (PrintWriter printWriter = new PrintWriter(
          new File(options.getDataflowJobFile()))) {
        String workSpecJson = DataflowPipelineTranslator.jobToString(newJob);
        printWriter.print(workSpecJson);
        LOG.info("Printed workflow specification to {}", options.getDataflowJobFile());
      } catch (IllegalStateException ex) {
        LOG.warn("Cannot translate workflow spec to json for debug.");
      } catch (FileNotFoundException ex) {
        LOG.warn("Cannot create workflow spec output file.");
      }
    }

    String jobIdToUpdate = null;
    if (options.getUpdate()) {
      jobIdToUpdate = getJobIdFromName(options.getJobName());
      newJob.setTransformNameMapping(options.getTransformNameMapping());
    }
    Job jobResult;
    try {
      Dataflow.Projects.Jobs.Create createRequest =
          dataflowClient
              .projects()
              .jobs()
              .create(options.getProject(), newJob);
      if (jobIdToUpdate != null) {
        createRequest.setReplaceJobId(jobIdToUpdate);
      }
      jobResult = createRequest.execute();
    } catch (GoogleJsonResponseException e) {
        throw new RuntimeException("Failed to create a workflow job: "
            + (e.getDetails() != null ? e.getDetails().getMessage() : e), e);
    } catch (IOException e) {
      throw new RuntimeException("Failed to create a workflow job", e);
    }
    // If the service returned client request id, the SDK needs to compare it
    // with the original id generated in the request, if they are not the same
    // (i.e., the returned job is not created by this request), throw
    // Error::Already_Exists.
    if (jobResult.getClientRequestId() != null && !jobResult.getClientRequestId().isEmpty()
        && !jobResult.getClientRequestId().equals(requestId)) {
      // If updating a job.
      if (options.getUpdate()) {
        throw new RuntimeException(
            "The job named " + newJob.getName() + " with id: " + jobIdToUpdate
                + " has already been updated into job id: " + jobResult.getId()
                + " and cannot be updated again. ");
      } else {
        throw new RuntimeException("There is already an active job named " + newJob.getName()
            + " with id: " + jobResult.getId()
            + ". If you want to submit a second job, try again by setting a "
            + "different name using --jobName.");
      }
    }

    LOG.info("To access the Dataflow monitoring console, please navigate to {}",
        MonitoringUtil.getJobMonitoringPageURL(options.getProject(), jobResult.getId()));
    System.out.println("Submitted job: " + jobResult.getId());

    LOG.info("To cancel the job using the 'gcloud' tool, run:\n> {}",
        MonitoringUtil.getGcloudCancelCommand(options, jobResult.getId()));

    // Obtain all of the extractors from the PTransforms used in the pipeline so the
    // DataflowPipelineJob has access to them.
    AggregatorPipelineExtractor aggregatorExtractor = new AggregatorPipelineExtractor(pipeline);
    Map<Aggregator<?, ?>, Collection<PTransform<?, ?>>> aggregatorSteps =
        aggregatorExtractor.getAggregatorSteps();

    DataflowAggregatorTransforms aggregatorTransforms =
        new DataflowAggregatorTransforms(aggregatorSteps, jobSpecification.getStepNames());

    // Use a raw client for post-launch monitoring, as status calls may fail
    // regularly and need not be retried automatically.
    DataflowPipelineJob dataflowPipelineJob =
        new DataflowPipelineJob(options.getProject(), jobResult.getId(),
            Transport.newRawDataflowClient(options).build(), aggregatorTransforms);

    return dataflowPipelineJob;
  }

  /**
   * Returns the DataflowPipelineTranslator associated with this object.
   */
  public DataflowPipelineTranslator getTranslator() {
    return translator;
  }

  /**
   * Sets callbacks to invoke during execution see {@code DataflowPipelineRunnerHooks}.
   */
  @Experimental
  public void setHooks(DataflowPipelineRunnerHooks hooks) {
    this.hooks = hooks;
  }

  /////////////////////////////////////////////////////////////////////////////

  /**
   * Specialized (non-)implementation for {@link Write.Bound} for the Dataflow runner in streaming
   * mode.
   */
  private static class StreamingWrite<T> extends PTransform<PCollection<T>, PDone> {
    private static final long serialVersionUID = 0L;

    /**
     * Builds an instance of this class from the overridden transform.
     */
    public StreamingWrite(Write.Bound<T> transform) { }

    @Override
    public PDone apply(PCollection<T> input) {
      throw new UnsupportedOperationException(
          "The Write transform is not supported by the Dataflow streaming runner.");
    }

    @Override
    protected String getKindString() {
      return "StreamingWrite";
    }
  }

  /**
   * Specialized implementation for {@link PubsubIO.Write} for the Dataflow runner in streaming
   * mode.
   *
   * <p>For internal use only. Subject to change at any time.
   *
   * <p>Public so the {@link com.google.cloud.dataflow.sdk.runners.dataflow.PubsubIOTranslator}
   * can access.
   */
  public static class StreamingPubsubIOWrite<T> extends PTransform<PCollection<T>, PDone> {
    private static final long serialVersionUID = 0L;

    private final PubsubIO.Write.Bound<T> transform;

    /**
     * Builds an instance of this class from the overridden transform.
     */
    public StreamingPubsubIOWrite(PubsubIO.Write.Bound<T> transform) {
      this.transform = transform;
    }

    public PubsubIO.Write.Bound<T> getOverriddenTransform() {
      return transform;
    }

    @Override
    public PDone apply(PCollection<T> input) {
      return PDone.in(input.getPipeline());
    }

    @Override
    protected String getKindString() {
      return "StreamingPubsubIOWrite";
    }
  }

  /**
   * Specialized implementation for {@link Read.Unbounded} for the Dataflow runner in streaming
   * mode.
   *
   * <p> In particular, if an UnboundedSource requires deduplication, then features of WindmillSink
   * are leveraged to do the deduplication.
   */
  private static class StreamingUnboundedRead<T> extends PTransform<PInput, PCollection<T>> {
    private static final long serialVersionUID = 0L;

    private final UnboundedSource<T, ?> source;

    /**
     * Builds an instance of this class from the overridden transform.
     */
    @SuppressWarnings("unused") // used via reflection in apply()
    public StreamingUnboundedRead(Read.Unbounded<T> transform) {
      this.source = transform.getSource();;
    }

    @Override
    protected Coder<T> getDefaultOutputCoder() {
      return source.getDefaultOutputCoder();
    }

    @Override
    public final PCollection<T> apply(PInput input) {
      source.validate();

      if (source.requiresDeduping()) {
        return Pipeline.applyTransform(input, new ReadWithIds<T>(source))
            .apply(new Deduplicate<T>());
      } else {
        return Pipeline.applyTransform(input, new ReadWithIds<T>(source))
            .apply(ValueWithRecordId.<T>stripIds());
      }
    }

    /**
     * {@link PTransform} that reads {@code (record,recordId)} pairs from an
     * {@link UnboundedSource}.
     */
    private static class ReadWithIds<T>
        extends PTransform<PInput, PCollection<ValueWithRecordId<T>>> {
      private static final long serialVersionUID = 0L;
      private final UnboundedSource<T, ?> source;

      private ReadWithIds(UnboundedSource<T, ?> source) {
        this.source = source;
      }

      @Override
      public final PCollection<ValueWithRecordId<T>> apply(PInput input) {
        return PCollection.<ValueWithRecordId<T>>createPrimitiveOutputInternal(
            input.getPipeline(), WindowingStrategy.globalDefault(), IsBounded.UNBOUNDED);
      }

      @Override
      protected Coder<ValueWithRecordId<T>> getDefaultOutputCoder() {
        return ValueWithRecordId.ValueWithRecordIdCoder.of(source.getDefaultOutputCoder());
      }

      public UnboundedSource<T, ?> getSource() {
        return source;
      }
    }

    @Override
    public String getKindString() {
      return "Read(" + approximateSimpleName(source.getClass()) + ")";
    }

    static {
      DataflowPipelineTranslator.registerTransformTranslator(
          ReadWithIds.class, new ReadWithIdsTranslator());
    }

    private static class ReadWithIdsTranslator
        implements DataflowPipelineTranslator.TransformTranslator<ReadWithIds<?>> {
      @Override
      public void translate(ReadWithIds<?> transform,
          DataflowPipelineTranslator.TranslationContext context) {
        BasicSerializableSourceFormat.translateReadHelper(
            transform.getSource(), transform, context);
      }
    }
  }

  /**
   * Remove values with duplicate ids.
   */
  private static class Deduplicate<T>
      extends PTransform<PCollection<ValueWithRecordId<T>>, PCollection<T>> {
    private static final long serialVersionUID = 0L;
    // Use a finite set of keys to improve bundling.  Without this, the key space
    // will be the space of ids which is potentially very large, which results in much
    // more per-key overhead.
    private static final int NUM_RESHARD_KEYS = 10000;
    @Override
    public PCollection<T> apply(PCollection<ValueWithRecordId<T>> input) {
      return input
          .apply(WithKeys.of(new SerializableFunction<ValueWithRecordId<T>, Integer>() {
                    private static final long serialVersionUID = 0L;

                    @Override
                    public Integer apply(ValueWithRecordId<T> value) {
                      return Arrays.hashCode(value.getId()) % NUM_RESHARD_KEYS;
                    }
                  }))
          .apply(
              Window.<KV<Integer, ValueWithRecordId<T>>>into(new GlobalWindows())
                  .triggering(Repeatedly.forever(AfterPane.elementCountAtLeast(1)))
                  .discardingFiredPanes())
          // WindmillSink will dedup based on ids in ValueWithRecordId.
          .apply(GroupByKey.<Integer, ValueWithRecordId<T>>create())
          .apply(ParDo.named("StripIds").of(
              new DoFn<KV<Integer, Iterable<ValueWithRecordId<T>>>, T>() {
                private static final long serialVersionUID = 0L;

                @Override
                public void processElement(ProcessContext c) {
                  for (ValueWithRecordId<T> value : c.element().getValue()) {
                    c.output(value.getValue());
                  }
                }
              }));
    }
  }

  /**
   * Specialized implementation for {@link Create.Values} for the Dataflow runner in streaming mode.
   */
  private static class StreamingCreate<T> extends PTransform<PInput, PCollection<T>> {
    private static final long serialVersionUID = 0L;

    private final Create.Values<T> transform;

    /**
     * Builds an instance of this class from the overridden transform.
     */
    @SuppressWarnings("unused") // used via reflection in apply()
    public StreamingCreate(Create.Values<T> transform) {
      this.transform = transform;
    }

    /**
     * {@link DoFn} that outputs a single KV.of(null, null) kick off the {@link GroupByKey}
     * in the streaming create implementation.
     */
    private static class OutputNullKv extends DoFn<String, KV<Void, Void>> {
      private static final long serialVersionUID = 0;

      @Override
      public void processElement(DoFn<String, KV<Void, Void>>.ProcessContext c) throws Exception {
        c.output(KV.of((Void) null, (Void) null));
      }
    }

    /**
     * A {@link DoFn} which outputs the specified elements by first encoding them to bytes using
     * the specified {@link Coder} so that they are serialized as part of the {@link DoFn} but
     * need not implement {@code Serializable}.
     */
    private static class OutputElements<T> extends DoFn<Object, T> {
      private static final long serialVersionUID = 0;

      private final Coder<T> coder;
      private final List<byte[]> encodedElements;

      public OutputElements(Iterable<T> elems, Coder<T> coder) {
        this.coder = coder;
        this.encodedElements = new ArrayList<>();
        for (T t : elems) {
          try {
            encodedElements.add(CoderUtils.encodeToByteArray(coder, t));
          } catch (CoderException e) {
            throw new IllegalArgumentException("Unable to encode value " + t
                + " with coder " + coder, e);
          }
        }
      }

      @Override
      public void processElement(ProcessContext c) throws IOException {
        for (byte[] encodedElement : encodedElements) {
          c.output(CoderUtils.decodeFromByteArray(coder, encodedElement));
        }
      }
    }

    @Override
    public PCollection<T> apply(PInput input) {
      try {
        Coder<T> coder = transform.getDefaultOutputCoder(input);
        return Pipeline.applyTransform(
            input, PubsubIO.Read.named("StartingSignal").subscription("_starting_signal/"))
            .apply(ParDo.of(new OutputNullKv()))
            .apply("GlobalSingleton", Window.<KV<Void, Void>>into(new GlobalWindows())
                .triggering(AfterPane.elementCountAtLeast(1))
                .discardingFiredPanes())
                .apply(GroupByKey.<Void, Void>create())
                .apply(Window.<KV<Void, Iterable<Void>>>into(new GlobalWindows()))
                .apply(ParDo.of(new OutputElements<>(transform.getElements(), coder)))
                .setCoder(coder);
      } catch (CannotProvideCoderException e) {
        throw new IllegalArgumentException("Unable to infer a coder and no Coder was specified. "
            + "Please set a coder by invoking Create.withCoder() explicitly.", e);
      }
    }

    @Override
    protected String getKindString() {
      return "StreamingCreate";
    }
  }

  /**
   * Specialized implementation for {@link View.AsMap} for the Dataflow runner in streaming mode.
   */
  private static class StreamingViewAsMap<K, V>
      extends PTransform<PCollection<KV<K, V>>, PCollectionView<Map<K, V>>> {
    private static final long serialVersionUID = 0L;

    @SuppressWarnings("unused") // used via reflection in apply()
    public StreamingViewAsMap(View.AsMap<K, V> transform) { }

    @Override
    public PCollectionView<Map<K, V>> apply(PCollection<KV<K, V>> input) {
      PCollectionView<Map<K, V>> view =
          PCollectionViews.mapView(
              input.getPipeline(),
              input.getWindowingStrategy(),
              input.getCoder());

      return input
          .apply(Combine.globally(new View.Concatenate<KV<K, V>>()).withoutDefaults())
          .apply(ParDo.of(StreamingPCollectionViewWriterFn.create(view, input.getCoder())))
          .apply(View.CreatePCollectionView.<KV<K, V>, Map<K, V>>of(view));
    }

    @Override
    protected String getKindString() {
      return "StreamingViewAsMap";
    }
  }

  /**
   * Specialized expansion for {@link View.AsMultimap} for the Dataflow runner in streaming mode.
   */
  private static class StreamingViewAsMultimap<K, V>
    extends PTransform<PCollection<KV<K, V>>, PCollectionView<Map<K, Iterable<V>>>> {
    private static final long serialVersionUID = 0L;

    /**
     * Builds an instance of this class from the overridden transform.
     */
    @SuppressWarnings("unused") // used via reflection in apply()
    public StreamingViewAsMultimap(View.AsMultimap<K, V> transform) { }

    @Override
    public PCollectionView<Map<K, Iterable<V>>> apply(PCollection<KV<K, V>> input) {
      PCollectionView<Map<K, Iterable<V>>> view =
          PCollectionViews.multimapView(
              input.getPipeline(),
              input.getWindowingStrategy(),
              input.getCoder());

      return input
          .apply(Combine.globally(new View.Concatenate<KV<K, V>>()).withoutDefaults())
          .apply(ParDo.of(StreamingPCollectionViewWriterFn.create(view, input.getCoder())))
          .apply(View.CreatePCollectionView.<KV<K, V>, Map<K, Iterable<V>>>of(view));
    }

    @Override
    protected String getKindString() {
      return "StreamingViewAsMultimap";
    }
  }

  /**
   * Specialized implementation for {@link View.AsIterable} for the Dataflow runner in streaming
   * mode.
   */
  private static class StreamingViewAsIterable<T>
      extends PTransform<PCollection<T>, PCollectionView<Iterable<T>>> {
    private static final long serialVersionUID = 0L;

    /**
     * Builds an instance of this class from the overridden transform.
     */
    @SuppressWarnings("unused") // used via reflection in apply()
    public StreamingViewAsIterable(View.AsIterable<T> transform) { }

    @Override
    public PCollectionView<Iterable<T>> apply(PCollection<T> input) {
      // Using Combine.globally(...).asSingletonView() allows automatic propagation of
      // the CombineFn's default value as the default value of the SingletonView.
      //
      // safe covariant cast List<T> -> Iterable<T>
      // not expressible in java, even with unchecked casts
      @SuppressWarnings({"rawtypes", "unchecked"})
      Combine.GloballyAsSingletonView<T, Iterable<T>> concatAndView =
      (Combine.GloballyAsSingletonView)
      Combine.globally(new View.Concatenate<T>()).asSingletonView();
      return input.apply(concatAndView);
    }

    @Override
    protected String getKindString() {
      return "StreamingViewAsIterable";
    }
  }

  private static class WrapAsList<T> extends DoFn<T, List<T>> {
    private static final long serialVersionUID = 0;

    @Override
    public void processElement(ProcessContext c) {
      c.output(Arrays.asList(c.element()));
    }
  }

  /**
   * Specialized expansion for {@link View.AsSingleton} for the Dataflow runner in streaming mode.
   */
  private static class StreamingViewAsSingleton<T>
      extends PTransform<PCollection<T>, PCollectionView<T>> {
    private static final long serialVersionUID = 0L;

    /**
     * Builds an instance of this class from the overridden transform.
     */
    @SuppressWarnings("unused") // used via reflection in apply()
    public StreamingViewAsSingleton(View.AsSingleton<T> transform) { }

    @Override
    public PCollectionView<T> apply(PCollection<T> input) {
      PCollectionView<T> view = PCollectionViews.singletonView(
          input.getPipeline(),
          input.getWindowingStrategy(),
          false, // no default
          null,  // unused default value
          input.getCoder());
      return input
          .apply(ParDo.of(new WrapAsList<T>()))
          .apply(ParDo.of(StreamingPCollectionViewWriterFn.create(view, input.getCoder())))
          .apply(View.CreatePCollectionView.<T, T>of(view));
    }

    @Override
    protected String getKindString() {
      return "StreamingViewAsSingleton";
    }
  }

  @Override
  public String toString() {
    return "DataflowPipelineRunner#" + options.getJobName();
  }

  /**
   * Attempts to detect all the resources the class loader has access to. This does not recurse
   * to class loader parents stopping it from pulling in resources from the system class loader.
   *
   * @param classLoader The URLClassLoader to use to detect resources to stage.
   * @throws IllegalArgumentException  If either the class loader is not a URLClassLoader or one
   * of the resources the class loader exposes is not a file resource.
   * @return A list of absolute paths to the resources the class loader uses.
   */
  protected static List<String> detectClassPathResourcesToStage(ClassLoader classLoader) {
    if (!(classLoader instanceof URLClassLoader)) {
      String message = String.format("Unable to use ClassLoader to detect classpath elements. "
          + "Current ClassLoader is %s, only URLClassLoaders are supported.", classLoader);
      LOG.error(message);
      throw new IllegalArgumentException(message);
    }

    List<String> files = new ArrayList<>();
    for (URL url : ((URLClassLoader) classLoader).getURLs()) {
      try {
        files.add(new File(url.toURI()).getAbsolutePath());
      } catch (IllegalArgumentException | URISyntaxException e) {
        String message = String.format("Unable to convert url (%s) to file.", url);
        LOG.error(message);
        throw new IllegalArgumentException(message, e);
      }
    }
    return files;
  }

  /**
   * Finds the id for the running job of the given name.
   */
  private String getJobIdFromName(String jobName) {
    try {
      ListJobsResponse listResult;
      String token = null;
      do {
        listResult = dataflowClient.projects().jobs()
            .list(options.getProject())
            .setPageToken(token)
            .execute();
        token = listResult.getNextPageToken();
        for (Job job : listResult.getJobs()) {
          if (job.getName().equals(jobName)
              && MonitoringUtil.toState(job.getCurrentState()).equals(State.RUNNING)) {
            return job.getId();
          }
        }
      } while (token != null);
    } catch (GoogleJsonResponseException e) {
      throw new RuntimeException(
          "Got error while looking up jobs: "
          + (e.getDetails() != null ? e.getDetails().getMessage() : e), e);
    } catch (IOException e) {
      throw new RuntimeException("Got error while looking up jobs: ", e);
    }

    throw new IllegalArgumentException("Could not find running job named " + jobName);
  }
}
