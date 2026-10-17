package com.portflow.resource;

import com.portflow.model.BlockReason;

import java.util.List;

/** GRANTED: resources reserved and task is RUNNING. WAIT: ordinary contention, try later. BLOCKED: cannot proceed until something is changed. */
public record AllocationResult(Outcome outcome, BlockReason reason, String message, List<String> resourceNames) {
    public enum Outcome { GRANTED, WAIT, BLOCKED }
    public static AllocationResult granted(List<String> names) { return new AllocationResult(Outcome.GRANTED, null, "Allocated " + names, names); }
    public static AllocationResult wait(String msg) { return new AllocationResult(Outcome.WAIT, null, msg, List.of()); }
    public static AllocationResult blocked(BlockReason r, String msg) { return new AllocationResult(Outcome.BLOCKED, r, msg, List.of()); }
    public boolean isGranted() { return outcome == Outcome.GRANTED; }
}
