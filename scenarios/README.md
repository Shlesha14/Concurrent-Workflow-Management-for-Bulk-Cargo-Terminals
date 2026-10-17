# Demonstration scenarios A–H

Run from the GUI (**Simulation** screen) or `./scripts/run-scenarios.sh A` … `H` / `ALL`. **Every scenario resets cargo, tasks, allocations, history and resources.**
The clock runs at 3000× during scenarios. "Observed" lines are read back from the database after the run; PASS is computed, not scripted.

| ID | Setup | Expected behaviour | Observable result |
|---|---|---|---|
| A | One 100-unit cool room (1–3 °C), 1 crane, spinach (6 h shelf life, registered first), lettuce (3 h), ice cream, **no frozen store**. Run under FCFS then PERISHABILITY. | FCFS stores spinach first; PERISHABILITY stores lettuce first; ice cream is never put in the cool room → `BLOCKED NO_COMPATIBLE_RESOURCE` | Two STORE start timestamps per policy; blocked reason; denial count |
| B | Fragile-capable crane/forklift, 1 inspection station, protected store; laptops + phones | No STORE/DISPATCH starts before INSPECT completes; all 8 tasks complete | Timeline lines; "STORE started before INSPECT finished: 0" |
| C | 3000 t stockyard, conveyor, reclaimer; two coal lots (2500 t + 2000 t) | Lot 2 waits (ALLOCATION_DENIED) until lot 1 is reclaimed; yard never above 3000 t | Peak tonnes ≤ 3000, denials ≥ 1 |
| D | Crane(2), forklifts, inspection, cold room, trucks; tomatoes + 2 general lots | ≥ 2 tasks run simultaneously on different threads | Peak concurrency, distinct threads, overlapping start/end timestamps |
| E | 3 general lots, 1 forklift; the forklift fails after the first completion, later repaired | Dependants become `BLOCKED RESOURCE_UNAVAILABLE`, no leaked ACTIVE allocations, revision written; after repair all 9 tasks complete | Revision ids, blocked/recovered counts, 2 revisions kept |
| F | Add the dependency UNLOAD→DISPATCH (closes a loop) | Rejected as a cycle; nothing stored; the valid workflow completes | Rejection message with the cycle; dependency count unchanged |
| G | One crane, six lots with different priority/deadlines; run under all 4 policies | All runs complete 18/18 tasks; deadline misses / waiting differ by policy | Comparison table (also in `reports/` via CSV export) |
| H | Single crane slot, 10 threads allocate at the same instant with the Java lock disabled | Exactly 1 granted, 9 get `WAIT` with a message, 1 ACTIVE allocation in DB | Granted/refused counts, messages |

Manual variations in the GUI: register an already-expired item (rejected/quarantined with a reason); register a lot bigger than every store (blocked `CAPACITY_EXCEEDS_MAX`); set simulated excursion probability to 1 in Settings and run a frozen-food workflow to see temperature alerts (**simulated** readings).
