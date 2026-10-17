package com.portflow.resource;

import com.portflow.dao.*;
import com.portflow.model.*;
import com.portflow.simulation.SimClock;
import com.portflow.util.Jdbc;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;

/**
 * Deadlock DETECTION and RECOVERY (OS: wait-for graph).
 * Because ResourceManager grants all-or-nothing, a waiting task holds nothing; the only long-lived holds are storage allocations
 * (status HELD) kept by workflows that have already stored cargo. Node = workflow. Edge X -> Y means: a task of X is
 * WAITING for storage that is (too) full because of a HELD allocation of Y. A cycle X -> Y -> X means neither can ever progress.
 * Recovery: pick the lowest-priority workflow in the cycle as VICTIM, release its holds and block its waiting tasks (operator can retry).
 * Limitation: only storage holds are modelled; slot resources are never held across tasks.
 */
public final class DeadlockDetector {
    private final EventLog log; private final SimClock clock;
    public DeadlockDetector(EventLog log, SimClock clock) { this.log = log; this.clock = clock; }

    public Map<Long, Set<Long>> waitForGraph(Connection c) throws SQLException {
        Map<Long, Set<Long>> g = new HashMap<>();
        List<Resource> resources = ResourceDao.all(c);
        for (Task t : TaskDao.byState(c, TaskState.WAITING_FOR_RESOURCE)) {
            for (TaskRequirement req : TaskDao.requirements(c, t.id())) {
                for (Resource r : resources) {
                    if (!r.typeCode().equals(req.resourceType()) || !r.isQuantityModel()) continue;
                    BigDecimal free = r.capacity().subtract(AllocationDao.inUse(c, r.id()));
                    if (free.compareTo(req.amount()) >= 0) continue;
                    for (AllocationDao.Alloc h : AllocationDao.onResource(c, r.id(), "HELD")) {
                        long holderWf = TaskDao.find(c, h.taskId()).map(Task::workflowId).orElse(-1L);
                        if (holderWf != t.workflowId() && holderWf > 0) g.computeIfAbsent(t.workflowId(), k -> new TreeSet<>()).add(holderWf);
                    }
                }
            }
        }
        return g;
    }

    /** Returns one cycle (list of workflow ids) per detected strongly connected loop; empty = no deadlock. */
    public List<List<Long>> findCycles(Map<Long, Set<Long>> g) {
        List<List<Long>> cycles = new ArrayList<>();
        Set<Long> done = new HashSet<>();
        for (Long start : new TreeSet<>(g.keySet())) {
            if (done.contains(start)) continue;
            Deque<Long> path = new ArrayDeque<>();
            dfs(start, g, path, new HashSet<>(), done, cycles);
        }
        return cycles;
    }

    private void dfs(Long n, Map<Long, Set<Long>> g, Deque<Long> path, Set<Long> onPath, Set<Long> done, List<List<Long>> out) {
        path.addLast(n); onPath.add(n);
        for (Long m : g.getOrDefault(n, Set.of())) {
            if (onPath.contains(m)) {
                List<Long> cyc = new ArrayList<>(); boolean in = false;
                for (Long p : path) { if (p.equals(m)) in = true; if (in) cyc.add(p); }
                out.add(cyc);
            } else if (!done.contains(m)) dfs(m, g, path, onPath, done, out);
        }
        path.removeLast(); onPath.remove(n); done.add(n);
    }

    /** Detects and resolves. Returns the victim workflow ids. */
    public List<Long> detectAndResolve(Connection c) throws SQLException {
        List<Long> victims = new ArrayList<>();
        for (List<Long> cycle : findCycles(waitForGraph(c))) {
            long victim = -1; int lowest = Integer.MAX_VALUE;
            for (long wf : cycle) {
                int prio = TaskDao.byWorkflow(c, wf).stream().mapToInt(Task::priority).max().orElse(3);
                if (prio < lowest || (prio == lowest && wf > victim)) { lowest = prio; victim = wf; }
            }
            final long v = victim;
            log.log(c, EventType.DEADLOCK_SUSPECTED, null, null, null, v, null, null, "Circular wait between workflows " + cycle + "; victim workflow " + v);
            for (Task t : TaskDao.byWorkflow(c, v))
                if (t.state() == TaskState.WAITING_FOR_RESOURCE) TaskDao.transition(c, log, clock, t, t.state(), TaskState.BLOCKED, BlockReason.DEADLOCK_VICTIM, "cycle " + cycle);
            for (AllocationDao.Alloc h : AllocationDao.heldInWorkflow(c, v)) {
                AllocationDao.setStatus(c, h.id(), "HELD", "REVOKED", clock.now(), "deadlock recovery");
                log.log(c, EventType.RESOURCE_REVOKED, h.taskId(), null, h.resourceId(), v, null, null, "Hold on resource " + h.resourceId() + " revoked to break deadlock");
            }
            Jdbc.update(c, "INSERT INTO schedule_revision (created_at, reason_code, description, policy_name, affected_tasks) VALUES (?, 'DEADLOCK_RECOVERY', ?, NULL, 0)",
                clock.now(), "Deadlock among workflows " + cycle + "; victim " + v);
            victims.add(v);
        }
        return victims;
    }
}
