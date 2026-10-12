package edu.portflow.db;

import java.sql.*;
import java.time.LocalDateTime;

public class AllocationDao {
    public long open(long taskId,long resourceId,LocalDateTime start,double capacity) throws SQLException {
        String sql="INSERT INTO schedule_allocation(task_id,resource_id,start_time,allocated_capacity,allocation_status) VALUES(?,?,?,?,'ALLOCATED')";
        try(Connection c=Database.getConnection();PreparedStatement ps=c.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS)){
            ps.setLong(1,taskId);ps.setLong(2,resourceId);ps.setTimestamp(3,Timestamp.valueOf(start));ps.setDouble(4,capacity);ps.executeUpdate();
            try(ResultSet rs=ps.getGeneratedKeys()){if(rs.next())return rs.getLong(1);}
        }
        throw new SQLException("Allocation insert failed.");
    }
    public void close(long allocationId,LocalDateTime end,String status) throws SQLException {
        String sql="UPDATE schedule_allocation SET end_time=?,allocation_status=? WHERE allocation_id=?";
        try(Connection c=Database.getConnection();PreparedStatement ps=c.prepareStatement(sql)){
            ps.setTimestamp(1,Timestamp.valueOf(end));ps.setString(2,status);ps.setLong(3,allocationId);ps.executeUpdate();
        }
    }
}
