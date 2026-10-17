# Viva preparation notes

## 60-second pitch
PortFlow simulates a bulk-cargo terminal. Cargo is classified by a configurable handling profile; the profile expands into a **DAG of tasks**.
A dispatcher thread orders ready tasks with a **scheduling policy**, a **resource manager** reserves compatible resources **all-or-nothing** using
a Java lock plus **MySQL row locks**, and a **fixed thread pool** runs independent tasks in parallel. Every state change is a **transaction** with an audit row.

## Questions you should be able to answer (with where to look)
1. *Why a thread pool?* Bounded concurrency and reuse; one thread per task could create unbounded threads. `ExecutionEngine.start`.
2. *Where is mutual exclusion?* `ResourceManager.allocateAndStart` (Java `ReentrantLock` + `FOR UPDATE`). Test 19 disables the Java lock to prove the DB alone is enough.
3. *Which deadlock conditions do you break?* Hold-and-wait (all-or-nothing), circular wait (ordered locking, DAG check). Mutual exclusion and no-preemption remain (running tasks drain). Detection exists for storage-hold cycles.
4. *Is deadlock impossible?* No. Timeouts and ordering reduce it; we detect and recover from the modelled case; InnoDB deadlocks are retried (≤3).
5. *Why READ COMMITTED?* We lock first, then read the usage; each statement must see the latest committed allocations. See `docs/ARCHITECTURE.md` §4.
6. *Why not keep a transaction open while the task runs?* Long transactions hold locks and block everyone; we commit the reservation, run, then commit the result.
7. *What if the app crashes mid-task?* The task stays RUNNING with ACTIVE allocations (not recovered automatically – a stated limitation; an operator can retry after cleanup). Crash recovery of the DB itself is InnoDB's.
8. *Difference between WAITING and BLOCKED?* Contention (temporary) vs a condition that needs a change (missing/unavailable resource, timeout, failed prerequisite).
9. *Why is capacity in two models?* Equipment is counted in slots, storage in quantity; the model belongs to the resource type.
10. *How is correctness enforced beyond Java?* CHECK, FK, `UNIQUE(live_key)`, two triggers, state-guarded updates.
11. *How does the composite score work, and can it break safety?* `docs/SCHEDULING.md`; it only orders candidates.
12. *Which policy is best?* None universally; scenario G shows EDF/PERISHABILITY reduce deadline misses vs FCFS in that input; results vary per run.
13. *What did the tests find?* A race between the engine and the operator service, endless VALIDATED dependants of blocked tasks, and an urgency score that hid shelf-life differences – all fixed (see `TESTING.md`).
14. *What is simulated?* Everything physical: time (SimClock), work duration, failures, temperatures.
15. *What is NOT implemented?* `docs/KNOWN_LIMITATIONS.md` (be upfront: berth model, deadlock avoidance, stored procedures, memory/file-system topics).

## Study these first
Java: `ResourceManager`, `ExecutionEngine`, `TaskDao.transition`, `TaskState`, `DagValidator`, `ResourceSelector`, `PerishabilityAwarePolicy`, `DeadlockDetector`, `Database.inTransaction`, `ResourceService.changeStatus`, `SchedulePlanner`.
SQL: `01_schema.sql` (allocation table, generated `live_key`), `02_triggers.sql`, `03_views.sql`, `06_transaction_demo.sql`.
Tests: `ConcurrencyAndTransactionDbTests`, `EngineDbTests`.

## Demo sequence (≈10 minutes)
1. **Settings** → *Reset database + load demo data* → **Resources** shows the terminal.
2. **Cargo** → register *Tomatoes* (TOMATO, 40 t) → *Create workflow* → **Workflows** shows the DAG (UNLOAD ‖ PREP_STORAGE → INSPECT → STORE → DISPATCH).
3. **Start engine** → **Live Monitor**: two tasks run at once on different `pf-worker-N` threads; resources acquired/released in the event list (*concurrency*).
4. **Resources** → select FORK1 → *Simulate FAILURE* → dependants become BLOCKED with a reason; **Scheduler** shows a new revision; set AVAILABLE → recovery (*failure handling, history*).
5. **Simulation** → Scenario H → exactly 1 of 10 simultaneous attempts is granted (*mutual exclusion + DB locking*); Scenario F → cycle rejected (*deadlock prevention*); Scenario G → policy comparison table.
6. Terminal: `mysql portflow < database/05_sample_queries.sql` → joins, aggregation, audit; `06_transaction_demo.sql` for locking/rollback.
