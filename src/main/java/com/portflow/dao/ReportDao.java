package com.portflow.dao;

import com.portflow.util.Jdbc;
import com.portflow.util.TableData;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;

/** Read-only reporting queries (joins, aggregation, subqueries, views). Kept here so no SQL lives in the UI. */
public final class ReportDao {
    private ReportDao() {}

    public record Dashboard(int cargoTotal, int waiting, int running, int completed, int failed, int blocked, int resourcesAvailable, int resourcesOccupied,
                            int resourcesDown, int urgentCargo, int expiredCargo, int activeWorkflows, double utilizationPct, int alerts) {}

    private static int count(Connection c, String sql, Object... p) throws SQLException { return Jdbc.one(c, sql, rs -> rs.getInt(1), p).orElse(0); }

    public static Dashboard dashboard(Connection c, LocalDateTime simNow, int urgentWithinMin) throws SQLException {
        LocalDateTime horizon = simNow.plusMinutes(urgentWithinMin);
        // utilisation = used slot-units / total slot-units over AVAILABLE slot resources (aggregation over a view)
        double util = Jdbc.one(c, "SELECT COALESCE(100*SUM(l.in_use)/NULLIF(SUM(l.capacity),0),0) FROM v_resource_load l JOIN resource_type t ON t.type_code=l.type_code WHERE t.capacity_model='SLOTS' AND l.status='AVAILABLE'", rs -> rs.getDouble(1)).orElse(0.0);
        return new Dashboard(
            count(c, "SELECT COUNT(*) FROM cargo"),
            count(c, "SELECT COUNT(*) FROM task WHERE state IN ('VALIDATED','READY','WAITING_FOR_RESOURCE')"),
            count(c, "SELECT COUNT(*) FROM task WHERE state='RUNNING'"),
            count(c, "SELECT COUNT(*) FROM task WHERE state='COMPLETED'"),
            count(c, "SELECT COUNT(*) FROM task WHERE state='FAILED'"),
            count(c, "SELECT COUNT(*) FROM task WHERE state='BLOCKED'"),
            count(c, "SELECT COUNT(*) FROM resource WHERE status='AVAILABLE'"),
            count(c, "SELECT COUNT(*) FROM v_resource_load WHERE in_use > 0"),
            count(c, "SELECT COUNT(*) FROM resource WHERE status<>'AVAILABLE'"),
            count(c, "SELECT COUNT(*) FROM cargo WHERE status IN ('REGISTERED','IN_PROGRESS') AND ((expires_at IS NOT NULL AND expires_at<=?) OR (deadline_at IS NOT NULL AND deadline_at<=?))", horizon, horizon),
            count(c, "SELECT COUNT(*) FROM cargo WHERE status IN ('REJECTED','QUARANTINED')"),
            count(c, "SELECT COUNT(*) FROM workflow_instance WHERE state IN ('CREATED','RUNNING','BLOCKED')"),
            util,
            count(c, "SELECT COUNT(*) FROM task WHERE state IN ('BLOCKED','FAILED')") + count(c, "SELECT COUNT(*) FROM event_log WHERE event_type='TEMPERATURE_EXCURSION'"));
    }

