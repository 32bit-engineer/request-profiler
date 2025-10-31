package com.resource.profiler.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Reporter {

  private Reporter() {}

  private static final Logger logger = Logger.getLogger("RRP.Reporter");

  public static void report(TraceNode root) {
    try {
      String json = root.toPrettyJson();
      Files.writeString(Path.of("output.json"), json);
    } catch (Exception t) {
      logger.log(Level.SEVERE, "Failed to serialize trace", t);
    }
  }

}
