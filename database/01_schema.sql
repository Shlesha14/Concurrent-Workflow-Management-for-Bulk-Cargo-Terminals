-- PortFlow schema. Works on MySQL 8.0.16+ and MariaDB 10.5+ (CHECK constraints are enforced).
-- Run:  mysql -u root -p < database/01_schema.sql   (then 02_triggers.sql, 03_views.sql, 04_seed.sql)
CREATE DATABASE IF NOT EXISTS portflow CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE portflow;

-- ---------- Reference / configuration tables (cargo profiles are DATA, not code) ----------
CREATE TABLE resource_type (
  type_code      VARCHAR(30)  PRIMARY KEY,
  description    VARCHAR(120) NOT NULL,
  -- SLOTS: capacity = number of tasks that may use the resource at once (1 slot per task).
  -- QUANTITY: capacity = cargo handling units (tonnes/pallets/crates) that may be stored at once.
  capacity_model ENUM('SLOTS','QUANTITY') NOT NULL
) ENGINE=InnoDB;

CREATE TABLE handling_profile (
  profile_code        VARCHAR(30)  PRIMARY KEY,
  display_name        VARCHAR(80)  NOT NULL,
  category            VARCHAR(30)  NOT NULL,
  requires_expiry     BOOLEAN      NOT NULL DEFAULT FALSE,
  temp_min_c          DECIMAL(5,1) NULL,
  temp_max_c          DECIMAL(5,1) NULL,
  max_wait_min        INT          NOT NULL,   -- max simulated minutes a task may wait for a resource
  expired_policy      ENUM('REJECT','QUARANTINE') NOT NULL DEFAULT 'REJECT',
  description         VARCHAR(255) NULL,
  simplification_note VARCHAR(255) NULL,
  CONSTRAINT ck_profile_wait CHECK (max_wait_min > 0),
  CONSTRAINT ck_profile_temp CHECK (temp_min_c IS NULL OR temp_max_c IS NULL OR temp_min_c <= temp_max_c)
) ENGINE=InnoDB;

CREATE TABLE profile_step (
  profile_code VARCHAR(30) NOT NULL,
  step_code    VARCHAR(30) NOT NULL,
  task_type    VARCHAR(20) NOT NULL,
  base_min     INT NOT NULL,                    -- simulated minutes
  per_unit_min DECIMAL(8,4) NOT NULL DEFAULT 0, -- extra minutes per cargo unit
  PRIMARY KEY (profile_code, step_code),
  CONSTRAINT fk_step_profile FOREIGN KEY (profile_code) REFERENCES handling_profile(profile_code),
  CONSTRAINT ck_step_dur CHECK (base_min > 0 AND per_unit_min >= 0)
) ENGINE=InnoDB;

CREATE TABLE profile_step_dep (
  profile_code    VARCHAR(30) NOT NULL,
  step_code       VARCHAR(30) NOT NULL,
  depends_on_step VARCHAR(30) NOT NULL,
  PRIMARY KEY (profile_code, step_code, depends_on_step),
  CONSTRAINT fk_psd_step FOREIGN KEY (profile_code, step_code)       REFERENCES profile_step(profile_code, step_code),
  CONSTRAINT fk_psd_dep  FOREIGN KEY (profile_code, depends_on_step) REFERENCES profile_step(profile_code, step_code),
  CONSTRAINT ck_psd_self CHECK (step_code <> depends_on_step)
) ENGINE=InnoDB;

CREATE TABLE profile_step_req (
  req_id          INT AUTO_INCREMENT PRIMARY KEY,
  profile_code    VARCHAR(30) NOT NULL,
  step_code       VARCHAR(30) NOT NULL,
  resource_type   VARCHAR(30) NOT NULL,
  capability      VARCHAR(30) NULL,               -- resource must list this capability
  amount_mode     ENUM('SLOT','CARGO_QTY') NOT NULL,
  hold_until_step VARCHAR(30) NULL,               -- allocation stays HELD until this step completes
  CONSTRAINT fk_psr_step FOREIGN KEY (profile_code, step_code) REFERENCES profile_step(profile_code, step_code),
  CONSTRAINT fk_psr_type FOREIGN KEY (resource_type) REFERENCES resource_type(type_code)
) ENGINE=InnoDB;

