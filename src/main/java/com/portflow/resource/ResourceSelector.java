package com.portflow.resource;

import com.portflow.model.*;

import java.math.BigDecimal;
import java.util.*;

/**
 * Compatibility rules and the resource-choice rule.
 * Capacity models: SLOTS (discrete: each task takes 1 slot) and QUANTITY (continuous: cargo handling units stored).
 * Choice rule (deterministic, documented in docs/SCHEDULING.md):
 *   QUANTITY resources -> BEST FIT: smallest leftover capacity after placing the cargo; tie -> lowest resource id
 *   SLOTS resources    -> FIRST FIT: lowest resource id that has a free slot
 */
public final class ResourceSelector {
    private ResourceSelector() {}

    /** Structural compatibility ignores current status and load: type, capability and storage condition only. */
    public static boolean structurallyCompatible(Resource r, TaskRequirement req, CargoProfile profile) {
        if (!r.typeCode().equals(req.resourceType())) return false;
        if (req.capability() != null && !req.capability().isBlank() && !r.capabilities().contains(req.capability())) return false;
        if (r.isQuantityModel() && profile != null && profile.hasTemperatureRange()) {
            // The storage's whole operating range must sit inside the cargo's permitted range (a -5..5 C room is NOT ok for 0..4 C produce).
            return r.tempMinC() != null && r.tempMaxC() != null && r.tempMinC() >= profile.tempMinC() && r.tempMaxC() <= profile.tempMaxC();
        }
        return true;
    }

    /** @param pending amounts already planned for other requirements of the same task */
    public static Optional<Resource> choose(List<Resource> sameType, Map<Integer, BigDecimal> inUse, Map<Integer, BigDecimal> pending, TaskRequirement req, CargoProfile profile) {
        Resource best = null;
        BigDecimal bestLeft = null;
        for (Resource r : sameType) {
            if (!"AVAILABLE".equals(r.status()) || !structurallyCompatible(r, req, profile)) continue;
            BigDecimal free = r.capacity().subtract(inUse.getOrDefault(r.id(), BigDecimal.ZERO)).subtract(pending.getOrDefault(r.id(), BigDecimal.ZERO));
            BigDecimal left = free.subtract(req.amount());
            if (left.signum() < 0) continue;
            if (!r.isQuantityModel()) return Optional.of(r);            // first fit (list is ordered by id)
            if (best == null || left.compareTo(bestLeft) < 0) { best = r; bestLeft = left; }   // best fit; strict < keeps lowest id on ties
        }
        return Optional.ofNullable(best);
    }

    /** Explains why nothing could be chosen. Distinguishes permanent problems (BLOCKED) from contention (WAIT). */
    public static AllocationResult explainFailure(List<Resource> sameType, TaskRequirement req, CargoProfile profile) {
        List<Resource> structural = sameType.stream().filter(r -> structurallyCompatible(r, req, profile)).toList();
        String what = req.resourceType() + (req.capability() != null ? "[" + req.capability() + "]" : "")
            + (profile != null && profile.hasTemperatureRange() && sameType.stream().anyMatch(Resource::isQuantityModel) ? " within " + profile.tempMinC() + ".." + profile.tempMaxC() + " C" : "");
        if (structural.isEmpty()) return AllocationResult.blocked(BlockReason.NO_COMPATIBLE_RESOURCE, "No " + what + " resource exists");
        List<Resource> available = structural.stream().filter(r -> "AVAILABLE".equals(r.status())).toList();
        if (available.isEmpty()) return AllocationResult.blocked(BlockReason.RESOURCE_UNAVAILABLE, "All compatible " + what + " resources are in maintenance/unavailable");
        if (available.stream().allMatch(r -> r.capacity().compareTo(req.amount()) < 0))
            return AllocationResult.blocked(BlockReason.CAPACITY_EXCEEDS_MAX, "Need " + req.amount() + " but the largest compatible " + what + " has " +
                available.stream().map(Resource::capacity).max(Comparator.naturalOrder()).orElse(BigDecimal.ZERO));
        return AllocationResult.wait("All compatible " + what + " resources are currently full");
    }
}
