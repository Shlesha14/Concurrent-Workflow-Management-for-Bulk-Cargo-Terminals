package com.portflow.dao;

import com.portflow.util.Jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public final class WorkflowDao {
    private WorkflowDao() {}

    public record Workflow(long id, long cargoId, String state) {}

    public static long insert(Connection c, long cargoId, LocalDateTime now) throws SQLException {
        return Jdbc.insert(c, "INSERT INTO workflow_instance (cargo_id, state, created_at) VALUES (?, 'CREATED', ?)", cargoId, now);
    }
    public static Optional<Workflow> byCargo(Connection c, long cargoId) throws SQLException {
        return Jdbc.one(c, "SELECT workflow_id, cargo_id, state FROM workflow_instance WHERE cargo_id=?", rs -> new Workflow(rs.getLong(1), rs.getLong(2), rs.getString(3)), cargoId);
    }
    public static List<Workflow> active(Connection c) throws SQLException {
        return Jdbc.query(c, "SELECT workflow_id, cargo_id, state FROM workflow_instance WHERE state IN ('CREATED','RUNNING','BLOCKED') ORDER BY workflow_id",
            rs -> new Workflow(rs.getLong(1), rs.getLong(2), rs.getString(3)));
    }
    public static void setState(Connection c, long id, String state, LocalDateTime completedAt) throws SQLException {
        Jdbc.update(c, "UPDATE workflow_instance SET state=?, completed_at=? WHERE workflow_id=?", state, completedAt, id);
    }
}
