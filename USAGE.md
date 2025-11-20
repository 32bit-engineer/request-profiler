# Usage Guide

This guide explains how to build, run, and interpret the output of the Request Resource Profiler.

## 1. Building the Agent

Navigate to the project root and run:

```bash
./gradlew clean build
```

This will produce the agent JAR file at:
`build/libs/request-resource-profiler-1.0.jar`

## 2. Attaching the Agent

To profile your Java application, you need to attach the agent using the `-javaagent` JVM flag.

### Standard Jar Run
```bash
java -javaagent:build/libs/request-resource-profiler-1.0.jar -jar your-application.jar
```

### IDE (IntelliJ/Eclipse)
Add the following to your Run Configuration's "VM Options":
```
-javaagent:/absolute/path/to/request-resource-profiler-1.0.jar
```

## 3. How it Works

Once attached, the agent automatically:
1.  **Intercepts Requests**: It listens for entry into methods ending with `Controller` (e.g., `MyController.execute`).
2.  **Tracks Threads**: It monitors `new Thread()`, `Thread.start()`, and `Executor.submit/execute()` calls.
3.  **Propagates Context**: It ensures that any thread or task spawned during the request is linked to that request's Trace ID.
4.  **Collects Metrics**: It records CPU time and memory allocation for every thread involved.

## 4. Output Format

The agent generates a JSON file for **each request** in the `logs/` directory (created in the working directory of your app).
Filename format: `request-<traceId>.json`

### Example JSON Output

```json
{
  "traceId" : "scriptxtest",
  "entryClass" : "com.engine.controller.BaseController",
  "entryMethod" : "execute",
  "startTime" : "2025-11-20T19:51:45.034118Z",
  "durationMs" : 1589,
  "cpuTimeMs" : 514,
  "gcCount" : 0,
  "gcTimeMs" : 0,
  "threads" : [ 
    {
      "threadId" : 127,
      "originClass" : "com.engine.ScriptingNode",
      "isVirtual" : true,
      "allocatedBytes" : 102400,
      "durationMs" : 31,
      "cpuTimeMs" : 15
    },
    {
      "threadId" : 74,
      "originClass" : "io.netty.util.concurrent.AbstractEventExecutor",
      "isVirtual" : false,
      "allocatedBytes" : 141551704,
      "durationMs" : 45,
      "cpuTimeMs" : 83
    }
  ]
}
```

### Field Descriptions

*   **traceId**: Unique identifier for the request.
*   **entryClass/Method**: The controller method that started the request.
*   **durationMs**: Total wall-clock time for the request.
*   **cpuTimeMs**: Total CPU time consumed by all threads associated with the request.
*   **threads**: List of threads that performed work for this request.
    *   **originClass**: The class that created/submitted the thread. This helps identify *who* is spawning threads.
    *   **isVirtual**: `true` if it's a Java Virtual Thread.
    *   **allocatedBytes**: Memory allocated by this thread *during this task*.
    *   **cpuTimeMs**: CPU time consumed by this thread *during this task*.

## 5. Troubleshooting

### `allocatedBytes` is -1
*   Ensure you are running on a JDK that supports `ThreadMXBean.getThreadAllocatedBytes` (JDK 14+ recommended).
*   The agent attempts to enable this automatically.

### `originClass` is "unknown" or internal
*   The agent tries to filter out JDK and agent classes to find the user code that initiated the thread.
*   It prioritizes classes starting with `com.engine`.
*   If you see `java.util.concurrent...`, it means the agent couldn't find a user class in the stack trace at the time of submission.
