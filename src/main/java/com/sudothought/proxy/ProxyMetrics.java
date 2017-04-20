package com.sudothought.proxy;

import com.sudothought.common.SamplerGauge;
import io.prometheus.client.Counter;
import io.prometheus.client.Gauge;
import io.prometheus.client.Summary;

public class ProxyMetrics {

  public final Counter scrapeRequests = Counter.build()
                                               .name("proxy_scrape_requests")
                                               .help("Proxy scrape requests")
                                               .labelNames("type")
                                               .register();

  public final Counter connects = Counter.build()
                                         .name("proxy_connect_count")
                                         .help("Proxy connect count")
                                         .register();

  public final Counter heartbeats = Counter.build()
                                           .name("proxy_heartbeat_count")
                                           .help("Proxy heartbeat count")
                                           .register();

  public final Summary scrapeRequestLatency = Summary.build()
                                                     .name("proxy_scrape_request_latency_seconds")
                                                     .help("Proxy scrape request latency in seconds")
                                                     .register();

  public ProxyMetrics(Proxy proxy) {

    Gauge.build()
         .name("proxy_start_time_seconds")
         .help("Proxy start time in seconds")
         .register()
         .setToCurrentTime();

    new SamplerGauge("proxy_agent_map_size",
                     "Proxy connected agents",
                     proxy::getAgentContextSize).register();

    new SamplerGauge("proxy_path_map_size",
                     "Proxy path map size",
                     proxy::getPathMapSize).register();

    new SamplerGauge("proxy_scrape_map_size",
                     "Proxy scrape map size",
                     proxy::getScrapeMapSize).register();

    new SamplerGauge("proxy_cummulative_agent_queue_size",
                     "Proxy cummulative agent queue size",
                     proxy::getTotalAgentRequestQueueSize).register();
  }
}
