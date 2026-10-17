package com.portflow.scheduler;

import java.util.Comparator;
import java.util.List;

/**
 * Chooses the ORDER in which ready tasks are offered resources. A policy never decides whether a task is allowed to run:
 * dependencies, compatibility, capacity and safety are enforced by the engine and ResourceManager regardless of the score.
 */
public interface SchedulingPolicy {
    String name();
    String description();

    /** Returns a new list sorted best-first. Must be deterministic: equal inputs give equal output (final tie-break = task id). */
    List<Candidate> order(List<Candidate> ready, SchedulingContext ctx);

    /** Shared last-resort tie-break so results are reproducible. */
    Comparator<Candidate> TIE = Comparator.comparing(Candidate::readyAt).thenComparingLong(Candidate::taskId);
}
