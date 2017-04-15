package com.sudothought.agent;

import com.google.common.collect.Maps;
import com.google.protobuf.Empty;
import com.sudothought.common.MetricsServer;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.google.common.net.HttpHeaders.CONTENT_TYPE;
import static com.sudothought.agent.AgentMetrics.AGENT_SCRAPE_QUEUE_SIZE;
import static com.sudothought.agent.AgentMetrics.AGENT_SCRAPE_REQUESTS;
import static com.sudothought.agent.AgentMetrics.AGENT_SCRAPE_REQUEST_LATENCY;

public class Agent {

  private static final Logger logger        = LoggerFactory.getLogger(Agent.class);
  private static final String AGENT_CONFIGS = "agent_configs";

  private final BlockingQueue<ScrapeResponse> scrapeResponseQueue = new ArrayBlockingQueue<>(1000);

  // Map path to PathContext
  private final Map<String, PathContext> pathContextMap = Maps.newConcurrentMap();
  private final AtomicReference<String>  agentIdRef     = new AtomicReference<>();
  private final AtomicBoolean            stopped        = new AtomicBoolean(false);

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

    Runtime.getRuntime()
           .addShutdownHook(
               new Thread(() -> {
                 System.err.println("*** Shutting down Agent ***");
                 Agent.this.stop();
                 System.err.println("*** Agent shut down ***");
               }));

    while (!this.isStopped()) {
      final AtomicBoolean connected = new AtomicBoolean(false);
      // Reset on each connection attempt
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

        final AtomicBoolean disconnected = new AtomicBoolean(false);

        this.asyncStub
            .readRequestsFromProxy(AgentInfo.newBuilder().setAgentId(this.getAgenId()).build(),
                                   new StreamObserver<ScrapeRequest>() {
                                     @Override
                                     public void onNext(final ScrapeRequest scrapeRequest) {
                                       final ScrapeResponse scrapeResponse = fetchScrape(scrapeRequest);
                                       try {
                                         AGENT_SCRAPE_REQUESTS.observe(1);
                                         AGENT_SCRAPE_QUEUE_SIZE.inc();
                                         scrapeResponseQueue.put(scrapeResponse);
                                       }
                                       catch (InterruptedException e) {
                                         // Ignore
                                       }
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
              AGENT_SCRAPE_QUEUE_SIZE.dec();
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

      try {
        Thread.sleep(2000);
      }
      catch (InterruptedException e) {
        // Ignore
      }
    }
  }

  private ScrapeResponse invalidScrapeResponse(final ScrapeRequest scrapeRequest) {
    return ScrapeResponse.newBuilder()
                         .setAgentId(scrapeRequest.getAgentId())
                         .setScrapeId(scrapeRequest.getScrapeId())
                         .setValid(false)
                         .setStatusCode(404)
                         .setText("")
                         .setContentType("")
                         .build();
  }

  private ScrapeResponse fetchScrape(final ScrapeRequest scrapeRequest) {
    final Summary.Timer requestTimer = AGENT_SCRAPE_REQUEST_LATENCY.startTimer();
    try {
      final String path = scrapeRequest.getPath();
      final PathContext pathContext = this.pathContextMap.get(path);
      if (pathContext == null) {
        logger.warn("Invalid path request: {}", path);
        return this.invalidScrapeResponse(scrapeRequest);
      }
      else {
        try {
          logger.info("Fetching path request /{} {}", path, pathContext.getUrl());
          final Response res = pathContext.fetchUrl(scrapeRequest);
          return ScrapeResponse.newBuilder()
                               .setAgentId(scrapeRequest.getAgentId())
                               .setScrapeId(scrapeRequest.getScrapeId())
                               .setValid(true)
                               .setStatusCode(res.code())
                               .setText(res.body().string())
                               .setContentType(res.header(CONTENT_TYPE))
                               .build();
        }
        catch (IOException e) {
          return this.invalidScrapeResponse(scrapeRequest);
        }
      }
    }
    finally {
      requestTimer.observeDuration();
    }
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

  private void connectAgent() { this.blockingStub.connectAgent(Empty.getDefaultInstance()); }

  private void registerAgent()
      throws ConnectException {
    final RegisterAgentRequest request = RegisterAgentRequest.newBuilder()
                                                             .setAgentId(this.getAgenId())
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
                                                           .setAgentId(this.getAgenId())
                                                           .setPath(path)
                                                           .build();
    final RegisterPathResponse response = this.blockingStub.registerPath(request);
    if (!response.getValid())
      throw new ConnectException("registerPath()");
    return response.getPathId();
  }

  public ManagedChannel getChannel() { return this.channel; }

  public void setAgentId(final String agentId) { this.agentIdRef.set(agentId); }

  private String getAgenId() { return this.agentIdRef.get(); }

  private boolean isStopped() { return this.stopped.get(); }
}
