package com.portflow.scheduler;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Earliest-deadline-first on min(deadline, expiry). Tasks with no limit go last. O(n log n). Optimal for meeting deadlines on one machine, but ignores priority and can cascade misses when overloaded. */
public final class EarliestDeadlinePolicy implements SchedulingPolicy {
    public String name() { return "EDF"; }
    public String description() { return "Earliest deadline/expiry first; tasks without a limit last."; }
    public List<Candidate> order(List<Candidate> ready, SchedulingContext ctx) {
        List<Candidate> l = new ArrayList<>(ready);
        Comparator<Candidate> byLimit = Comparator.comparing(c -> c.limit() == null ? LocalDateTime.MAX : c.limit());
        l.sort(byLimit.thenComparing(Comparator.comparingInt(Candidate::priority).reversed()).thenComparing(TIE));
        return l;
    }
}
