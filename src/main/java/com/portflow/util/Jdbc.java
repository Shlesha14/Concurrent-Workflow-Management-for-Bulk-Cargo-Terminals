package com.portflow.util;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Tiny JDBC helper. Every statement is a PreparedStatement, so user data is never concatenated into SQL. */
public final class Jdbc {
    private Jdbc() {}

    @FunctionalInterface public interface RowMapper<T> { T map(ResultSet rs) throws SQLException; }

    private static void bind(PreparedStatement ps, Object[] params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            Object v = params[i];
            if (v == null) ps.setNull(i + 1, Types.NULL);
            else if (v instanceof Enum<?> e) ps.setString(i + 1, e.name());
            else ps.setObject(i + 1, v);
        }
    }

    public static <T> List<T> query(Connection c, String sql, RowMapper<T> m, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                List<T> out = new ArrayList<>();
                while (rs.next()) out.add(m.map(rs));
                return out;
            }
        }
    }

    public static <T> Optional<T> one(Connection c, String sql, RowMapper<T> m, Object... params) throws SQLException {
        List<T> l = query(c, sql, m, params);
        return l.isEmpty() ? Optional.empty() : Optional.of(l.get(0));
    }

    public static int update(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) { bind(ps, params); return ps.executeUpdate(); }
    }

    public static long insert(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(ps, params);
            ps.executeUpdate();
            try (ResultSet k = ps.getGeneratedKeys()) { k.next(); return k.getLong(1); }
        }
    }

    public static LocalDateTime ts(ResultSet rs, String col) throws SQLException { return rs.getObject(col, LocalDateTime.class); }
    public static Long longOrNull(ResultSet rs, String col) throws SQLException { long v = rs.getLong(col); return rs.wasNull() ? null : v; }
    public static Integer intOrNull(ResultSet rs, String col) throws SQLException { int v = rs.getInt(col); return rs.wasNull() ? null : v; }
    public static Double doubleOrNull(ResultSet rs, String col) throws SQLException { double v = rs.getDouble(col); return rs.wasNull() ? null : v; }
}
