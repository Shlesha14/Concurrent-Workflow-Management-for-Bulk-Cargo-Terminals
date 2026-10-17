package com.portflow.monitoring;

import com.portflow.resource.ResourceManager;
import com.portflow.util.Database;
import com.portflow.util.Jdbc;

import java.sql.SQLException;

/** Computes run metrics from what actually happened (task rows, allocation rows, lock statistics). Nothing here is estimated or hard-coded. */
public final class MetricsService {
    public record RunMetrics(String label, int tasks, int completed, int failed, int blocked, int deadlineMisses, double avgWaitMin, double makespanMin,
                             double throughputPerHour, double avgUtilizationPct, long conflicts, double lockWaitMs, int peakConcurrency) {
        public static String header() { return "label,tasks,completed,failed,blocked,deadline_misses,avg_wait_min,makespan_min,throughput_per_sim_hour,avg_slot_utilization_pct,conflicts,lock_wait_ms,peak_concurrency"; }
        public String toCsv() { return String.join(",", label, "" + tasks, "" + completed, "" + failed, "" + blocked, "" + deadlineMisses, f(avgWaitMin), f(makespanMin), f(throughputPerHour), f(avgUtilizationPct), "" + conflicts, f(lockWaitMs), "" + peakConcurrency); }
        private static String f(double d) { return String.format(java.util.Locale.ROOT, "%.2f", d); }
    }

    private final Database db; private final ResourceManager rm;
    public MetricsService(Database db, ResourceManager rm) { this.db = db; this.rm = rm; }

    public RunMetrics compute(String label, int peakConcurrency) throws SQLException {
        return db.read(c -> {
            int tasks = n(c, "SELECT COUNT(*) FROM task"), done = n(c, "SELECT COUNT(*) FROM task WHERE state='COMPLETED'");
            int failed = n(c, "SELECT COUNT(*) FROM task WHERE state='FAILED'"), blocked = n(c, "SELECT COUNT(*) FROM task WHERE state='BLOCKED'");
            // a miss = finished late, or still unfinished although the deadline passed (flag set by the engine)
            int missed = n(c, "SELECT COUNT(*) FROM task WHERE deadline_missed = TRUE");
            double wait = d(c, "SELECT COALESCE(AVG(TIMESTAMPDIFF(SECOND, ready_at, started_at))/60,0) FROM task WHERE started_at IS NOT NULL AND ready_at IS NOT NULL");
            double makespan = d(c, "SELECT COALESCE(TIMESTAMPDIFF(SECOND, MIN(created_at), MAX(completed_at))/60,0) FROM task WHERE completed_at IS NOT NULL");
            double thr = makespan > 0 ? done / (makespan / 60.0) : 0;
            // utilisation: busy slot-seconds / (capacity x makespan) averaged over slot resources that were used
            double util = makespan <= 0 ? 0 : d(c, "SELECT COALESCE(AVG(u),0) FROM (SELECT 100*SUM(TIMESTAMPDIFF(SECOND,a.start_time,a.end_time))/(r.capacity*?) AS u FROM allocation a JOIN resource r ON r.resource_id=a.resource_id " +
                "JOIN resource_type t ON t.type_code=r.type_code WHERE t.capacity_model='SLOTS' AND a.end_time IS NOT NULL GROUP BY r.resource_id, r.capacity) x", makespan * 60);
            ResourceManager.LockStats s = rm.stats();
            return new RunMetrics(label, tasks, done, failed, blocked, missed, wait, makespan, thr, util, s.conflicts(), s.totalLockWaitNanos() / 1e6, peakConcurrency);
        });
    }
    private static int n(java.sql.Connection c, String sql) throws SQLException { return Jdbc.one(c, sql, rs -> rs.getInt(1)).orElse(0); }
    private static double d(java.sql.Connection c, String sql, Object... p) throws SQLException { return Jdbc.one(c, sql, rs -> rs.getDouble(1), p).orElse(0.0); }
}
