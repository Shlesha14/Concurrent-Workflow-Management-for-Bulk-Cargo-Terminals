package com.portflow.dao;

import com.portflow.model.EventType;
import com.portflow.simulation.SimClock;
import com.portflow.util.Jdbc;
import com.portflow.util.TableData;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** Append-only audit log. Written inside the SAME transaction as the state change it describes, so a committed change always has its audit row. */
public final class EventLog {
    private final SimClock clock;
    public EventLog(SimClock clock) { this.clock = clock; }

    public void log(Connection c, EventType type, Long taskId, Long cargoId, Integer resourceId, Long workflowId,
                    String from, String to, String message) throws SQLException {
        String msg = message.length() > 390 ? message.substring(0, 390) : message;
        Jdbc.update(c, "INSERT INTO event_log (ts, sim_ts, event_type, task_id, cargo_id, resource_id, workflow_id, thread_name, from_state, to_state, message) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
            LocalDateTime.now(), clock.now(), type.name(), taskId, cargoId, resourceId, workflowId, Thread.currentThread().getName(), from, to, msg);
    }

    public void simple(Connection c, EventType type, String message) throws SQLException { log(c, type, null, null, null, null, null, null, message); }

    /** Filtered history for the Activity Log screen. Null/blank filters are ignored; all values are bound parameters. */
    public static TableData search(Connection c, Long taskId, Long cargoId, Integer resourceId, String eventType, String stateText, LocalDateTime from, LocalDateTime to, int limit) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT event_id, sim_ts, event_type, task_id, cargo_id, resource_id, thread_name, from_state, to_state, message FROM event_log WHERE 1=1");
        List<Object> p = new ArrayList<>();
        if (taskId != null) { sql.append(" AND task_id=?"); p.add(taskId); }
        if (cargoId != null) { sql.append(" AND cargo_id=?"); p.add(cargoId); }
        if (resourceId != null) { sql.append(" AND resource_id=?"); p.add(resourceId); }
        if (eventType != null && !eventType.isBlank()) { sql.append(" AND event_type=?"); p.add(eventType); }
        if (stateText != null && !stateText.isBlank()) { sql.append(" AND (from_state=? OR to_state=?)"); p.add(stateText); p.add(stateText); }
        if (from != null) { sql.append(" AND ts>=?"); p.add(from); }
        if (to != null) { sql.append(" AND ts<=?"); p.add(to); }
        sql.append(" ORDER BY event_id DESC LIMIT ").append(Math.max(1, Math.min(limit, 5000)));
        try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
            for (int i = 0; i < p.size(); i++) ps.setObject(i + 1, p.get(i) instanceof Enum<?> e ? e.name() : p.get(i));
            try (ResultSet rs = ps.executeQuery()) { return TableData.from(rs); }
        }
    }
}
