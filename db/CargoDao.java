package edu.portflow.db;

import edu.portflow.model.Cargo;
import java.sql.*;

public class CargoDao {
    public long insert(Cargo cargo) throws SQLException {
        String sql = "INSERT INTO cargo(cargo_name,category,profile_id,quantity,unit,arrival_time,manufacturing_time,expiry_time,deadline_time,priority,status,notes) " +
                "SELECT ?,?,profile_id,?,?,?,?,?,?,?,'WAITING',? FROM handling_profile WHERE profile_code=?";
        try (Connection c = Database.getConnection(); PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, cargo.name()); ps.setString(2, cargo.category().name());
            ps.setDouble(3, cargo.quantity()); ps.setString(4, cargo.unit());
            ps.setTimestamp(5, Timestamp.valueOf(cargo.arrivalTime()));
            if (cargo.manufacturingTime()==null) ps.setNull(6, Types.TIMESTAMP); else ps.setTimestamp(6, Timestamp.valueOf(cargo.manufacturingTime()));
            if (cargo.expiryTime()==null) ps.setNull(7, Types.TIMESTAMP); else ps.setTimestamp(7, Timestamp.valueOf(cargo.expiryTime()));
            if (cargo.deadlineTime()==null) ps.setNull(8, Types.TIMESTAMP); else ps.setTimestamp(8, Timestamp.valueOf(cargo.deadlineTime()));
            ps.setInt(9, cargo.priority()); ps.setString(10, cargo.notes()); ps.setString(11, cargo.profile().code());
            if (ps.executeUpdate() != 1) throw new SQLException("No matching handling profile found.");
            try (ResultSet rs=ps.getGeneratedKeys()) { if (rs.next()) return rs.getLong(1); }
        }
        throw new SQLException("Cargo inserted but generated key was not returned.");
    }
}
