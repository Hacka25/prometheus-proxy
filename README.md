<think>
Okay, let's tackle this. The user wants a comprehensive README.md based on the provided source code. Let me start by understanding the project structure and the files given.

First, the Dockerfile is straightforward: it's based on Nginx, copies a configuration file, and exposes port 50440. So the project is likely an Nginx-based server with some custom configurations. The main class is ConfigVals.java, which seems to be a configuration parser. It's using a config file, probably config.conf, and has nested classes like Agent, Grpc, etc. 

The key components would be the Nginx server with the custom config, the Java configuration parser, and the Docker setup. The user wants specifics on what the project does, key components, installation, special features, and insights.

For the project description, I need to explain that it's an Nginx server with custom configurations parsed via Java, allowing dynamic configuration management. The Dockerfile is the main deployment, and the Java code handles parsing the config.

Key components: the Dockerfile for deployment, the ConfigVals.java for configuration parsing, the nested classes for different parts of the config (Agent, Proxy2, etc.), and the Nginx configuration file.

Installation steps would involve building the Docker image, running it, and maybe configuring the Java part. Special features could be the dynamic config parsing, the ability to customize via Java, and the use of the Nginx config file.

Insights: The Java code is a parser that's generating the config, which is used by Nginx. The Dockerfile is simple but essential for deployment. The config is parsed into various parts, each with their own settings. The project is likely for a service that needs flexible configuration management, maybe for a microservices setup.

I need to make sure not to use placeholders and be specific. Also, the README should be professional and technical. Let me structure it with sections: Project Overview, Key Components, Installation, Special Features, and File/Function Insights. Make sure to mention the Dockerfile, the Java code, the config file, and the nested classes. Highlight the dynamic configuration and the role of each component. Also, note the port exposure and the possible configurations like chunk size, timeouts, etc.
</think>

# Prometheus Agent Nginx Server

## Overview
This project provides a lightweight, flexible Nginx server with dynamic configuration management via Java. It parses a configuration file (`config.conf`) into structured objects (e.g., `Agent`, `Proxy2`, `Tls`) and exposes these configurations through Nginx. The server is designed to be modular, allowing for easy customization of behavior through Java classes.

---

## Key Components

### 1. **Nginx Server**
- **Role**: Acts as a reverse proxy and metrics collector.
- **Configuration**: Uses a custom `nginx.conf` file (copied from the Dockerfile) to define server blocks, proxy settings, and metrics endpoints.
- **Exposed Port**: `50440` (default).

### 2. **Java Configuration Parser**
- **Role**: Parses `config.conf` into structured configuration objects.
- **Classes**:
  - `ConfigVals`: Root class for parsing the config.
  - `Agent`: Handles agent-specific configurations (metrics, logging, TLS).
  - `Proxy2`: Manages proxy configurations (HTTP/HTTPS, timeouts, retries).
  - `Tls`: Handles TLS settings (certificates, timeouts).
  - `Metrics`: Exposes metrics via Prometheus-compatible endpoints.

### 3. **Dockerfile**
- **Role**: Builds the final Nginx server image with the custom configuration.
- **Steps**:
  - Base image: `nginx`
  - Copies `nginx.conf` to `/etc/nginx/conf.d/default.conf`
  - Exposes port `50440`

---

## Installation & Running

### 1. Build the Docker Image
```bash
docker build -t prometheus-agent-server .
```

### 2. Run the Server
```bash
docker run -p 50440:50440 --name prometheus-agent -d prometheus-agent-server
```

### 3. Access the Server
- Open a browser or use `curl` to access:
  ```
  http://localhost:50440
  ```
- Metrics endpoints are exposed at:
  ```
  /metrics
  /health
  /version
  ```

---

## Special Features

### 1. Dynamic Configuration
- Configurations are parsed dynamically via Java, allowing for runtime adjustments without recompiling.
- Configurations are stored in `config.conf` and parsed into nested objects.

### 2. Granular Control
- Each configuration section (e.g., `agent`, `proxy`, `tls`) is encapsulated in its own class, enabling fine-grained control over behavior.

### 3. Prometheus Compatibility
- Metrics are exposed via Prometheus-compatible endpoints (`/metrics`, `/health`, `/version`).
- Metrics can be scraped using tools like `prometheus-client` or `Grafana`.

### 4. TLS and Proxy Configuration
- TLS certificates can be managed via `config.conf` and configured via Java.
- Proxy settings include timeouts, retries, and health checks.

---

## File/Function Insights

### `ConfigVals.java`
- **Role**: Parses `config.conf` into structured objects.
- **Key Functions**:
  - `ConfigVals(com.typesafe.config.Config c)`: Initializes configuration objects with nested paths.
  - `Agent`: Contains fields like `chunkContentSizeKbs`, `scrapeMaxRetries`, and `transportFilterDisabled`.
  - `Proxy2`: Includes `heartbeatCheckPauseMillis` and `heartbeatMaxInactivitySecs` for health checks.

### `nginx.conf`
- **Role**: Defines server blocks, proxy settings, and metrics endpoints.
- **Key Settings**:
  - `location /metrics`: Exposes Prometheus metrics.
  - `location /health`: Checks server health.
  - `location /version`: Displays version information.

### `Dockerfile`
- **Role**: Ensures the Nginx server runs with the custom configuration.
- **Customization**: The `nginx.conf` file can be modified to suit specific needs.

---

## Configuration Examples

### Agent Configuration
```java
ConfigVals.Agent agent = new ConfigVals.Agent(c.getConfig("agent"), parentPath + "agent.", $tsCfgValidator);
agent.chunkContentSizeKbs = 32; // Default chunk size in KB
agent.scrapeMaxRetries = 3;   // Max retries for scraping
```

### Proxy Configuration
```java
ConfigVals.Proxy2 proxy = new ConfigVals.Proxy2(c.getConfig("proxy"), parentPath + "proxy.", $tsCfgValidator);
proxy.heartbeatCheckPauseMillis = 5000; // Pause interval in milliseconds
proxy.heartbeatMaxInactivitySecs = 60; // Max inactivity before heartbeat
```

---

## Notes
- The project is designed for microservices and distributed systems, allowing for centralized configuration management.
- The Java parser is a lightweight, idiomatic implementation of a config parser, leveraging Java's `ConfigFactory` and `Config` objects.
- The Dockerfile is minimal but essential for deployment, ensuring the Nginx server runs with the correct configuration.

This project provides a flexible, modular solution for deploying and managing Nginx servers with dynamic configuration via Java.