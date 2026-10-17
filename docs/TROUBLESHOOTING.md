# Troubleshooting
| Symptom | Cause / fix |
|---|---|
| Dialog "Cannot reach the MySQL server…" | MySQL not running (`brew services start mysql`), wrong `db.url` host/port in `portflow.properties`. |
| "MySQL rejected the user name or password" | Fix `db.user` / `db.password`; the user needs rights on the `portflow` schema. |
| "The PortFlow database does not exist" | `CREATE DATABASE portflow;` (or run `database/01_schema.sql`). |
| App offers to create tables on start | The schema is not installed yet – answer *Yes*. |
| `mysql: command not found` (macOS) | `brew install mysql` and add `/opt/homebrew/opt/mysql/bin` to `PATH`. |
| `Public Key Retrieval is not allowed` | Keep `allowPublicKeyRetrieval=true` in the URL (template has it) or use SSL. |
| `CHECK constraint … is violated` / `Check constraint` ignored | MySQL < 8.0.16 does not enforce CHECK. Upgrade. |
| DB tests show as *aborted* | No reachable MySQL for `portflow_test` – create it and pass `-Dportflow.test.*`. They are **not** passing in that case. |
| `UiSmokeTest` aborted | Headless machine; it needs a display. |
| Tasks stay `WAITING_FOR_RESOURCE` | Normal contention; check *Resources* (free capacity). They become `BLOCKED WAIT_TIMEOUT` after the profile max wait. |
| Task `BLOCKED NO_COMPATIBLE_RESOURCE` | No resource has the required type/capability/temperature range – add one (Resources) then **Retry**. |
| Everything is slow or too fast | Settings → *Simulation speed* (sim seconds per real second; 60 = 1 sim-minute per second). |
| "System is busy (no free database connection)" | Pool exhausted; raise `db.pool.size` (must exceed workers + UI threads). |
| Scenario/load test wiped my data | By design: they reset cargo, tasks, resources. Use a separate schema for experiments. |
| Maven cannot resolve plugins offline | Run once with network access. |
