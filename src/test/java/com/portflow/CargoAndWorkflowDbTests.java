package com.portflow;

import com.portflow.config.PortFlowContext;
import com.portflow.service.CargoService;
import com.portflow.service.ServiceException;
import com.portflow.util.Jdbc;
import org.junit.jupiter.api.*;

import java.sql.SQLException;
import java.util.List;

import static com.portflow.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class CargoAndWorkflowDbTests {
    PortFlowContext ctx;
    @BeforeEach void setUp() throws Exception { ctx = fresh(fastClock()); }
    @AfterEach void tearDown() { if (ctx != null) ctx.close(); }

    // Test 1
    @Test void validCargoRegistersAndGetsFiveTaskWorkflow() throws Exception {
        CargoService.Registration r = ctx.cargo.register(draft("Spinach", "LEAFY_VEG", 20, 4, ctx.now(), ctx.now().minusHours(6), ctx.now().plusHours(30), null));
        assertTrue(r.accepted(), r.message());
        var wf = ctx.workflows.createWorkflow(r.cargoId());
        assertEquals(5, wf.taskCount());
        assertEquals(5, count(ctx, "SELECT COUNT(*) FROM task WHERE cargo_id=?", r.cargoId()));
        assertEquals(4, count(ctx, "SELECT COUNT(*) FROM task_dependency"));
        assertEquals(5, count(ctx, "SELECT COUNT(*) FROM task WHERE state='VALIDATED'"));
        assertTrue(count(ctx, "SELECT COUNT(*) FROM event_log WHERE event_type='CARGO_REGISTERED'") == 1);
    }
    // Test 2
    @Test void invalidQuantityAndDatesAreNotStored() throws Exception {
        var r = ctx.cargo.register(draft("Bad", "LEAFY_VEG", -3, 3, ctx.now(), ctx.now(), ctx.now().minusHours(1), null));
        assertNull(r.cargoId()); assertFalse(r.issues().isEmpty());
        assertEquals(0, count(ctx, "SELECT COUNT(*) FROM cargo"));
    }
    // Test 3
    @Test void coalWithoutExpiryIsAccepted() throws Exception {
        assertTrue(ctx.cargo.register(draft("Steam coal", "COAL", 4000, 3, ctx.now(), null, null, null)).accepted());
    }
    // Test 4
    @Test void expiredCargoIsRejectedOrQuarantinedByProfilePolicy() throws Exception {
        var veg = ctx.cargo.register(draft("Old tomatoes", "TOMATO", 10, 3, ctx.now(), ctx.now().minusDays(9), ctx.now().minusDays(1), null));
        var ice = ctx.cargo.register(draft("Melted ice cream", "FROZEN_FOOD", 10, 3, ctx.now(), ctx.now().minusDays(9), ctx.now().minusDays(1), null));
        assertEquals("REJECTED", veg.status()); assertEquals("QUARANTINED", ice.status());
        assertThrows(ServiceException.class, () -> ctx.workflows.createWorkflow(veg.cargoId()), "expired cargo must not get a workflow");
        assertEquals(0, count(ctx, "SELECT COUNT(*) FROM task"));
        assertTrue(count(ctx, "SELECT COUNT(*) FROM event_log WHERE event_type IN ('CARGO_REJECTED','CARGO_QUARANTINED')") == 2);
    }
    // DB-level CHECK constraints are a second line of defence
    @Test void databaseRejectsNegativeQuantityEvenIfAppIsBypassed() {
        assertThrows(SQLException.class, () -> sql(ctx, "INSERT INTO cargo (name, profile_code, quantity, unit, arrival_time, created_at) VALUES ('x','GENERAL',-1,'TONNE',NOW(),NOW())"));
    }
    // Test 5
    @Test void unknownDependencyIsRejected() throws Exception {
        profile(ctx, "TWO", 600, new String[]{"A", "UNLOAD", "10", "", ""}, new String[]{"B", "STORE", "10", "A", ""});
        long c = cargoWithWorkflow(ctx, "x", "TWO", 1, 3, null);
        long a = taskId(c, "A");
        assertThrows(ServiceException.class, () -> ctx.workflows.addDependency(a, 987654));
    }
    // Test 6
    @Test void cycleIsRejectedBeforeExecution() throws Exception {
        profile(ctx, "TWO", 600, new String[]{"A", "UNLOAD", "10", "", ""}, new String[]{"B", "STORE", "10", "A", ""});
        long c = cargoWithWorkflow(ctx, "x", "TWO", 1, 3, null);
        ServiceException e = assertThrows(ServiceException.class, () -> ctx.workflows.addDependency(taskId(c, "A"), taskId(c, "B")));
        assertTrue(e.details().toString().contains("cycle"), e.details().toString());
        assertEquals(1, count(ctx, "SELECT COUNT(*) FROM task_dependency"), "rejected edge must not be stored");
    }
    @Test void cycleInjectedBehindTheApplicationIsBlockedByEngineAndNeverWaitsForever() throws Exception {
        profile(ctx, "TWO", 600, new String[]{"A", "UNLOAD", "10", "", "CRANE"}, new String[]{"B", "STORE", "10", "A", "FORKLIFT"});
        res(ctx, "CR", "CRANE", 1); res(ctx, "FK", "FORKLIFT", 1);
        long c = cargoWithWorkflow(ctx, "x", "TWO", 1, 3, null);
        sql(ctx, "INSERT INTO task_dependency VALUES (?,?)", taskId(c, "A"), taskId(c, "B"));   // raw SQL: A <-> B cycle
        ctx.engine.start();
        assertTrue(ctx.engine.awaitIdle(5000), "engine must settle, not wait forever");
        assertEquals("BLOCKED", stateOf(ctx, c, "A")); assertEquals("BLOCKED", stateOf(ctx, c, "B"));
        final long aId = taskId(c, "A");
        assertEquals("INVALID_WORKFLOW", ctx.db.read(cn -> Jdbc.one(cn, "SELECT block_code FROM task WHERE task_id=?", rs -> rs.getString(1), aId).get()));
        assertEquals(0, count(ctx, "SELECT COUNT(*) FROM allocation"));
    }
    // Test 7
    @Test void databaseTriggerRefusesToStartTaskWithUnfinishedPrerequisite() throws Exception {
        profile(ctx, "TWO", 600, new String[]{"A", "UNLOAD", "10", "", ""}, new String[]{"B", "STORE", "10", "A", ""});
        long c = cargoWithWorkflow(ctx, "x", "TWO", 1, 3, null);
        long b = taskId(c, "B");
        SQLException e = assertThrows(SQLException.class, () -> sql(ctx, "UPDATE task SET state='RUNNING' WHERE task_id=?", b));
        assertTrue(e.getMessage().contains("Prerequisite"), e.getMessage());
        assertThrows(SQLException.class, () -> sql(ctx, "UPDATE task SET state='COMPLETED' WHERE task_id=?", b));
    }
    // Test 23
    @Test void emptyDatabaseDoesNotBreakReportsEngineOrMetrics() throws Exception {
        var d = ctx.reports.dashboard();
        assertEquals(0, d.cargoTotal()); assertEquals(0, d.running()); assertEquals(0, d.alerts());
        assertTrue(ctx.reports.tasks().rows().isEmpty()); assertTrue(ctx.reports.cargo("").rows().isEmpty());
        ctx.engine.start(); assertTrue(ctx.engine.awaitIdle(2000));
        assertEquals(0, ctx.metrics.compute("empty", 0).tasks());
    }
    @Test void editingBlockedAfterWorkflowExists() throws Exception {
        long c = cargoWithWorkflow(ctx, "Pallets", "GENERAL", 10, 3, null);
        var g = ctx.cargo.find(c).get();
        var r = ctx.cargo.update(new com.portflow.model.Cargo(c, "Renamed", g.profileCode(), g.quantity(), g.unit(), g.arrivalTime(), null, null, 3, null, null, null, g.status(), null, g.createdAt()));
        assertNull(r.cargoId());
    }

    long taskId(long cargoId, String step) throws Exception {
        return ctx.db.read(cn -> Jdbc.one(cn, "SELECT task_id FROM task WHERE cargo_id=? AND step_code=?", rs -> rs.getLong(1), cargoId, step).orElseThrow());
    }
}
