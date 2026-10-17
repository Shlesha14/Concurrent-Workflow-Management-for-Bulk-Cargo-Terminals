package com.portflow.service;

import com.portflow.dao.*;
import com.portflow.model.*;
import com.portflow.resource.ResourceManager;
import com.portflow.simulation.SimClock;
import com.portflow.util.Database;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/** Operator actions on tasks: retry and cancel (with the state-machine and safety rules). */
public final class TaskAdminService {
    private final Database db; private final SimClock clock; private final EventLog log; private final ResourceManager rm;
    private static final class Wrapped extends RuntimeException { final ServiceException inner; Wrapped(ServiceException e) { super(e.getMessage()); inner = e; } }
    public TaskAdminService(Database db, SimClock clock, EventLog log, ResourceManager rm) { this.db = db; this.clock = clock; this.log = log; this.rm = rm; }

    public void retry(long taskId) throws SQLException, ServiceException {
        run(c -> {
            Task t = TaskDao.find(c, taskId).orElseThrow(() -> new ServiceException("Task not found"));
            if (t.state() != TaskState.FAILED && t.state() != TaskState.BLOCKED) throw new ServiceException("Only FAILED or BLOCKED tasks can be retried (task is " + t.state() + ")");
            if (BlockReason.INVALID_WORKFLOW.name().equals(t.blockCode())) throw new ServiceException("The dependency graph is invalid; fix the workflow before retrying");
            if (TaskDao.hasFailedPrerequisite(c, taskId)) throw new ServiceException("A prerequisite task has failed; retry or fix the prerequisite first");
            TaskDao.resetRetries(c, taskId);
            // BLOCKED -> VALIDATED lets the engine re-check prerequisites; FAILED -> READY retries immediately.
            TaskDao.transition(c, log, clock, t, t.state(), t.state() == TaskState.FAILED ? TaskState.READY : TaskState.VALIDATED, null, "operator retry");
            log.log(c, EventType.OPERATOR_ACTION, taskId, t.cargoId(), null, t.workflowId(), null, null, "Operator retried task #" + taskId);
        });
    }

    public void cancel(long taskId) throws SQLException, ServiceException {
        run(c -> {
            Task t = TaskDao.find(c, taskId).orElseThrow(() -> new ServiceException("Task not found"));
            if (t.state() == TaskState.RUNNING) throw new ServiceException("A running task cannot be cancelled safely; wait for it to finish");
            if (t.state().isTerminal()) throw new ServiceException("Task is already " + t.state());
            TaskDao.transition(c, log, clock, t, t.state(), TaskState.CANCELLED, null, "operator cancel");
            log.log(c, EventType.OPERATOR_ACTION, taskId, t.cargoId(), null, t.workflowId(), null, null, "Operator cancelled task #" + taskId);
        });
    }

    /** Cancels every unfinished, non-running task of a workflow and releases storage still held for it. */
    public int cancelWorkflow(long workflowId) throws SQLException, ServiceException {
        final int[] n = {0};
        run(c -> {
            for (Task t : TaskDao.byWorkflow(c, workflowId)) {
                if (t.state() == TaskState.RUNNING) throw new ServiceException("Task #" + t.id() + " is running; try again when it finishes");
            }
            for (Task t : TaskDao.byWorkflow(c, workflowId)) if (!t.state().isTerminal()) { TaskDao.transition(c, log, clock, t, t.state(), TaskState.CANCELLED, null, "workflow cancelled"); n[0]++; }
            rm.releaseHeldForWorkflow(c, workflowId, "workflow cancelled");
            WorkflowDao.setState(c, workflowId, "CANCELLED", clock.now());
            log.log(c, EventType.OPERATOR_ACTION, null, null, null, workflowId, null, "CANCELLED", "Operator cancelled workflow #" + workflowId + " (" + n[0] + " tasks)");
        });
        return n[0];
    }

    @FunctionalInterface private interface Act { void run(Connection c) throws SQLException, ServiceException; }
    private void run(Act a) throws SQLException, ServiceException {
        try { db.inTransaction(c -> { try { a.run(c); } catch (ServiceException e) { throw new Wrapped(e); } return null; }); }
        catch (Wrapped w) { throw w.inner; }
        catch (TaskDao.StateConflictException e) { throw new ServiceException("The task changed state while you were acting; refresh and try again"); }
    }
}
