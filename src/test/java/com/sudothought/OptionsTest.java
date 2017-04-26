package com.sudothought;

import com.sudothought.agent.AgentOptions;
import com.sudothought.common.ConfigVals;
import com.sudothought.proxy.ProxyOptions;
import org.junit.Test;

import java.util.List;

import static com.sudothought.common.EnvVars.AGENT_CONFIG;
import static com.sudothought.common.EnvVars.PROXY_CONFIG;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.util.Lists.newArrayList;

public class OptionsTest {

  private static String CONFIG = "https://raw.githubusercontent.com/pambrose/prometheus-proxy/master/etc/test-configs/junit-test.conf";

  @Test
  public void verifyDefaultValues() {
    final ConfigVals configVals = readProxyOptions(newArrayList());
    assertThat(configVals.proxy.http.port).isEqualTo(8080);
    assertThat(configVals.proxy.internal.zipkin.enabled).isEqualTo(false);
  }

  @Test
  public void verifyConfValues() {
    final ConfigVals configVals = readProxyOptions(newArrayList("--config", CONFIG));
    assertThat(configVals.proxy.http.port).isEqualTo(8181);
    assertThat(configVals.proxy.internal.zipkin.enabled).isEqualTo(true);
  }

  @Test
  public void verifyUnquotedPropValue() {
    final ConfigVals configVals = readProxyOptions(newArrayList("-Dproxy.http.port=9393", "-Dproxy.internal.zipkin.enabled=true"));
    assertThat(configVals.proxy.http.port).isEqualTo(9393);
    assertThat(configVals.proxy.internal.zipkin.enabled).isEqualTo(true);
  }

  @Test
  public void verifyQuotedPropValue() {
    final ConfigVals configVals = readProxyOptions(newArrayList("-D\"proxy.http.port=9394\""));
    assertThat(configVals.proxy.http.port).isEqualTo(9394);
  }

  @Test
  public void verifyPathConfigs() {
    final ConfigVals configVals = readAgentOptions(newArrayList("--config", CONFIG));
    assertThat(configVals.agent.pathConfigs.size()).isEqualTo(3);
  }


  public void verifyProxyDefaults() {
    final ProxyOptions options = new ProxyOptions(Proxy.class.getName());
    options.parseArgs(newArrayList());
    options.readConfig(PROXY_CONFIG.name(), false);

    final ConfigVals configVals = new ConfigVals(options.getConfig());
    options.assignOptions(configVals);

    assertThat(options.getProxyPort()).isEqualTo(8080);
    assertThat(options.getAgentPort()).isEqualTo(50021);
  }

  public void verifyAgentDefaults() {
    AgentOptions options = new AgentOptions(Agent.class.getName());
    options.parseArgs(newArrayList("--name", "test-name", "--proxy", "host5"));
    options.readConfig(AGENT_CONFIG.name(), false);

    final ConfigVals configVals = new ConfigVals(options.getConfig());
    options.assignOptions(configVals);

    assertThat(options.getEnableMetrics()).isEqualTo(false);
    assertThat(options.getDynamicParams().size()).isEqualTo(0);
    assertThat(options.getAgentName()).isEqualTo("test-name");
    assertThat(options.getProxyHostname()).isEqualTo("host5");
  }

  private ConfigVals readProxyOptions(final List<String> argList) {
    final ProxyOptions options = new ProxyOptions(Proxy.class.getName());
    options.parseArgs(argList);
    options.readConfig(PROXY_CONFIG.name(), false);

    final ConfigVals configVals = new ConfigVals(options.getConfig());
    options.assignOptions(configVals);
    return configVals;
  }

  private ConfigVals readAgentOptions(final List<String> argList) {
    AgentOptions options = new AgentOptions(Agent.class.getName());
    options.parseArgs(argList);
    options.readConfig(AGENT_CONFIG.name(), false);

    final ConfigVals configVals = new ConfigVals(options.getConfig());
    options.assignOptions(configVals);
    return configVals;
  }
}