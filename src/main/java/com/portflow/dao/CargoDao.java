package com.portflow.dao;

import com.portflow.model.Cargo;
import com.portflow.util.Jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class CargoDao {
    private CargoDao() {}

    static final Jdbc.RowMapper<Cargo> MAP = rs -> new Cargo(rs.getLong("cargo_id"), rs.getString("name"), rs.getString("profile_code"),
        rs.getBigDecimal("quantity"), rs.getString("unit"), Jdbc.ts(rs, "arrival_time"), Jdbc.ts(rs, "manufactured_at"), Jdbc.ts(rs, "expires_at"),
        rs.getInt("priority"), Jdbc.ts(rs, "deadline_at"), rs.getString("special_handling"), rs.getString("extra_info"),
        rs.getString("status"), rs.getString("status_reason"), Jdbc.ts(rs, "created_at"));

    public static long insert(Connection c, Cargo g) throws SQLException {
        return Jdbc.insert(c, "INSERT INTO cargo (name, profile_code, quantity, unit, arrival_time, manufactured_at, expires_at, priority, deadline_at, special_handling, extra_info, status, status_reason, created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            g.name(), g.profileCode(), g.quantity(), g.unit(), g.arrivalTime(), g.manufacturedAt(), g.expiresAt(), g.priority(), g.deadlineAt(),
            g.specialHandling(), g.extraInfo(), g.status(), g.statusReason(), g.createdAt());
    }

    public static void update(Connection c, Cargo g) throws SQLException {
        Jdbc.update(c, "UPDATE cargo SET name=?, profile_code=?, quantity=?, unit=?, arrival_time=?, manufactured_at=?, expires_at=?, priority=?, deadline_at=?, special_handling=?, extra_info=?, status=?, status_reason=? WHERE cargo_id=?",
            g.name(), g.profileCode(), g.quantity(), g.unit(), g.arrivalTime(), g.manufacturedAt(), g.expiresAt(), g.priority(), g.deadlineAt(),
            g.specialHandling(), g.extraInfo(), g.status(), g.statusReason(), g.id());
    }

    public static void setStatus(Connection c, long id, String status, String reason) throws SQLException {
        Jdbc.update(c, "UPDATE cargo SET status=?, status_reason=? WHERE cargo_id=?", status, reason, id);
    }

    public static Optional<Cargo> find(Connection c, long id) throws SQLException { return Jdbc.one(c, "SELECT * FROM cargo WHERE cargo_id=?", MAP, id); }

    public static List<Cargo> search(Connection c, String text) throws SQLException {
        if (text == null || text.isBlank()) return Jdbc.query(c, "SELECT * FROM cargo ORDER BY cargo_id DESC", MAP);
        String like = "%" + text.trim() + "%";
        return Jdbc.query(c, "SELECT * FROM cargo WHERE name LIKE ? OR profile_code LIKE ? OR status LIKE ? ORDER BY cargo_id DESC", MAP, like, like, like);
    }

    public static List<Cargo> all(Connection c) throws SQLException { return search(c, null); }
}
