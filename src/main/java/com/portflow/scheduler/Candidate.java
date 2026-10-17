package com.portflow.scheduler;

import java.time.LocalDateTime;

/** Snapshot of a ready task used only for ordering. Hard constraints (dependencies, compatibility, capacity) are checked elsewhere. */
public record Candidate(long taskId, long workflowId, long cargoId, String stepCode, int priority, LocalDateTime readyAt,
                        LocalDateTime deadline, LocalDateTime expiry, int durationMin, int maxWaitMin) {

    /** Earliest instant by which the task must be finished/started: min(deadline, expiry). Null = no time limit. */
    public LocalDateTime limit() {
        if (deadline == null) return expiry;
        if (expiry == null) return deadline;
        return deadline.isBefore(expiry) ? deadline : expiry;
    }
}
