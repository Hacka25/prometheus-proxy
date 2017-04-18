package com.sudothought.proxy;

import com.github.kristofa.brave.Brave;
import com.github.kristofa.brave.grpc.BraveGrpcServerInterceptor;
import com.google.common.collect.Maps;
import com.sudothought.agent.AgentContext;
import com.sudothought.common.InstrumentedMap;
import com.sudothought.common.MetricsServer;
import com.sudothought.common.Utils;
import com.sudothought.common.ZipkinReporter;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import io.prometheus.client.hotspot.DefaultExports;
import me.dinowernli.grpc.prometheus.Configuration;
import me.dinowernli.grpc.prometheus.MonitoringServerInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class Proxy {

  private static final Logger logger = LoggerFactory.getLogger(Proxy.class);

  private final AtomicBoolean                   stopped          = new AtomicBoolean(false);
  private final ProxyMetrics                    metrics          = new ProxyMetrics();
  // Map agent_id to AgentContext
  private final Map<String, AgentContext>       agentContextMap  = new InstrumentedMap<>(Maps.newConcurrentMap(),
                                                                                         this.metrics.agentMapSize);
  // Map path to AgentContext
  private final Map<String, AgentContext>       pathMap          = new InstrumentedMap<>(Maps.newConcurrentMap(),
                                                                                         this.metrics.pathMapSize);
  // Map scrape_id to agent_id
  private final Map<Long, ScrapeRequestWrapper> scrapeRequestMap = new InstrumentedMap<>(Maps.newConcurrentMap(),
                                                                                         this.metrics.scrapeMapSize);
  private final ZipkinReporter zipkinReporter;
  private final Server         grpcServer;
  private final HttpServer     httpServer;
  private final MetricsServer  metricsServer;

  private Proxy(final int proxyPort, final int metricsPort, final int grpcPort)
      throws IOException {

    this.zipkinReporter = new ZipkinReporter("http://45.55.23.198:9411/api/v1/spans", "prometheus-proxy");
    final ProxyServiceImpl proxyService = new ProxyServiceImpl(this);
    // TODO Make this a configuration option
    //final Configuration grpc_metrics = Configuration.cheapMetricsOnly();
    final Configuration grpc_metrics = Configuration.allMetrics();
    final ServerServiceDefinition serviceDef =
        ServerInterceptors.intercept(proxyService.bindService(),
                                     new ProxyInterceptor(),
                                     MonitoringServerInterceptor.create(grpc_metrics),
                                     BraveGrpcServerInterceptor.create(this.zipkinReporter.getBrave()));
    this.grpcServer = ServerBuilder.forPort(grpcPort)
                                   .addService(serviceDef)
                                   .addTransportFilter(new ProxyTransportFilter(this))
                                   .build();
    this.httpServer = new HttpServer(this, proxyPort);
    this.metricsServer = new MetricsServer(metricsPort);
  }

  public static void main(final String[] argv)
      throws IOException {
    logger.info(Utils.getBanner("banners/proxy.txt"));

    final ProxyArgs args = new ProxyArgs();
    args.parseArgs(Proxy.class.getName(), argv);

    final Proxy proxy = new Proxy(args.http_port, args.metrics_port, args.grpc_port);
    proxy.start();
    proxy.waitUntilShutdown();
  }

  private void start()
      throws IOException {
    this.grpcServer.start();
    logger.info("Started gRPC server listening on {}", this.grpcServer.getPort());

    this.httpServer.start();
    this.metricsServer.start();

    DefaultExports.initialize();

    Runtime.getRuntime()
           .addShutdownHook(
               new Thread(() -> {
                 System.err.println("*** Shutting down Proxy ***");
                 Proxy.this.stop();
                 System.err.println("*** Proxy shut down ***");
               }));
  }

  private void stop() {
    this.stopped.set(true);
    this.httpServer.stop();
    this.metricsServer.stop();
    this.zipkinReporter.close();
    this.grpcServer.shutdown();
  }

  private void waitUntilShutdown() {
    try {
      this.grpcServer.awaitTermination();
    }
    catch (InterruptedException e) {
      // Ignore
    }
  }

  public boolean isStopped() { return this.stopped.get(); }

  public void addAgentContext(final AgentContext agentContext) {
    this.agentContextMap.put(agentContext.getAgentId(), agentContext);
  }

  public AgentContext getAgentContext(String agentId) { return this.agentContextMap.get(agentId); }

  public AgentContext removeAgentContext(String agentId) {
    final AgentContext agentContext = this.agentContextMap.remove(agentId);
    if (agentContext != null)
      logger.info("Removed {}", agentContext);
    else
      logger.error("Missing AgentContext for agent_id: {}", agentId);
    return agentContext;
  }

  public void addScrapeRequest(final ScrapeRequestWrapper scrapeRequest) {
    this.scrapeRequestMap.put(scrapeRequest.getScrapeId(), scrapeRequest);
  }

  public ScrapeRequestWrapper removeScrapeRequest(long scrapeId) {
    return this.scrapeRequestMap.remove(scrapeId);
  }

  public AgentContext getAgentContextByPath(final String path) { return this.pathMap.get(path); }

  public boolean containsPath(final String path) { return this.pathMap.containsKey(path);}

  public void addPath(final String path, final AgentContext agentContext) {
    synchronized (this.pathMap) {
      this.pathMap.put(path, agentContext);
      logger.info("Added path /{} for {}", path, agentContext);
    }
  }

  public void removePathByAgentId(final String agentId) {
    synchronized (this.pathMap) {
      for (Map.Entry<String, AgentContext> elem : this.pathMap.entrySet()) {
        if (elem.getValue().getAgentId().equals(agentId)) {
          final AgentContext agentContext = this.pathMap.remove(elem.getKey());
          if (agentContext != null)
            logger.info("Removed path /{} for {}", elem.getKey(), agentContext);
          else
            logger.error("Missing path /{} for agent_id: {}", elem.getKey(), agentId);
        }
      }
    }
  }

  public ProxyMetrics getMetrics() { return this.metrics; }

  public Brave getBrave() { return this.zipkinReporter.getBrave(); }
}
