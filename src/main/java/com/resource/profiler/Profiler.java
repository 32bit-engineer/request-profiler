package com.resource.profiler;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

import java.lang.instrument.Instrumentation;
import java.util.concurrent.atomic.AtomicLong;

public class Profiler {

  // Global request counter - MUST be public for Advice access
  public static final AtomicLong REQUEST_SEQ = new AtomicLong();

  public static void premain(String agentArgs, Instrumentation inst) {
    // 1. Instrument the request entry point
    // We exclude common framework packages to avoid accidental instrumentation of
    // internal classes like org.apache.naming.ContextAccessController
    new AgentBuilder.Default()
        .type(ElementMatchers.nameEndsWith("Controller")
            .and(ElementMatchers.not(ElementMatchers.nameStartsWith("org.apache.")))
            .and(ElementMatchers.not(ElementMatchers.nameStartsWith("org.springframework.")))
            .and(ElementMatchers.not(ElementMatchers.nameStartsWith("java.")))
            .and(ElementMatchers.not(ElementMatchers.nameStartsWith("javax.")))
            .and(ElementMatchers.not(ElementMatchers.nameStartsWith("sun.")))
            .and(ElementMatchers.not(ElementMatchers.nameStartsWith("jdk."))))
        .transform(
            (builder, typeDescription, classLoader, module, protectionDomain) -> builder.method(ElementMatchers.any())
                .intercept(Advice.to(RequestEntryAdvice.class)))
        .installOn(inst);

    // 2. Instrument Thread constructors to capture the direct caller class
    // We must override the default ignore matcher to allow instrumentation of
    // java.lang.Thread
    new AgentBuilder.Default()
        .ignore(ElementMatchers.nameStartsWith("net.bytebuddy."))
        .type(ElementMatchers.isSubTypeOf(Thread.class))
        .transform((builder, typeDescription, classLoader, module, protectionDomain) -> builder
            .constructor(ElementMatchers.any())
            .intercept(Advice.to(ThreadConstructorAdvice.class)))
        .installOn(inst);

    // 3. Instrument Thread.start() to capture threads that might be created via
    // factories or virtual threads
    new AgentBuilder.Default()
        .ignore(ElementMatchers.nameStartsWith("net.bytebuddy."))
        .type(ElementMatchers.isSubTypeOf(Thread.class))
        .transform((builder, typeDescription, classLoader, module, protectionDomain) -> builder
            .method(ElementMatchers.named("start"))
            .intercept(Advice.to(ThreadStartAdvice.class)))
        .installOn(inst);

    // 4. Instrument Executor/ExecutorService to propagate context
    new AgentBuilder.Default()
        .type(ElementMatchers.isSubTypeOf(java.util.concurrent.Executor.class))
        .transform((builder, typeDescription, classLoader, module, protectionDomain) -> builder
            .method(ElementMatchers.named("execute").and(ElementMatchers.takesArguments(Runnable.class)))
            .intercept(Advice.to(ExecutorExecuteAdvice.class))
            .method(ElementMatchers.named("submit").and(ElementMatchers.takesArguments(Runnable.class)))
            .intercept(Advice.to(ExecutorSubmitRunnableAdvice.class))
            .method(ElementMatchers.named("submit")
                .and(ElementMatchers.takesArguments(java.util.concurrent.Callable.class)))
            .intercept(Advice.to(ExecutorSubmitCallableAdvice.class)))
        .installOn(inst);

    // 3. Register a GC listener
    GcMonitor.register();

    // 5. Enable Thread Memory Allocation Tracking
    try {
      java.lang.management.ThreadMXBean mxBean = java.lang.management.ManagementFactory.getThreadMXBean();
      if (mxBean instanceof com.sun.management.ThreadMXBean) {
        ((com.sun.management.ThreadMXBean) mxBean).setThreadAllocatedMemoryEnabled(true);
      }
    } catch (Throwable t) {
      // Ignore if not supported
    }
  }

  /** Advice that runs at the very beginning of a request handling method. */
  public static class RequestEntryAdvice {
    @Advice.OnMethodEnter
    public static void onEnter(@Advice.AllArguments Object[] args,
        @Advice.Origin("#t") String className,
        @Advice.Origin("#m") String methodName) {
      // Try to find a trace ID in arguments, otherwise generate one
      String traceId = (args.length > 0 && args[0] instanceof String)
          ? (String) args[0]
          : "trace-" + REQUEST_SEQ.incrementAndGet();

      RequestContext ctx = new RequestContext(traceId, className, methodName);
      RequestContextHolder.set(ctx);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit(@Advice.Thrown Throwable thrown) {
      RequestContext ctx = RequestContextHolder.get();
      if (ctx != null) {
        ctx.finish(); // compute totals, GC pressure, etc.
        // ctx.writeJson(); // write to file / log (already called in finish)
        RequestContextHolder.remove();
      }
    }
  }

