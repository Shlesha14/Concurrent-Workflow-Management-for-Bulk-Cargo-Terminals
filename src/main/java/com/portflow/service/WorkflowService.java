package com.portflow.service;

import com.portflow.dao.*;
import com.portflow.model.*;
import com.portflow.resource.ResourceManager;
import com.portflow.simulation.SimClock;
import com.portflow.util.Database;
import com.portflow.workflow.DagValidator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;

/** Builds a workflow (a DAG of tasks) from the cargo's handling profile and validates it before anything can run. */
public final class WorkflowService {
    public record Created(long workflowId, int taskCount, List<String> warnings) {}

    private final Database db; private final SimClock clock; private final EventLog log; private final ResourceManager rm;
    public WorkflowService(Database db, SimClock clock, EventLog log, ResourceManager rm) { this.db = db; this.clock = clock; this.log = log; this.rm = rm; }

    public Created createWorkflow(long cargoId) throws SQLException, ServiceException {
        try {
            return db.inTransaction(c -> {
                try { return build(c, cargoId); } catch (ServiceException e) { throw new Wrapped(e); }
            });
        } catch (Wrapped w) { throw w.inner; }
    }
    private static final class Wrapped extends RuntimeException { final ServiceException inner; Wrapped(ServiceException e) { super(e.getMessage()); inner = e; } }

    private Created build(Connection c, long cargoId) throws SQLException, ServiceException {
        Cargo cargo = CargoDao.find(c, cargoId).orElseThrow(() -> new ServiceException("Cargo #" + cargoId + " not found"));
        if (!"REGISTERED".equals(cargo.status())) throw new ServiceException("Cargo #" + cargoId + " is " + cargo.status() + " and cannot get a workflow" + (cargo.statusReason() != null ? ": " + cargo.statusReason() : ""));
        if (WorkflowDao.byCargo(c, cargoId).isPresent()) throw new ServiceException("Cargo #" + cargoId + " already has a workflow");
        if (CargoValidator.isExpired(cargo, clock.now())) throw new ServiceException("Cargo #" + cargoId + " has expired; it cannot be scheduled");
        CargoProfile p = ProfileDao.find(c, cargo.profileCode()).orElseThrow(() -> new ServiceException("Unknown profile " + cargo.profileCode()));

        // 1. validate the template graph (cycle / unknown dependency) BEFORE inserting anything
        Map<String, List<String>> prereq = new LinkedHashMap<>();
        for (CargoProfile.Step s : p.steps()) prereq.put(s.code(), s.dependsOn());
        List<String> errors = DagValidator.validate(prereq.keySet(), prereq);
        if (p.steps().isEmpty()) errors = List.of("Profile " + p.code() + " has no workflow steps");
        if (!errors.isEmpty()) {
            log.log(c, EventType.WORKFLOW_REJECTED, null, cargoId, null, null, null, null, "Workflow for cargo #" + cargoId + " rejected: " + errors);
            throw new ServiceException("Invalid workflow template for " + p.code(), errors);
        }
        List<String> order = DagValidator.topologicalOrder(prereq.keySet(), prereq);
        Map<String, CargoProfile.Step> byCode = new HashMap<>();
        p.steps().forEach(s -> byCode.put(s.code(), s));

        long wf = WorkflowDao.insert(c, cargoId, clock.now());
        Map<String, Long> ids = new HashMap<>();
        List<String> warnings = new ArrayList<>();
        for (String code : order) {
            CargoProfile.Step s = byCode.get(code);
            int dur = (int) Math.min(600, Math.max(1, Math.ceil(s.baseMin() + s.perUnitMin() * cargo.quantity().doubleValue())));
            long tid = TaskDao.insertTask(c, wf, cargoId, code, s.taskType(), cargo.priority(), clock.now(), cargo.deadlineAt(), dur);
            ids.put(code, tid);
            for (String d : s.dependsOn()) TaskDao.insertDependency(c, tid, ids.get(d));      // topological order => prerequisite already inserted
            for (CargoProfile.Req r : s.requirements()) {
                BigDecimal amt = "CARGO_QTY".equals(r.amountMode()) ? cargo.quantity().setScale(2, RoundingMode.HALF_UP) : BigDecimal.ONE;
                TaskDao.insertRequirement(c, tid, r.resourceType(), r.capability(), amt, r.holdUntilStep());
            }
            warnings.addAll(rm.diagnose(c, TaskDao.requirements(c, tid), p, code));
        }
        // 2. re-validate the persisted graph, then CREATED -> VALIDATED
        validateStored(c, wf);
        for (Task t : TaskDao.byWorkflow(c, wf)) TaskDao.transition(c, log, clock, t, TaskState.CREATED, TaskState.VALIDATED, null, "dependency graph valid");
        WorkflowDao.setState(c, wf, "RUNNING", null);
        CargoDao.setStatus(c, cargoId, "IN_PROGRESS", null);
        log.log(c, EventType.WORKFLOW_CREATED, null, cargoId, null, wf, null, "RUNNING", "Workflow #" + wf + " created for cargo #" + cargoId + " with " + ids.size() + " tasks" + (warnings.isEmpty() ? "" : "; warnings: " + warnings));
        return new Created(wf, ids.size(), warnings);
    }

