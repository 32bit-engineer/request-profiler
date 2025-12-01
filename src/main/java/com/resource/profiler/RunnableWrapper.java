package com.resource.profiler;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

public class RunnableWrapper implements Runnable {
    private final Runnable delegate;
    private final RequestContext context;
    private final String originClass;

    private static final ThreadMXBean MX = ManagementFactory.getThreadMXBean();

    public RunnableWrapper(Runnable delegate, RequestContext context) {
        this.delegate = delegate;
        this.context = context;
        this.originClass = findOriginClass();
    }

    private String findOriginClass() {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        for (StackTraceElement element : stack) {
            String className = element.getClassName();
            // Match user's package name, but exclude known infrastructure/decorator classes
            // if needed
            if (className.startsWith("com.engine") && !className.contains("DecoratorExecutorService")) {
                return className;
            }
        }
        // Re-scanning for non-platform as fallback if com.engine not found
        for (StackTraceElement element : stack) {
            String className = element.getClassName();
            if (!className.startsWith("java.") &&
                    !className.startsWith("javax.") &&
                    !className.startsWith("sun.") &&
                    !className.startsWith("jdk.") &&
                    !className.startsWith("com.resource.profiler") &&
                    !className.startsWith("net.bytebuddy") &&
                    !className.startsWith("org.springframework")) {
                return className;
            }
        }
        return "unknown";
    }

    @Override
    public void run() {
        RequestContextHolder.set(context);
        Thread currentThread = Thread.currentThread();
        long threadId = currentThread.threadId();

        // Register this thread (worker or virtual) with the context using captured
        // origin
        Profiler.registerThread(currentThread, originClass);

        long startCpu = MX.getThreadCpuTime(threadId);
        long startAlloc = -1;
        if (MX instanceof com.sun.management.ThreadMXBean) {
            com.sun.management.ThreadMXBean sunMx = (com.sun.management.ThreadMXBean) MX;
            if (sunMx.isThreadAllocatedMemorySupported()) {
                startAlloc = sunMx.getThreadAllocatedBytes(threadId);
            }
        }
        long startTime = System.nanoTime();

        try {
            delegate.run();
        } finally {
            long endTime = System.nanoTime();
            long endCpu = MX.getThreadCpuTime(threadId);
            long endAlloc = -1;
            if (MX instanceof com.sun.management.ThreadMXBean) {
                com.sun.management.ThreadMXBean sunMx = (com.sun.management.ThreadMXBean) MX;
                if (sunMx.isThreadAllocatedMemorySupported()) {
                    endAlloc = sunMx.getThreadAllocatedBytes(threadId);
                }
            }

            long cpuDelta = (startCpu != -1 && endCpu != -1) ? (endCpu - startCpu) : -1;
            long allocDelta = (startAlloc != -1 && endAlloc != -1) ? (endAlloc - startAlloc) : -1;
            long durationDelta = endTime - startTime;

            context.updateThreadMetrics(threadId, cpuDelta, allocDelta, durationDelta);

            RequestContextHolder.remove();
        }
    }
}
