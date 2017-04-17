package com.sudothought.agent;

import com.google.common.collect.Maps;
import com.google.common.util.concurrent.RateLimiter;
import com.google.protobuf.Empty;
import com.sudothought.common.InstrumentedBlockingQueue;
import com.sudothought.common.MetricsServer;
import com.sudothought.common.Utils;
import com.sudothought.grpc.AgentInfo;
import com.sudothought.grpc.ProxyServiceGrpc;
import com.sudothought.grpc.RegisterAgentRequest;
import com.sudothought.grpc.RegisterAgentResponse;
import com.sudothought.grpc.RegisterPathRequest;
import com.sudothought.grpc.RegisterPathResponse;
import com.sudothought.grpc.ScrapeRequest;
import com.sudothought.grpc.ScrapeResponse;
import io.grpc.ClientInterceptor;
import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import io.prometheus.client.Summary;
import io.prometheus.client.hotspot.DefaultExports;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.google.common.net.HttpHeaders.CONTENT_TYPE;
import static com.sudothought.common.Utils.newInstrumentedThreadFactory;
import static java.util.concurrent.Executors.newCachedThreadPool;

public class Agent {

  private static final Logger logger        = LoggerFactory.getLogger(Agent.class);
  private static final String AGENT_CONFIGS = "agent_configs";

  private final AgentMetrics                  metrics             = new AgentMetrics();
  private final BlockingQueue<ScrapeResponse> scrapeResponseQueue = new InstrumentedBlockingQueue<>(new ArrayBlockingQueue<>(256),
                                                                                                    this.metrics.scrapeQueueSize);
  private final AtomicBoolean                 stopped             = new AtomicBoolean(false);
  // Map path to PathContext
  private final Map<String, PathContext>      pathContextMap      = Maps.newConcurrentMap();
  private final AtomicReference<String>       agentIdRef          = new AtomicReference<>();
  private final RateLimiter                   reconnectLimiter    = RateLimiter.create(1.0 / 3);
  private final ExecutorService               executorService     = newCachedThreadPool(newInstrumentedThreadFactory("agent_fetch",
                                                                                                                     "Agent fetch",
                                                                                                                     true));

  private final String                                    hostname;
  private final List<Map<String, String>>                 agentConfigs;
  private final ManagedChannel                            channel;
  private final ProxyServiceGrpc.ProxyServiceBlockingStub blockingStub;
  private final ProxyServiceGrpc.ProxyServiceStub         asyncStub;
  private final MetricsServer                             metricsServer;

  private Agent(String hostname, final int metricsPort, final List<Map<String, String>> agentConfigs) {
    this.agentConfigs = agentConfigs;

    final String host;
    final int port;
    if (hostname.contains(":")) {
      String[] vals = hostname.split(":");
      host = vals[0];
      port = Integer.valueOf(vals[1]);
    }
    else {
      host = hostname;
      port = 50051;
    }
    this.hostname = String.format("%s:%s", host, port);
    final ManagedChannelBuilder<?> channelBuilder = ManagedChannelBuilder.forAddress(host, port)
                                                                         .usePlaintext(true);
    this.channel = channelBuilder.build();
    final ClientInterceptor interceptor = new AgentClientInterceptor(this);
    this.blockingStub = ProxyServiceGrpc.newBlockingStub(ClientInterceptors.intercept(this.channel, interceptor));
    this.asyncStub = ProxyServiceGrpc.newStub(ClientInterceptors.intercept(this.channel, interceptor));
    this.metricsServer = new MetricsServer(metricsPort);
  }

  public static void main(final String[] argv)
      throws IOException {
    logger.info(Utils.getBanner("banners/agent.txt"));

    final AgentArgs agentArgs = new AgentArgs();
    agentArgs.parseArgs(Agent.class.getName(), argv);

    final List<Map<String, String>> agentConfigs;
    try {
      agentConfigs = readAgentConfigs(agentArgs.config);
    }
    catch (IOException e) {
      logger.error(e.getMessage());
      return;
    }

    final Agent agent = new Agent(agentArgs.proxy_hostname, agentArgs.metrics_port, agentConfigs);
    agent.run();
  }

