package com.resource.profiler;

import com.resource.profiler.agent.ExecutorAdvice;
import com.resource.profiler.agent.JfrEventListener;
import com.resource.profiler.agent.ServletAdvice;
import com.resource.profiler.agent.ThreadStartAdvice;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

import java.lang.instrument.Instrumentation;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Profiler {

  private Profiler() {}

  private static final Logger logger = Logger.getLogger("Profiler");

  public static void premain(String agentArgs, Instrumentation inst) {
    logger.info("[RRP] Agent starting...");

    // Intercept Servlet filters (doFilter)
    new AgentBuilder.Default()
        .type(ElementMatchers.nameContains("Filter"))
        .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
            builder.method(ElementMatchers.named("doFilter"))
                .intercept(Advice.to(ServletAdvice.class)))
        .installOn(inst);

    // Intercept Executors (execute(Runnable))
    new AgentBuilder.Default()
        .type(ElementMatchers.nameContains("Executor"))
        .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
            builder.method(ElementMatchers.named("execute").and(ElementMatchers.takesArguments(Runnable.class)))
                .intercept(Advice.to(ExecutorAdvice.class)))
        .installOn(inst);

    // Intercept Thread.start() to capture virtual thread creation
    new AgentBuilder.Default()
        .type(ElementMatchers.named("java.lang.Thread"))
        .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
            builder.method(ElementMatchers.named("start").and(ElementMatchers.takesArguments(0)))
                .intercept(Advice.to(ThreadStartAdvice.class)))
        .installOn(inst);

    // Start a JFR listener (optional, if available) to capture virtual-thread lifecycle events
    try {
      JfrEventListener.start();
    } catch (Exception t) {
      String msg = String.format("JFR listener not available or failed to start: %s" , t.getMessage());
      logger.log(Level.WARNING, msg);
    }

    logger.info("[RRP] Agent installed.");
  }
}


