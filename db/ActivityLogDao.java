package edu.portflow.db;

import edu.portflow.model.ActivityEvent;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public class ActivityLogDao {
    public void insert(ActivityEvent event) throws SQLException {
        String sql = "INSERT INTO activity_log(task_id,event_type,event_time,thread_name,message) VALUES(?,?,?,?,?)";
        try (Connection c = Database.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            if (event.taskId() == null) ps.setNull(1, java.sql.Types.BIGINT); else ps.setLong(1, event.taskId());
            ps.setString(2, event.type()); ps.setTimestamp(3, java.sql.Timestamp.valueOf(event.time()));
            ps.setString(4, event.threadName()); ps.setString(5, event.message()); ps.executeUpdate();
        }
    }
}