  private static List<Map<String, String>> readAgentConfigs(final String filename)
      throws IOException {
    logger.info("Loading configuration file {}", filename);
    final Yaml yaml = new Yaml();
    try (final InputStream input = new FileInputStream(new File(filename))) {
      final Map<String, List<Map<String, String>>> data = (Map<String, List<Map<String, String>>>) yaml.load(input);
      if (!data.containsKey(AGENT_CONFIGS))
        throw new IOException(String.format("Missing %s key in config file %s", AGENT_CONFIGS, filename));
      return data.get(AGENT_CONFIGS);
    }
    catch (FileNotFoundException e) {
      throw new IOException(String.format("Config file not found: %s", filename));
    }
  }

  private static String getHostName() {
    try {
      final String hostname = InetAddress.getLocalHost().getHostName();
      final String address = InetAddress.getLocalHost().getHostAddress();
      return hostname;
    }
    catch (UnknownHostException e) {
      return "Unknown";
    }
  }

  private void run()
      throws IOException {
    this.metricsServer.start();
    DefaultExports.initialize();

    // Prime the limiter
    this.reconnectLimiter.acquire();

    Runtime.getRuntime()
           .addShutdownHook(
               new Thread(() -> {
                 System.err.println("*** Shutting down Agent ***");
                 Agent.this.stop();
                 System.err.println("*** Agent shut down ***");
               }));

    while (!this.isStopped()) {
      final AtomicBoolean connected = new AtomicBoolean(false);
      final AtomicBoolean disconnected = new AtomicBoolean(false);
      // Reset values for each connection attempt
      this.setAgentId(null);
      this.pathContextMap.clear();
      this.scrapeResponseQueue.clear();

      try {
        logger.info("Connecting to proxy at {}...", this.hostname);
        this.connectAgent();
        logger.info("Connected to proxy at {}", this.hostname);
        connected.set(true);

        this.registerAgent();
        this.registerPaths();

        this.asyncStub
            .readRequestsFromProxy(AgentInfo.newBuilder().setAgentId(this.getAgentId()).build(),
                                   new StreamObserver<ScrapeRequest>() {
                                     @Override
                                     public void onNext(final ScrapeRequest scrapeRequest) {
                                       executorService.submit(
                                           () -> {
                                             final ScrapeResponse scrapeResponse = fetchMetrics(scrapeRequest);
                                             try {
                                               scrapeResponseQueue.put(scrapeResponse);
                                             }
                                             catch (InterruptedException e) {
                                               // Ignore
                                             }
                                           });
                                     }

                                     @Override
                                     public void onError(Throwable t) {
                                       final Status status = Status.fromThrowable(t);
                                       logger.info("Error in readRequestsFromProxy(): {}", status);
                                       disconnected.set(true);
                                     }

                                     @Override
                                     public void onCompleted() {
                                       disconnected.set(true);
                                     }
                                   });

        final StreamObserver<ScrapeResponse> responseObserver = this.asyncStub.writeResponsesToProxy(
            new StreamObserver<Empty>() {
              @Override
              public void onNext(Empty rmpty) {
                // Ignore
              }

              @Override
              public void onError(Throwable t) {
                final Status status = Status.fromThrowable(t);
                logger.info("Error in writeResponsesToProxy(): {}", status);
                disconnected.set(true);
              }

              @Override
              public void onCompleted() {
                disconnected.set(true);
              }
            });

        while (!disconnected.get()) {
          try {
            // Set a short timeout to check if client has disconnected
            final ScrapeResponse response = this.scrapeResponseQueue.poll(1, TimeUnit.SECONDS);
            if (response != null)
              responseObserver.onNext(response);
          }
          catch (InterruptedException e) {
            // Ignore
          }
        }

        // responseObserver.onCompleted();
      }
      catch (ConnectException e) {
        // Ignore
      }
      catch (StatusRuntimeException e) {
        logger.info("Cannot connect to proxy at {} [{}]", this.hostname, e.getMessage());
      }

      if (connected.get())
        logger.info("Disconnected from proxy at {}", this.hostname);

      final double secsWaiting = this.reconnectLimiter.acquire();
      logger.info("Waited {} secs to reconnect", secsWaiting);
    }
  }

