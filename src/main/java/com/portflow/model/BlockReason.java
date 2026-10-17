package com.portflow.model;

/** Why a task is BLOCKED. Separates invalid input, permanent impossibility and ordinary contention (which is WAITING, not BLOCKED). */
public enum BlockReason {
    PREREQUISITE_FAILED("A prerequisite task failed, was cancelled, expired or is itself blocked"),
    RESOURCE_UNAVAILABLE("Compatible resources exist but are all in maintenance/unavailable"),
    NO_COMPATIBLE_RESOURCE("No resource in the terminal has the required type, capability or storage condition"),
    CAPACITY_EXCEEDS_MAX("Requested amount is larger than the capacity of every compatible resource"),
    WAIT_TIMEOUT("Waited for a resource longer than the profile's maximum waiting time"),
    RETRIES_EXHAUSTED("Task failed and the maximum number of retries was used"),
    DEADLOCK_VICTIM("Chosen as victim to break a suspected circular wait"),
    INFORMATION_MISSING("Required cargo information is missing"),
    INVALID_WORKFLOW("The workflow dependency graph is invalid (cycle or unknown dependency)");
    public final String description;
    BlockReason(String d) { description = d; }
}
