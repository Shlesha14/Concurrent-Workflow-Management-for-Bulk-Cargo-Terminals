# Database design (MySQL 8 / InnoDB)

ER diagram: `diagrams/er.png` (source `er.dot`). Scripts: `database/01_schema.sql` … `06_transaction_demo.sql`.

## From the handwritten notes to the schema
| Notebook | Schema | Why it changed |
|---|---|---|
| `Cargo(cargo_id, cargo_type, manufacturing_time, expiry_time, quantity, priority, arrival_time, status)` | `cargo` + `handling_profile` | `cargo_type` became an FK to a configurable profile (rules are data). Added unit, deadline, special handling, status reason. Status values changed from "Waiting" to `REGISTERED/IN_PROGRESS/COMPLETED/REJECTED/QUARANTINED` because task-level waiting belongs to the task. |
| `Resources(resource_id, name, type, capacity, status, location)` | `resource`, `resource_type`, `resource_capability` | Capacity model made explicit (SLOTS vs QUANTITY, see below). Capabilities are a multi-valued attribute → own table (1NF). Storage temperature range added. Notebook example *Crane CR1, capacity 2, Terminal B* and *storage 300 + 700* are in the seed data. |
| `Task(task_id, cargo_id, task_type, priority, status, created_at, deadline, processing_time)` (the `**` items) | `task` + `workflow_instance` | Added workflow grouping, block reason, retry count, thread name, timestamps. `priority` and `processing_time` (`duration_min`) are filled from cargo/profile. |
| `TaskResource(task_id, resource_id, required_from, required_until, allocation_status)` | `task_requirement` (what is needed) + `allocation` (what was granted, when) | **Ambiguity fixed**: the notebook fixes a *specific* resource and time before scheduling. In PortFlow the scheduler chooses the concrete resource at run time, so the requirement holds *type + capability + amount* and `allocation` stores the choice and real interval. |
| `Schedule/Allocation(schedule_id, task_id, resource_id, start, end, status)` | `allocation` (+ `schedule_revision`, `plan_entry` for plans) | Status lifecycle `ACTIVE→HELD→RELEASED/REVOKED`; rows never deleted. Plans/revisions separate from actual allocations. |
| `TaskDependency(task_id, depends_on_task_id)` | `task_dependency` | Same, plus CHECK `task_id <> depends_on_task_id`; cycles rejected in `DagValidator`. |
| `ActivityLog(event_id, task_id, resource_id, event_type, timestamp, message)` | `event_log` | Added cargo/workflow id, thread, from/to state, simulation timestamp. Append-only; intentionally has **no foreign keys** so history survives any data change. |

## Capacity models (never mixed)
* **SLOTS** (cranes, conveyors, forklifts, trucks, inspection stations, reclaimers): discrete; each task uses exactly 1 slot for its duration; `capacity` = number of simultaneous tasks.
* **QUANTITY** (cold rooms, chilled/frozen stores, dry stores, stockyards): continuous; a task reserves the cargo quantity (in handling units: tonnes/pallets/crates; kg is converted to tonnes) and keeps it (`HELD`) until the step named in `hold_until_step` (normally `DISPATCH`/`RECLAIM`) completes.
The model belongs to the **resource type**, so one resource can only ever follow one model.

## Normalisation notes (documented analysis, not tool-verified)
* **1NF** – no repeating groups: capabilities and dependencies are rows.
* **2NF/3NF** – profile attributes depend only on `profile_code`; resource attributes only on `resource_id`; step attributes on `(profile_code, step_code)`; no transitive dependency such as profile→category→…; `resource_type.capacity_model` depends on the type, not on each resource.
* **Deliberate denormalisation**: `task.cargo_id` (derivable via workflow, kept for fast joins; consistent because workflow↔cargo is 1:1 via `UNIQUE(cargo_id)`), `task.priority`/`deadline_at` (a snapshot at creation so later cargo edits cannot change running work), `event_log` descriptive columns (history must not change).

## Integrity rules and where they are enforced
| Rule | DB enforcement | Java enforcement |
|---|---|---|
| quantity > 0, priority 1–5, expiry > manufacturing, deadline ≥ arrival | `CHECK` (cargo) | `CargoValidator` (friendly messages) |
| end ≥ start (task, allocation), planned_end > planned_start | `CHECK` | engine uses one clock |
| valid dependency, no self-dependency | FK + `CHECK` | `DagValidator` (cycles) |
| one live allocation per requirement | `UNIQUE(live_key)` on a generated column (non-NULL only while ACTIVE/HELD) | – |
| no over-allocation | **row locks** (`FOR UPDATE`) + capacity check in one transaction | `ResourceManager` |
| incompatible cargo ↔ storage | – (needs profile data) | `ResourceSelector` (type, capability, temperature range ⊆ profile range) |
| no start/complete with unfinished prerequisites | trigger `trg_task_prereq_guard` | state machine + engine |
| audit of every resource status change | trigger `trg_resource_status_audit` | – |
| audit of every task/allocation change | – | `EventLog` inside the same transaction (rolled back with it) |

## Transaction boundaries
| Operation | Transaction | Isolation |
|---|---|---|
| Register cargo (+ audit) | 1 tx | READ COMMITTED |
| Create workflow (tasks, deps, requirements, VALIDATED, audit) | 1 tx | READ COMMITTED |
| Allocate + start | 1 tx (locks, inserts, state, audit) | READ COMMITTED + `FOR UPDATE` |
| Task completion / failure + release | 1 new tx each | READ COMMITTED |
| Resource failure / repair + blocking/recovery + revision | 1 tx (locks the resource type first) | READ COMMITTED |
| Simulated work | **none** (no transaction held while "running") | – |

## Indexes
`idx_task_state`, `idx_task_cargo`, `idx_al_resource_status (resource_id,status)` (capacity sum), `idx_resource_type_status`, `idx_td_dep`, `idx_cargo_expiry`, `idx_cargo_status`, `idx_ev_task/cargo/res`, `idx_ev_type_ts`, `idx_pe_rev`, `idx_sr_res_ts`. `EXPLAIN` examples are in `05_sample_queries.sql`.

## Views
`v_task_overview` (task ⨝ cargo), `v_resource_load` (capacity, in-use, free via LEFT JOIN + SUM), `v_blocked_tasks`.
Not implemented: stored procedures, SQL assertions (MySQL has none – CHECK + triggers are used), user/role security.
