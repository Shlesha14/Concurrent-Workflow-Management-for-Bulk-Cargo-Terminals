package com.portflow.resource;

import com.portflow.dao.*;
import com.portflow.model.*;
import com.portflow.simulation.SimClock;
import com.portflow.util.Database;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Resource allocation with two cooperating layers.
 *  1. Java layer (OS): a fair ReentrantLock with tryLock(timeout) serialises the allocation decision inside this JVM
 *     (mutual exclusion over a critical section, bounded wait, so a thread can never wait forever).
 *  2. Database layer (DBMS): inside one READ COMMITTED transaction all resources of the needed type are locked with
 *     SELECT ... FOR UPDATE in a fixed order (type, then id). The row lock is what truly prevents over-allocation, even
 *     with several JVMs, and the fixed order prevents DB-level circular waits.
 * Deadlock PREVENTION: a task gets ALL its resources or NONE (no hold-and-wait), acquired in a global order (no circular wait).
 */
public final class ResourceManager {
    public record LockStats(long attempts, long conflicts, long totalLockWaitNanos, long maxLockWaitNanos) {}

    private final Database db;
    private final SimClock clock;
    private final EventLog log;
    private final ReentrantLock allocationLock = new ReentrantLock(true);
    private volatile boolean useJavaLock = true;
    private volatile long lockTimeoutMs = 3000;
    private volatile Runnable faultInjector;               // test hook: runs after inserts, before commit
    private final AtomicLong attempts = new AtomicLong(), conflicts = new AtomicLong(), waitNanos = new AtomicLong(), maxWaitNanos = new AtomicLong();

    public ResourceManager(Database db, SimClock clock, EventLog log) { this.db = db; this.clock = clock; this.log = log; }

    public void setUseJavaLock(boolean b) { useJavaLock = b; }
    public void setLockTimeoutMs(long ms) { lockTimeoutMs = ms; }
    public void setFaultInjector(Runnable r) { faultInjector = r; }
    public LockStats stats() { return new LockStats(attempts.get(), conflicts.get(), waitNanos.get(), maxWaitNanos.get()); }
    public void resetStats() { attempts.set(0); conflicts.set(0); waitNanos.set(0); maxWaitNanos.set(0); }

    private void addWait(long n) { waitNanos.addAndGet(n); maxWaitNanos.accumulateAndGet(n, Math::max); }

