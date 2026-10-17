package com.portflow.dao;

import com.portflow.util.Jdbc;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/** Allocation rows are never deleted: ACTIVE -> HELD -> RELEASED, or REVOKED. Capacity in use = SUM(amount) over ACTIVE+HELD. */
public final class AllocationDao {
    private AllocationDao() {}

    public record Alloc(long id, long taskId, int resourceId, BigDecimal amount, String status, String holdUntilStep) {}

    public static BigDecimal inUse(Connection c, int resourceId) throws SQLException {
        return Jdbc.one(c, "SELECT COALESCE(SUM(amount),0) FROM allocation WHERE resource_id=? AND status IN ('ACTIVE','HELD')", rs -> rs.getBigDecimal(1), resourceId).orElse(BigDecimal.ZERO);
    }

    public static long insert(Connection c, long taskId, long requirementId, int resourceId, BigDecimal amount, LocalDateTime start, String holdUntil) throws SQLException {
        return Jdbc.insert(c, "INSERT INTO allocation (task_id, requirement_id, resource_id, amount, status, start_time, hold_until_step) VALUES (?,?,?,?, 'ACTIVE', ?, ?)",
            taskId, requirementId, resourceId, amount, start, holdUntil);
    }

    public static List<Alloc> forTask(Connection c, long taskId, String status) throws SQLException {
        return Jdbc.query(c, "SELECT allocation_id, task_id, resource_id, amount, status, hold_until_step FROM allocation WHERE task_id=? AND status=?",
            rs -> new Alloc(rs.getLong(1), rs.getLong(2), rs.getInt(3), rs.getBigDecimal(4), rs.getString(5), rs.getString(6)), taskId, status);
    }

    public static List<Alloc> onResource(Connection c, int resourceId, String status) throws SQLException {
        return Jdbc.query(c, "SELECT allocation_id, task_id, resource_id, amount, status, hold_until_step FROM allocation WHERE resource_id=? AND status=? ORDER BY allocation_id",
            rs -> new Alloc(rs.getLong(1), rs.getLong(2), rs.getInt(3), rs.getBigDecimal(4), rs.getString(5), rs.getString(6)), resourceId, status);
    }

    public static List<Alloc> heldInWorkflow(Connection c, long workflowId) throws SQLException {
        return Jdbc.query(c, "SELECT a.allocation_id, a.task_id, a.resource_id, a.amount, a.status, a.hold_until_step FROM allocation a JOIN task t ON t.task_id=a.task_id WHERE t.workflow_id=? AND a.status='HELD'",
            rs -> new Alloc(rs.getLong(1), rs.getLong(2), rs.getInt(3), rs.getBigDecimal(4), rs.getString(5), rs.getString(6)), workflowId);
    }

    public static int setStatus(Connection c, long allocId, String from, String to, LocalDateTime end, String note) throws SQLException {
        return Jdbc.update(c, "UPDATE allocation SET status=?, end_time=COALESCE(?, end_time), note=? WHERE allocation_id=? AND status=?", to, end, note, allocId, from);
    }
}
