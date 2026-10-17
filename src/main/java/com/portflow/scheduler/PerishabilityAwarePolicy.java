package com.portflow.scheduler;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Composite urgency score in [0,1]; higher = served first.  H = horizon (minutes, default 480).
 *   priorityN = (priority-1)/4
 *   slack     = minutes until min(deadline, expiry) minus the estimated duration                (no such limit => urgencyN = 0)
 *   urgencyN  = 1 - clamp(slack/H, 0, 1)                                                          (slack <= 0 => 1)
 *   waitN     = clamp(minutesWaited / maxWait, 0, 1)     share of the profile's maximum waiting time already used
 *               (maxWait = profile max_wait_min; ages tasks so nothing starves and favours tasks near a WAIT_TIMEOUT)
 *   shortN    = 1 - clamp(duration/240, 0, 1)                                                     (short jobs slightly favoured)
 *   score     = wP*priorityN + wU*urgencyN + wW*waitN + wS*shortN      (weights from Settings, default .30/.40/.20/.10)
 * Ties: earlier deadline/expiry, earlier ready time, lower task id. The score only ORDERS tasks; it can never bypass a hard constraint.
 * Complexity: O(n log n). Limitation: weights are tuned by hand, not learned.
 */
public final class PerishabilityAwarePolicy implements SchedulingPolicy {
    public String name() { return "PERISHABILITY"; }
    public String description() { return "Weighted score of priority, remaining shelf-life/deadline slack, waiting time and duration."; }

    public static double score(Candidate c, SchedulingContext x) {
        double h = x.horizonMin();
        double priorityN = (c.priority() - 1) / 4.0;
        double urgencyN = c.limit() == null ? 0 : 1 - clamp((minutes(x.now(), c.limit()) - c.durationMin()) / h);
        double waitN = clamp(minutes(c.readyAt(), x.now()) / (c.maxWaitMin() > 0 ? c.maxWaitMin() : h));
        double shortN = 1 - clamp(c.durationMin() / 240.0);
        return x.wPriority() * priorityN + x.wUrgency() * urgencyN + x.wWait() * waitN + x.wShort() * shortN;
    }

    private static double minutes(LocalDateTime a, LocalDateTime b) { return Duration.between(a, b).toMillis() / 60000.0; }
    private static double clamp(double v) { return Math.max(0, Math.min(1, v)); }

    public List<Candidate> order(List<Candidate> ready, SchedulingContext ctx) {
        List<Candidate> l = new ArrayList<>(ready);
        l.sort(Comparator.<Candidate>comparingDouble(c -> -score(c, ctx))
            .thenComparing(c -> c.limit() == null ? LocalDateTime.MAX : c.limit()).thenComparing(TIE));
        return l;
    }
}