  private ScrapeResponse fetchMetrics(final ScrapeRequest scrapeRequest) {
    int status_code = 404;
    final String path = scrapeRequest.getPath();
    final ScrapeResponse.Builder scrapeResponse = ScrapeResponse.newBuilder()
                                                                .setAgentId(scrapeRequest.getAgentId())
                                                                .setScrapeId(scrapeRequest.getScrapeId());
    final PathContext pathContext = this.pathContextMap.get(path);
    if (pathContext == null) {
      logger.warn("Invalid path request: {}", path);
      this.metrics.scrapeRequests.labels("invalid_path").observe(1);
      return scrapeResponse.setValid(false)
                           .setStatusCode(status_code)
                           .setText("")
                           .setContentType("")
                           .build();
    }

    final Summary.Timer requestTimer = this.metrics.scrapeRequestLatency.startTimer();
    try {
      logger.info("Fetching path request /{} {}", path, pathContext.getUrl());
      final Response response = pathContext.fetchUrl(scrapeRequest);
      status_code = response.code();
      if (response.isSuccessful()) {
        this.metrics.scrapeRequests.labels("success").observe(1);
        return scrapeResponse.setValid(true)
                             .setStatusCode(status_code)
                             .setText(response.body().string())
                             .setContentType(response.header(CONTENT_TYPE))
                             .build();
      }
    }
    catch (IOException e) {
      // Ignore
    }
    finally {
      requestTimer.observeDuration();
    }
    metrics.scrapeRequests.labels("unsuccessful").observe(1);
    return scrapeResponse.setValid(false)
                         .setStatusCode(status_code)
                         .setText("")
                         .setContentType("")
                         .build();
  }

  public void stop() {
    this.stopped.set(true);
    this.metricsServer.stop();
    try {
      this.channel.shutdown().awaitTermination(1, TimeUnit.SECONDS);
    }
    catch (InterruptedException e) {
      // Ignore
    }
  }

  // If successful, this will create an agentContxt on the Proxy and an interceptor will
  // add an agent_id to the headers
  private void connectAgent() { this.blockingStub.connectAgent(Empty.getDefaultInstance()); }

  private void registerAgent()
      throws ConnectException {
    final RegisterAgentRequest request = RegisterAgentRequest.newBuilder()
                                                             .setAgentId(this.getAgentId())
                                                             .setHostname(getHostName())
                                                             .build();
    final RegisterAgentResponse response = this.blockingStub.registerAgent(request);
    if (!response.getValid())
      throw new ConnectException("registerAgent()");
  }

  private void registerPaths()
      throws ConnectException {
    for (Map<String, String> agentConfig : this.agentConfigs) {
      final String path = agentConfig.get("path");
      final String url = agentConfig.get("url");
      final long pathId = this.registerPath(path);
      logger.info("Registered {} as /{}", url, path);
      this.pathContextMap.put(path, new PathContext(pathId, path, url));
    }
  }

  private long registerPath(final String path)
      throws ConnectException {
    final RegisterPathRequest request = RegisterPathRequest.newBuilder()
                                                           .setAgentId(this.getAgentId())
                                                           .setPath(path)
                                                           .build();
    final RegisterPathResponse response = this.blockingStub.registerPath(request);
    if (!response.getValid())
      throw new ConnectException("registerPath()");
    return response.getPathId();
  }

  private boolean isStopped() { return this.stopped.get(); }

  public ManagedChannel getChannel() { return this.channel; }

  public String getAgentId() { return this.agentIdRef.get(); }

  public void setAgentId(final String agentId) { this.agentIdRef.set(agentId); }

}
