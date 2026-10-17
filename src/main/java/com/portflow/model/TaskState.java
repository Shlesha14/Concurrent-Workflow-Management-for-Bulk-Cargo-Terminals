package com.portflow.model;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** Task state machine. Only the transitions listed in ALLOWED are legal; the DAO enforces this before every update. */
public enum TaskState {
    CREATED, VALIDATED, READY, WAITING_FOR_RESOURCE, RUNNING, COMPLETED, FAILED, BLOCKED, CANCELLED, EXPIRED;

    private static final Map<TaskState, Set<TaskState>> ALLOWED = new EnumMap<>(TaskState.class);
    static {
        ALLOWED.put(CREATED, EnumSet.of(VALIDATED, BLOCKED, CANCELLED));
        ALLOWED.put(VALIDATED, EnumSet.of(READY, BLOCKED, CANCELLED, EXPIRED));
        ALLOWED.put(READY, EnumSet.of(WAITING_FOR_RESOURCE, RUNNING, BLOCKED, CANCELLED, EXPIRED));
        ALLOWED.put(WAITING_FOR_RESOURCE, EnumSet.of(READY, RUNNING, BLOCKED, CANCELLED, EXPIRED));
        ALLOWED.put(RUNNING, EnumSet.of(COMPLETED, FAILED));
        ALLOWED.put(FAILED, EnumSet.of(READY, CANCELLED));
        ALLOWED.put(BLOCKED, EnumSet.of(READY, VALIDATED, CANCELLED, EXPIRED));
        ALLOWED.put(COMPLETED, EnumSet.noneOf(TaskState.class));
        ALLOWED.put(CANCELLED, EnumSet.noneOf(TaskState.class));
        ALLOWED.put(EXPIRED, EnumSet.noneOf(TaskState.class));
    }

    public boolean canGoTo(TaskState next) { return ALLOWED.get(this).contains(next); }
    /** Terminal: nothing can happen to the task any more. */
    public boolean isTerminal() { return this == COMPLETED || this == CANCELLED || this == EXPIRED; }
    /** Failure-like: dependants must not run. */
    public boolean isFailureLike() { return this == FAILED || this == CANCELLED || this == EXPIRED; }
}
