# PortFlow — Concurrent Workflow Management for Bulk Cargo Terminals

Team OSDBMS-V-2026-T064 · OS (TCS-502) + DBMS (TCS-503) · Graphic Era (Deemed to be) University

PortFlow is an **educational simulation** (no real equipment, no real sensors, no commercial port). It accepts cargo,
chooses a handling profile (fresh produce, frozen/chilled, electronics, coal, cement, general), builds a **DAG workflow**,
schedules compatible resources, runs independent tasks **concurrently** on a worker pool, survives failures, and stores
every state change in **MySQL** using transactions. Java 21 · Swing · JDBC · Maven · JUnit 5.

> Original approved title: *Concurrent Workflow Management for Bulk Cargo Terminals* (Phase I). The Phase II report uses the
> working title *PortFlow: An Integrated Planning System for Dry-Bulk Export Terminals*; see `report-support/PHASE_ALIGNMENT.md`.

## 1. Prerequisites and exact versions
| Component | Version used / required |
|---|---|
| JDK | **21 (LTS)** (built and tested with OpenJDK 21.0.x) |
| Maven | 3.9.x (pom targets `maven.compiler.release=21`) |
| MySQL | **8.0.16 or newer** (CHECK constraints must be enforced). MariaDB 10.5+ also works (used for the verification run) |
| JDBC driver | `mysql-connector-j 8.4.0` (declared in `pom.xml`, runtime scope — the only runtime dependency) |
| Tests | JUnit Jupiter 5.10.2, surefire 3.2.5 |
| OS | macOS, Linux, Windows (no OS-specific code) |

## 2. Create the database (macOS / Linux)
```bash
# install MySQL (macOS):   brew install mysql && brew services start mysql
mysql -u root -p -e "CREATE USER IF NOT EXISTS 'portflow'@'localhost' IDENTIFIED BY 'choose-a-password'; \
                     GRANT ALL ON portflow.* TO 'portflow'@'localhost'; GRANT ALL ON portflow_test.* TO 'portflow'@'localhost';"
./scripts/setup-db.sh root          # runs database/01_schema.sql, 02_triggers.sql, 03_views.sql, 04_seed.sql
```
Or skip this: the application offers to install the schema itself on first start (Settings → *Reset database* also does it).
`database/05_sample_queries.sql` and `06_transaction_demo.sql` are demonstration scripts (see §8).

## 3. Configure the connection
```bash
cp portflow.properties.template portflow.properties     # git-ignored; edit db.user / db.password
```
Never commit real passwords. Credentials are never shown in the UI (only the URL without query and the user name).

## 4. Compile / 5. Run / 6. Test
```bash
mvn clean package -DskipTests          # compiles; builds target/portflow-1.0.0.jar (shaded, includes the driver)
java -jar target/portflow-1.0.0.jar    # start the Swing application   (or: ./scripts/run-app.sh)

# tests need a scratch schema that is DROPPED/RECREATED – never point them at real data
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS portflow_test"
mvn test -Dportflow.test.url="jdbc:mysql://localhost:3306/portflow_test?useSSL=false&allowPublicKeyRetrieval=true" \
         -Dportflow.test.user=portflow -Dportflow.test.password=choose-a-password
```
Without a reachable MySQL the DB tests are **skipped, not passed** (JUnit shows them as aborted). `UiSmokeTest` needs a display and is skipped on headless machines.

## 7. Run the demonstration scenarios (A–H)
GUI: **Simulation → pick scenario → Run scenario** (or *Run all A–H*). Command line: `./scripts/run-scenarios.sh [A..H|ALL]`.
Each scenario resets the operational data, builds its own terminal/cargo, runs the real engine and prints *expected* vs *observed*
values taken from the database. Details and what to look at: `scenarios/README.md`.
Load/stress test: Simulation → *Run load test* or `./scripts/run-stress.sh cargo=100 cranes=2 forklifts=2 trucks=2 workers=8`
(CSV written to `reports/`).

## 8. Inspect the database and logs
* GUI: **Activity Log** (filter by task / cargo / resource / type / state / time, export CSV), **Live Monitor**, **Dashboard**.
* SQL: `mysql portflow < database/05_sample_queries.sql` (joins, GROUP BY, subqueries, views, EXPLAIN, audit, utilisation).
* Transactions/locking: `database/06_transaction_demo.sql` (two-session walkthrough of `SELECT … FOR UPDATE`, commit, rollback).

## 9. Project layout
```
database/    01_schema.sql 02_triggers.sql 03_views.sql 04_seed.sql 05_sample_queries.sql 06_transaction_demo.sql
src/main/java/com/portflow/{config,model,dao,service,workflow,scheduler,resource,engine,simulation,monitoring,ui,util}
src/test/java/com/portflow/        (JUnit tests + fixtures in TestSupport)
docs/        ARCHITECTURE, DATABASE_DESIGN, SCHEDULING, SYLLABUS_MAPPING, TESTING, VIVA_NOTES, CONTRIBUTION_PLAN, KNOWN_LIMITATIONS, TROUBLESHOOTING, diagrams/
scenarios/   README.md (A–H instructions)      report-support/  report + presentation material
scripts/     setup-db.sh run-app.sh run-tests.sh run-scenarios.sh run-stress.sh
```

## 10. What is and is not claimed
See `docs/KNOWN_LIMITATIONS.md` and `docs/TESTING.md` (which states exactly which tests were executed, on what, and what was **not** executed).
Cargo profiles are simplified simulation rules, not real regulations. Temperatures are **simulated** values, never sensor data.