  /** Advice that runs after every `new Thread(...)` call. */
  public static class ThreadConstructorAdvice {
    @Advice.OnMethodExit
    public static void onExit(@Advice.This Thread thread) {
      try {
        // Use reflection to avoid direct dependency on Profiler (which is on
        // AppClassLoader)
        // from classes loaded by Bootstrap/Platform ClassLoaders (like
        // java.lang.Thread).
        ClassLoader systemLoader = ClassLoader.getSystemClassLoader();
        if (systemLoader != null) {
          Class<?> profilerClass = systemLoader.loadClass("com.resource.profiler.Profiler");
          java.lang.reflect.Method method = profilerClass.getMethod("registerThread", Thread.class);
          method.invoke(null, thread);
        }
      } catch (Throwable t) {
        // Ignore errors
      }
    }
  }

  /** Advice that runs when `Thread.start()` is called. */
  public static class ThreadStartAdvice {
    @Advice.OnMethodEnter
    public static void onEnter(@Advice.This Thread thread) {
      try {
        ClassLoader systemLoader = ClassLoader.getSystemClassLoader();
        if (systemLoader != null) {
          Class<?> profilerClass = systemLoader.loadClass("com.resource.profiler.Profiler");
          java.lang.reflect.Method method = profilerClass.getMethod("registerThread", Thread.class);
          method.invoke(null, thread);
        }
      } catch (Throwable t) {
        // Ignore errors
      }
    }
  }

  public static class ExecutorExecuteAdvice {
    @Advice.OnMethodEnter
    public static void onEnter(@Advice.Argument(value = 0, readOnly = false) Runnable task) {
      try {
        if (task == null)
          return;
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null)
          return;

        // Load RequestContextHolder via TCCL
        Class<?> holderClass = cl.loadClass("com.resource.profiler.RequestContextHolder");
        java.lang.reflect.Method getMethod = holderClass.getMethod("get");
        Object ctx = getMethod.invoke(null);

        if (ctx != null) {
          // Load RunnableWrapper via TCCL
          Class<?> wrapperClass = cl.loadClass("com.resource.profiler.RunnableWrapper");
          Class<?> contextClass = cl.loadClass("com.resource.profiler.RequestContext");
          java.lang.reflect.Constructor<?> ctor = wrapperClass.getConstructor(Runnable.class, contextClass);
          task = (Runnable) ctor.newInstance(task, ctx);
        }
      } catch (Throwable t) {
        // Ignore errors to avoid breaking app
      }
    }
  }

  public static class ExecutorSubmitRunnableAdvice {
    @Advice.OnMethodEnter
    public static void onEnter(@Advice.Argument(value = 0, readOnly = false) Runnable task) {
      try {
        if (task == null)
          return;
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null)
          return;

        Class<?> holderClass = cl.loadClass("com.resource.profiler.RequestContextHolder");
        java.lang.reflect.Method getMethod = holderClass.getMethod("get");
        Object ctx = getMethod.invoke(null);

        if (ctx != null) {
          Class<?> wrapperClass = cl.loadClass("com.resource.profiler.RunnableWrapper");
          Class<?> contextClass = cl.loadClass("com.resource.profiler.RequestContext");
          java.lang.reflect.Constructor<?> ctor = wrapperClass.getConstructor(Runnable.class, contextClass);
          task = (Runnable) ctor.newInstance(task, ctx);
        }
      } catch (Throwable t) {
        // Ignore
      }
    }
  }

  public static class ExecutorSubmitCallableAdvice {
    @Advice.OnMethodEnter
    public static void onEnter(@Advice.Argument(value = 0, readOnly = false) java.util.concurrent.Callable<?> task) {
      try {
        if (task == null)
          return;
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null)
          return;

        Class<?> holderClass = cl.loadClass("com.resource.profiler.RequestContextHolder");
        java.lang.reflect.Method getMethod = holderClass.getMethod("get");
        Object ctx = getMethod.invoke(null);

        if (ctx != null) {
          Class<?> wrapperClass = cl.loadClass("com.resource.profiler.CallableWrapper");
          Class<?> contextClass = cl.loadClass("com.resource.profiler.RequestContext");
          java.lang.reflect.Constructor<?> ctor = wrapperClass.getConstructor(java.util.concurrent.Callable.class,
              contextClass);
          task = (java.util.concurrent.Callable<?>) ctor.newInstance(task, ctx);
        }
      } catch (Throwable t) {
        // Ignore
      }
    }
  }

  public static void registerThread(Thread thread) {
    // The direct caller class is obtained from the stack trace.
    StackTraceElement[] stack = Thread.currentThread().getStackTrace();
    // Find the first frame that is NOT the Thread class, Profiler, or Agent
    // infrastructure
    String originClass = "unknown";
    for (StackTraceElement element : stack) {
      String className = element.getClassName();
      if (!className.startsWith("java.lang.Thread") &&
          !className.startsWith("com.resource.profiler") &&
          !className.startsWith("net.bytebuddy")) {
        originClass = className;
        break;
      }
    }
    registerThread(thread, originClass);
  }

  public static void registerThread(Thread thread, String originClass) {
    RequestContext ctx = RequestContextHolder.get();
    if (ctx != null) {
      ctx.registerThread(thread, originClass);
    }
  }
}
