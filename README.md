Here is a professional, technical, and comprehensive README.md file based on the provided source code:

**README.md**
===============

**Project Overview**

The project is a configuration-driven application that provides a set of features for managing and monitoring various aspects of an agent. The application is designed to be highly configurable, allowing users to customize its behavior through a configuration file.

**Key Components**

The project consists of the following key components:

1. `ConfigVals`: This Java class serves as the central hub for configuring the application's behavior. It provides a set of properties that can be customized through a configuration file.
2. `Dockerfile`: This Dockerfile is used to build a container image for the application. It copies the `nginx.conf` file into the container and exposes port 50440.

**How to Install and Run**

To install and run the project, follow these steps:

1. Clone the repository using Git: `git clone <repository-url>`
2. Build the Docker image by running the following command: `docker build -t <image-name> .`
3. Run the container using the following command: `docker run -p 50440:50440 <image-name>`
4. Access the application's UI by navigating to `http://localhost:50440`

**Special Features and Configurations**

The project provides a range of special features and configurations that can be customized through the configuration file. Some notable features include:

1. **Agent Debug Servlet**: This feature enables the agent debug servlet on port 8093, allowing for debugging and monitoring of the application.
2. **Transport Filter Disabled**: This feature disables the transport filter, allowing for direct communication between the agent and the proxy.
3. **Keep-Alive Without Calls**: This feature enables keep-alive without calls, which allows the agent to send periodic heartbeat messages to the proxy even when there are no active connections.

**File-Level Insights**

The project consists of several files that provide additional insights into its behavior:

1. `src/main/java/io/prometheus/common/ConfigVals.java`: This Java file provides a comprehensive overview of the configuration values and their corresponding properties.
2. `nginx/docker/Dockerfile`: This Dockerfile provides insight into how the application is packaged and deployed using Docker.

**Troubleshooting Tips**

If you encounter any issues while running the project, refer to the following troubleshooting tips:

1. Check the container logs for any errors or warnings: `docker logs -f <container-name>`
2. Verify that the configuration file is correctly formatted and contains valid values.
3. Consult the application's documentation and online resources for further guidance on troubleshooting.

I hope this README.md file provides a comprehensive overview of the project and its features!

## Architecture Diagram

```mermaid
graph TD
A[File: nginx/docker/Dockerfile] -->|FROM|> B[Nginx]
C[File: src/main/java/io/prometheus/common/ConfigVals.java] -->|CONTAINS|> D[Class: ConfigVals]
E[Class: Agent] -->|CONTAINS|> F[Fields: {name, minGzipSizeBytes, transportFilterDisabled, ...}]
G[Class: Admin] -->|CONTAINS|> H[Fields: {debugEnabled, enabled, healthCheckPath, pingPath, port, threadDumpPath, versionPath}]
J[Class: Grpc] -->|CONTAINS|> K[Fields: {keepAliveTimeSecs, keepAliveTimeoutSecs, keepAliveWithoutCalls}]
L[Class: Http] -->|CONTAINS|> M[Fields: {enableTrustAllX509Certificates}]
P[Class: Internal] -->|CONTAINS|> Q[Fields: {cioTimeoutSecs, heartbeatCheckPauseMillis, heartbeatEnabled, heartbeatMaxInactivitySecs, reconnectPauseSecs, scrapeRequestBacklogUnhealthySize}]
R[Class: Metrics] -->|CONTAINS|> S[Fields: {}]
T[Class: Proxy] -->|CONTAINS|> U[Fields: {}]
V[Class: Tls] -->|CONTAINS|> W[Fields: {}]
X[A] -->|COPY|> Y[File: ./nginx.conf]
Z[X] -->|EXPOSE|> AA[Port 50440]
```