package com.portflow.service;

import com.portflow.dao.*;
import com.portflow.model.*;
import com.portflow.resource.AllocationResult;
import com.portflow.resource.ResourceSelector;
import com.portflow.scheduler.SchedulePlanner;
import com.portflow.scheduler.SchedulingPolicy;
import com.portflow.simulation.SimClock;
import com.portflow.util.Database;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.function.Supplier;

/** Resource CRUD plus the failure / recovery behaviour (schedule revision). */
public final class ResourceService {
    public record StatusChange(int blockedTasks, int recoveredTasks, int revokedHolds, int runningDrained, long revisionId) {}

    private final Database db; private final SimClock clock; private final EventLog log;
    private final Supplier<SchedulingPolicy> policy; private final SchedulePlanner planner = new SchedulePlanner();
    private static final class Wrapped extends RuntimeException { final ServiceException inner; Wrapped(ServiceException e) { super(e.getMessage()); inner = e; } }

    public ResourceService(Database db, SimClock clock, EventLog log, Supplier<SchedulingPolicy> policy) { this.db = db; this.clock = clock; this.log = log; this.policy = policy; }

    public List<Resource> list() throws SQLException { return db.read(ResourceDao::all); }
    public List<String> types() throws SQLException { return db.read(ResourceDao::types); }

    public int add(String name, String type, BigDecimal capacity, String location, Double tMin, Double tMax, Collection<String> caps) throws SQLException, ServiceException {
        if (name == null || name.isBlank()) throw new ServiceException("Resource name is required");
        if (capacity == null || capacity.signum() <= 0) throw new ServiceException("Capacity must be greater than zero");
        if (tMin != null && tMax != null && tMin > tMax) throw new ServiceException("Minimum temperature is above the maximum");
        try {
            return db.inTransaction(c -> {
                int id = ResourceDao.insert(c, name.trim(), type, capacity, "AVAILABLE", location, tMin, tMax, caps);
                log.log(c, EventType.OPERATOR_ACTION, null, null, id, null, null, null, "Resource " + name + " (" + type + ") added, capacity " + capacity);
                return id;
            });
        } catch (SQLException e) {
            if (e.getErrorCode() == 1062) throw new ServiceException("A resource named '" + name + "' already exists");
            throw e;
        }
    }

    /** Refuses edits that would contradict live allocations (e.g. shrinking storage below what is stored). */
    public void update(int id, String name, BigDecimal capacity, String location, Double tMin, Double tMax, Collection<String> caps) throws SQLException, ServiceException {
        try {
            db.inTransaction(c -> {
                try {
                    Resource r = ResourceDao.lockByType(c, ResourceDao.find(c, id).orElseThrow(() -> new ServiceException("Resource not found")).typeCode())
                        .stream().filter(x -> x.id() == id).findFirst().get();
                    BigDecimal used = AllocationDao.inUse(c, id);
                    if (capacity.compareTo(used) < 0) throw new ServiceException("Capacity " + capacity + " is below the " + used + " currently allocated on " + r.name());
                    boolean tempChanged = !Objects.equals(tMin, r.tempMinC()) || !Objects.equals(tMax, r.tempMaxC());
                    if (tempChanged && !AllocationDao.onResource(c, id, "HELD").isEmpty()) throw new ServiceException("Cannot change the temperature range while cargo is stored in " + r.name());
                    ResourceDao.update(c, id, name.trim(), capacity, location, tMin, tMax, caps);
                    log.log(c, EventType.OPERATOR_ACTION, null, null, id, null, null, null, "Resource " + name + " updated");
                    return null;
                } catch (ServiceException e) { throw new Wrapped(e); }
            });
        } catch (Wrapped w) { throw w.inner; }
    }

    /** Simulated maintenance / failure (AVAILABLE -> MAINTENANCE|UNAVAILABLE) or recovery (-> AVAILABLE). */
    public StatusChange setStatus(int id, String newStatus, String reason) throws SQLException, ServiceException {
        if (!Set.of("AVAILABLE", "MAINTENANCE", "UNAVAILABLE").contains(newStatus)) throw new ServiceException("Unknown status " + newStatus);
        try {
            return db.inTransaction(c -> {
                try { return changeStatus(c, id, newStatus, reason); } catch (ServiceException e) { throw new Wrapped(e); }
            });
        } catch (Wrapped w) { throw w.inner; }
    }

