# Scheduling and resource selection

A **policy only orders** ready tasks. Hard rules (prerequisites complete, cargo arrived and not expired, compatible resource with capacity, retry back-off time reached)
are applied before and regardless of any score. All policies are deterministic; the last tie-break is the task id.

| Policy | Use when | Selects next | Pros / cons | Complexity |
|---|---|---|---|---|
| `FCFS` | arrival order is the rule, ordinary jobs | oldest `ready_at` | fair, no starvation; ignores urgency | O(n log n) |
| `PRIORITY` | urgent operational tasks | highest priority (5→1), then FCFS | simple; low priorities can starve | O(n log n) |
| `EDF` | explicit deadlines / shelf-life | earliest `min(deadline, expiry)`; no limit = last | minimises lateness on one resource; ignores priority; cascades when overloaded | O(n log n) |
| `PERISHABILITY` | mixed cargo with shelf-life and waiting limits | highest composite score (below) | balances urgency, priority and ageing; weights are hand-tuned | O(n log n) |

## Composite score (units, normalisation, assumptions)
`H` = urgency horizon in minutes (default 480). All terms are in [0,1]; weights come from Settings (default 0.30 / 0.40 / 0.20 / 0.10).
```
priorityN = (priority-1)/4
slack     = minutes from now to min(task deadline, cargo expiry) − estimated duration     (no limit → urgencyN = 0)
urgencyN  = 1 − clamp(slack / H, 0, 1)                                                      (slack ≤ 0 → 1)
waitN     = clamp(minutes already waiting / profile max_wait_min, 0, 1)                    (ageing; near a WAIT_TIMEOUT → 1)
shortN    = 1 − clamp(duration / 240, 0, 1)
score     = wP·priorityN + wU·urgencyN + wW·waitN + wS·shortN
```
Ties: earlier deadline/expiry, then earlier `ready_at`, then lower task id. First-expiring-first-out for perishables emerges from `urgencyN`.
Scenario A shows it: with one scarce cold room, FCFS stores the lot registered first, PERISHABILITY stores the lot that expires first.

## Resource selection (reproducible)
* **Compatibility** (`ResourceSelector.structurallyCompatible`): same type; has the required capability; for storage with a profile temperature range, the storage's whole operating range must lie inside the cargo's range (a −5…10 °C room is rejected for 0…4 °C produce; frozen rooms are never used for vegetables).
* **SLOTS resources → first fit**: lowest resource id with a free slot (equipment is interchangeable; keeps the outcome deterministic).
* **QUANTITY resources → best fit**: the compatible store with the smallest leftover capacity after placing the cargo; tie → lowest id (keeps large stores free for large cargo; objective: reduce fragmentation).
* All requirements of a task are chosen first; allocation happens only if **all** can be satisfied.

## Metrics used to compare policies (`MetricsService`, computed from the database)
tasks completed/failed/blocked, **deadline misses** (flag set when finished late or still open past the deadline), **average waiting** (ready→start, simulated minutes), **makespan** (first creation → last completion), **throughput** (completed tasks per simulated hour), **average slot utilisation** (busy slot-seconds ÷ capacity×makespan), **conflicts** (denied/timed-out allocation attempts), **lock-wait** (ms spent waiting for the Java lock and the DB row locks), **peak concurrency**.
Results depend on timing noise because the simulation runs in real threads: expect small run-to-run differences (in verification runs FCFS missed 4–7 of 18 deadlines, PRIORITY 1–2, EDF 0–1, PERISHABILITY 0–2). Do not quote one run as a benchmark – run the scenario several times and report the range.

## Schedule preview / revision (`SchedulePlanner`)
Forward list scheduling on the current database state: at each event time, ready tasks are ordered by the chosen policy and assigned if every requirement fits (SLOTS slot free-time, QUANTITY remaining capacity). Output: task→resource intervals, **conflicts** (more simultaneous uses than capacity; impossible by construction but also checked for hand-made plans), **deadline/expiry risks**, tasks that cannot be placed. Plans are stored per `schedule_revision`.
Simplification: storage is not released inside the plan.
