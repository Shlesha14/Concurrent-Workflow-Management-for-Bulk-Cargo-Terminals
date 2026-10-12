package edu.portflow.db;

import edu.portflow.model.WorkflowTask;
import java.sql.*;

public class TaskDao {
    public long insert(WorkflowTask task) throws SQLException {
        String sql = "INSERT INTO task(cargo_id,task_name,task_type,priority,status,estimated_minutes,created_at,deadline_time) VALUES(?,?,?,?,?,?,?,?)";
        try (Connection c=Database.getConnection(); PreparedStatement ps=c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, task.cargoId()); ps.setString(2, task.name()); ps.setString(3, task.taskType());
            ps.setInt(4, task.priority()); ps.setString(5, task.status().name()); ps.setInt(6, task.estimatedMinutes());
            ps.setTimestamp(7, Timestamp.valueOf(task.createdAt()));
            if(task.deadline()==null) ps.setNull(8,Types.TIMESTAMP); else ps.setTimestamp(8,Timestamp.valueOf(task.deadline()));
            ps.executeUpdate();
            try(ResultSet rs=ps.getGeneratedKeys()){if(rs.next()) return rs.getLong(1);}
        }
        throw new SQLException("Task insert failed.");
    }
    public void updateState(long id, String status, String worker, String failure) throws SQLException {
        String sql="UPDATE task SET status=?,worker_name=?,failure_reason=?,actual_start=CASE WHEN ?='RUNNING' THEN NOW() ELSE actual_start END, completed_at=CASE WHEN ? IN ('COMPLETED','FAILED','EXPIRED') THEN NOW() ELSE completed_at END WHERE task_id=?";
        try(Connection c=Database.getConnection(); PreparedStatement ps=c.prepareStatement(sql)){
            ps.setString(1,status); ps.setString(2,worker); ps.setString(3,failure); ps.setString(4,status); ps.setString(5,status); ps.setLong(6,id); ps.executeUpdate();
        }
    }
}
