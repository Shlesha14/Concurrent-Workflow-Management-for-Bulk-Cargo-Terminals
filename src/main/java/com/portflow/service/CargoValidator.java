package com.portflow.service;

import com.portflow.model.Cargo;
import com.portflow.model.CargoProfile;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Pure validation (no database). Separates MISSING information from INVALID/contradictory information. */
public final class CargoValidator {
    private CargoValidator() {}

    public enum Kind { MISSING, INVALID }
    public record Issue(Kind kind, String field, String message) {
        @Override public String toString() { return kind + " " + field + ": " + message; }
    }
    public static final Set<String> UNITS = Set.of("TONNE", "KG", "PALLET", "CRATE");

    public static List<Issue> validate(Cargo g, CargoProfile profile, LocalDateTime now) {
        List<Issue> out = new ArrayList<>();
        if (g.name() == null || g.name().isBlank()) out.add(new Issue(Kind.MISSING, "name", "Cargo name is required"));
        if (profile == null) { out.add(new Issue(Kind.MISSING, "profile", "Select a cargo category")); return out; }
        if (g.quantity() == null) out.add(new Issue(Kind.MISSING, "quantity", "Quantity is required"));
        else if (g.quantity().signum() <= 0) out.add(new Issue(Kind.INVALID, "quantity", "Quantity must be greater than zero"));
        if (g.unit() == null || !UNITS.contains(g.unit().toUpperCase())) out.add(new Issue(Kind.INVALID, "unit", "Unit must be one of " + UNITS));
        if (g.arrivalTime() == null) out.add(new Issue(Kind.MISSING, "arrival", "Arrival date/time is required"));
        if (g.priority() < 1 || g.priority() > 5) out.add(new Issue(Kind.INVALID, "priority", "Priority must be between 1 (low) and 5 (high)"));
        if (profile.requiresExpiry()) {
            if (g.manufacturedAt() == null) out.add(new Issue(Kind.MISSING, "manufactured", profile.displayName() + " requires a manufacturing/production time"));
            if (g.expiresAt() == null) out.add(new Issue(Kind.MISSING, "expiry", profile.displayName() + " requires an expiry time"));
        }
        if (g.manufacturedAt() != null && g.expiresAt() != null && !g.expiresAt().isAfter(g.manufacturedAt()))
            out.add(new Issue(Kind.INVALID, "expiry", "Expiry must be after the manufacturing time"));
        if (g.manufacturedAt() != null && g.arrivalTime() != null && g.manufacturedAt().isAfter(g.arrivalTime()))
            out.add(new Issue(Kind.INVALID, "manufactured", "Cargo cannot arrive before it was manufactured"));
        if (g.deadlineAt() != null) {
            if (g.arrivalTime() != null && g.deadlineAt().isBefore(g.arrivalTime())) out.add(new Issue(Kind.INVALID, "deadline", "Deadline is before the arrival time"));
            if (g.expiresAt() != null && g.deadlineAt().isAfter(g.expiresAt())) out.add(new Issue(Kind.INVALID, "deadline", "Deadline is after the expiry time (contradictory)"));
            if (g.deadlineAt().isBefore(now)) out.add(new Issue(Kind.INVALID, "deadline", "Deadline has already passed"));
        }
        return out;
    }

    /** Expiry is not a validation error: it is a policy outcome (reject or quarantine), decided by the profile. */
    public static boolean isExpired(Cargo g, LocalDateTime now) { return g.expiresAt() != null && !g.expiresAt().isAfter(now); }
}