    /** Reserves every requirement of the task and moves it to RUNNING in ONE transaction (all or nothing). */
    public AllocationResult allocateAndStart(Task task, Cargo cargo, CargoProfile profile) throws SQLException {
        attempts.incrementAndGet();
        boolean locked = false;
        long t0 = System.nanoTime();
        try {
            if (useJavaLock) {
                try { locked = allocationLock.tryLock(lockTimeoutMs, TimeUnit.MILLISECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return AllocationResult.wait("Interrupted"); }
                addWait(System.nanoTime() - t0);
                if (!locked) { conflicts.incrementAndGet(); return AllocationResult.wait("Timed out waiting for the allocation lock"); }
            }
            AllocationResult r = db.inTransaction(Connection.TRANSACTION_READ_COMMITTED, c -> doAllocate(c, task, cargo, profile));
            if (r.outcome() == AllocationResult.Outcome.WAIT) conflicts.incrementAndGet();
            return r;
        } finally {
            if (locked) allocationLock.unlock();
        }
    }

    private AllocationResult doAllocate(Connection c, Task task, Cargo cargo, CargoProfile profile) throws SQLException {
        List<TaskRequirement> reqs = new ArrayList<>(TaskDao.requirements(c, task.id()));
        reqs.sort(Comparator.comparing(TaskRequirement::resourceType).thenComparingLong(TaskRequirement::id));   // global lock order
        Map<Integer, BigDecimal> pending = new HashMap<>();
        List<Object[]> plan = new ArrayList<>();
        Map<String, List<Resource>> lockedTypes = new HashMap<>();
        Map<Integer, BigDecimal> inUse = new HashMap<>();
        for (TaskRequirement req : reqs) {
            List<Resource> same = lockedTypes.get(req.resourceType());
            if (same == null) {
                long l0 = System.nanoTime();
                same = ResourceDao.lockByType(c, req.resourceType());        // row locks, ordered by id
                addWait(System.nanoTime() - l0);
                lockedTypes.put(req.resourceType(), same);
                for (Resource r : same) inUse.put(r.id(), AllocationDao.inUse(c, r.id()));   // read AFTER locking
            }
            Optional<Resource> pick = ResourceSelector.choose(same, inUse, pending, req, profile);
            if (pick.isEmpty()) {
                AllocationResult why = ResourceSelector.explainFailure(same, req, profile);
                log.log(c, EventType.ALLOCATION_DENIED, task.id(), task.cargoId(), null, task.workflowId(), null, null,
                    "Task #" + task.id() + " " + task.stepCode() + " denied: " + why.message());
                return why;                                                   // nothing inserted yet -> nothing to undo
            }
            pending.merge(pick.get().id(), req.amount(), BigDecimal::add);
            plan.add(new Object[]{req, pick.get()});
        }
        LocalDateTime now = clock.now();
        List<String> names = new ArrayList<>();
        for (Object[] p : plan) {
            TaskRequirement req = (TaskRequirement) p[0];
            Resource r = (Resource) p[1];
            AllocationDao.insert(c, task.id(), req.id(), r.id(), req.amount(), now, req.holdUntilStep());
            names.add(r.name());
            log.log(c, EventType.RESOURCE_ACQUIRED, task.id(), task.cargoId(), r.id(), task.workflowId(), null, null,
                "Task #" + task.id() + " " + task.stepCode() + " acquired " + r.name() + " (" + req.amount().stripTrailingZeros().toPlainString() + ")");
        }
        Runnable f = faultInjector;
        if (f != null) f.run();                                               // may throw -> whole transaction rolls back
        TaskDao.transition(c, log, clock, task, task.state(), TaskState.RUNNING, null, "resources " + names);
        return AllocationResult.granted(names);
    }

    /** Called inside the completion transaction. Equipment is released; storage with hold_until_step stays HELD. Then releases holds waiting for this step. */
    public void releaseOnCompletion(Connection c, Task t) throws SQLException {
        LocalDateTime now = clock.now();
        for (AllocationDao.Alloc a : AllocationDao.forTask(c, t.id(), "ACTIVE")) {
            if (a.holdUntilStep() != null) {
                AllocationDao.setStatus(c, a.id(), "ACTIVE", "HELD", null, "stored; held until " + a.holdUntilStep());
                log.log(c, EventType.RESOURCE_HELD, t.id(), t.cargoId(), a.resourceId(), t.workflowId(), null, null, "Task #" + t.id() + " now holds resource " + a.resourceId() + " until " + a.holdUntilStep());
            } else {
                AllocationDao.setStatus(c, a.id(), "ACTIVE", "RELEASED", now, "task completed");
                log.log(c, EventType.RESOURCE_RELEASED, t.id(), t.cargoId(), a.resourceId(), t.workflowId(), null, null, "Task #" + t.id() + " released resource " + a.resourceId());
            }
        }
        for (AllocationDao.Alloc h : AllocationDao.heldInWorkflow(c, t.workflowId())) {
            if (t.stepCode().equals(h.holdUntilStep())) {
                AllocationDao.setStatus(c, h.id(), "HELD", "RELEASED", now, "released by step " + t.stepCode());
                log.log(c, EventType.RESOURCE_RELEASED, h.taskId(), t.cargoId(), h.resourceId(), t.workflowId(), null, null, "Held resource " + h.resourceId() + " released by " + t.stepCode());
            }
        }
    }

    /** Safety net used from finally blocks: releases every ACTIVE allocation of a task that did not complete. */
    public int releaseOnFailure(Connection c, Task t, String note) throws SQLException {
        int n = 0;
        for (AllocationDao.Alloc a : AllocationDao.forTask(c, t.id(), "ACTIVE")) {
            AllocationDao.setStatus(c, a.id(), "ACTIVE", "RELEASED", clock.now(), note);
            log.log(c, EventType.RESOURCE_RELEASED, t.id(), t.cargoId(), a.resourceId(), t.workflowId(), null, null, "Task #" + t.id() + " released resource " + a.resourceId() + " (" + note + ")");
            n++;
        }
        return n;
    }

    public int releaseHeldForWorkflow(Connection c, long workflowId, String note) throws SQLException {
        int n = 0;
        for (AllocationDao.Alloc a : AllocationDao.heldInWorkflow(c, workflowId)) {
            AllocationDao.setStatus(c, a.id(), "HELD", "RELEASED", clock.now(), note);
            log.log(c, EventType.RESOURCE_RELEASED, a.taskId(), null, a.resourceId(), workflowId, null, null, "Held resource " + a.resourceId() + " released (" + note + ")");
            n++;
        }
        return n;
    }

    /** Non-locking pre-check used at workflow creation: describes requirements nothing in the terminal could ever satisfy. */
    public List<String> diagnose(Connection c, List<TaskRequirement> reqs, CargoProfile profile, String stepCode) throws SQLException {
        List<Resource> all = ResourceDao.all(c);
        List<String> warnings = new ArrayList<>();
        for (TaskRequirement req : reqs) {
            List<Resource> same = all.stream().filter(r -> r.typeCode().equals(req.resourceType())).toList();
            AllocationResult r = ResourceSelector.explainFailure(same, req, profile);
            if (r.outcome() == AllocationResult.Outcome.BLOCKED) warnings.add(stepCode + ": " + r.message());
        }
        return warnings;
    }
}