    /** Throws if the stored graph of a workflow has a cycle or dangling edge. */
    public void validateStored(Connection c, long workflowId) throws SQLException, ServiceException {
        List<String> errors = storedGraphErrors(c, workflowId);
        if (!errors.isEmpty()) throw new ServiceException("Invalid workflow graph", errors);
    }

    public List<String> storedGraphErrors(Connection c, long workflowId) throws SQLException {
        List<Task> tasks = TaskDao.byWorkflow(c, workflowId);
        List<Long> ids = tasks.stream().map(Task::id).toList();
        Map<Long, List<Long>> pre = new HashMap<>();
        for (long[] e : TaskDao.edges(c, workflowId)) pre.computeIfAbsent(e[0], k -> new ArrayList<>()).add(e[1]);
        return DagValidator.validate(ids, pre);
    }

    /** Adds an extra prerequisite edge if it keeps the graph acyclic and inside one workflow; otherwise rejects it. */
    public void addDependency(long taskId, long dependsOnTaskId) throws SQLException, ServiceException {
        try {
            db.inTransaction(c -> {
                try {
                    Task t = TaskDao.find(c, taskId).orElseThrow(() -> new ServiceException("Task " + taskId + " not found"));
                    Task d = TaskDao.find(c, dependsOnTaskId).orElseThrow(() -> new ServiceException("Prerequisite task " + dependsOnTaskId + " not found"));
                    if (t.workflowId() != d.workflowId()) throw new ServiceException("Tasks belong to different workflows");
                    if (t.state() != TaskState.VALIDATED && t.state() != TaskState.CREATED) throw new ServiceException("Dependencies can only be added to tasks that have not started");
                    List<Long> ids = TaskDao.byWorkflow(c, t.workflowId()).stream().map(Task::id).toList();
                    Map<Long, List<Long>> pre = new HashMap<>();
                    for (long[] e : TaskDao.edges(c, t.workflowId())) pre.computeIfAbsent(e[0], k -> new ArrayList<>()).add(e[1]);
                    pre.computeIfAbsent(taskId, k -> new ArrayList<>()).add(dependsOnTaskId);
                    List<String> errs = DagValidator.validate(ids, pre);
                    if (!errs.isEmpty()) {
                        log.log(c, EventType.WORKFLOW_REJECTED, taskId, t.cargoId(), null, t.workflowId(), null, null, "Dependency " + taskId + " -> " + dependsOnTaskId + " rejected: " + errs);
                        throw new ServiceException("Dependency rejected", errs);
                    }
                    TaskDao.insertDependency(c, taskId, dependsOnTaskId);
                    log.log(c, EventType.OPERATOR_ACTION, taskId, t.cargoId(), null, t.workflowId(), null, null, "Dependency added: task " + taskId + " waits for " + dependsOnTaskId);
                    return null;
                } catch (ServiceException e) { throw new Wrapped(e); }
            });
        } catch (Wrapped w) { throw w.inner; }
    }

    public List<Task> tasks() throws SQLException { return db.read(TaskDao::all); }
}
