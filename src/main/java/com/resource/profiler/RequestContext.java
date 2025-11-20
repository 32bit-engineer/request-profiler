package com.resource.profiler;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class RequestContext {

    private final String traceId;
    private final String entryClass;
    private final String entryMethod;
    private final long startNanos = System.nanoTime();
    private final Instant startInstant = Instant.now();

    // threadId -> ThreadInfo
    private final Map<Long, ThreadInfo> threads = new ConcurrentHashMap<>();

    // GC pressure counters
    private long gcCount = 0;
    private long gcTimeMs = 0;

    private static final ThreadMXBean THREAD_MX = ManagementFactory.getThreadMXBean();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public RequestContext(String traceId, String entryClass, String entryMethod) {
        this.traceId = traceId;
        this.entryClass = entryClass;
        this.entryMethod = entryMethod;
    }

    /** Called by the ThreadConstructorAdvice */
    public void registerThread(Thread thread, String originClass) {
        long id = thread.threadId();
        boolean isVirtual = thread.isVirtual();
        // Only register if not already present, or update origin if unknown
        threads.compute(id, (k, v) -> {
            if (v == null) {
                return new ThreadInfo(id, originClass, isVirtual);
            }
            // If we have a better origin class, we could update it, but ThreadInfo is
            // immutable-ish
            return v;
        });
    }

    public void updateThreadMetrics(long threadId, long cpuNanos, long allocBytes, long durationNanos) {
        ThreadInfo info = threads.get(threadId);
        if (info != null) {
            info.addMetrics(cpuNanos, allocBytes, durationNanos);
        }
    }

    /** Called by GcMonitor whenever a GC occurs */
    public void recordGc(long durationMs) {
        gcCount++;
        gcTimeMs += durationMs;
    }

    /** Compute totals and write JSON */
    public void finish() {
        long durationNs = System.nanoTime() - startNanos;
        long cpuTimeNs = THREAD_MX.getCurrentThreadCpuTime(); // for the request thread

        // Populate per-thread metrics
        for (ThreadInfo ti : threads.values()) {
            ti.collectMetrics();
        }

        // Build the final JSON structure
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("traceId", traceId);
        json.put("entryClass", entryClass);
        json.put("entryMethod", entryMethod);
        json.put("startTime", startInstant.toString());
        json.put("durationMs", durationNs / 1_000_000);
        json.put("cpuTimeMs", cpuTimeNs / 1_000_000);
        json.put("gcCount", gcCount);
        json.put("gcTimeMs", gcTimeMs);
        json.put("threads", threads.values());

        // Store JSON
        writeJson(json);
    }

    /** Write JSON to a file named <traceId>.json under logs/ */
    public void writeJson(Map<String, Object> payload) {
        try {
            File outDir = new File("logs");
            outDir.mkdirs();
            File outFile = new File(outDir, traceId + ".json");
            MAPPER.writerWithDefaultPrettyPrinter()
                    .writeValue(outFile, payload != null ? payload : Collections.emptyMap());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
