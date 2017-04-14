package com.sudothought.agent;

import com.beust.jcommander.Parameter;
import com.sudothought.common.BaseArgs;

public class AgentArgs
    extends BaseArgs {

  @Parameter(names = {"-p", "--proxy"}, description = "Proxy url")
  public String proxy = "localhost:50051";
  @Parameter(names = {"-c", "--config"}, required = true, description = "Configuration .yml file")
  public String config;

}
