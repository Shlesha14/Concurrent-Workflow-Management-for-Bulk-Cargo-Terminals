package com.portflow.scheduler;

import com.portflow.dao.*;
import com.portflow.model.*;
import com.portflow.resource.ResourceSelector;
import com.portflow.service.Settings;
import com.portflow.util.Jdbc;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Forward list-scheduler used for PREVIEW and for recording schedule revisions. It simulates the future on the CURRENT
 * database state using the chosen policy and the same compatibility/capacity rules as the live engine; it changes no task.
 * SLOTS resources: each slot has a "free at" time. QUANTITY resources: remaining capacity (storage is held, so it is not freed in the plan).
 */
public final class SchedulePlanner {
    public record Entry(long taskId, String step, int resourceId, String resourceName, LocalDateTime start, LocalDateTime end) {}
    public record Plan(String policy, LocalDateTime createdAt, List<Entry> entries, List<String> conflicts, List<String> deadlineRisks, List<String> unscheduled) {}

    public Plan preview(Connection c, SchedulingPolicy policy, Settings s, LocalDateTime now) throws SQLException {
        List<Resource> resources = ResourceDao.all(c);
        Map<Integer, Resource> resById = new HashMap<>();
        resources.forEach(r -> resById.put(r.id(), r));
        Map<Integer, LocalDateTime[]> slots = new HashMap<>();
        Map<Integer, BigDecimal> remaining = new HashMap<>();
        for (Resource r : resources) {
            if (r.isQuantityModel()) remaining.put(r.id(), AllocationDao.inUse(c, r.id()));   // holds "used" so far; choose() subtracts from capacity
            else { LocalDateTime[] a = new LocalDateTime[r.capacity().intValue()]; Arrays.fill(a, now); slots.put(r.id(), a); }
        }
        Map<Long, Cargo> cargoById = new HashMap<>();
        for (Cargo g : CargoDao.all(c)) cargoById.put(g.id(), g);
        Map<String, CargoProfile> profiles = new HashMap<>();
        for (CargoProfile p : ProfileDao.loadAll(c)) profiles.put(p.code(), p);

        List<Task> all = TaskDao.all(c);
        Map<Long, LocalDateTime> endTime = new HashMap<>();       // planned/estimated completion; null value = already completed
        List<Task> pending = new ArrayList<>();
        for (Task t : all) {
            switch (t.state()) {
                case COMPLETED -> endTime.put(t.id(), LocalDateTime.MIN);
                case RUNNING -> {
                    LocalDateTime est = (t.startedAt() == null ? now : t.startedAt()).plusMinutes(t.durationMin());
                    endTime.put(t.id(), est.isBefore(now) ? now : est);
                    for (AllocationDao.Alloc a : AllocationDao.forTask(c, t.id(), "ACTIVE")) {
                        LocalDateTime[] sl = slots.get(a.resourceId());
                        if (sl != null) { int i = earliest(sl); sl[i] = endTime.get(t.id()); }
                    }
                }
                case VALIDATED, READY, WAITING_FOR_RESOURCE -> pending.add(t);
                default -> { }
            }
        }
        Map<Long, List<Long>> prereq = new HashMap<>();
        for (Task t : all) prereq.put(t.id(), TaskDao.prerequisites(c, t.id()));

        List<Entry> entries = new ArrayList<>();
        Map<Integer, BigDecimal> planned = new HashMap<>();
        int guard = 0;
        LocalDateTime t = now;
        while (!pending.isEmpty() && guard++ < 5000) {
            final LocalDateTime tNow = t;
            List<Task> ready = new ArrayList<>();
            for (Task p : pending) {
                boolean ok = prereq.get(p.id()).stream().allMatch(d -> endTime.containsKey(d) && !endTime.get(d).isAfter(tNow));
                Cargo g = cargoById.get(p.cargoId());
                if (ok && !g.arrivalTime().isAfter(tNow)) ready.add(p);
            }
            SchedulingContext ctx = new SchedulingContext(t, s.wPriority(), s.wUrgency(), s.wWait(), s.wShort(), s.horizonMin());
            Map<Long, Task> byId = new HashMap<>();
            List<Candidate> cands = new ArrayList<>();
            for (Task p : ready) {
                byId.put(p.id(), p);
                Cargo g = cargoById.get(p.cargoId());
                cands.add(new Candidate(p.id(), p.workflowId(), p.cargoId(), p.stepCode(), p.priority(), p.readyAt() == null ? now : p.readyAt(), p.deadlineAt(), g.expiresAt(), p.durationMin(), profiles.get(g.profileCode()).maxWaitMin()));
            }
            for (Candidate cand : policy.order(cands, ctx)) {
                Task task = byId.get(cand.taskId());
                List<Entry> got = tryAssign(c, task, cargoById.get(task.cargoId()), profiles.get(cargoById.get(task.cargoId()).profileCode()), t, resources, slots, remaining, planned);
                if (got != null) { entries.addAll(got); endTime.put(task.id(), t.plusMinutes(task.durationMin())); pending.remove(task); }
            }
            LocalDateTime next = null;
            for (LocalDateTime[] sl : slots.values()) for (LocalDateTime x : sl) if (x.isAfter(t) && (next == null || x.isBefore(next))) next = x;
            for (LocalDateTime x : endTime.values()) if (x.isAfter(t) && (next == null || x.isBefore(next))) next = x;
            for (Task p : pending) { LocalDateTime a = cargoById.get(p.cargoId()).arrivalTime(); if (a.isAfter(t) && (next == null || a.isBefore(next))) next = a; }
            if (next == null) break;
            t = next;
        }
        List<String> unscheduled = new ArrayList<>();
        for (Task p : pending) unscheduled.add("Task #" + p.id() + " " + p.stepCode() + " (cargo #" + p.cargoId() + ") cannot be placed (blocked prerequisite or no compatible resource)");
        List<String> risks = new ArrayList<>();
        for (Entry e : entries) {
            Task task = all.stream().filter(x -> x.id() == e.taskId()).findFirst().get();
            Cargo g = cargoById.get(task.cargoId());
            if (task.deadlineAt() != null && e.end().isAfter(task.deadlineAt())) risks.add("Task #" + task.id() + " " + task.stepCode() + " may finish " + e.end() + " after deadline " + task.deadlineAt());
            else if (g.expiresAt() != null && e.end().isAfter(g.expiresAt())) risks.add("Task #" + task.id() + " " + task.stepCode() + " may finish after cargo expiry " + g.expiresAt());
        }
        return new Plan(policy.name(), now, entries, detectConflicts(entries, resById), risks, unscheduled);
    }

