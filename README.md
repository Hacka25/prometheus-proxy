Model run timed out.

## Architecture Diagram

```mermaid
graph TD
    A[File: gradle.properties] -->|contains|> B[Kotlin Code Style]
    C[File: settings.gradle] -->|defines root project name|> D[Prometheus Proxy]
    E[File: README.md] -->|describes project overview|> F[Project Overview]
    G[Class: ConfigVals] -->|provides configuration properties|> H[Properties]
    I[Class: Agent] -->|manages and monitors agent aspects|> J[Features]
    K[Class: Admin] -->|provides administrative features|> L[Feature]
    M[Class: Grpc] -->|handles gRPC communication|> N[Purpose]
    O[Class: Http] -->|enables HTTP access|> P[Port 50440]
    Q[Class: Internal] -->|manages internal processes|> R[Process]
    S[Class: Metrics] -->|collects and reports metrics|> T[Metric]
    U[Class: Proxy] -->|handles proxy requests|> V[Request]
    W[Class: Tls] -->|secure communication|> X[SSL/TLS]
    Y[A] -->|COPY|> Z[Nginx.conf]
    AA[Y] -->|EXPOSE|> AB[Port 50440]
```