package com.resource.profiler.agent;

import com.resource.profiler.core.RequestContext;
import net.bytebuddy.asm.Advice;

public class ServletAdvice {

  private ServletAdvice() {}

  @Advice.OnMethodEnter
  public static void enter() {
    RequestContext.start();
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class)
  public static void exit() {
    RequestContext.end();
  }
}