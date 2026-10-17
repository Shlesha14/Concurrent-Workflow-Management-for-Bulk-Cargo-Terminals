package com.portflow.dao;

import com.portflow.model.Resource;
import com.portflow.util.Jdbc;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;

public final class ResourceDao {
    private ResourceDao() {}

    private static final String BASE = "SELECT r.*, t.capacity_model FROM resource r JOIN resource_type t ON t.type_code = r.type_code";

    private static Resource map(java.sql.ResultSet rs, Map<Integer, Set<String>> caps) throws SQLException {
        int id = rs.getInt("resource_id");
        return new Resource(id, rs.getString("name"), rs.getString("type_code"), rs.getBigDecimal("capacity"), rs.getString("status"),
            rs.getString("location"), Jdbc.doubleOrNull(rs, "temp_min_c"), Jdbc.doubleOrNull(rs, "temp_max_c"),
            caps.getOrDefault(id, Set.of()), rs.getString("capacity_model"));
    }

    private static Map<Integer, Set<String>> capabilities(Connection c) throws SQLException {
        Map<Integer, Set<String>> m = new HashMap<>();
        Jdbc.query(c, "SELECT resource_id, capability FROM resource_capability", rs -> { m.computeIfAbsent(rs.getInt(1), k -> new TreeSet<>()).add(rs.getString(2)); return 0; });
        return m;
    }

    public static List<Resource> all(Connection c) throws SQLException {
        Map<Integer, Set<String>> caps = capabilities(c);
        return Jdbc.query(c, BASE + " ORDER BY r.resource_id", rs -> map(rs, caps));
    }

    public static Optional<Resource> find(Connection c, int id) throws SQLException {
        Map<Integer, Set<String>> caps = capabilities(c);
        return Jdbc.one(c, BASE + " WHERE r.resource_id=?", rs -> map(rs, caps), id);
    }

    /** Locking read: ALL resources of a type, ordered by id, FOR UPDATE. Global lock order (type, id) prevents DB-level deadlock. */
    public static List<Resource> lockByType(Connection c, String type) throws SQLException {
        Map<Integer, Set<String>> caps = capabilities(c);
        return Jdbc.query(c, BASE + " WHERE r.type_code=? ORDER BY r.resource_id FOR UPDATE", rs -> map(rs, caps), type);
    }

    public static int insert(Connection c, String name, String type, BigDecimal cap, String status, String location, Double tMin, Double tMax, Collection<String> capabilities) throws SQLException {
        int id = (int) Jdbc.insert(c, "INSERT INTO resource (name, type_code, capacity, status, location, temp_min_c, temp_max_c) VALUES (?,?,?,?,?,?,?)",
            name, type, cap, status, location, tMin, tMax);
        setCapabilities(c, id, capabilities);
        return id;
    }

    public static void update(Connection c, int id, String name, BigDecimal cap, String location, Double tMin, Double tMax, Collection<String> capabilities) throws SQLException {
        Jdbc.update(c, "UPDATE resource SET name=?, capacity=?, location=?, temp_min_c=?, temp_max_c=? WHERE resource_id=?", name, cap, location, tMin, tMax, id);
        setCapabilities(c, id, capabilities);
    }

    private static void setCapabilities(Connection c, int id, Collection<String> caps) throws SQLException {
        Jdbc.update(c, "DELETE FROM resource_capability WHERE resource_id=?", id);
        for (String cap : new TreeSet<>(caps)) if (!cap.isBlank()) Jdbc.update(c, "INSERT INTO resource_capability (resource_id, capability) VALUES (?,?)", id, cap.trim().toUpperCase());
    }

    public static void setStatus(Connection c, int id, String status) throws SQLException { Jdbc.update(c, "UPDATE resource SET status=? WHERE resource_id=?", status, id); }

    public static List<String> types(Connection c) throws SQLException { return Jdbc.query(c, "SELECT type_code FROM resource_type ORDER BY type_code", rs -> rs.getString(1)); }
}
