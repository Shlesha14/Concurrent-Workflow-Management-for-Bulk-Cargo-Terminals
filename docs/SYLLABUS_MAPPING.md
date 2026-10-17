# Syllabus mapping (OS TCS-502, DBMS TCS-503)

Topics were read from your uploaded syllabus scans (OCR; **please check the exact wording against the PDFs** before printing this table in the report).
Status key: **IT** implemented and tested · **I** implemented, not separately tested · **D** documented only · **N** not implemented.

## Operating Systems
| Syllabus topic | Where in PortFlow | Class / script / screen | Student can demonstrate | Limitation | Status |
|---|---|---|---|---|---|
| Processes vs threads, multithreading | Worker threads executing tasks | `engine.ExecutionEngine` | Live Monitor shows `pf-worker-N`; scenario D overlaps | Java threads (1:1 OS threads); no `fork`/process creation | IT (test 8) |
| Thread pools, thread lifecycle | Fixed `ExecutorService`; dispatcher thread | `ExecutionEngine.start/stop` | Change workers in Settings; stop = interrupt + safe fail | – | IT |
| CPU / task scheduling criteria and algorithms | FCFS, priority, EDF, composite policy; comparison | `scheduler.*`, Scheduler + Simulation screens | Scenario G table; Scenario A order | No round-robin/SJF/multilevel (shortness is a score term); real CPU scheduling is done by the JVM/OS | IT (tests 22 + policy unit tests) |
| Inter-process communication / signalling | `Semaphore` wake-up between workers and dispatcher; shared state via DB | `ExecutionEngine.wake` | Dispatcher reacts without polling delay | Not pipes/sockets/shared memory | I |
| Critical section, mutual exclusion | `ReentrantLock` (tryLock timeout) + DB row locks around allocation | `resource.ResourceManager` | 10 threads race for 1 crane → 1 granted (scenario H, tests 19) | Peterson/hardware instructions not implemented (used implicitly by the JVM) | IT |
| Semaphores / locks | Counting-capacity accounting per resource (SLOTS ≈ counting semaphore; implemented in the database, not `java.util.concurrent.Semaphore`); `Semaphore` only for wake-up; bounded blocking pool in `util.Database` | `ResourceManager`, `Database` | Capacity 2 crane never exceeded (test 10) | Resource slots are *not* `Semaphore` objects – explain why: state must survive in MySQL | IT |
| Classical sync problems | Bounded buffer ≈ connection pool; producer/consumer ≈ dispatcher/workers | `Database`, `ExecutionEngine` | Pool exhaustion message | Not a textbook implementation | I |
| Deadlock: conditions | Documented per condition (mutual exclusion, hold-and-wait, no preemption, circular wait) | `docs/ARCHITECTURE.md` | Explain which condition each mechanism removes | – | D |
| Deadlock prevention | All-or-nothing acquisition (no hold-and-wait); ordered locking (no circular wait); DAG validation | `ResourceManager`, `DagValidator` | Scenario F; test 6; ordered `FOR UPDATE` | Running tasks are not preemptible | IT |
| Deadlock avoidance (Banker's) | – | – | – | – | N |
| Deadlock detection and recovery | Wait-for graph over workflows, cycle DFS, victim selection, hold revocation | `resource.DeadlockDetector` | `circularWaitBetweenWorkflowsIsDetectedAndResolvedByVictim` | Models storage holds only; engine runs detection every 5th tick | IT |
| Resource allocation, failure handling, recovery | Failure → drain/block/revoke/revise; retry with back-off; safe release in `finally` | `ResourceService`, `ExecutionEngine.fail/safetyRelease` | Scenario E; tests 13–17 | – | IT |
| Logging and monitoring | Append-only audit log, live monitor, metrics | `EventLog`, Monitor/Activity screens | Filter log by task; export CSV | – | IT (via many tests) |
| Memory management, paging, virtual memory | – | – | – | Out of project scope | N |
| File system, disk scheduling, I/O systems | – | – | – | Out of project scope | N |
| Protection / security (OS) | Prepared statements, no committed credentials | `util.Jdbc` | – | No users/roles | D |
| Shell / Unix commands | Helper scripts only | `scripts/` | – | Convenience, not a syllabus demonstration | D |

## DBMS
| Syllabus topic | Where | Class / script | Demonstrate | Limitation | Status |
|---|---|---|---|---|---|
| DBMS vs files, data independence | Profiles as data; views | `01_schema.sql`, `03_views.sql` | Add a cargo profile by INSERT only | – | I |
| ER modelling, ER→relational | ER diagram and mapping table | `docs/diagrams/er.png`, `DATABASE_DESIGN.md` | Walk the notebook → schema table | Hand-made diagram | D |
| Keys, relational constraints | PK/FK/UNIQUE/CHECK/NOT NULL; generated-column UNIQUE | `01_schema.sql` | `databaseRejectsNegativeQuantity…`, duplicate live allocation test | CHECK needs MySQL ≥ 8.0.16 | IT |
| SQL: DDL/DML, joins, aggregation, subqueries | Reports and dashboard | `05_sample_queries.sql`, `dao.ReportDao` | Run the file | – | IT (dashboard/report queries run in UI smoke + tests) |
| Views, indexes | 3 views; 12+ indexes; EXPLAIN | `03_views.sql`, `05_sample_queries.sql` | EXPLAIN uses `idx_task_state` | No performance benchmark | I |
| Triggers (assertions) | Prerequisite guard, resource audit | `02_triggers.sql` | Test 7 | MySQL has no `CREATE ASSERTION` | IT |
| Stored procedures / functions | – | – | – | Allocation logic lives in Java | N |
| Embedded / dynamic SQL, JDBC | `PreparedStatement` everywhere; dynamic filter with bound parameters | `util.Jdbc`, `EventLog.search` | Activity filters | – | I |
| Normalisation (FD, 2NF/3NF/BCNF) | Design analysis and deliberate denormalisation list | `DATABASE_DESIGN.md` | Defend each table | Not machine-verified | D |
| Transactions and ACID | Explicit tx boundaries; rollback on failure; audit in the same tx | `util.Database.inTransaction` | Test 20 (rollback), `06_transaction_demo.sql` | – | IT |
| Concurrency control: locking, isolation | READ COMMITTED + `FOR UPDATE`, fixed lock order, optimistic `WHERE state=?` | `ResourceManager`, `TaskDao.transition` | Test 19 with Java lock disabled | 2PL itself is InnoDB's; serializability not separately demonstrated | IT |
| Deadlocks in DBMS | Retry on SQLSTATE 40001 (max 3) | `Database.inTransaction` | – | No induced InnoDB deadlock test | I |
| Recovery, logs, checkpoints | Application audit/history tables; rollback | `event_log`, `allocation` | Rollback demo | Crash recovery is InnoDB's redo log, not our code | D |
| Security (SQL injection) | Parameterised queries, config template | `Jdbc`, `portflow.properties.template` | Show that no SQL is concatenated with user data (limits are validated integers) | No GRANT-based roles | I |
