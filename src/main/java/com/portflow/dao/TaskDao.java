package com.portflow.dao;

import com.portflow.model.*;
import com.portflow.simulation.SimClock;
import com.portflow.util.Jdbc;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.*;

public final class TaskDao {
    private TaskDao() {}

    /** Thrown when another thread already changed the task (optimistic check "WHERE state = expected" matched no row). */
    public static final class StateConflictException extends RuntimeException {
        public StateConflictException(String m) { super(m); }
    }

    static final Jdbc.RowMapper<Task> MAP = rs -> new Task(rs.getLong("task_id"), rs.getLong("workflow_id"), rs.getLong("cargo_id"),
        rs.getString("step_code"), rs.getString("task_type"), rs.getInt("priority"), TaskState.valueOf(rs.getString("state")),
        rs.getString("block_code"), rs.getString("block_reason"), Jdbc.ts(rs, "created_at"), Jdbc.ts(rs, "ready_at"), Jdbc.ts(rs, "deadline_at"),
        rs.getInt("duration_min"), Jdbc.ts(rs, "started_at"), Jdbc.ts(rs, "completed_at"), rs.getInt("retry_count"), Jdbc.ts(rs, "not_before"),
        rs.getString("worker_thread"), rs.getBoolean("deadline_missed"));

    public static long insertTask(Connection c, long workflowId, long cargoId, String step, String type, int priority, LocalDateTime now, LocalDateTime deadline, int durationMin) throws SQLException {
        return Jdbc.insert(c, "INSERT INTO task (workflow_id, cargo_id, step_code, task_type, priority, state, created_at, deadline_at, duration_min) VALUES (?,?,?,?,?,'CREATED',?,?,?)",
            workflowId, cargoId, step, type, priority, now, deadline, durationMin);
    }

    public static void insertDependency(Connection c, long taskId, long dependsOn) throws SQLException {
        Jdbc.update(c, "INSERT INTO task_dependency (task_id, depends_on_task_id) VALUES (?,?)", taskId, dependsOn);
    }

    public static long insertRequirement(Connection c, long taskId, String type, String capability, BigDecimal amount, String holdUntil) throws SQLException {
        return Jdbc.insert(c, "INSERT INTO task_requirement (task_id, resource_type, capability, amount, hold_until_step) VALUES (?,?,?,?,?)", taskId, type, capability, amount, holdUntil);
    }

    public static Optional<Task> find(Connection c, long id) throws SQLException { return Jdbc.one(c, "SELECT * FROM task WHERE task_id=?", MAP, id); }
    public static List<Task> byState(Connection c, TaskState... states) throws SQLException {
        StringBuilder in = new StringBuilder();
        for (int i = 0; i < states.length; i++) in.append(i > 0 ? ",?" : "?");
        return Jdbc.query(c, "SELECT * FROM task WHERE state IN (" + in + ") ORDER BY task_id", MAP, (Object[]) states);
    }
    public static List<Task> byWorkflow(Connection c, long wf) throws SQLException { return Jdbc.query(c, "SELECT * FROM task WHERE workflow_id=? ORDER BY task_id", MAP, wf); }
    public static List<Task> all(Connection c) throws SQLException { return Jdbc.query(c, "SELECT * FROM task ORDER BY task_id", MAP); }

    public static List<TaskRequirement> requirements(Connection c, long taskId) throws SQLException {
        return Jdbc.query(c, "SELECT * FROM task_requirement WHERE task_id=? ORDER BY resource_type, requirement_id",
            rs -> new TaskRequirement(rs.getLong("requirement_id"), rs.getLong("task_id"), rs.getString("resource_type"), rs.getString("capability"),
                rs.getBigDecimal("amount"), rs.getString("hold_until_step")), taskId);
    }

    public static List<Long> prerequisites(Connection c, long taskId) throws SQLException {
        return Jdbc.query(c, "SELECT depends_on_task_id FROM task_dependency WHERE task_id=? ORDER BY 1", rs -> rs.getLong(1), taskId);
    }

    /** Pairs (task, prerequisite) for a whole workflow, used for DAG validation. */
    public static List<long[]> edges(Connection c, long workflowId) throws SQLException {
        return Jdbc.query(c, "SELECT d.task_id, d.depends_on_task_id FROM task_dependency d JOIN task t ON t.task_id=d.task_id WHERE t.workflow_id=?",
            rs -> new long[]{rs.getLong(1), rs.getLong(2)}, workflowId);
    }

