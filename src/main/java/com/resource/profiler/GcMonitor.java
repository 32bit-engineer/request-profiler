package com.resource.profiler;

import com.sun.management.GarbageCollectionNotificationInfo;
import javax.management.*;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;
import javax.management.openmbean.CompositeData;

public class GcMonitor {

    public static void register() {
        try {
            List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
            for (GarbageCollectorMXBean gcBean : gcBeans) {
                if (gcBean instanceof NotificationEmitter) {
                    NotificationEmitter emitter = (NotificationEmitter) gcBean;
                    emitter.addNotificationListener((notification, handback) -> {
                        if (notification.getType()
                                .equals(GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION)) {
                            GarbageCollectionNotificationInfo info = GarbageCollectionNotificationInfo
                                    .from((CompositeData) notification.getUserData());
                            long duration = info.getGcInfo().getDuration(); // ms
                            // Propagate to the current request (if any)
                            RequestContext ctx = RequestContextHolder.get();
                            if (ctx != null) {
                                ctx.recordGc(duration);
                            }
                        }
                    }, null, null);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
