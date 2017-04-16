package com.sudothought.agent;

import io.prometheus.client.Gauge;
import io.prometheus.client.Summary;

public class AgentMetrics {
  public final Summary scrapeRequests = Summary.build()
                                               .name("agent_scrape_requests")
                                               .help("Agent scrape requests")
                                               .register();

  public final Summary invalidPaths = Summary.build()
                                             .name("agent_invalid_paths")
                                             .help("Agent invalid paths")
                                             .register();

  public final Summary scrapeRequestLatency = Summary.build()
                                                     .name("agent_scrape_request_latency_seconds")
                                                     .help("Agent scrape request latency in seconds")
                                                     .register();

  public final Gauge scrapeQueueSize = Gauge.build()
                                            .name("agent_scrape_queue_size")
                                            .help("Agent scrape response queue size")
                                            .register();
}
