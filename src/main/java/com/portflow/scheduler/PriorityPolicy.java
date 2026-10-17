package com.portflow.scheduler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Operator priority (5 = highest) first, FCFS among equals. O(n log n). Can starve low priorities under constant high-priority load. */
public final class PriorityPolicy implements SchedulingPolicy {
    public String name() { return "PRIORITY"; }
    public String description() { return "Highest user priority first; ties by ready time."; }
    public List<Candidate> order(List<Candidate> ready, SchedulingContext ctx) {
        List<Candidate> l = new ArrayList<>(ready);
        l.sort(Comparator.comparingInt(Candidate::priority).reversed().thenComparing(TIE));
        return l;
    }
}
