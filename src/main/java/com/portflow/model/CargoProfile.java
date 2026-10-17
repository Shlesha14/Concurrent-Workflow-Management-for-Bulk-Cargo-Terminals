package com.portflow.model;

import java.util.List;

/** Handling profile loaded from the database: rules + workflow template. Adding a cargo category = inserting rows. */
public record CargoProfile(String code, String displayName, String category, boolean requiresExpiry,
                           Double tempMinC, Double tempMaxC, int maxWaitMin, String expiredPolicy,
                           String description, String simplificationNote, List<Step> steps) {

    public record Step(String code, String taskType, int baseMin, double perUnitMin, List<String> dependsOn, List<Req> requirements) {}
    /** amountMode: SLOT (1 slot) or CARGO_QTY (cargo quantity). */
    public record Req(String resourceType, String capability, String amountMode, String holdUntilStep) {}

    public boolean hasTemperatureRange() { return tempMinC != null && tempMaxC != null; }
}
