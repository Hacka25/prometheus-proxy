package com.sudothought.agent;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.ForwardingClientCallListener;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;

import static com.sudothought.proxy.Proxy.AGENT_ID;

public class AgentClientInterceptor
    implements ClientInterceptor {
  private final Agent agent;

  public AgentClientInterceptor(Agent agent) {
    this.agent = agent;
  }

  @Override
  public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(final MethodDescriptor<ReqT, RespT> method,
                                                             final CallOptions callOptions,
                                                             final Channel next) {
    final String methodName = method.getFullMethodName();
    return new ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(this.agent.getChannel().newCall(method,
                                                                                                            callOptions)) {
      @Override
      public void start(final Listener<RespT> responseListener, final Metadata headers) {
        super.start(
            new ForwardingClientCallListener.SimpleForwardingClientCallListener<RespT>(responseListener) {
              @Override
              public void onHeaders(Metadata headers) {
                // Grab agent_id from headers
                final String agent_id = headers.get(Metadata.Key.of(AGENT_ID, Metadata.ASCII_STRING_MARSHALLER));
                if (agent_id != null)
                  agent.setAgentId(agent_id);
                super.onHeaders(headers);
              }

              @Override
              public void onMessage(RespT message) {
                super.onMessage(message);
              }

              @Override
              public void onClose(Status status, Metadata trailers) {
                super.onClose(status, trailers);
              }

              @Override
              public void onReady() {
                super.onReady();
              }
            },
            headers);
      }
    };
  }
}
