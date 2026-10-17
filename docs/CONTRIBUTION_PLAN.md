# Four-member contribution plan
Derived from the pending-task table of your Phase II report (names as in that report). Adjust to what each person actually does; do not claim work that was not done.

| Member | Area (Phase II assignment) | Suggested ownership in this codebase | Own and be able to explain |
|---|---|---|---|
| Tulika Tyagi (lead) | Conflict detection and schedule revision after disruption; integration | `ResourceService`, `SchedulePlanner`, `DeadlockDetector`, `ExecutionEngine` (failure paths) | Scenario E, revision rules, deadlock section |
| Vertika Tyagi | Scheduling and allocation logic | `scheduler.*`, `ResourceSelector`, `ResourceManager`, `workflow.DagValidator` | Policies, score, best-fit/first-fit, Scenarios G/F |
| Shlesha Bisht | Relational schema, constraints, seed data, repository | `database/*.sql`, `dao.*`, `EventLog`, `Database` | ER diagram, normalisation, transactions, `05/06` SQL files |
| Rudrani Saxena | Dashboard and scheduling interface | `ui.*`, `ReportService`, `ReportDao` | Screens, SwingWorker/EDT rule, live refresh |
| All | Testing with normal and conflicting scenarios | `src/test`, `scenarios/` | Run `mvn test`, scenarios A–H, write the results section |

Suggested workflow: one GitHub branch per member, pull requests reviewed by another member, commit messages that name the feature, run the full test suite before merging.
