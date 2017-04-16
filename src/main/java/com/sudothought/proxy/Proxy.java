package com.sudothought.proxy;

import com.google.common.collect.Maps;
import com.sudothought.agent.AgentContext;
import com.sudothought.common.InstrumentedMap;
import com.sudothought.common.MetricsServer;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import io.prometheus.client.hotspot.DefaultExports;
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
  // Map path to agent_id
  private final Map<String, String>             pathMap          = new InstrumentedMap<>(Maps.newConcurrentMap(),
                                                                                         this.metrics.pathMapSize);
  // Map scrape_id to agent_id
  private final Map<Long, ScrapeRequestContext> scrapeRequestMap = new InstrumentedMap<>(Maps.newConcurrentMap(),
                                                                                         this.metrics.scrapeMapSize);

  private final Server        grpcServer;
  private final HttpServer    httpServer;
  private final MetricsServer metricsServer;

  private Proxy(final int proxyPort, final int metricsPort, final int grpcPort)
      throws IOException {
    final ProxyServiceImpl proxyService = new ProxyServiceImpl(this);
    final ServerInterceptor interceptor = new ProxyInterceptor();
    final ServerServiceDefinition serviceDef = ServerInterceptors.intercept(proxyService.bindService(), interceptor);
    this.grpcServer = ServerBuilder.forPort(grpcPort)
                                   .addService(serviceDef)
                                   .addTransportFilter(new ProxyTransportFilter(this))
                                   .build();
    this.httpServer = new HttpServer(this, proxyPort);
    this.metricsServer = new MetricsServer(metricsPort);
  }

  public static void main(final String[] argv)
      throws IOException {
    final ProxyArgs proxyArgs = new ProxyArgs();
    proxyArgs.parseArgs(Proxy.class.getName(), argv);

    final Proxy proxy = new Proxy(proxyArgs.http_port, proxyArgs.metrics_port, proxyArgs.grpc_port);
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
    this.grpcServer.shutdown();
    this.httpServer.stop();
    this.metricsServer.stop();
  }

  private void waitUntilShutdown() {
    try {
      this.grpcServer.awaitTermination();
    }
    catch (InterruptedException e) {
      // Ignore
    }
  }

  public boolean isValidAgentId(final String agentId) {return this.agentContextMap.containsKey(agentId);}

  public boolean isStopped() { return this.stopped.get(); }

  public void addAgentContext(final String agentId, final AgentContext agentContext) {
    this.agentContextMap.put(agentId, agentContext);
  }

  public AgentContext getAgentContext(String agentId) { return this.agentContextMap.get(agentId); }

  public AgentContext removeAgentContext(String agentId) {
    final AgentContext agentContext = this.agentContextMap.remove(agentId);
    if (agentContext != null) {
      logger.info("Removed AgentContext {} for agent_id: {}", agentContext.getRemoteAddr(), agentId);
    }
    else {
      logger.error("Missing AgentContext for agent_id: {}", agentId);
    }
    return agentContext;
  }

  public void addScrapeRequest(final ScrapeRequestContext scrapeRequestContext) {
    this.scrapeRequestMap.put(scrapeRequestContext.getScrapeId(), scrapeRequestContext);
    this.metrics.scrapeRequests.observe(1);
  }

  public ScrapeRequestContext removeScrapeRequest(long scrapeId) {
    return this.scrapeRequestMap.remove(scrapeId);
  }

  public String getAgentIdByPath(final String path) { return this.pathMap.get(path); }

  public boolean containsPath(final String path) { return this.pathMap.containsKey(path);}

  public void addPath(final String path, final String agentId, final AgentContext agentContext) {
    synchronized (this.pathMap) {
      this.pathMap.put(path, agentId);
      logger.info("Added path /{} for agent_id: {} [{} {}}",
                  path, agentId, agentContext.getRemoteAddr(), agentContext.getHostname());
    }
  }

  public void removePathByAgentId(final String agentId) {
    for (Map.Entry<String, String> elem : this.pathMap.entrySet()) {
      if (elem.getValue().equals(agentId)) {
        final String path = this.pathMap.remove(elem.getKey());
        if (path != null) {
          logger.info("Removed path /{} for agent_id: {}", elem.getKey(), agentId);
        }
        else {
          logger.error("Missing path /{} for agent_id: {}", elem.getKey(), agentId);
        }
      }
    }
  }

  public ProxyMetrics getMetrics() { return this.metrics; }
}
