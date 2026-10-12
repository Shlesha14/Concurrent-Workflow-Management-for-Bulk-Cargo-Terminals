package edu.portflow.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public final class Database {
    private Database() {}
    public static Connection getConnection() throws SQLException {
        String url = System.getenv().getOrDefault("PORTFLOW_DB_URL",
                "jdbc:mysql://localhost:3306/portflow?useSSL=false&serverTimezone=UTC");
        String user = System.getenv().getOrDefault("PORTFLOW_DB_USER", "portflow_user");
        String password = System.getenv().getOrDefault("PORTFLOW_DB_PASSWORD", "");
        return DriverManager.getConnection(url, user, password);
    }
    public static boolean canConnect() {
        try (Connection ignored = getConnection()) { return true; }
        catch (SQLException ex) { return false; }
    }
}
