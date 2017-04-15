package com.sudothought.proxy;

import com.sudothought.agent.AgentContext;
import com.sudothought.grpc.ScrapeResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.Service;

import static com.google.common.net.HttpHeaders.ACCEPT;
import static com.google.common.net.HttpHeaders.ACCEPT_ENCODING;
import static com.google.common.net.HttpHeaders.CONTENT_ENCODING;

public class HttpServer {

  private static final Logger logger = LoggerFactory.getLogger(HttpServer.class);

  private final Proxy   proxy;
  private final int     port;
  private final Service service;

  public HttpServer(final Proxy proxy, final int port) {
    this.proxy = proxy;
    this.port = port;
    this.service = Service.ignite();
    this.service.port(this.port);
  }

  public void start() {
    logger.info("Started proxy listening on {}", this.port);

    this.service.get(
        "/*",
        (req, res) -> {
          res.header("cache-control", "no-cache");

          final String path = req.splat()[0];
          final String agentId = this.proxy.getAgentIdByPath(path);

          if (agentId == null) {
            logger.info("Missing path request /{}", path);
            res.status(404);
            return null;
          }

          final AgentContext agentContext = proxy.getAgentContext(agentId);
          if (agentContext == null) {
            logger.info("Missing AgentContext /{} agent_id: {}", path, agentId);
            res.status(404);
            return null;
          }

          final ScrapeRequestContext scrapeRequestContext = new ScrapeRequestContext(agentId, path, req.headers(ACCEPT));
          this.proxy.addScrapeRequest(scrapeRequestContext);
          agentContext.addScrapeRequest(scrapeRequestContext);

          while (true) {
            // Returns false if timed out
            if (scrapeRequestContext.waitUntilComplete(1000))
              break;

            // Check if agent is disconnected or agent is hung
            if (!proxy.isValidAgentId(agentId) || scrapeRequestContext.ageInSecs() >= 5 || proxy.isStopped()) {
              res.status(503);
              return null;
            }
          }

          logger.info("Results returned from agent for scrape_id: {}", scrapeRequestContext.getScrapeId());

          final ScrapeResponse scrapeResponse = scrapeRequestContext.getScrapeResponse();
          final int status_code = scrapeResponse.getStatusCode();
          res.status(status_code);

          // Do not return content on error status codes
          if (status_code >= 400) {
            return null;
          }
          else {
            final String accept_encoding = req.headers(ACCEPT_ENCODING);
            if (accept_encoding != null && accept_encoding.contains("gzip"))
              res.header(CONTENT_ENCODING, "gzip");
            res.type(scrapeResponse.getContentType());
            return scrapeRequestContext.getScrapeResponse().getText();
          }
        });
  }

  public void stop() { this.service.stop(); }
}
