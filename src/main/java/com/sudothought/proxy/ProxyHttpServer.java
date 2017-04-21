package com.sudothought.proxy;

import brave.Span;
import brave.Tracer;
import com.github.kristofa.brave.sparkjava.BraveTracing;
import com.sudothought.common.ConfigVals;
import com.sudothought.grpc.ScrapeResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.ExceptionHandlerImpl;
import spark.Request;
import spark.Response;
import spark.Service;

import static com.google.common.net.HttpHeaders.ACCEPT;
import static com.google.common.net.HttpHeaders.ACCEPT_ENCODING;
import static com.google.common.net.HttpHeaders.CONTENT_ENCODING;

public class ProxyHttpServer {

  private static final Logger logger = LoggerFactory.getLogger(ProxyHttpServer.class);

  private final Proxy            proxy;
  private final int              port;
  private final Service          http;
  private final Tracer           tracer;
  private final ConfigVals.Proxy configVals;

  public ProxyHttpServer(final Proxy proxy, final int port) {
    this.proxy = proxy;
    this.port = port;
    this.http = Service.ignite();
    this.http.port(this.port);
    this.tracer = this.proxy.isZipkinEnabled()
                  ? this.proxy.getZipkinReporter().newTracer("proxy-http")
                  : null;
    this.configVals = this.proxy.getConfigVals();
  }

  public void start() {
    logger.info("Started proxy listening on {}", this.port);
    if (this.proxy.isZipkinEnabled()) {
      final BraveTracing tracing = BraveTracing.create(this.proxy.getBrave());
      this.http.before(tracing.before());
      this.http.exception(Exception.class, tracing.exception(new ExceptionHandlerImpl(Exception.class) {
        @Override
        public void handle(Exception exception, Request request, Response response) {
          response.status(404);
          exception.printStackTrace();
        }
      }));
      this.http.afterAfter(tracing.afterAfter());
    }

    this.http.get("/*",
                  (req, res) -> {
                    res.header("cache-control", "no-cache");

                    final Span rootSpan = this.tracer != null ? this.tracer.newTrace()
                                                                           .name("round-trip")
                                                                           .tag("version", "1.0.0")
                                                                           .start()
                                                              : null;
                    try {
                      final String[] vals = req.splat();
                      if (vals == null || vals.length == 0) {
                        logger.info("Request missing path");
                        res.status(404);
                        if (this.proxy.isMetricsEnabled())
                          this.proxy.getMetrics().scrapeRequests.labels("missing_path").inc();
                        return null;
                      }

                      final String path = vals[0];

                      if (this.configVals.internal.blitzEnabled && path.equals(this.configVals.internal.blitzPath)) {
                        res.status(200);
                        res.type("text/plain");
                        return "42";
                      }

                      final AgentContext agentContext = this.proxy.getAgentContextByPath(path);

                      if (agentContext == null) {
                        logger.debug("Invalid path request /{}", path);
                        res.status(404);
                        if (this.proxy.isMetricsEnabled())
                          this.proxy.getMetrics().scrapeRequests.labels("invalid_path").inc();
                        return null;
                      }

                      if (rootSpan != null)
                        rootSpan.tag("path", path);
                      final ScrapeRequestWrapper scrapeRequest = new ScrapeRequestWrapper(this.proxy,
                                                                                          agentContext,
                                                                                          rootSpan,
                                                                                          path,
                                                                                          req.headers(ACCEPT));
                      try {
                        this.proxy.addToScrapeRequestMap(scrapeRequest);
                        agentContext.addToScrapeRequestQueue(scrapeRequest);

                        final int timeoutSecs = this.configVals.internal.scrapeRequestTimeoutSecs;
                        final int checkMillis = this.configVals.internal.scrapeRequestCheckMillis;
                        while (true) {
                          // Returns false if timed out
                          if (scrapeRequest.waitUntilCompleteMillis(checkMillis))
                            break;

                          // Check if agent is disconnected or agent is hung
                          if (scrapeRequest.ageInSecs() >= timeoutSecs
                              || !scrapeRequest.getAgentContext().isValid()
                              || this.proxy.isStopped()) {
                            res.status(503);
                            if (this.proxy.isMetricsEnabled())
                              this.proxy.getMetrics().scrapeRequests.labels("time_out").inc();
                            return null;
                          }
                        }
                      }
                      finally {
                        final ScrapeRequestWrapper prev = this.proxy.removeFromScrapeRequestMap(scrapeRequest.getScrapeId());
                        //System.err.println("After remove size = " + this.proxy.getScrapeMapSize());
                        if (prev == null)
                          logger.error("Scrape request {} missing in map", scrapeRequest.getScrapeId());
                      }

                      logger.debug("Results returned from {} for {}", agentContext, scrapeRequest);

                      final ScrapeResponse scrapeResponse = scrapeRequest.getScrapeResponse();
                      final int status_code = scrapeResponse.getStatusCode();
                      res.status(status_code);

                      // Do not return content on error status codes
                      if (status_code >= 400) {
                        if (this.proxy.isMetricsEnabled())
                          this.proxy.getMetrics().scrapeRequests.labels("path_not_found").inc();
                        return null;
                      }
                      else {
                        final String accept_encoding = req.headers(ACCEPT_ENCODING);
                        if (accept_encoding != null && accept_encoding.contains("gzip"))
                          res.header(CONTENT_ENCODING, "gzip");
                        res.type(scrapeResponse.getContentType());
                        if (this.proxy.isMetricsEnabled())
                          this.proxy.getMetrics().scrapeRequests.labels("success").inc();
                        return scrapeRequest.getScrapeResponse().getText();
                      }
                    }
                    finally {
                      if (rootSpan != null)
                        rootSpan.finish();
                    }
                  });
  }

  public void stop() { this.http.stop(); }
}
