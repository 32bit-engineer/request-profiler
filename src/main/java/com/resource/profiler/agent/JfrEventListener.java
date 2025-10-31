package com.resource.profiler.agent;


import com.resource.profiler.core.RequestContext;
import java.time.Duration;
import java.util.logging.Level;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingStream;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

public class JfrEventListener {
  
  private static final String V_TID= "virtualThreadId";
  private static final String VT = "virtualThread";

  private JfrEventListener() {}

  private static final Logger logger = Logger.getLogger("JfrEventListener.JFR");
  private static final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
    Thread t = new Thread(r, "RRP-JfrEventListener");
    t.setDaemon(true);
    return t;
  });
  private static volatile boolean started = false;

  public static void start() {
    if (started) {
      return;
    }
    started = true;
    executor.submit(() -> {
      try (RecordingStream rs = new RecordingStream()) {
        rs.enable("jdk.VirtualThreadStart");
        rs.enable("jdk.VirtualThreadEnd");
        rs.enable("jdk.VirtualThreadMount");
        rs.enable("jdk.VirtualThreadUnmount");
        rs.enable("jdk.ThreadCPULoad").withPeriod(Duration.ofMillis(100));

        rs.onEvent(JfrEventListener::handleEvent);

        logger.info("[RRP] JFR listener started.");
        rs.start(); // blocks until the stream is closed
      } catch (Exception t) {
        logger.log(Level.WARNING, "JFR RecordingStream failure", t);
      }
    });
  }

  private static void handleEvent(RecordedEvent e) {
    String type = e.getEventType().getName();
    try {
      switch (type) {
        case "jdk.VirtualThreadStart":
          // some JDKs provide a virtualThreadId; others provide a reference to the Thread
          long vId = safeGetLong(e, V_TID);
          Thread vt = safeGetThread(e, VT);
          if (vId != -1) {
            RequestContext.onVirtualThreadStart(vId, vt);
          } else if (vt != null) {
            RequestContext.onVirtualThreadStartByThread(vt);
          }
          break;

        case "jdk.VirtualThreadEnd":
          long vIdEnd = safeGetLong(e, V_TID);
          if (vIdEnd != -1) {
            RequestContext.onVirtualThreadCompleted(vIdEnd);
          }
          break;

        case "jdk.VirtualThreadMount":
          long vIdMount = safeGetLong(e, V_TID);
          long carrier = safeGetLong(e, "carrierThreadId");
          if (vIdMount != -1 && carrier != -1) {
            RequestContext.onVirtualThreadMounted(vIdMount, carrier);
          }
          break;

        case "jdk.VirtualThreadUnmount":
          long vIdUnmount = safeGetLong(e, V_TID);
          if (vIdUnmount != -1) {
            RequestContext.onVirtualThreadUnmounted(vIdUnmount);
          }
          break;

        case "jdk.ThreadCPULoad":
          // Thread load event gives a per-OS-thread CPU load sample
          try {
            long osTid = e.getLong("osThreadId");
            double user = e.getDouble("user");
            double system = e.getDouble("system");
            RequestContext.onCarrierCpuSample(osTid, user, system);
          } catch (Exception _) {
            // ignored
          }
          break;

        default: return;
      }

    } catch (Exception ex) {
      logger.log(Level.FINE, "JFR event handling error", ex);
    }
  }

  private static long safeGetLong(RecordedEvent e, String name) {
    try {
      return e.getLong(name);
    } catch (Exception _) {
      return -1L;
    }
  }

  private static Thread safeGetThread(RecordedEvent e, String name) {
    try {
      Object o = e.getValue(name);
      if (o instanceof Thread) {
        return (Thread) o;
      }
    } catch (Exception _) {
      // ignored
    }
    return null;
  }
}

