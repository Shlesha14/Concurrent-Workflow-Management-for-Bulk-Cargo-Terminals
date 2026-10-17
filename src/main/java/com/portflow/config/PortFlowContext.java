package com.portflow.config;

import com.portflow.dao.EventLog;
import com.portflow.engine.ExecutionEngine;
import com.portflow.engine.FailureInjector;
import com.portflow.monitoring.MetricsService;
import com.portflow.resource.ResourceManager;
import com.portflow.scheduler.PolicyRegistry;
import com.portflow.scheduler.SchedulingPolicy;
import com.portflow.service.*;
import com.portflow.simulation.SimClock;
import com.portflow.util.Database;

import java.sql.SQLException;
import java.time.LocalDateTime;

/** Composition root: builds and wires every component once. The UI and tests receive this object instead of using globals. */
public final class PortFlowContext implements AutoCloseable {
    public final Database db;
    public final SimClock clock;
    public final EventLog log;
    public final ResourceManager resourceManager;
    public final CargoService cargo;
    public final WorkflowService workflows;
    public final ResourceService resources;
    public final TaskAdminService admin;
    public final ReportService reports;
    public final MetricsService metrics;
    public final FailureInjector injector = new FailureInjector();
    public final ExecutionEngine engine;
    private volatile SchedulingPolicy policy = PolicyRegistry.get("PERISHABILITY");

    public PortFlowContext(AppConfig cfg, SimClock clock) {
        this.db = new Database(cfg);
        this.clock = clock;
        this.log = new EventLog(clock);
        this.resourceManager = new ResourceManager(db, clock, log);
        this.cargo = new CargoService(db, clock, log);
        this.workflows = new WorkflowService(db, clock, log, resourceManager);
        this.resources = new ResourceService(db, clock, log, () -> policy);
        this.admin = new TaskAdminService(db, clock, log, resourceManager);
        this.reports = new ReportService(db, clock);
        this.metrics = new MetricsService(db, resourceManager);
        this.engine = new ExecutionEngine(db, clock, log, resourceManager, workflows, injector, policy);
    }

    public SchedulingPolicy policy() { return policy; }
    public void setPolicy(String name) { policy = PolicyRegistry.get(name); engine.setPolicy(policy); }
    public Settings settings() throws SQLException { return db.read(Settings::load); }
    public LocalDateTime now() { return clock.now(); }

    @Override public void close() { engine.stop(); db.close(); }
}
