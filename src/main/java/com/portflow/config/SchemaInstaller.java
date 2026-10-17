package com.portflow.config;

import com.portflow.util.Database;
import com.portflow.util.Jdbc;
import com.portflow.util.SqlScriptRunner;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/** Installs/refreshes the schema from the same SQL files the README tells users to run by hand (database/*.sql). */
public final class SchemaInstaller {
    private SchemaInstaller() {}
    public static final List<String> SCRIPTS = List.of("/sql/01_schema.sql", "/sql/02_triggers.sql", "/sql/03_views.sql", "/sql/04_seed.sql");

    public static void install(Database db, boolean dropExisting) throws SQLException, IOException {
        try (Connection c = db.connection()) {
            if (dropExisting) {
                try (Statement st = c.createStatement()) {
                    st.execute("SET FOREIGN_KEY_CHECKS=0");
                    for (String v : Jdbc.query(c, "SELECT table_name FROM information_schema.views WHERE table_schema=DATABASE()", rs -> rs.getString(1))) st.execute("DROP VIEW IF EXISTS `" + v + "`");
                    for (String t : Jdbc.query(c, "SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE'", rs -> rs.getString(1))) st.execute("DROP TABLE IF EXISTS `" + t + "`");
                    st.execute("SET FOREIGN_KEY_CHECKS=1");
                }
            }
            for (String s : SCRIPTS) SqlScriptRunner.runResource(c, s, true);
        }
    }

    public static boolean isInstalled(Database db) {
        try (Connection c = db.connection()) {
            return Jdbc.one(c, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='handling_profile'", rs -> rs.getInt(1)).orElse(0) > 0;
        } catch (SQLException e) { return false; }
    }

    /** Clears operational data (and optionally resources) but keeps profiles and settings. Used by scenarios and tests. */
    public static void resetOperationalData(Database db, boolean includeResources) throws SQLException {
        try (Connection c = db.connection(); Statement st = c.createStatement()) {
            st.execute("SET FOREIGN_KEY_CHECKS=0");
            for (String t : List.of("event_log", "storage_reading", "plan_entry", "schedule_revision", "allocation", "task_requirement", "task_dependency", "task", "workflow_instance", "cargo"))
                st.execute("TRUNCATE TABLE " + t);
            if (includeResources) { st.execute("TRUNCATE TABLE resource_capability"); st.execute("TRUNCATE TABLE resource"); }
            st.execute("SET FOREIGN_KEY_CHECKS=1");
        }
    }
}
