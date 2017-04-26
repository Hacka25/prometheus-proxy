package io.prometheus.proxy;

import com.beust.jcommander.Parameter;
import io.prometheus.common.BaseOptions;
import io.prometheus.common.ConfigVals;

import static io.prometheus.common.EnvVars.AGENT_PORT;
import static io.prometheus.common.EnvVars.PROXY_PORT;

public class ProxyOptions
    extends BaseOptions {

  @Parameter(names = {"-p", "--port"}, description = "Listen port for Prometheus")
  private Integer proxyPort = null;
  @Parameter(names = {"-a", "--agent_port"}, description = "Listen port for agents")
  private Integer agentPort = null;

  public ProxyOptions(String programName) {
    super(programName);
  }

  public void assignOptions(final ConfigVals configVals) {

    if (this.proxyPort == null)
      this.proxyPort = PROXY_PORT.getEnv(configVals.proxy.http.port);

    if (this.agentPort == null)
      this.agentPort = AGENT_PORT.getEnv(configVals.proxy.agent.port);

    this.assignMetricsPort(configVals.proxy.metrics.port);
    this.assignEnableMetrics(configVals.proxy.metrics.enabled);
  }

  public int getProxyPort() { return this.proxyPort; }

  public int getAgentPort() { return this.agentPort; }
}