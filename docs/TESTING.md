# Testing — what was actually run

## Environment of the verification run (honest statement)
* JDK OpenJDK 21, **compiled with `javac`** (Maven Central was unreachable, so `mvn` could not build).
* Tests executed with the **JUnit Console Launcher** (platform 1.9.x) against **MariaDB 10.11** using **MariaDB Connector/J 2.7.6**.
* **Not executed:** `mvn test`; any run on MySQL 8 / Connector/J 8.4; Windows/macOS runs.
* Result of the last full run on the **final code** in that environment: **65 tests found, 65 passed, 0 failed, 0 aborted** (≈71 s). Scenarios F and H were also run through the command-line runner on a database installed with the manual `mysql < database/*.sql` path.
  Fix history that tests drove: a state-conflict race in `ResourceService` (engine vs operator), dependants of BLOCKED tasks waiting forever, an unfair urgency score (wait limit hid shelf-life differences).

## Run commands
```bash
mvn test -Dportflow.test.url="jdbc:mysql://localhost:3306/portflow_test?useSSL=false&allowPublicKeyRetrieval=true" -Dportflow.test.user=... -Dportflow.test.password=...
mvn test -Dtest=PureLogicTests                      # no database needed (23 tests)
```
Fixtures: `TestSupport` (fast clock: 1 simulated minute ≈ 10–50 ms; throw-away `TEST` profiles; resource helpers). Deterministic inputs everywhere; only timing-derived metrics vary.

## Required scenarios → tests
| # | Scenario | Test(s) |
|---|---|---|
| 1 | Valid cargo registration | `CargoAndWorkflowDbTests.validCargoRegistersAndGetsFiveTaskWorkflow` |
| 2 | Invalid dates/quantities | `…invalidQuantityAndDatesAreNotStored`, `PureLogicTests.invalidQuantityAndDatesAreReportedAsInvalid`, `databaseRejectsNegativeQuantityEvenIfAppIsBypassed` |
| 3 | No expiry where optional | `coalWithoutExpiryIsAccepted`, `PureLogicTests.coalNeedsNoExpiry` |
| 4 | Already expired | `expiredCargoIsRejectedOrQuarantinedByProfilePolicy` |
| 5 | Invalid dependency | `unknownDependencyIsRejected`, `PureLogicTests.unknownDependencyRejected` |
| 6 | Cycle | `cycleIsRejectedBeforeExecution`, `cycleInjectedBehindTheApplication…`, `PureLogicTests.cycleRejected`, scenario F |
| 7 | Incomplete prerequisite | `databaseTriggerRefusesToStartTaskWithUnfinishedPrerequisite`, `failedPrerequisiteBlocksDependant…` (B starts after A) |
| 8 | Concurrent independent tasks | `EngineDbTests.twoIndependentTasksReallyRunAtTheSameTime` (overlapping DB timestamps, 2 threads), scenario D |
| 9 | Competing for one exclusive resource | `twoTasksNeverShareOneExclusiveResource` |
| 10 | Shared limited capacity | `sharedResourceNeverExceedsItsCapacity` |
| 11 | Incompatible storage | `PureLogicTests.frozenRoomIsIncompatible…`, `roomWiderThanProfileRangeIsRejected`, scenario A |
| 12 | Insufficient capacity | `insufficientStorageIsBlockedWithClearReason`, `storageContentionWaitsThenSucceeds…`, scenario C |
| 13 | Resource unavailable mid-workflow | `resourceFailureBlocksDependantsThenRecovers…`, scenario E |
| 14 | Failure releases resources | `taskFailureReleasesItsResources` |
| 15 | Retry after temporary failure | `transientFailureIsRetriedAutomaticallyAndSucceeds` |
| 16 | Permanently blocked reported | `impossibleTaskIsReportedAndLongWaitsTimeOut`, `maximumWaitingTimeIsEnforced` |
| 17 | Failed prerequisite | `failedPrerequisiteBlocksDependantAndOperatorRetryRecoversIt` |
| 18 | Revision keeps history | `resourceFailureBlocksDependants…` (2 revisions + plan entries), `failedStorageRevokesHoldButKeepsAllocationHistory` |
| 19 | Concurrent reservations | `ConcurrencyAndTransactionDbTests.concurrentReservations…` (DB-lock-only and Java-lock variants), scenario H |
| 20 | Rollback after injected failure | `transactionRollsBackCompletelyWhenFailureIsInjectedBeforeCommit` |
| 21 | Deadline / max wait | `missedDeadlineIsFlaggedAndLogged`, `maximumWaitingTimeIsEnforced`, `cargoExpiringWhileWaitingIsExpiredNotProcessed` |
| 22 | Policy comparison, identical input | `policiesCompareOnIdenticalInputsAndEdfBeatsFcfsOnDeadlines`, `PureLogicTests.policiesOrderTheSameInputDifferently`, scenario G |
| 23 | Empty database | `emptyDatabaseDoesNotBreakReportsEngineOrMetrics` |
| 24 | DB connection failure | `PureLogicTests.unreachableDatabaseGivesFriendlyMessage`; the engine survives DB errors (`lastError`, back-off) – **not** covered by an automated test of an outage in mid-run |
| – | Deadlock detection/recovery | `circularWaitBetweenWorkflowsIsDetectedAndResolvedByVictim` |
| – | Stress / load | `ScenarioAndStressDbTests.stressRunCompletesEverythingWithoutOverAllocation` (20 cargo, 60 tasks) and `StressTest` CLI |
| – | UI | `ui.UiSmokeTest` (all 9 screens open and refresh on live data; needs a display) |

## Load test
`./scripts/run-stress.sh cargo=100 cranes=2 forklifts=2 trucks=2 workers=8 policy=PERISHABILITY speed=6000` prints and saves `reports/stress_<ts>.csv`
(columns: tasks, completed, failed, blocked, deadline_misses, avg_wait_min, makespan_min, throughput_per_sim_hour, avg_slot_utilization_pct, conflicts, lock_wait_ms, peak_concurrency).
No benchmark numbers are quoted in this repository because none were collected for your hardware; generate your own and describe the machine in the report.
