package com.resource.profiler.agent;

import com.resource.profiler.core.RequestContext;
import net.bytebuddy.asm.Advice;

public class ExecutorAdvice {

  private ExecutorAdvice() {}

  @Advice.OnMethodEnter
  public static void onEnter(@Advice.Argument(value = 0, readOnly = false) Runnable task) {
    // wrap task so child execution is tracked
    RequestContext.wrap(task);
  }
}
