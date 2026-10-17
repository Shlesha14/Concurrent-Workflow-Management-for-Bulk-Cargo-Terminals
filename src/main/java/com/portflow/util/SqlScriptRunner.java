package com.portflow.util;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** Executes .sql files (supports the mysql client's DELIMITER command). Used to install the schema from Java and in tests. */
public final class SqlScriptRunner {
    private SqlScriptRunner() {}

    public static List<String> split(String script) {
        List<String> out = new ArrayList<>();
        String delim = ";";
        StringBuilder cur = new StringBuilder();
        for (String line : script.split("\\R")) {
            String t = line.trim();
            if (t.startsWith("--") || t.isEmpty()) continue;
            if (t.toUpperCase().startsWith("DELIMITER ")) { delim = t.substring(10).trim(); continue; }
            cur.append(line).append('\n');
            if (t.endsWith(delim)) {
                String stmt = cur.toString().trim();
                stmt = stmt.substring(0, stmt.length() - delim.length()).trim();
                if (!stmt.isEmpty()) out.add(stmt);
                cur.setLength(0);
            }
        }
        if (!cur.toString().isBlank()) out.add(cur.toString().trim());
        return out;
    }

    /** @param skipDatabaseSelection ignore CREATE DATABASE / USE so the script runs against the already-connected schema */
    public static void runResource(Connection c, String resource, boolean skipDatabaseSelection) throws SQLException, IOException {
        try (InputStream in = SqlScriptRunner.class.getResourceAsStream(resource)) {
            if (in == null) throw new FileNotFoundException("Missing classpath resource " + resource);
            String script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            try (Statement st = c.createStatement()) {
                for (String s : split(script)) {
                    String up = s.toUpperCase();
                    if (skipDatabaseSelection && (up.startsWith("CREATE DATABASE") || up.startsWith("USE "))) continue;
                    st.execute(s);
                }
            }
        }
    }
}
