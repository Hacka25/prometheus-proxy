package com.sudothought;

import com.sudothought.agent.Agent;
import com.sudothought.agent.AgentArgs;
import com.sudothought.common.ConfigVals;
import com.sudothought.proxy.Proxy;
import com.sudothought.proxy.ProxyArgs;
import com.typesafe.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

import static com.sudothought.common.EnvVars.AGENT_CONFIG;
import static com.sudothought.common.EnvVars.PROXY_CONFIG;

public class TestUtils {

  private static final Logger logger = LoggerFactory.getLogger(TestUtils.class);

  public static Proxy startProxy(final String serverName, final boolean metrics_enabled)
      throws IOException {

    logger.info(com.sudothought.common.Utils.getBanner("banners/proxy.txt"));
    final ProxyArgs proxyArgs = new ProxyArgs();
    proxyArgs.parseArgs(Proxy.class.getName(), TestConstants.argv);

    final Config proxyConfig = com.sudothought.common.Utils.readConfig(proxyArgs.config, PROXY_CONFIG, false);
    final ConfigVals proxyConfigVals = new ConfigVals(proxyConfig);
    proxyArgs.assignArgs(proxyConfigVals);

    Proxy proxy = new Proxy(proxyConfigVals,
                            proxyArgs.grpc_port,
                            TestConstants.PROXY_PORT,
                            metrics_enabled,
                            proxyArgs.metrics_port,
                            serverName,
                            true);
    proxy.start();

    return proxy;
  }

  public static Agent startAgent(final String serverName, final boolean metrics_enabled)
      throws IOException {

    logger.info(com.sudothought.common.Utils.getBanner("banners/agent.txt"));
    final AgentArgs agentArgs = new AgentArgs();
    agentArgs.parseArgs(Agent.class.getName(), TestConstants.argv);

    final Config agentConfig = com.sudothought.common.Utils.readConfig(agentArgs.config, AGENT_CONFIG, true);
    final ConfigVals configVals = new ConfigVals(agentConfig);
    agentArgs.assignArgs(configVals);

    Agent agent = new Agent(configVals,
                            serverName,
                            agentArgs.agent_name,
                            agentArgs.proxy_host,
                            metrics_enabled,
                            agentArgs.metrics_port,
                            true);
    agent.start();

    return agent;
  }
}
