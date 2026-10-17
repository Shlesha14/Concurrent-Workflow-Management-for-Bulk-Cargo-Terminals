package com.portflow.util;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Immutable snapshot of a query result, so the UI never touches JDBC objects. */
public record TableData(List<String> columns, List<List<Object>> rows) {
    public static TableData from(ResultSet rs) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        List<String> cols = new ArrayList<>();
        for (int i = 1; i <= md.getColumnCount(); i++) cols.add(md.getColumnLabel(i));
        List<List<Object>> rows = new ArrayList<>();
        while (rs.next()) {
            List<Object> r = new ArrayList<>();
            for (int i = 1; i <= cols.size(); i++) r.add(rs.getObject(i));
            rows.add(r);
        }
        return new TableData(cols, rows);
    }
    public String toCsv() {
        StringBuilder sb = new StringBuilder(String.join(",", columns)).append('\n');
        for (List<Object> r : rows) {
            for (int i = 0; i < r.size(); i++) {
                String v = r.get(i) == null ? "" : r.get(i).toString().replace("\"", "\"\"");
                sb.append(i > 0 ? "," : "").append('"').append(v).append('"');
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