    private static TableData table(Connection c, String sql, Object... p) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < p.length; i++) ps.setObject(i + 1, p[i]);
            try (ResultSet rs = ps.executeQuery()) { return TableData.from(rs); }
        }
    }

    public static TableData cargoTable(Connection c, String text, LocalDateTime simNow) throws SQLException {
        String like = text == null || text.isBlank() ? "%" : "%" + text.trim() + "%";
        return table(c, "SELECT g.cargo_id AS id, g.name, g.profile_code AS profile, CONCAT(g.quantity,' ',g.unit) AS quantity, g.priority AS prio, g.arrival_time AS arrival, g.expires_at AS expiry, " +
            "CASE WHEN g.expires_at IS NULL THEN '-' WHEN g.expires_at<=? THEN 'EXPIRED' ELSE CONCAT(TIMESTAMPDIFF(MINUTE, ?, g.expires_at),' min left') END AS shelf_life, g.deadline_at AS deadline, g.status, g.status_reason AS reason " +
            "FROM cargo g WHERE g.name LIKE ? OR g.profile_code LIKE ? OR g.status LIKE ? ORDER BY g.cargo_id DESC", simNow, simNow, like, like, like);
    }

    public static TableData resourceTable(Connection c) throws SQLException {
        return table(c, "SELECT l.resource_id AS id, l.name, l.type_code AS type, l.capacity, l.in_use, l.free_capacity AS free, l.status, r.location, " +
            "CASE WHEN r.temp_min_c IS NULL THEN '-' ELSE CONCAT(r.temp_min_c,'..',r.temp_max_c,' C') END AS temp_range, " +
            "(SELECT GROUP_CONCAT(rc.capability ORDER BY rc.capability SEPARATOR ',') FROM resource_capability rc WHERE rc.resource_id=l.resource_id) AS capabilities " +
            "FROM v_resource_load l JOIN resource r ON r.resource_id=l.resource_id ORDER BY l.resource_id");
    }

    public static TableData allocationTable(Connection c, int limit) throws SQLException {
        return table(c, "SELECT a.allocation_id AS id, a.task_id, t.step_code AS step, r.name AS resource, a.amount, a.status, a.start_time, a.end_time, a.note " +
            "FROM allocation a JOIN task t ON t.task_id=a.task_id JOIN resource r ON r.resource_id=a.resource_id ORDER BY a.allocation_id DESC LIMIT " + Math.max(1, Math.min(limit, 2000)));
    }

    public static TableData taskTable(Connection c) throws SQLException {
        return table(c, "SELECT task_id AS id, workflow_id AS wf, cargo, step_code AS step, priority AS prio, state, block_code, block_reason, ready_at, started_at, completed_at, deadline_at, retry_count AS retries, worker_thread AS thread, deadline_missed AS missed FROM v_task_overview ORDER BY task_id");
    }

    public static TableData dependencyTable(Connection c) throws SQLException {
        return table(c, "SELECT d.task_id, t.step_code AS step, d.depends_on_task_id AS waits_for, p.step_code AS prerequisite, p.state AS prerequisite_state, t.state AS task_state " +
            "FROM task_dependency d JOIN task t ON t.task_id=d.task_id JOIN task p ON p.task_id=d.depends_on_task_id ORDER BY d.task_id, d.depends_on_task_id");
    }

    public static TableData running(Connection c) throws SQLException {
        return table(c, "SELECT task_id, cargo, step_code AS step, state, worker_thread AS thread, started_at, " +
            "(SELECT GROUP_CONCAT(r.name SEPARATOR ',') FROM allocation a JOIN resource r ON r.resource_id=a.resource_id WHERE a.task_id=v.task_id AND a.status='ACTIVE') AS resources " +
            "FROM v_task_overview v WHERE state IN ('RUNNING','READY','WAITING_FOR_RESOURCE') ORDER BY FIELD(state,'RUNNING','WAITING_FOR_RESOURCE','READY'), task_id");
    }

    public static TableData recentEvents(Connection c, int n) throws SQLException {
        return table(c, "SELECT event_id AS id, sim_ts, event_type AS type, task_id, resource_id AS res, thread_name AS thread, message FROM event_log ORDER BY event_id DESC LIMIT " + Math.max(1, Math.min(n, 500)));
    }

    public static TableData alerts(Connection c) throws SQLException {
        return table(c, "SELECT 'TASK' AS kind, CONCAT('#', task_id, ' ', step_code) AS item, CONCAT(state, ': ', COALESCE(block_reason, block_code, '')) AS detail FROM v_blocked_tasks " +
            "UNION ALL SELECT 'TEMPERATURE', CONCAT('resource ', resource_id), message FROM event_log WHERE event_type='TEMPERATURE_EXCURSION' " +
            "UNION ALL SELECT 'DEADLINE', CONCAT('task ', task_id), message FROM event_log WHERE event_type='TASK_DEADLINE_MISSED' " +
            "UNION ALL SELECT 'DEADLOCK', CONCAT('workflow ', workflow_id), message FROM event_log WHERE event_type='DEADLOCK_SUSPECTED' ORDER BY 1, 2");
    }

    public static TableData utilizationReport(Connection c) throws SQLException {
        return table(c, "SELECT r.name AS resource, r.type_code AS type, COUNT(a.allocation_id) AS uses, " +
            "ROUND(COALESCE(SUM(TIMESTAMPDIFF(SECOND, a.start_time, COALESCE(a.end_time, a.start_time)))/60,0),1) AS busy_min " +
            "FROM resource r LEFT JOIN allocation a ON a.resource_id=r.resource_id AND a.status IN ('RELEASED','ACTIVE') " +
            "GROUP BY r.resource_id, r.name, r.type_code ORDER BY busy_min DESC, r.name");
    }

    public static TableData urgentCargo(Connection c, LocalDateTime simNow, int withinMin) throws SQLException {
        return table(c, "SELECT cargo_id AS id, name, profile_code AS profile, expires_at, TIMESTAMPDIFF(MINUTE, ?, expires_at) AS minutes_left, status FROM cargo " +
            "WHERE expires_at IS NOT NULL AND status IN ('REGISTERED','IN_PROGRESS') AND expires_at <= ? ORDER BY expires_at", simNow, simNow.plusMinutes(withinMin));
    }

    public static TableData revisions(Connection c) throws SQLException {
        return table(c, "SELECT v.revision_id AS id, v.created_at, v.reason_code AS reason, v.policy_name AS policy, r.name AS resource, v.affected_tasks AS tasks, v.description " +
            "FROM schedule_revision v LEFT JOIN resource r ON r.resource_id=v.resource_id ORDER BY v.revision_id DESC");
    }

    public static TableData planEntries(Connection c, long revisionId) throws SQLException {
        return table(c, "SELECT p.task_id, t.step_code AS step, r.name AS resource, p.planned_start AS start, p.planned_end AS end FROM plan_entry p JOIN task t ON t.task_id=p.task_id JOIN resource r ON r.resource_id=p.resource_id WHERE p.revision_id=? ORDER BY p.planned_start, p.task_id", revisionId);
    }

    public static TableData profiles(Connection c) throws SQLException {
        return table(c, "SELECT profile_code AS code, display_name AS name, category, requires_expiry AS needs_expiry, temp_min_c AS t_min, temp_max_c AS t_max, max_wait_min AS max_wait, expired_policy AS expired_policy, simplification_note AS simplification FROM handling_profile ORDER BY category, profile_code");
    }

    public static TableData readings(Connection c, int n) throws SQLException {
        return table(c, "SELECT s.reading_id AS id, r.name AS storage, s.ts, s.temp_c, s.excursion, s.is_simulated AS simulated FROM storage_reading s JOIN resource r ON r.resource_id=s.resource_id ORDER BY s.reading_id DESC LIMIT " + Math.max(1, Math.min(n, 500)));
    }
}