    /** Moves a task if it is still in one of the allowed states; re-reads and retries when the engine changed it concurrently. */
    private boolean moveTask(Connection c, long taskId, List<TaskState> allowedFrom, TaskState to, BlockReason reason, String detail) throws SQLException {
        for (int attempt = 0; attempt < 3; attempt++) {
            Task cur = TaskDao.find(c, taskId).orElse(null);
            if (cur == null || !allowedFrom.contains(cur.state())) return false;
            try { TaskDao.transition(c, log, clock, cur, cur.state(), to, reason, detail); return true; }
            catch (TaskDao.StateConflictException e) { /* re-read and decide again */ }
        }
        return false;
    }

    private StatusChange changeStatus(Connection c, int id, String newStatus, String reason) throws SQLException, ServiceException {
        Resource r0 = ResourceDao.find(c, id).orElseThrow(() -> new ServiceException("Resource not found"));
        List<Resource> sameType = ResourceDao.lockByType(c, r0.typeCode());            // serialise with allocations
        Resource r = sameType.stream().filter(x -> x.id() == id).findFirst().get();
        if (r.status().equals(newStatus)) return new StatusChange(0, 0, 0, 0, 0);
        ResourceDao.setStatus(c, id, newStatus);                                       // trigger writes RESOURCE_STATUS_CHANGED
        int blocked = 0, recovered = 0, revoked = 0, drained = 0;
        if (!"AVAILABLE".equals(newStatus)) {
            // Running tasks cannot be moved or rolled back: they DRAIN (finish normally). No new work is placed here.
            drained = AllocationDao.onResource(c, id, "ACTIVE").size();
            for (AllocationDao.Alloc h : AllocationDao.onResource(c, id, "HELD")) {
                AllocationDao.setStatus(c, h.id(), "HELD", "REVOKED", clock.now(), "resource " + newStatus);
                log.log(c, EventType.RESOURCE_REVOKED, h.taskId(), null, id, null, null, null, "Stored cargo hold on " + r.name() + " revoked: resource " + newStatus + " (cargo needs operator action)");
                revoked++;
            }
            for (Task t : TaskDao.byState(c, TaskState.VALIDATED, TaskState.READY, TaskState.WAITING_FOR_RESOURCE)) {
                CargoProfile p = ProfileDao.find(c, CargoDao.find(c, t.cargoId()).get().profileCode()).get();
                for (TaskRequirement req : TaskDao.requirements(c, t.id())) {
                    if (!req.resourceType().equals(r.typeCode())) continue;
                    List<Resource> now = ResourceDao.all(c).stream().filter(x -> x.typeCode().equals(req.resourceType())).toList();
                    AllocationResult res = ResourceSelector.explainFailure(now, req, p);
                    if (res.outcome() == AllocationResult.Outcome.BLOCKED) {
                        if (moveTask(c, t.id(), List.of(TaskState.VALIDATED, TaskState.READY, TaskState.WAITING_FOR_RESOURCE), TaskState.BLOCKED, res.reason(), res.message())) blocked++;
                        break;
                    }
                }
            }
        } else {
            for (Task t : TaskDao.byState(c, TaskState.BLOCKED)) {
                if (!BlockReason.RESOURCE_UNAVAILABLE.name().equals(t.blockCode()) && !BlockReason.NO_COMPATIBLE_RESOURCE.name().equals(t.blockCode())) continue;
                CargoProfile p = ProfileDao.find(c, CargoDao.find(c, t.cargoId()).get().profileCode()).get();
                boolean stillBlocked = false;
                for (TaskRequirement req : TaskDao.requirements(c, t.id())) {
                    List<Resource> now = ResourceDao.all(c).stream().filter(x -> x.typeCode().equals(req.resourceType())).toList();
                    if (ResourceSelector.explainFailure(now, req, p).outcome() == AllocationResult.Outcome.BLOCKED) { stillBlocked = true; break; }
                }
                if (!stillBlocked && moveTask(c, t.id(), List.of(TaskState.BLOCKED), TaskState.READY, null, "resource " + r.name() + " available again")) recovered++;
            }
        }
        String code = "AVAILABLE".equals(newStatus) ? "RESOURCE_RESTORED" : "RESOURCE_UNAVAILABLE";
        SchedulePlanner.Plan plan = planner.preview(c, policy.get(), Settings.load(c), clock.now());
        String desc = r.name() + " -> " + newStatus + (reason != null && !reason.isBlank() ? " (" + reason + ")" : "") + "; blocked " + blocked + ", recovered " + recovered + ", holds revoked " + revoked + ", running drained " + drained;
        long rev = planner.save(c, plan, code, desc, id, clock.now());
        log.log(c, EventType.SCHEDULE_REVISED, null, null, id, null, null, null, "Schedule revision #" + rev + ": " + desc);
        return new StatusChange(blocked, recovered, revoked, drained, rev);
    }
}
