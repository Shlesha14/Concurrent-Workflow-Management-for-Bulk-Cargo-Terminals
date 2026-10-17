package com.portflow.service;

import com.portflow.util.Jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

/** Snapshot of app_setting rows with safe bounds (values outside the bounds are clamped, never trusted). */
public record Settings(int workers, int pollMs, int maxRetries, int backoffMs, int lockTimeoutMs, double speed, double excursionProbability,
                       double wPriority, double wUrgency, double wWait, double wShort, int horizonMin, int uiRefreshMs) {

    public static Settings load(Connection c) throws SQLException {
        Map<String, String> m = new HashMap<>();
        Jdbc.query(c, "SELECT setting_key, setting_value FROM app_setting", rs -> m.put(rs.getString(1), rs.getString(2)));
        return new Settings(
            (int) clamp(num(m, "engine.workers", 4), 1, 16), (int) clamp(num(m, "engine.poll_ms", 100), 20, 2000),
            (int) clamp(num(m, "engine.max_retries", 2), 0, 10), (int) clamp(num(m, "engine.backoff_ms", 200), 10, 60000),
            (int) clamp(num(m, "engine.lock_timeout_ms", 3000), 100, 60000), clamp(num(m, "sim.speed", 600), 1, 100000),
            clamp(num(m, "sim.excursion_probability", 0), 0, 1), clamp(num(m, "weight.priority", .3), 0, 1), clamp(num(m, "weight.urgency", .4), 0, 1),
            clamp(num(m, "weight.wait", .2), 0, 1), clamp(num(m, "weight.short", .1), 0, 1), (int) clamp(num(m, "urgency.horizon_min", 480), 30, 100000),
            (int) clamp(num(m, "ui.refresh_ms", 1000), 250, 10000));
    }

    private static double num(Map<String, String> m, String k, double def) {
        try { return Double.parseDouble(m.getOrDefault(k, String.valueOf(def))); } catch (NumberFormatException e) { return def; }
    }
    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }

    public static void save(Connection c, String key, String value) throws SQLException {
        Jdbc.update(c, "INSERT INTO app_setting (setting_key, setting_value) VALUES (?,?) ON DUPLICATE KEY UPDATE setting_value=VALUES(setting_value)", key, value);
    }
}
