package com.sudothought.agent;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public class PathContext {

  private static final Logger logger = LoggerFactory.getLogger(PathContext.class);

  private final OkHttpClient client = new OkHttpClient();

  private final long   path_id;
  private final String path;
  private final String url;

  private Request request;

  public PathContext(long path_id, String path, String url) {
    this.path_id = path_id;
    this.path = path;
    this.url = url;
    this.request = new Request.Builder().url(url).build();
  }

  public long getPath_id() {
    return this.path_id;
  }

  public String getPath() {
    return this.path;
  }

  public String getUrl() {
    return this.url;
  }

  public Response fetchUrl()
      throws IOException {
    try {
      return this.client.newCall(this.request).execute();
    }
    catch (IOException e) {
      logger.info("Failed HTTP request: {} [{}: {}]", this.getUrl(), e.getClass().getSimpleName(), e.getMessage());
      throw e;
    }
  }
}
