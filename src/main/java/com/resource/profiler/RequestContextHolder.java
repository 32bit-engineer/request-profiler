package com.resource.profiler;

public class RequestContextHolder {
    private static final ThreadLocal<RequestContext> holder = new ThreadLocal<>();

    public static void set(RequestContext ctx) {
        holder.set(ctx);
    }

    public static RequestContext get() {
        return holder.get();
    }

    public static void remove() {
        holder.remove();
    }
}
