-- Demonstration queries (run after the app/scenarios created some data):  mysql -u root -p portflow < database/05_sample_queries.sql
USE portflow;

-- 1. Multi-table JOIN: every task with its cargo, profile and executing thread
SELECT t.task_id, g.name AS cargo, p.display_name AS profile, t.step_code, t.state, t.worker_thread
FROM task t JOIN cargo g ON g.cargo_id = t.cargo_id JOIN handling_profile p ON p.profile_code = g.profile_code
ORDER BY t.task_id;

-- 2. Aggregation + GROUP BY: tasks per state per profile category
SELECT p.category, t.state, COUNT(*) AS tasks
FROM task t JOIN cargo g ON g.cargo_id = t.cargo_id JOIN handling_profile p ON p.profile_code = g.profile_code
GROUP BY p.category, t.state ORDER BY p.category, t.state;

-- 3. Resource utilisation report (busy minutes of simulated time per resource)
SELECT r.name, r.type_code, COUNT(a.allocation_id) AS uses,
       ROUND(SUM(TIMESTAMPDIFF(SECOND, a.start_time, a.end_time)) / 60, 1) AS busy_minutes
FROM resource r LEFT JOIN allocation a ON a.resource_id = r.resource_id AND a.end_time IS NOT NULL
GROUP BY r.resource_id, r.name, r.type_code ORDER BY busy_minutes DESC;

-- 4. Subquery: cargo whose workflow has at least one BLOCKED or FAILED task, with the reason
SELECT g.cargo_id, g.name, (SELECT GROUP_CONCAT(CONCAT(t.step_code, ':', t.block_code))
                            FROM task t WHERE t.cargo_id = g.cargo_id AND t.state IN ('BLOCKED','FAILED')) AS problems
FROM cargo g WHERE g.cargo_id IN (SELECT cargo_id FROM task WHERE state IN ('BLOCKED','FAILED'));

-- 5. Views (defined in 03_views.sql): free capacity right now, and blocked tasks
SELECT * FROM v_resource_load ORDER BY free_capacity;
SELECT * FROM v_blocked_tasks;

-- 6. Expiring / urgent cargo (next 120 minutes of the simulation clock; replace @sim_now with the app's clock if needed)
SET @sim_now = (SELECT COALESCE(MAX(sim_ts), NOW()) FROM event_log);
SELECT cargo_id, name, expires_at, TIMESTAMPDIFF(MINUTE, @sim_now, expires_at) AS minutes_left
FROM cargo WHERE expires_at IS NOT NULL AND status IN ('REGISTERED','IN_PROGRESS') AND expires_at <= DATE_ADD(@sim_now, INTERVAL 120 MINUTE)
ORDER BY expires_at;

-- 7. Audit / history: full life of one task (change the id)
SELECT event_id, sim_ts, event_type, thread_name, from_state, to_state, message FROM event_log WHERE task_id = 1 ORDER BY event_id;

-- 8. Allocation history is never deleted: show released, held and revoked rows
SELECT a.allocation_id, t.step_code, r.name AS resource, a.amount, a.status, a.start_time, a.end_time, a.note
FROM allocation a JOIN task t ON t.task_id = a.task_id JOIN resource r ON r.resource_id = a.resource_id ORDER BY a.allocation_id;

-- 9. Schedule revisions and their plans
SELECT v.revision_id, v.created_at, v.reason_code, v.policy_name, v.affected_tasks, v.description FROM schedule_revision v ORDER BY v.revision_id;

-- 10. Index usage: EXPLAIN shows idx_task_state / idx_al_resource_status being used
EXPLAIN SELECT * FROM task WHERE state = 'WAITING_FOR_RESOURCE';
EXPLAIN SELECT SUM(amount) FROM allocation WHERE resource_id = 1 AND status IN ('ACTIVE','HELD');

-- 11. Simulated storage temperature readings and excursions (is_simulated is always TRUE)
SELECT r.name, s.ts, s.temp_c, s.excursion FROM storage_reading s JOIN resource r ON r.resource_id = s.resource_id ORDER BY s.reading_id DESC LIMIT 20;

-- 12. Constraints at work (each statement below is EXPECTED TO FAIL; run them one at a time)
-- INSERT INTO cargo (name, profile_code, quantity, unit, arrival_time, created_at) VALUES ('x','GENERAL',-1,'TONNE',NOW(),NOW());   -- CHECK ck_cargo_qty
-- INSERT INTO task_dependency VALUES (1,1);                                                                                         -- CHECK ck_td_self
-- UPDATE task SET state='RUNNING' WHERE task_id = (SELECT task_id FROM (SELECT d.task_id FROM task_dependency d JOIN task p ON p.task_id=d.depends_on_task_id WHERE p.state<>'COMPLETED' LIMIT 1) x);  -- trigger trg_task_prereq_guard
