# Architecture

Diagrams: `diagrams/architecture.png`, `diagrams/sequence.png` (execution cycle), `diagrams/er.png` (database). Sources are the `.dot` files (Graphviz).

## Layers (UI → service → DAO → MySQL; OS core beside the services)
| Package | Responsibility | Concept |
|---|---|---|
| `config` | `AppConfig` (properties), `PortFlowContext` (composition root, no globals), `SchemaInstaller` | – |
| `model` | `TaskState` (state machine), `BlockReason`, `EventType`, records (`Cargo`, `CargoProfile`, `Resource`, `Task`, `TaskRequirement`) | state machine |
| `dao` | JDBC only. `TaskDao.transition` is the *only* way to change a task state; `EventLog` writes audit rows in the caller's transaction | transactions, audit |
| `service` | Business rules: `CargoService`/`CargoValidator`, `WorkflowService`, `ResourceService`, `TaskAdminService`, `ReportService`, `Settings` | – |
| `workflow` | `DagValidator` (Kahn, O(V+E)) | deadlock prevention (no cycles) |
| `scheduler` | `SchedulingPolicy` + 4 policies, `SchedulePlanner` (preview, conflict detection, revisions) | CPU scheduling |
| `resource` | `ResourceManager`, `ResourceSelector`, `DeadlockDetector` | mutual exclusion, allocation, deadlock |
| `engine` | `ExecutionEngine` (dispatcher + worker pool), `FailureInjector` | multithreading |
| `simulation` | `SimClock`, `ScenarioRunner` (A–H), `StressTest`, `PolicyComparator`, `DemoData` | – |
| `monitoring` | `MetricsService`, `TemperatureSimulator` (simulated) | – |
| `ui` | Swing screens; every DB call runs in a `SwingWorker`, components are touched only on the EDT | – |
| `util` | `Database` (bounded pool + `inTransaction`), `Jdbc` (prepared statements only), `SqlScriptRunner`, `TableData` | JDBC |

## How Java concurrency and MySQL transactions cooperate
1. **Dispatcher** (one thread) decides *who goes next* using the selected policy. **Workers** (fixed `ExecutorService`, size from settings) only execute.
2. **Allocation** (`ResourceManager.allocateAndStart`): a fair `ReentrantLock` with `tryLock(timeout)` serialises the decision inside the JVM
   (bounded wait – never indefinite). Inside it, **one READ COMMITTED transaction** locks every resource row of the needed types with
   `SELECT … FOR UPDATE` in the fixed order *(type, id)*, reads the in-use capacity **after** locking, plans *all* requirements, and only then inserts the
   allocation rows, the audit rows and the `READY→RUNNING` transition, then commits. Any failure → rollback of all of it.
   The Java lock is an optimisation; **correctness comes from the row locks** (test: `concurrentReservationsOfOneCraneGrantExactlyOne_databaseLockOnly` disables the Java lock).
3. **No long transactions**: the simulated work happens with *no* transaction open; completion is a **new** transaction (`RUNNING→COMPLETED`, release equipment or mark storage `HELD`, audit rows).
4. **Isolation choice**: READ COMMITTED for allocation (each statement sees committed data; we lock first, then read). REPEATABLE READ would take its snapshot at the first plain read and could miss a concurrent commit. Deadlock/serialisation failures (SQLSTATE 40001) are retried up to 3 times by `Database.inTransaction`.
5. **Optimistic state changes**: `UPDATE task SET state=? … WHERE task_id=? AND state=?`; zero rows ⇒ `StateConflictException` (someone else won) – used by operator actions and the engine.
6. **DB as last line of defence**: CHECK constraints, FK/UNIQUE (`live_key` unique ⇒ one live allocation per requirement), trigger `trg_task_prereq_guard` (cannot start/complete a task with unfinished prerequisites), trigger `trg_resource_status_audit`.

## Task state machine
`CREATED → VALIDATED → READY ⇄ WAITING_FOR_RESOURCE → RUNNING → COMPLETED | FAILED`; any pre-run state may go `BLOCKED`, `CANCELLED` or `EXPIRED`;
`FAILED → READY` (retry), `BLOCKED → READY/VALIDATED` (recovery). Illegal transitions throw (`TaskState.canGoTo`). A timeout **never** marks a task successful.

## Distinguishing situations (never confused)
| Situation | Where decided | Result |
|---|---|---|
| Missing / contradictory cargo data | `CargoValidator` | Not stored; issues listed as MISSING / INVALID |
| Already expired cargo | `CargoService` (profile policy) | `REJECTED` or `QUARANTINED`, reason recorded, no workflow |
| Invalid dependency graph | `DagValidator` / engine check | `INVALID_WORKFLOW`, nothing runs |
| No compatible resource exists | `ResourceSelector.explainFailure` | `BLOCKED NO_COMPATIBLE_RESOURCE` |
| Compatible resources all down | same | `BLOCKED RESOURCE_UNAVAILABLE` (auto-recovers on repair) |
| Amount larger than every compatible resource | same | `BLOCKED CAPACITY_EXCEEDS_MAX` |
| Ordinary contention (resources full) | same | `WAITING_FOR_RESOURCE`; `WAIT_TIMEOUT` after profile max-wait |
| Prerequisite failed/blocked | engine housekeeping | `BLOCKED PREREQUISITE_FAILED` |
| Circular wait between workflows | `DeadlockDetector` | victim `BLOCKED DEADLOCK_VICTIM`, its holds revoked |

## Deadlock: prevention, detection, recovery, limits
* **Prevention** – *no hold-and-wait*: a task acquires **all** its resources atomically or none; *no circular wait*: fixed global lock order (type, id); *no cycles in workflows*: DAG validation before execution; *bounded waits*: `tryLock` timeout, profile `max_wait_min`, connection-pool timeout, retry limit with exponential back-off.
* **Detection/recovery** – `DeadlockDetector` builds a wait-for graph between workflows (waiting task → workflow holding the full storage via a `HELD` allocation), finds cycles (DFS) and breaks them by choosing the lowest-priority workflow as victim (test: `circularWaitBetweenWorkflowsIsDetectedAndResolvedByVictim`).
* **Limits** – a timeout does not prove absence of deadlock; only storage holds persist across tasks, so only those are modelled; deadlock *avoidance* (Banker's algorithm) is not implemented. InnoDB's own deadlock detector can still abort a transaction → retried.

## Schedule revision rules
Running tasks cannot be moved or rolled back: when a resource fails they **drain** (finish). New work is no longer placed there. Waiting tasks with no remaining compatible resource become `BLOCKED`.
Storage `HELD` on a failed resource is `REVOKED` (row kept, alert logged – cargo needs operator action). A `schedule_revision` row plus `plan_entry` rows (a fresh forward plan) are written; older revisions are never deleted. Repair triggers recovery and another revision.
