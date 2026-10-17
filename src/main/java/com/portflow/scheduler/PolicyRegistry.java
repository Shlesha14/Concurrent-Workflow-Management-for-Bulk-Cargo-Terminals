package com.portflow.scheduler;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PolicyRegistry {
    private static final Map<String, SchedulingPolicy> POLICIES = new LinkedHashMap<>();
    static {
        for (SchedulingPolicy p : List.of(new FcfsPolicy(), new PriorityPolicy(), new EarliestDeadlinePolicy(), new PerishabilityAwarePolicy())) POLICIES.put(p.name(), p);
    }
    private PolicyRegistry() {}
    public static SchedulingPolicy get(String name) {
        SchedulingPolicy p = POLICIES.get(name);
        if (p == null) throw new IllegalArgumentException("Unknown scheduling policy " + name);
        return p;
    }
    public static List<SchedulingPolicy> all() { return List.copyOf(POLICIES.values()); }
    public static List<String> names() { return List.copyOf(POLICIES.keySet()); }
}
