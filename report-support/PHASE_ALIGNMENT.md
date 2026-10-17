# Alignment with your Phase I and Phase II documents

## Contradictions found (and how they were resolved)
| Topic | Phase I proposal | Phase II progress report | This project |
|---|---|---|---|
| Title | *Concurrent Workflow Management for Bulk Cargo Terminals* | *PortFlow: An Integrated Planning System for Dry-Bulk Export Terminals* | Kept **Phase I title** for official material (as instructed). Ask your supervisor which title goes on the final report. |
| Core idea | OS concurrency (threads, mutex, DAG, deadlock) tied to DB transactions | Berth allocation + stockyard storage + reclaimer scheduling for vessels | Built the Phase I core, extended to multi-cargo. The coal profile covers stockyard + reclaimer. **No berth/vessel model.** |
| Equipment failure | Excluded ("deterministic, no failures") | Disruption handling and schedule revision required | The new master prompt requires failures → implemented (failure, blocking, revision, history). Update the report's *Assumptions* section accordingly. |
| Lock manager | "Custom locking logic with priority wait queues" | – | Implemented with `ReentrantLock(tryLock)` + MySQL row locks + policy-ordered dispatch, not a hand-written lock manager. Explain this honestly in the viva. |
| Scheduler | – | "simplified heuristic" | Four policies + preview planner. |
| Progress | – | "Implementation pending"; testing table all *pending* | Everything is now implemented; the testing table can be filled only from your own runs (see `docs/TESTING.md`). |

Phase II deliverables → where they are: operations dashboard (Dashboard), scheduling module (`scheduler`, Scheduler screen), conflict checker (`SchedulePlanner.detectConflicts`, allocation constraints), schedule revision and history (`ResourceService`, `schedule_revision`, `plan_entry`), documentation (`docs/`).
Phase II tests (berth conflict, reclaimer overlap, eligibility, stockyard capacity, revision, history) map to: exclusive-resource test, capability/compatibility tests, capacity tests, revision tests (see `docs/TESTING.md`); berth-specific tests do not exist because berths are not modelled.

Phase II also lists the repository `Concurrent-Workflow-Management-for-Bulk-Cargo-Terminals` – push this project there.
