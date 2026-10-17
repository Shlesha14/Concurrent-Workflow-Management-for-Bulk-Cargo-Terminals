-- Two-session demonstration of the allocation protocol used by ResourceManager (READ COMMITTED + SELECT ... FOR UPDATE).
-- Open TWO mysql sessions. Pre-condition: run scenario H or create a resource 'CRX' of type CRANE with capacity 1 and no allocations.
USE portflow;

-- ===== Session 1 =====
SET SESSION TRANSACTION ISOLATION LEVEL READ COMMITTED;
START TRANSACTION;
SELECT resource_id, capacity FROM resource WHERE type_code = 'CRANE' ORDER BY resource_id FOR UPDATE;        -- row locks, fixed order
SELECT COALESCE(SUM(amount),0) AS in_use FROM allocation WHERE resource_id = 1 AND status IN ('ACTIVE','HELD'); -- read AFTER locking
-- ... in_use = 0 < capacity 1, so Session 1 may reserve:
-- INSERT INTO allocation (task_id, requirement_id, resource_id, amount, status, start_time) VALUES (<task>, <req>, 1, 1, 'ACTIVE', NOW(3));

-- ===== Session 2 (run now; it BLOCKS on FOR UPDATE until Session 1 commits or rolls back) =====
-- SET SESSION TRANSACTION ISOLATION LEVEL READ COMMITTED;
-- START TRANSACTION;
-- SELECT resource_id, capacity FROM resource WHERE type_code = 'CRANE' ORDER BY resource_id FOR UPDATE;
-- (waits...)  after Session 1 COMMIT it proceeds and now sees in_use = 1 = capacity => it must NOT insert (the application answers WAIT).

-- ===== Session 1 =====
COMMIT;      -- or ROLLBACK to see that nothing (allocation row AND audit row) survives

-- Rollback demonstration: everything between START TRANSACTION and ROLLBACK disappears together.
START TRANSACTION;
INSERT INTO event_log (ts, event_type, message) VALUES (NOW(3), 'OPERATOR_ACTION', 'this row will be rolled back');
ROLLBACK;
SELECT COUNT(*) AS should_be_zero FROM event_log WHERE message = 'this row will be rolled back';
