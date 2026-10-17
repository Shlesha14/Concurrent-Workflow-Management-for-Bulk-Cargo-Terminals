package com.portflow.scheduler;

import java.util.ArrayList;
import java.util.List;

/** First-come first-served: oldest ready time first. O(n log n). Fair and starvation-free, but ignores urgency. */
public final class FcfsPolicy implements SchedulingPolicy {
    public String name() { return "FCFS"; }
    public String description() { return "Oldest ready task first (arrival order)."; }
    public List<Candidate> order(List<Candidate> ready, SchedulingContext ctx) {
        List<Candidate> l = new ArrayList<>(ready); l.sort(TIE); return l;
    }
}
