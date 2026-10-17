package com.portflow.scheduler;

import java.time.LocalDateTime;

public record SchedulingContext(LocalDateTime now, double wPriority, double wUrgency, double wWait, double wShort, int horizonMin) {
    public static SchedulingContext of(LocalDateTime now) { return new SchedulingContext(now, 0.30, 0.40, 0.20, 0.10, 480); }
}