CREATE TABLE app_setting (
  setting_key   VARCHAR(60) PRIMARY KEY,
  setting_value VARCHAR(120) NOT NULL
) ENGINE=InnoDB;

-- ---------- Operational tables ----------
CREATE TABLE cargo (
  cargo_id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  name             VARCHAR(100) NOT NULL,
  profile_code     VARCHAR(30)  NOT NULL,
  quantity         DECIMAL(12,2) NOT NULL,
  unit             VARCHAR(10)  NOT NULL,
  arrival_time     DATETIME(3)  NOT NULL,
  manufactured_at  DATETIME(3)  NULL,
  expires_at       DATETIME(3)  NULL,
  priority         TINYINT      NOT NULL DEFAULT 3,
  deadline_at      DATETIME(3)  NULL,
  special_handling VARCHAR(255) NULL,
  extra_info       VARCHAR(255) NULL,
  status           ENUM('REGISTERED','IN_PROGRESS','COMPLETED','REJECTED','QUARANTINED') NOT NULL DEFAULT 'REGISTERED',
  status_reason    VARCHAR(255) NULL,
  created_at       DATETIME(3)  NOT NULL,
  CONSTRAINT fk_cargo_profile FOREIGN KEY (profile_code) REFERENCES handling_profile(profile_code),
  CONSTRAINT ck_cargo_qty      CHECK (quantity > 0),
  CONSTRAINT ck_cargo_priority CHECK (priority BETWEEN 1 AND 5),
  CONSTRAINT ck_cargo_expiry   CHECK (manufactured_at IS NULL OR expires_at IS NULL OR expires_at > manufactured_at),
  CONSTRAINT ck_cargo_deadline CHECK (deadline_at IS NULL OR deadline_at >= arrival_time),
  INDEX idx_cargo_expiry (expires_at),
  INDEX idx_cargo_status (status),
  INDEX idx_cargo_profile (profile_code)
) ENGINE=InnoDB;

CREATE TABLE resource (
  resource_id INT AUTO_INCREMENT PRIMARY KEY,
  name        VARCHAR(60)  NOT NULL UNIQUE,
  type_code   VARCHAR(30)  NOT NULL,
  capacity    DECIMAL(12,2) NOT NULL,
  status      ENUM('AVAILABLE','MAINTENANCE','UNAVAILABLE') NOT NULL DEFAULT 'AVAILABLE',
  location    VARCHAR(60)  NULL,
  temp_min_c  DECIMAL(5,1) NULL,
  temp_max_c  DECIMAL(5,1) NULL,
  CONSTRAINT fk_resource_type FOREIGN KEY (type_code) REFERENCES resource_type(type_code),
  CONSTRAINT ck_resource_cap  CHECK (capacity > 0),
  CONSTRAINT ck_resource_temp CHECK (temp_min_c IS NULL OR temp_max_c IS NULL OR temp_min_c <= temp_max_c),
  INDEX idx_resource_type_status (type_code, status)
) ENGINE=InnoDB;