    /** True when every prerequisite is COMPLETED. */
    public static boolean prerequisitesDone(Connection c, long taskId) throws SQLException {
        return Jdbc.one(c, "SELECT COUNT(*) FROM task_dependency d JOIN task p ON p.task_id=d.depends_on_task_id WHERE d.task_id=? AND p.state<>'COMPLETED'", rs -> rs.getInt(1), taskId).orElse(0) == 0;
    }

    /** True when a prerequisite cannot complete without intervention (FAILED, CANCELLED, EXPIRED or BLOCKED), so this task cannot either. */
    public static boolean hasFailedPrerequisite(Connection c, long taskId) throws SQLException {
        return Jdbc.one(c, "SELECT COUNT(*) FROM task_dependency d JOIN task p ON p.task_id=d.depends_on_task_id WHERE d.task_id=? AND p.state IN ('FAILED','CANCELLED','EXPIRED','BLOCKED')", rs -> rs.getInt(1), taskId).orElse(0) > 0;
    }

    /**
     * The ONLY way to change a task's state. Validates the state machine, uses "WHERE state = expected" so two threads
     * cannot both win, and writes the audit row in the same transaction.
     */
    public static void transition(Connection c, EventLog log, SimClock clock, Task t, TaskState expected, TaskState next, BlockReason reason, String detail) throws SQLException {
        if (!expected.canGoTo(next)) throw new IllegalStateException("Illegal task transition " + expected + " -> " + next + " for task " + t.id());
        LocalDateTime now = clock.now();
        boolean coded = (next == TaskState.BLOCKED || next == TaskState.FAILED) && reason != null;
        String code = coded ? reason.name() : null;
        String text = (next == TaskState.BLOCKED || next == TaskState.FAILED) ? ((reason != null ? reason.description : "") + (detail != null && !detail.isBlank() ? " - " + detail : "")) : null;
        if (text != null) { text = text.startsWith(" - ") ? text.substring(3) : text; if (text.isBlank()) text = null; }
        int n = Jdbc.update(c, "UPDATE task SET state=?, block_code=?, block_reason=?, ready_at = CASE WHEN ?='READY' AND ?<>'WAITING_FOR_RESOURCE' THEN ? ELSE ready_at END WHERE task_id=? AND state=?",
            next, code, text, next.name(), expected.name(), now, t.id(), expected);
        if (n == 0) throw new StateConflictException("Task " + t.id() + " is no longer in state " + expected);
        log.log(c, EventType.TASK_STATE_CHANGED, t.id(), t.cargoId(), null, t.workflowId(), expected.name(), next.name(),
            "Task #" + t.id() + " " + t.stepCode() + ": " + expected + " -> " + next + (text != null ? " (" + text + ")" : detail != null ? " (" + detail + ")" : ""));
    }

    public static void markStarted(Connection c, long id, LocalDateTime now, String thread) throws SQLException {
        Jdbc.update(c, "UPDATE task SET started_at=?, worker_thread=?, not_before=NULL WHERE task_id=?", now, thread, id);
    }
    public static void markCompleted(Connection c, long id, LocalDateTime now, boolean missed) throws SQLException {
        Jdbc.update(c, "UPDATE task SET completed_at=?, deadline_missed=? WHERE task_id=?", now, missed, id);
    }
    public static void scheduleRetry(Connection c, long id, LocalDateTime notBefore) throws SQLException {
        Jdbc.update(c, "UPDATE task SET retry_count=retry_count+1, not_before=? WHERE task_id=?", notBefore, id);
    }
    public static void resetRetries(Connection c, long id) throws SQLException { Jdbc.update(c, "UPDATE task SET retry_count=0, not_before=NULL WHERE task_id=?", id); }
    public static void flagDeadlineMissed(Connection c, long id) throws SQLException { Jdbc.update(c, "UPDATE task SET deadline_missed=TRUE WHERE task_id=?", id); }

    public static void setWorker(Connection c, long id, String thread) throws SQLException { Jdbc.update(c, "UPDATE task SET worker_thread=? WHERE task_id=?", thread, id); }

    public static List<Task> findByStep(Connection c, String step) throws SQLException { return Jdbc.query(c, "SELECT * FROM task WHERE step_code=? ORDER BY task_id", MAP, step); }
}
