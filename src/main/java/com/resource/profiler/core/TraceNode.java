package com.resource.profiler.core;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TraceNode {

  public String id;
  public String threadName;
  public TraceNode parent;
  public List<TraceNode> children = Collections.synchronizedList(new ArrayList<>());

  // wall-clock nanos
  public long startWall = 0L;
  public long endWall = 0L;

  // cpu nanos (may be 0 for virtual threads)
  public long startCpu = 0L;
  public long endCpu = 0L;

  // heap bytes
  public long startMem = 0L;
  public long endMem = 0L;

  // best-effort carrier binding info
  public long mountedCarrierOsThreadId = -1L;
  public long mountedAtNano = 0L;

  public TraceNode(String id, String threadName) {
    this.id = id;
    this.threadName = threadName;
  }

  public long getWallMs() {
    return (endWall - startWall) / 1_000_000;
  }

  public long getCpuMs() {
    return (endCpu - startCpu) / 1_000_000;
  }

  public long getMemDelta() {
    return endMem - startMem;
  }

  public String toPrettyJson() {
    Map<String, Object> map = asMap(this);
    return toJsonPretty(map);
  }

  private Map<String, Object> asMap(TraceNode node) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", node.id);
    m.put("threadName", node.threadName);
    m.put("wallMs", node.getWallMs());
    m.put("cpuMs", node.getCpuMs());
    m.put("memDelta", node.getMemDelta());
    List<Object> kids = new ArrayList<>();
    for (TraceNode c : node.children) {
      kids.add(asMap(c));
    }
    m.put("children", kids);
    return m;
  }

  private static String toJsonPretty(Object o) {
    // minimal pretty-printer to avoid external deps
    StringWriter sw = new StringWriter();
    PrintWriter pw = new PrintWriter(sw);
    prettyPrint(o, pw, 0);
    pw.flush();
    return sw.toString();
  }

  private static void prettyPrint(Object o, PrintWriter pw, int indent) {
    String ind = "  ".repeat(indent);
    if (o instanceof Map) {
      pw.println("{");
      Map<?, ?> m = (Map<?, ?>) o;
      Iterator<? extends Map.Entry<?, ?>> it = m.entrySet().iterator();
      while (it.hasNext()) {
        Map.Entry<?, ?> e = it.next();
        pw.print(ind + "  \"" + e.getKey() + "\": ");
        prettyPrint(e.getValue(), pw, indent + 1);
        if (it.hasNext()) {
          pw.println(",");
        } else {
          pw.println();
        }
      }
      pw.print(ind + "}");
    } else if (o instanceof List) {
      pw.println("[");
      List<?> L = (List<?>) o;
      for (int i = 0; i < L.size(); i++) {
        pw.print(ind + "  ");
        prettyPrint(L.get(i), pw, indent + 1);
        if (i < L.size() - 1) {
          pw.println(",");
        } else {
          pw.println();
        }
      }
      pw.print(ind + "]");
    } else if (o instanceof String) {
      pw.print("\"" + escape((String) o) + "\"");
    } else {
      pw.print(o);
    }
  }

  private static String escape(String s) {
    return s
        .replace("\\", "\\\\")  // escape backslashes
        .replace("\"", "\\\""); // escape double quotes
  }

}