CREATE TABLE resource_capability (
  resource_id INT NOT NULL,
  capability  VARCHAR(30) NOT NULL,
  PRIMARY KEY (resource_id, capability),
  CONSTRAINT fk_rc_resource FOREIGN KEY (resource_id) REFERENCES resource(resource_id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE workflow_instance (
  workflow_id  BIGINT AUTO_INCREMENT PRIMARY KEY,
  cargo_id     BIGINT NOT NULL,
  state        ENUM('CREATED','RUNNING','COMPLETED','BLOCKED','FAILED','CANCELLED') NOT NULL DEFAULT 'CREATED',
  created_at   DATETIME(3) NOT NULL,
  completed_at DATETIME(3) NULL,
  CONSTRAINT fk_wf_cargo FOREIGN KEY (cargo_id) REFERENCES cargo(cargo_id),
  UNIQUE KEY uq_wf_cargo (cargo_id)            -- one workflow instance per cargo item
) ENGINE=InnoDB;

CREATE TABLE task (
  task_id        BIGINT AUTO_INCREMENT PRIMARY KEY,
  workflow_id    BIGINT NOT NULL,
  cargo_id       BIGINT NOT NULL,
  step_code      VARCHAR(30) NOT NULL,
  task_type      VARCHAR(20) NOT NULL,
  priority       TINYINT NOT NULL,
  state          ENUM('CREATED','VALIDATED','READY','WAITING_FOR_RESOURCE','RUNNING','COMPLETED','FAILED','BLOCKED','CANCELLED','EXPIRED') NOT NULL DEFAULT 'CREATED',
  block_code     VARCHAR(30) NULL,
  block_reason   VARCHAR(255) NULL,
  created_at     DATETIME(3) NOT NULL,
  ready_at       DATETIME(3) NULL,
  deadline_at    DATETIME(3) NULL,
  duration_min   INT NOT NULL,
  started_at     DATETIME(3) NULL,
  completed_at   DATETIME(3) NULL,
  retry_count    INT NOT NULL DEFAULT 0,
  not_before     DATETIME(3) NULL,
  worker_thread  VARCHAR(60) NULL,
  deadline_missed BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT fk_task_wf    FOREIGN KEY (workflow_id) REFERENCES workflow_instance(workflow_id),
  CONSTRAINT fk_task_cargo FOREIGN KEY (cargo_id)    REFERENCES cargo(cargo_id),
  CONSTRAINT ck_task_prio  CHECK (priority BETWEEN 1 AND 5),
  CONSTRAINT ck_task_dur   CHECK (duration_min > 0),
  CONSTRAINT ck_task_times CHECK (completed_at IS NULL OR started_at IS NULL OR completed_at >= started_at),
  UNIQUE KEY uq_task_step (workflow_id, step_code),
  INDEX idx_task_state (state),
  INDEX idx_task_cargo (cargo_id)
) ENGINE=InnoDB;

CREATE TABLE task_dependency (
  task_id            BIGINT NOT NULL,
  depends_on_task_id BIGINT NOT NULL,
  PRIMARY KEY (task_id, depends_on_task_id),
  CONSTRAINT fk_td_task FOREIGN KEY (task_id)            REFERENCES task(task_id),
  CONSTRAINT fk_td_dep  FOREIGN KEY (depends_on_task_id) REFERENCES task(task_id),
  CONSTRAINT ck_td_self CHECK (task_id <> depends_on_task_id),
  INDEX idx_td_dep (depends_on_task_id)
) ENGINE=InnoDB;

-- Improves the notebook's TaskResource(task_id, resource_id, required_from/until, allocation_status):
-- a requirement says WHAT KIND of resource a task needs; the concrete resource and the time interval
-- are chosen at run time and recorded in `allocation`.
CREATE TABLE task_requirement (
  requirement_id  BIGINT AUTO_INCREMENT PRIMARY KEY,
  task_id         BIGINT NOT NULL,
  resource_type   VARCHAR(30) NOT NULL,
  capability      VARCHAR(30) NULL,
  amount          DECIMAL(12,2) NOT NULL,
  hold_until_step VARCHAR(30) NULL,
  CONSTRAINT fk_tr_task FOREIGN KEY (task_id)       REFERENCES task(task_id),
  CONSTRAINT fk_tr_type FOREIGN KEY (resource_type) REFERENCES resource_type(type_code),
  CONSTRAINT ck_tr_amt  CHECK (amount > 0),
  INDEX idx_tr_task (task_id)
) ENGINE=InnoDB;

CREATE TABLE schedule_revision (
  revision_id   BIGINT AUTO_INCREMENT PRIMARY KEY,
  created_at    DATETIME(3) NOT NULL,
  reason_code   VARCHAR(30) NOT NULL,     -- INITIAL_PLAN, RESOURCE_UNAVAILABLE, RESOURCE_RESTORED, MANUAL_PLAN, DEADLOCK_RECOVERY
  description   VARCHAR(255) NOT NULL,
  resource_id   INT NULL,
  policy_name   VARCHAR(40) NULL,
  affected_tasks INT NOT NULL DEFAULT 0,
  CONSTRAINT fk_rev_resource FOREIGN KEY (resource_id) REFERENCES resource(resource_id)
) ENGINE=InnoDB;

-- Notebook's Schedule/Allocation table. Rows are NEVER deleted: they move ACTIVE -> HELD -> RELEASED or REVOKED.
CREATE TABLE allocation (
  allocation_id  BIGINT AUTO_INCREMENT PRIMARY KEY,
  task_id        BIGINT NOT NULL,
  requirement_id BIGINT NOT NULL,
  resource_id    INT NOT NULL,
  amount         DECIMAL(12,2) NOT NULL,
  status         ENUM('ACTIVE','HELD','RELEASED','REVOKED') NOT NULL DEFAULT 'ACTIVE',
  start_time     DATETIME(3) NOT NULL,
  end_time       DATETIME(3) NULL,
  hold_until_step VARCHAR(30) NULL,
  note           VARCHAR(255) NULL,
  -- Non-NULL only while the allocation counts against capacity => UNIQUE forbids two live allocations for one requirement.
  live_key       BIGINT GENERATED ALWAYS AS (CASE WHEN status IN ('ACTIVE','HELD') THEN requirement_id ELSE NULL END) VIRTUAL,
  CONSTRAINT fk_al_task FOREIGN KEY (task_id)        REFERENCES task(task_id),
  CONSTRAINT fk_al_req  FOREIGN KEY (requirement_id) REFERENCES task_requirement(requirement_id),
  CONSTRAINT fk_al_res  FOREIGN KEY (resource_id)    REFERENCES resource(resource_id),
  CONSTRAINT ck_al_amt  CHECK (amount > 0),
  CONSTRAINT ck_al_time CHECK (end_time IS NULL OR end_time >= start_time),
  UNIQUE KEY uq_al_live (live_key),
  INDEX idx_al_resource_status (resource_id, status),
  INDEX idx_al_task (task_id)
) ENGINE=InnoDB;

CREATE TABLE plan_entry (
  plan_entry_id BIGINT AUTO_INCREMENT PRIMARY KEY,
  revision_id   BIGINT NOT NULL,
  task_id       BIGINT NOT NULL,
  resource_id   INT NOT NULL,
  planned_start DATETIME(3) NOT NULL,
  planned_end   DATETIME(3) NOT NULL,
  CONSTRAINT fk_pe_rev  FOREIGN KEY (revision_id) REFERENCES schedule_revision(revision_id),
  CONSTRAINT fk_pe_task FOREIGN KEY (task_id)     REFERENCES task(task_id),
  CONSTRAINT fk_pe_res  FOREIGN KEY (resource_id) REFERENCES resource(resource_id),
  CONSTRAINT ck_pe_time CHECK (planned_end > planned_start),
  INDEX idx_pe_rev (revision_id)
) ENGINE=InnoDB;

CREATE TABLE storage_reading (
  reading_id  BIGINT AUTO_INCREMENT PRIMARY KEY,
  resource_id INT NOT NULL,
  ts          DATETIME(3) NOT NULL,
  temp_c      DECIMAL(5,1) NOT NULL,
  excursion   BOOLEAN NOT NULL DEFAULT FALSE,
  is_simulated BOOLEAN NOT NULL DEFAULT TRUE,     -- always TRUE: there is no real sensor
  CONSTRAINT fk_sr_res FOREIGN KEY (resource_id) REFERENCES resource(resource_id),
  INDEX idx_sr_res_ts (resource_id, ts)
) ENGINE=InnoDB;

-- Notebook's Activity Log, extended with thread name, state transition and workflow id. Append-only.
CREATE TABLE event_log (
  event_id    BIGINT AUTO_INCREMENT PRIMARY KEY,
  ts          DATETIME(3) NOT NULL,            -- wall-clock time of the event
  sim_ts      DATETIME(3) NULL,                -- simulation-clock time
  event_type  VARCHAR(40) NOT NULL,
  task_id     BIGINT NULL,
  cargo_id    BIGINT NULL,
  resource_id INT NULL,
  workflow_id BIGINT NULL,
  thread_name VARCHAR(60) NULL,
  from_state  VARCHAR(30) NULL,
  to_state    VARCHAR(30) NULL,
  message     VARCHAR(400) NOT NULL,
  INDEX idx_ev_task (task_id),
  INDEX idx_ev_cargo (cargo_id),
  INDEX idx_ev_res (resource_id),
  INDEX idx_ev_type_ts (event_type, ts)
) ENGINE=InnoDB;