    private static int earliest(LocalDateTime[] a) { int b = 0; for (int i = 1; i < a.length; i++) if (a[i].isBefore(a[b])) b = i; return b; }

    private List<Entry> tryAssign(Connection c, Task task, Cargo cargo, CargoProfile profile, LocalDateTime t, List<Resource> resources,
                                  Map<Integer, LocalDateTime[]> slots, Map<Integer, BigDecimal> used, Map<Integer, BigDecimal> planned) throws SQLException {
        List<TaskRequirement> reqs = new ArrayList<>(TaskDao.requirements(c, task.id()));
        reqs.sort(Comparator.comparing(TaskRequirement::resourceType).thenComparingLong(TaskRequirement::id));
        LocalDateTime end = t.plusMinutes(task.durationMin());
        List<Object[]> picks = new ArrayList<>();          // {resource, slotIndex or -1, requirement}
        Map<Integer, Integer> tentativeSlots = new HashMap<>();
        Map<Integer, BigDecimal> tentativeQty = new HashMap<>();
        for (TaskRequirement req : reqs) {
            List<Resource> same = resources.stream().filter(r -> r.typeCode().equals(req.resourceType())).toList();
            Resource chosen = null; int slot = -1;
            if (!same.isEmpty() && same.get(0).isQuantityModel()) {
                Map<Integer, BigDecimal> inUse = new HashMap<>();
                for (Resource r : same) inUse.put(r.id(), used.getOrDefault(r.id(), BigDecimal.ZERO).add(planned.getOrDefault(r.id(), BigDecimal.ZERO)));
                chosen = ResourceSelector.choose(same, inUse, tentativeQty, req, profile).orElse(null);
                if (chosen != null) tentativeQty.merge(chosen.id(), req.amount(), BigDecimal::add);
            } else {
                for (Resource r : same) {
                    if (!"AVAILABLE".equals(r.status()) || !ResourceSelector.structurallyCompatible(r, req, profile)) continue;
                    LocalDateTime[] sl = slots.get(r.id());
                    for (int i = 0; i < sl.length; i++) {
                        final int idx = i;
                        long taken = tentativeSlots.entrySet().stream().filter(e -> e.getKey() == r.id() * 1000 + idx).count();
                        if (taken == 0 && !sl[i].isAfter(t)) { chosen = r; slot = i; break; }
                    }
                    if (chosen != null) break;
                }
                if (chosen != null) tentativeSlots.put(chosen.id() * 1000 + slot, 1);
            }
            if (chosen == null) return null;
            picks.add(new Object[]{chosen, slot, req});
        }
        List<Entry> out = new ArrayList<>();
        for (Object[] p : picks) {
            Resource r = (Resource) p[0]; int slot = (Integer) p[1]; TaskRequirement req = (TaskRequirement) p[2];
            if (slot >= 0) slots.get(r.id())[slot] = end; else planned.merge(r.id(), req.amount(), BigDecimal::add);
            out.add(new Entry(task.id(), task.stepCode(), r.id(), r.name(), t, end));
        }
        return out;
    }

