package com.resource.profiler.agent;

import com.resource.profiler.core.RequestContext;
import net.bytebuddy.asm.Advice;

public class ThreadStartAdvice {

  private ThreadStartAdvice() {}

  @Advice.OnMethodEnter
  public static void onEnter(@Advice.This Thread t) {
    try {
      if (t.isVirtual()) {
        // we notify RequestContext that a virtual thread object was created
        RequestContext.onVirtualThreadCreated(t);
      }
    } catch (Exception _) {
      // ignored
    }
  }
}

