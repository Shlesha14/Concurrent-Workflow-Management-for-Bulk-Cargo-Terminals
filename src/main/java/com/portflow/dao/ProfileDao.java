package com.portflow.dao;

import com.portflow.model.CargoProfile;
import com.portflow.util.Jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;

public final class ProfileDao {
    private ProfileDao() {}

    public static List<CargoProfile> loadAll(Connection c) throws SQLException {
        List<CargoProfile> out = new ArrayList<>();
        for (String code : Jdbc.query(c, "SELECT profile_code FROM handling_profile ORDER BY category, profile_code", rs -> rs.getString(1)))
            find(c, code).ifPresent(out::add);
        return out;
    }

    public static Optional<CargoProfile> find(Connection c, String code) throws SQLException {
        var head = Jdbc.one(c, "SELECT * FROM handling_profile WHERE profile_code=?", rs -> new Object[]{
            rs.getString("display_name"), rs.getString("category"), rs.getBoolean("requires_expiry"), Jdbc.doubleOrNull(rs, "temp_min_c"),
            Jdbc.doubleOrNull(rs, "temp_max_c"), rs.getInt("max_wait_min"), rs.getString("expired_policy"),
            rs.getString("description"), rs.getString("simplification_note")}, code);
        if (head.isEmpty()) return Optional.empty();
        Object[] h = head.get();
        Map<String, List<String>> deps = new HashMap<>();
        Jdbc.query(c, "SELECT step_code, depends_on_step FROM profile_step_dep WHERE profile_code=? ORDER BY step_code, depends_on_step",
            rs -> { deps.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(rs.getString(2)); return 0; }, code);
        Map<String, List<CargoProfile.Req>> reqs = new HashMap<>();
        Jdbc.query(c, "SELECT step_code, resource_type, capability, amount_mode, hold_until_step FROM profile_step_req WHERE profile_code=? ORDER BY req_id",
            rs -> { reqs.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(
                new CargoProfile.Req(rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5))); return 0; }, code);
        List<CargoProfile.Step> steps = Jdbc.query(c, "SELECT step_code, task_type, base_min, per_unit_min FROM profile_step WHERE profile_code=? ORDER BY step_code",
            rs -> new CargoProfile.Step(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getDouble(4),
                deps.getOrDefault(rs.getString(1), List.of()), reqs.getOrDefault(rs.getString(1), List.of())), code);
        return Optional.of(new CargoProfile(code, (String) h[0], (String) h[1], (Boolean) h[2], (Double) h[3], (Double) h[4],
            (Integer) h[5], (String) h[6], (String) h[7], (String) h[8], steps));
    }

    public static void updateConstraints(Connection c, String code, int maxWaitMin, Double tMin, Double tMax, boolean requiresExpiry) throws SQLException {
        Jdbc.update(c, "UPDATE handling_profile SET max_wait_min=?, temp_min_c=?, temp_max_c=?, requires_expiry=? WHERE profile_code=?",
            maxWaitMin, tMin, tMax, requiresExpiry, code);
    }
}
