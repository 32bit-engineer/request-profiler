package com.resource.profiler.core;


import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;


public class RequestContext {

  private RequestContext() {}

  private static final ThreadMXBean mxBean = ManagementFactory.getThreadMXBean();

  // Map actual Thread object (carrier or virtual) to TraceNode
  private static final ConcurrentMap<Thread, TraceNode> context = new ConcurrentHashMap<>();
  private static final AtomicLong REQ_COUNTER = new AtomicLong();

  // Mapping from JFR-provided ThreadId -> TraceNode
  private static final ConcurrentMap<Long, TraceNode> tIdToNode = new ConcurrentHashMap<>();

  // Mapping from OS thread id (carrier) -> last observed CPU nanos (sampled via ThreadCPULoad or ThreadMXBean)
  private static final ConcurrentMap<Long, Double> carrierCpuSnapshot = new ConcurrentHashMap<>();


  public static void start() {
    TraceNode root = new TraceNode("req-" + REQ_COUNTER.incrementAndGet(), Thread.currentThread().getName());
    root.startWall = System.nanoTime();
    root.startMem = currentMemory();
    if (mxBean.isThreadCpuTimeSupported()) {
      try {
        root.startCpu = mxBean.getCurrentThreadCpuTime();
      } catch (Exception _) {
        // ignored
      }
    }
    context.put(Thread.currentThread(), root);
  }

  public static void end() {
    TraceNode root = context.remove(Thread.currentThread());
    if (root != null) {
      root.endWall = System.nanoTime();
      root.endMem = currentMemory();
      if (mxBean.isThreadCpuTimeSupported()) {
        try {
          root.endCpu = mxBean.getCurrentThreadCpuTime();
        } catch (Exception _) {
          // ignored
        }
      }
      Reporter.report(root);
    }
  }

  public static Runnable wrap(Runnable r) {
    TraceNode parent = context.get(Thread.currentThread());
    if (parent == null) {
      return r;
    }

    return () -> {
      Thread t = Thread.currentThread();
      TraceNode child = new TraceNode("child-" + UUID.randomUUID(), t.getName());
      child.parent = parent;
      child.startWall = System.nanoTime();
      child.startMem = currentMemory();
      if (mxBean.isThreadCpuTimeSupported()) {
        try {
          child.startCpu = mxBean.getCurrentThreadCpuTime();
        } catch (Exception _) {
          //  ignored
        }
      }

      context.put(t, child);
      try {
        r.run();
      } finally {
        child.endWall = System.nanoTime();
        child.endMem = currentMemory();
        if (mxBean.isThreadCpuTimeSupported()) {
          try {
            child.endCpu = mxBean.getCurrentThreadCpuTime();
          } catch (Exception _) {
            // ignored
          }
        }
        parent.children.add(child);
        context.remove(t);
        // ensure parent mapping remains present keyed by its original thread object (best-effort)
        Thread parentThread = parentThreadFor(parent);
        if (parentThread != null) {
          context.putIfAbsent(parentThread, parent);
        }
      }
    };
  }

  // Called by ThreadStartAdvice when Thread.start() is invoked on a Thread object
  public static void onVirtualThreadCreated(Thread t) {
    // Best-effort: associate the virtual thread object with the current carrier's request context
    TraceNode carrierCtx = context.get(Thread.currentThread());
    String idPrefix = t.isVirtual() ? "vt-obj-" : "pt-obj-";
    if (carrierCtx != null) {
      
      TraceNode vtNode = new TraceNode(idPrefix + System.identityHashCode(t), t.getName());
      vtNode.parent = carrierCtx;
      vtNode.startWall = System.nanoTime();
      vtNode.startMem = currentMemory();
      // store by the Thread object so when it runs we can find it
      context.put(t, vtNode);
      // also store a fallback id mapping keyed by identityHashCode
      tIdToNode.put((long) System.identityHashCode(t), vtNode);
    }
  }

  // Hooks called by JFR listener when a virtual thread is observed to start with a JFR virtualThreadId
  public static void onVirtualThreadStart(long virtualId, Thread t) {
    TraceNode carrierCtx = context.get(Thread.currentThread());
    TraceNode vtNode = new TraceNode("vt-" + virtualId, t != null ? t.getName() : ("vt-" + virtualId));
    vtNode.parent = carrierCtx;
    vtNode.startWall = System.nanoTime();
    vtNode.startMem = currentMemory();
    tIdToNode.put(virtualId, vtNode);
    if (t != null) {
      context.put(t, vtNode);
    }
  }

  public static void onVirtualThreadStartByThread(Thread t) {
    // fallback when JFR didn't provide an id, but we got a Thread reference
    TraceNode carrierCtx = context.get(Thread.currentThread());
    TraceNode vtNode = new TraceNode("vt-obj-" + System.identityHashCode(t), t.getName());
    vtNode.parent = carrierCtx;
    vtNode.startWall = System.nanoTime();
    vtNode.startMem = currentMemory();
    context.put(t, vtNode);
    tIdToNode.put((long) System.identityHashCode(t), vtNode);
  }

  public static void onVirtualThreadMounted(long virtualId, long carrierOsThreadId) {
    // record the carrier->virtual binding time so we can attribute CPU samples
    TraceNode node = tIdToNode.get(virtualId);
    if (node != null) {
      node.mountedCarrierOsThreadId = carrierOsThreadId;
      node.mountedAtNano = System.nanoTime();
    }
  }

  public static void onVirtualThreadUnmounted(long virtualId) {
    TraceNode node = tIdToNode.get(virtualId);
    if (node != null) {
      long now = System.nanoTime();
      // if we had a mounted carrier id, we could compute a delta using carrierCpuSnapshot samples
      node.endWall = node.endWall == 0 ? now : node.endWall;
      node.endMem = currentMemory();
      // note: CPU attribution will happen via onCarrierCpuSample if available
      if (node.parent != null) {
        node.parent.children.add(node);
      }
      // keep node until JFR sends VirtualThreadEnd to finalize
    }
  }

  public static void onVirtualThreadCompleted(long virtualId) {
    TraceNode node = tIdToNode.remove(virtualId);
    if (node != null) {
      node.endWall = node.endWall == 0 ? System.nanoTime() : node.endWall;
      node.endMem = currentMemory();
      if (node.parent != null) {
        node.parent.children.add(node);
      }
      // done
    }
  }

  // Called by JFR ThreadCPULoad event handler. osThreadId is platform/OS thread id.
  public static void onCarrierCpuSample(long osThreadId, double user, double system) {
    // store the sample (user+system) as a double fraction or sample value — we keep it simple
    carrierCpuSnapshot.put(osThreadId, user + system);
    // Advanced: you could attribute deltas to currently mounted virtual threads by matching
    // vtNode.mountedCarrierOsThreadId == osThreadId and computing deltas.
  }

  // Helper: find a Thread object for a parent TraceNode by matching its name (best-effort)
  public static Thread parentThreadFor(TraceNode parent) {
    if (parent == null) {
      return null;
    }
    String name = parent.threadName;
    for (Thread th : Thread.getAllStackTraces().keySet()) {
      if (th.getName().equals(name)) {
        return th;
      }
    }
    return null;
  }

  // Helper: get current heap usage bytes
  public static long currentMemory() {
    return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
  }
}
