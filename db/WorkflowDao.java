package edu.portflow.db;

import edu.portflow.model.Cargo;
import edu.portflow.model.WorkflowTask;
import java.sql.*;
import java.util.List;

public class WorkflowDao {
    public void insertCargoAndWorkflow(Cargo cargo, List<WorkflowTask> tasks) throws SQLException {
        String cargoSql = "INSERT INTO cargo(cargo_id,cargo_name,category,profile_id,quantity,unit,arrival_time,manufacturing_time,expiry_time,deadline_time,priority,status,notes) " +
                "SELECT ?,?,?,profile_id,?,?,?,?,?,?,?,'WAITING',? FROM handling_profile WHERE profile_code=?";
        String taskSql = "INSERT INTO task(task_id,cargo_id,task_name,task_type,priority,status,estimated_minutes,created_at,deadline_time) VALUES(?,?,?,?,?,?,?,?,?)";
        String depSql = "INSERT INTO task_dependency(task_id,depends_on_task_id) VALUES(?,?)";
        String reqSql = "INSERT INTO task_resource_requirement(task_id,resource_type,required_capacity,required_storage_class) VALUES(?,?,?,?)";
        try(Connection c=Database.getConnection()) {
            c.setAutoCommit(false);
            try {
                try(PreparedStatement ps=c.prepareStatement(cargoSql)) {
                    ps.setLong(1,cargo.id()); ps.setString(2,cargo.name()); ps.setString(3,cargo.category().name());
                    ps.setDouble(4,cargo.quantity()); ps.setString(5,cargo.unit());
                    ps.setTimestamp(6,Timestamp.valueOf(cargo.arrivalTime()));
                    if(cargo.manufacturingTime()==null)ps.setNull(7,Types.TIMESTAMP);else ps.setTimestamp(7,Timestamp.valueOf(cargo.manufacturingTime()));
                    if(cargo.expiryTime()==null)ps.setNull(8,Types.TIMESTAMP);else ps.setTimestamp(8,Timestamp.valueOf(cargo.expiryTime()));
                    if(cargo.deadlineTime()==null)ps.setNull(9,Types.TIMESTAMP);else ps.setTimestamp(9,Timestamp.valueOf(cargo.deadlineTime()));
                    ps.setInt(10,cargo.priority()); ps.setString(11,cargo.notes()); ps.setString(12,cargo.profile().code());
                    if(ps.executeUpdate()!=1)throw new SQLException("Handling profile not found: "+cargo.profile().code());
                }
                try(PreparedStatement ps=c.prepareStatement(taskSql); PreparedStatement dep=c.prepareStatement(depSql); PreparedStatement req=c.prepareStatement(reqSql)) {
                    for(WorkflowTask t:tasks) {
                        ps.setLong(1,t.id()); ps.setLong(2,cargo.id()); ps.setString(3,t.name()); ps.setString(4,t.taskType());
                        ps.setInt(5,t.priority()); ps.setString(6,t.status().name()); ps.setInt(7,t.estimatedMinutes());
                        ps.setTimestamp(8,Timestamp.valueOf(t.createdAt()));
                        if(t.deadline()==null)ps.setNull(9,Types.TIMESTAMP);else ps.setTimestamp(9,Timestamp.valueOf(t.deadline()));
                        ps.executeUpdate();
                        for(Long parent:t.dependencies()){dep.setLong(1,t.id());dep.setLong(2,parent);dep.addBatch();}
                        req.setLong(1,t.id());req.setString(2,t.requiredResourceType());req.setDouble(3,t.requiredCapacity());
                        req.setString(4,t.requiredStorageClass());req.addBatch();
                    }
                    dep.executeBatch(); req.executeBatch();
                }
                c.commit();
            } catch(SQLException ex) { c.rollback(); throw ex; }
            finally { c.setAutoCommit(true); }
        }
    }
}
