# Request Resource Profiler

A lightweight Java Agent designed to profile resource usage (CPU, memory, threads) at a **per-request level**. It is particularly useful for identifying the true cost of requests in threaded or asynchronous applications (e.g., Spring Boot, ExecutorServices).

## Features

*   **Request Isolation**: Tracks resources for individual requests by instrumenting entry points (e.g., Controllers).
*   **Thread Tracking**:
    *   Captures all threads spawned by a request, including **Virtual Threads**.
    *   Tracks tasks submitted to `Executor` and `ExecutorService`.
    *   Identifies the **Origin Class** (the user code that created the thread/task).
*   **Resource Metrics**:
    *   **CPU Time**: Total CPU time consumed by the request and each of its threads.
    *   **Allocated Memory**: Total bytes allocated by each thread (requires JDK 14+).
    *   **GC Pressure**: Tracks GC events occurring during the request.
*   **Traceability**: Generates a JSON report for each request, keyed by a unique Trace ID.

## Project Structure

```
request-resource-profiler/
├── src/main/java/com/resource/profiler/
│   ├── Profiler.java          # Agent Entry Point & Instrumentation Logic
│   ├── RequestContext.java    # Per-request state management
│   ├── ThreadInfo.java        # Per-thread metrics
│   ├── RunnableWrapper.java   # Context propagation for Runnables
│   ├── CallableWrapper.java   # Context propagation for Callables
│   └── GcMonitor.java         # GC Event Listener
└── build.gradle               # Build configuration
```

## Requirements

*   **Java 17+** (Tested with JDK 21/24)
*   **Gradle 8+**

## Quick Start

1.  **Build the Agent**:
    ```bash
    ./gradlew clean build
    ```
    The agent JAR will be created at `build/libs/request-resource-profiler-1.0.jar`.

2.  **Run with your Application**:
    ```bash
    java -javaagent:build/libs/request-resource-profiler-1.0.jar -jar your-app.jar
    ```

3.  **Check Logs**:
    After making requests to your application, check the `logs/` directory for JSON reports (e.g., `logs/request-<traceId>.json`).

See [USAGE.md](USAGE.md) for detailed instructions and output format.
