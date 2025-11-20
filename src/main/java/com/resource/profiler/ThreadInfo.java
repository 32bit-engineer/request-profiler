package com.resource.profiler;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

public class ThreadInfo {
    private final long threadId;
    private final String originClass;
    private final boolean isVirtual;
    private final long startNanos;
    private long endNanos;
    private long cpuTimeNanos;
    private long allocatedBytes = -1; // requires Java 14+ with ThreadMXBean.getThreadAllocatedBytes
    private long durationMs = 0; // Added field for accumulation

    private static final ThreadMXBean MX = ManagementFactory.getThreadMXBean();

    public ThreadInfo(long threadId, String originClass, boolean isVirtual) {
        this.threadId = threadId;
        this.originClass = originClass;
        this.isVirtual = isVirtual;
        this.startNanos = System.nanoTime();
    }

    /** Called when the request finishes to capture final metrics */
    public void collectMetrics() {
        this.endNanos = System.nanoTime();
        // For non-pooled threads (like manually created ones), we try to read current
        // state
        // If thread is dead, this might return -1 or 0.
        // This method is primarily for the 'main' thread or threads not wrapped by
        // RunnableWrapper.
        long currentCpu = MX.getThreadCpuTime(threadId);
        if (currentCpu != -1) {
            this.cpuTimeNanos = currentCpu;
        }

        if (MX instanceof com.sun.management.ThreadMXBean) {
            com.sun.management.ThreadMXBean sunMx = (com.sun.management.ThreadMXBean) MX;
            if (sunMx.isThreadAllocatedMemorySupported()) {
                long currentAlloc = sunMx.getThreadAllocatedBytes(threadId);
                if (currentAlloc != -1) {
                    this.allocatedBytes = currentAlloc;
                }
            }
        }
    }

    public void addMetrics(long cpuNanos, long allocBytes, long durationNanos) {
        if (cpuNanos > 0) {
            this.cpuTimeNanos += cpuNanos;
        }
        if (allocBytes > 0) {
            if (this.allocatedBytes == -1)
                this.allocatedBytes = 0;
            this.allocatedBytes += allocBytes;
        }
        if (durationNanos > 0) {
            this.durationMs += (durationNanos / 1_000_000);
        }
    }

    // Getters for JSON serialization (Jackson will use them automatically)
    public long getThreadId() {
        return threadId;
    }

    public String getOriginClass() {
        return originClass;
    }

    public boolean getIsVirtual() {
        return isVirtual;
    }

    public long getDurationMs() {
        if (durationMs > 0)
            return durationMs; // Return accumulated duration if present
        if (endNanos == 0) {
            return (System.nanoTime() - startNanos) / 1_000_000;
        }
        return (endNanos - startNanos) / 1_000_000;
    }

    public long getCpuTimeMs() {
        return cpuTimeNanos / 1_000_000;
    }

    public long getAllocatedBytes() {
        return allocatedBytes;
    }
}
