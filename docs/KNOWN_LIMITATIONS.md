# Known limitations and future work

**Verification limits (important)**
* `mvn` could **not** be run in the build environment (Maven Central unreachable). `pom.xml` was validated for well-formedness (`mvn -o validate`) only; compilation and tests were run with `javac` + the JUnit Console Launcher. **Run `mvn clean test` once on your laptop** and fix any plugin-version issue; this is the first thing to do.
* Tests ran on **MariaDB 10.11 + MariaDB Connector/J 2.7.6**, not on MySQL 8 + Connector/J 8.4. The SQL is standard (CHECK, generated column, triggers with SIGNAL, `FOR UPDATE`) but behaviour on MySQL 8 is **unverified**.
* No JAR is shipped (it needs Maven's shade plugin and the MySQL driver).
* The UI was exercised by an automated smoke test under a virtual display (every screen opened and refreshed with live data) – not by a human usability test. Dialog flows (edit cargo, add dependency, settings save) were not clicked through.

**Model limits**
* Simulation only. Temperatures are generated values (`is_simulated = TRUE`). No real sensors, equipment or regulations.
* No berth/vessel entities (the Phase II report mentions berth allocation – see `report-support/PHASE_ALIGNMENT.md`).
* Storage is "held" per workflow until a named step completes; there is no relocation of stored cargo when a store fails (hold is revoked and an alert is logged).
* Running tasks are never preempted, moved or cancelled (they drain) – a deliberate safety simplification.
* Deadlock *avoidance* (Banker's algorithm) is not implemented; detection models only storage holds.
* Cargo quantities of all units are treated as 1 handling unit per tonne/pallet/crate for storage capacity.
* Resource-failure injection is manual (button) or scripted (`FailureInjector`); no random failure generator beyond the optional temperature-excursion probability.
* No login / role model (out of scope; would not add to the OS/DBMS demonstration).
* Syllabus areas not implemented: memory management, virtual memory, file systems, disk scheduling, protection, shell programming; stored procedures; crash recovery (left to InnoDB); relational algebra division. See `SYLLABUS_MAPPING.md`.
* Timing: simulated time runs on real threads, so metrics vary slightly between runs.

**Future work** – berth/vessel model; Banker's-style avoidance; random failure scenarios; stored procedures for allocation; CI pipeline; charts in the Analytics screen; role-based login.
