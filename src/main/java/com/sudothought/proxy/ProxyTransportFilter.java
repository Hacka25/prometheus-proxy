package com.sudothought.proxy;

import io.grpc.Attributes;
import io.grpc.ServerTransportFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

public class ProxyTransportFilter
    extends ServerTransportFilter {

  private static final Logger logger = LoggerFactory.getLogger(ProxyTransportFilter.class);

  private final Proxy proxy;

  public ProxyTransportFilter(Proxy proxy) {
    this.proxy = proxy;
  }

  private String getRemoteAddr(Attributes attributes) {
    final Optional<Attributes.Key<?>> key_opt = attributes.keys()
                                                          .stream()
                                                          .filter(key -> key.toString().equals("remote-addr"))
                                                          .findFirst();
    if (key_opt.isPresent()) {
      final Attributes.Key<Object> key = (Attributes.Key<Object>) key_opt.get();
      final Object val = attributes.get(key);
      if (val != null)
        return val.toString();
    }
    return "Unknown";
  }

  @Override
  public Attributes transportReady(final Attributes attributes) {
    final String remote_addr = this.getRemoteAddr(attributes);
    final AgentContext agentContext = new AgentContext(this.proxy, remote_addr);
    this.proxy.addAgentContext(agentContext);
    logger.info("Connected to {}", agentContext);
    return Attributes.newBuilder()
                     .set(Proxy.ATTRIB_AGENT_ID, agentContext.getAgentId())
                     .setAll(attributes)
                     .build();
  }

  @Override
  public void transportTerminated(final Attributes attributes) {
    final String agentId = attributes.get(Proxy.ATTRIB_AGENT_ID);
    this.proxy.removePathByAgentId(agentId);
    final AgentContext agentContext = this.proxy.removeAgentContext(agentId);
    if (agentContext != null)
      logger.info("Disconnected from {}", agentContext);
    else
      logger.info("Disconnected with invalid agent_id:{}", agentId);
    super.transportTerminated(attributes);
  }
}
