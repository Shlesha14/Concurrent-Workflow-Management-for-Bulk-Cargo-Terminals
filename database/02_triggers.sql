-- Triggers (kept to two, each with a distinct purpose). Run with the mysql client (DELIMITER is a client command).
USE portflow;
DROP TRIGGER IF EXISTS trg_task_prereq_guard;
DROP TRIGGER IF EXISTS trg_resource_status_audit;
DELIMITER $$

-- DBMS-level guard: even a buggy application cannot start or complete a task whose prerequisites are unfinished.
CREATE TRIGGER trg_task_prereq_guard BEFORE UPDATE ON task FOR EACH ROW
BEGIN
  IF NEW.state IN ('RUNNING','COMPLETED') AND OLD.state <> NEW.state THEN
    IF EXISTS (SELECT 1 FROM task_dependency d JOIN task p ON p.task_id = d.depends_on_task_id
               WHERE d.task_id = NEW.task_id AND p.state <> 'COMPLETED') THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Prerequisite tasks are not COMPLETED';
    END IF;
  END IF;
END$$

-- Audit guarantee: every resource status change is logged no matter which code path made it.
CREATE TRIGGER trg_resource_status_audit AFTER UPDATE ON resource FOR EACH ROW
BEGIN
  IF OLD.status <> NEW.status THEN
    INSERT INTO event_log (ts, event_type, resource_id, message)
    VALUES (NOW(3), 'RESOURCE_STATUS_CHANGED', NEW.resource_id,
            CONCAT('Resource ', NEW.name, ' status ', OLD.status, ' -> ', NEW.status));
  END IF;
END$$
DELIMITER ;
