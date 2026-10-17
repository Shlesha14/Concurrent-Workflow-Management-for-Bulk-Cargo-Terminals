package com.portflow.engine;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Controlled failure simulation: deterministic, set up by tests/scenarios. No random failures unless a scenario asks. */
public final class FailureInjector {
    public static final int PERMANENT = Integer.MAX_VALUE;
    private final Map<Long, Integer> remaining = new ConcurrentHashMap<>();

    /** Fail the next {@code times} execution attempts of the task (PERMANENT = always). */
    public void failTask(long taskId, int times) { remaining.put(taskId, times); }

    /** @return 0 = no failure, 1 = transient failure, 2 = permanent failure */
    int consume(long taskId) {
        Integer left = remaining.get(taskId);
        if (left == null || left <= 0) return 0;
        if (left == PERMANENT) return 2;
        remaining.put(taskId, left - 1);
        return 1;
    }
}
