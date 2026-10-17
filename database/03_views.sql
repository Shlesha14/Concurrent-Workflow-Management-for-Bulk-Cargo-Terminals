USE portflow;
CREATE OR REPLACE VIEW v_task_overview AS
SELECT t.task_id, t.workflow_id, c.name AS cargo, t.step_code, t.task_type, t.priority, t.state,
       t.block_code, t.block_reason, t.ready_at, t.started_at, t.completed_at, t.deadline_at,
       t.worker_thread, t.retry_count, t.deadline_missed
FROM task t JOIN cargo c ON c.cargo_id = t.cargo_id;

CREATE OR REPLACE VIEW v_resource_load AS
SELECT r.resource_id, r.name, r.type_code, r.status, r.capacity,
       COALESCE(SUM(CASE WHEN a.status IN ('ACTIVE','HELD') THEN a.amount END), 0) AS in_use,
       r.capacity - COALESCE(SUM(CASE WHEN a.status IN ('ACTIVE','HELD') THEN a.amount END), 0) AS free_capacity
FROM resource r LEFT JOIN allocation a ON a.resource_id = r.resource_id
GROUP BY r.resource_id, r.name, r.type_code, r.status, r.capacity;

CREATE OR REPLACE VIEW v_blocked_tasks AS
SELECT task_id, cargo, step_code, state, block_code, block_reason FROM v_task_overview WHERE state IN ('BLOCKED','FAILED');