    /** Conflict = more simultaneous planned uses of a SLOTS resource than its capacity. Works on any plan (also hand-made ones). */
    public static List<String> detectConflicts(List<Entry> entries, Map<Integer, Resource> resources) {
        List<String> out = new ArrayList<>();
        Map<Integer, List<Entry>> by = new HashMap<>();
        for (Entry e : entries) by.computeIfAbsent(e.resourceId(), k -> new ArrayList<>()).add(e);
        for (var en : by.entrySet()) {
            Resource r = resources.get(en.getKey());
            if (r == null || r.isQuantityModel()) continue;
            for (Entry a : en.getValue()) {
                long overlapping = en.getValue().stream().filter(b -> b.start().isBefore(a.end()) && a.start().isBefore(b.end())).count();
                if (overlapping > r.capacity().intValue()) out.add("Resource " + r.name() + " overbooked at " + a.start() + ": " + overlapping + " tasks, capacity " + r.capacity().intValue());
            }
        }
        return out.stream().distinct().toList();
    }

    /** Persists a plan as a new revision; older revisions and their entries are kept forever. */
    public long save(Connection c, Plan plan, String reasonCode, String description, Integer resourceId, LocalDateTime now) throws SQLException {
        long rev = Jdbc.insert(c, "INSERT INTO schedule_revision (created_at, reason_code, description, resource_id, policy_name, affected_tasks) VALUES (?,?,?,?,?,?)",
            now, reasonCode, description.length() > 250 ? description.substring(0, 250) : description, resourceId, plan.policy(),
            (int) plan.entries().stream().map(Entry::taskId).distinct().count());
        for (Entry e : plan.entries())
            Jdbc.update(c, "INSERT INTO plan_entry (revision_id, task_id, resource_id, planned_start, planned_end) VALUES (?,?,?,?,?)", rev, e.taskId(), e.resourceId(), e.start(), e.end());
        return rev;
    }
}
