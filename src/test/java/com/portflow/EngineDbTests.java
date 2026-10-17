package com.portflow;

import com.portflow.config.PortFlowContext;
import com.portflow.engine.FailureInjector;
import com.portflow.model.Cargo;
import com.portflow.monitoring.MetricsService.RunMetrics;
import com.portflow.scheduler.PolicyRegistry;
import com.portflow.simulation.PolicyComparator;
import com.portflow.simulation.SimClock;
import com.portflow.util.Jdbc;
import org.junit.jupiter.api.*;

import java.util.List;

import static com.portflow.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** End-to-end tests of the concurrent execution engine against a real MySQL-compatible database. */
class EngineDbTests {
    PortFlowContext ctx;
    @BeforeEach void setUp() throws Exception { ctx = fresh(fastClock()); }
    @AfterEach void tearDown() { if (ctx != null) ctx.close(); }

    long tid(long cargoId, String step) throws Exception { return ctx.db.read(c -> Jdbc.one(c, "SELECT task_id FROM task WHERE cargo_id=? AND step_code=?", rs -> rs.getLong(1), cargoId, step).orElseThrow()); }
    void runToIdle() throws Exception { ctx.engine.start(); assertTrue(ctx.engine.awaitIdle(30000), "engine did not become idle"); }
    String col(String sql, Object... p) throws Exception { return ctx.db.read(c -> Jdbc.one(c, sql, rs -> rs.getString(1), p).orElse(null)); }

    // Test 8
    @Test void twoIndependentTasksReallyRunAtTheSameTime() throws Exception {
        profile(ctx, "PAR", 600, new String[]{"A", "UNLOAD", "40", "", "CRANE"}, new String[]{"B", "PREPARE", "40", "", "FORKLIFT"});
        res(ctx, "CR", "CRANE", 1); res(ctx, "FK", "FORKLIFT", 1);
        long c = cargoWithWorkflow(ctx, "x", "PAR", 1, 3, null);
        runToIdle();
        assertEquals("COMPLETED", stateOf(ctx, c, "A")); assertEquals("COMPLETED", stateOf(ctx, c, "B"));
        assertTrue(ctx.engine.maxConcurrentObserved() >= 2, "peak concurrency " + ctx.engine.maxConcurrentObserved());
        assertEquals(1, count(ctx, "SELECT COUNT(*) FROM task a JOIN task b ON a.step_code='A' AND b.step_code='B' AND a.cargo_id=b.cargo_id WHERE a.started_at < b.completed_at AND b.started_at < a.completed_at"), "intervals overlap");
        assertEquals(2, count(ctx, "SELECT COUNT(DISTINCT worker_thread) FROM task"), "two different worker threads");
        assertTrue(count(ctx, "SELECT COUNT(*) FROM task WHERE worker_thread LIKE 'pf-worker-%'") == 2);
    }
    // Test 9
    @Test void twoTasksNeverShareOneExclusiveResource() throws Exception {
        profile(ctx, "ONE", 600, new String[]{"A", "UNLOAD", "30", "", "CRANE"});
        res(ctx, "CR", "CRANE", 1);
        for (int i = 0; i < 3; i++) cargoWithWorkflow(ctx, "c" + i, "ONE", 1, 3, null);
        runToIdle();
        assertEquals(3, count(ctx, "SELECT COUNT(*) FROM task WHERE state='COMPLETED'"));
        assertEquals(0, count(ctx, "SELECT COUNT(*) FROM allocation a JOIN allocation b ON a.resource_id=b.resource_id AND a.allocation_id<b.allocation_id WHERE a.start_time<b.end_time AND b.start_time<a.end_time"), "no overlapping use of the exclusive crane");
    }
    // Test 10
    @Test void sharedResourceNeverExceedsItsCapacity() throws Exception {
        profile(ctx, "ONE", 600, new String[]{"A", "UNLOAD", "30", "", "CRANE"});
        res(ctx, "CR", "CRANE", 2);
        for (int i = 0; i < 6; i++) cargoWithWorkflow(ctx, "c" + i, "ONE", 1, 3, null);
        runToIdle();
        int maxOverlap = count(ctx, "SELECT MAX(n) FROM (SELECT (SELECT COUNT(*) FROM allocation b WHERE b.resource_id=a.resource_id AND b.start_time<=a.start_time AND b.end_time>a.start_time) AS n FROM allocation a) x");
        assertEquals(2, maxOverlap, "capacity 2 is used fully but never exceeded (max simultaneous allocations at any start instant)");
        assertEquals(6, count(ctx, "SELECT COUNT(*) FROM task WHERE state='COMPLETED'"));
    }
    // Test 12
    @Test void insufficientStorageIsBlockedWithClearReason() throws Exception {
        profile(ctx, "STO", 600, new String[]{"S", "STORE", "10", "", "DRY_STORE"});
        res(ctx, "DRY", "DRY_STORE", 100);
        var reg = ctx.cargo.register(draft("Huge", "STO", 500, 3, ctx.now(), null, null, null));
        var created = ctx.workflows.createWorkflow(reg.cargoId());
        assertFalse(created.warnings().isEmpty(), "operator is warned at creation");
        runToIdle();
        assertEquals("BLOCKED", stateOf(ctx, reg.cargoId(), "S"));
        assertEquals("CAPACITY_EXCEEDS_MAX", col("SELECT block_code FROM task WHERE cargo_id=?", reg.cargoId()));
        assertEquals(0, count(ctx, "SELECT COUNT(*) FROM allocation"));
    }
    @Test void storageContentionWaitsThenSucceedsWhenSpaceIsReleased() throws Exception {
        profile(ctx, "STO2", 600, new String[]{"S", "STORE", "20", "", "DRY_STORE::D"}, new String[]{"D", "DISPATCH", "10", "S", ""});
        res(ctx, "DRY", "DRY_STORE", 100);
        for (int i = 0; i < 2; i++) assertTrue(ctx.cargo.register(draft("p" + i, "STO2", 70, 3, ctx.now(), null, null, null)).accepted());
        for (var g : ctx.cargo.search("")) ctx.workflows.createWorkflow(g.id());
        runToIdle();
        assertEquals(4, count(ctx, "SELECT COUNT(*) FROM task WHERE state='COMPLETED'"), "second pallet stored after first was dispatched");
        assertTrue(count(ctx, "SELECT COUNT(*) FROM event_log WHERE event_type='ALLOCATION_DENIED'") >= 1);
        assertEquals(0, count(ctx, "SELECT COUNT(*) FROM allocation WHERE status IN ('ACTIVE','HELD')"), "everything released at the end");
    }
    // Test 13 + 18
    @Test void resourceFailureBlocksDependantsThenRecoversAndHistoryIsKept() throws Exception {
        profile(ctx, "TWO", 600, new String[]{"A", "UNLOAD", "20", "", "CRANE"}, new String[]{"B", "STORE", "20", "A", "FORKLIFT"});
        res(ctx, "CR", "CRANE", 1); int fk = res(ctx, "FK", "FORKLIFT", 1);
        long c = cargoWithWorkflow(ctx, "x", "TWO", 1, 3, null);
        var ch = ctx.resources.setStatus(fk, "UNAVAILABLE", "simulated breakdown");
        assertEquals(1, ch.blockedTasks());
        assertEquals("BLOCKED", stateOf(ctx, c, "B"));
        runToIdle();
        assertEquals("COMPLETED", stateOf(ctx, c, "A"));
        assertEquals("BLOCKED", stateOf(ctx, c, "B"));
        assertEquals("RESOURCE_UNAVAILABLE", col("SELECT block_code FROM task WHERE cargo_id=? AND step_code='B'", c));
        var rec = ctx.resources.setStatus(fk, "AVAILABLE", "repaired");
        assertEquals(1, rec.recoveredTasks());
        runToIdle();
        assertEquals("COMPLETED", stateOf(ctx, c, "B"));
        assertEquals(2, count(ctx, "SELECT COUNT(*) FROM schedule_revision WHERE reason_code IN ('RESOURCE_UNAVAILABLE','RESOURCE_RESTORED')"), "both revisions kept");
        assertTrue(count(ctx, "SELECT COUNT(*) FROM plan_entry") > 0);
        assertEquals(2, count(ctx, "SELECT COUNT(*) FROM event_log WHERE event_type='RESOURCE_STATUS_CHANGED'"), "DB trigger audited both changes");
        assertEquals(0, count(ctx, "SELECT COUNT(*) FROM allocation WHERE status IN ('ACTIVE','HELD')"));
    }
    @Test void failedStorageRevokesHoldButKeepsAllocationHistory() throws Exception {
        profile(ctx, "STO3", 600, new String[]{"S", "STORE", "10", "", "DRY_STORE::D"}, new String[]{"D", "DISPATCH", "10", "S", "TRUCK"});
        int dry = res(ctx, "DRY", "DRY_STORE", 100);
        long c = cargoWithWorkflow(ctx, "x", "STO3", 50, 3, null);        // no truck yet: D will block, S stays held
        runToIdle();
        assertEquals(1, count(ctx, "SELECT COUNT(*) FROM allocation WHERE status='HELD'"));
        var ch = ctx.resources.setStatus(dry, "UNAVAILABLE", "cold-room failure");
        assertEquals(1, ch.revokedHolds());
        assertEquals(1, count(ctx, "SELECT COUNT(*) FROM allocation WHERE status='REVOKED'"), "row kept, status changed");
    }
    // Test 14
    @Test void taskFailureReleasesItsResources() throws Exception {
        profile(ctx, "ONE", 600, new String[]{"A", "UNLOAD", "20", "", "CRANE"});
        res(ctx, "CR", "CRANE", 1);
        long c = cargoWithWorkflow(ctx, "x", "ONE", 1, 3, null);
        ctx.injector.failTask(tid(c, "A"), FailureInjector.PERMANENT);
        runToIdle();
        assertEquals("FAILED", stateOf(ctx, c, "A"));
        assertEquals("RETRIES_EXHAUSTED", col("SELECT block_code FROM task WHERE cargo_id=?", c));
        assertEquals(0, count(ctx, "SELECT COUNT(*) FROM allocation WHERE status IN ('ACTIVE','HELD')"));
        assertEquals(1, count(ctx, "SELECT COUNT(*) FROM allocation WHERE status='RELEASED'"));
        assertTrue(count(ctx, "SELECT COUNT(*) FROM event_log WHERE event_type='TASK_FAILED'") >= 1);
    }
    // Test 15
    @Test void transientFailureIsRetriedAutomaticallyAndSucceeds() throws Exception {
        profile(ctx, "ONE", 600, new String[]{"A", "UNLOAD", "20", "", "CRANE"});
        res(ctx, "CR", "CRANE", 1);
        long c = cargoWithWorkflow(ctx, "x", "ONE", 1, 3, null);
        ctx.injector.failTask(tid(c, "A"), 1);
        runToIdle();
        assertEquals("COMPLETED", stateOf(ctx, c, "A"));
        assertEquals("1", col("SELECT retry_count FROM task WHERE cargo_id=?", c));
        assertEquals(1, count(ctx, "SELECT COUNT(*) FROM event_log WHERE event_type='TASK_RETRY_SCHEDULED'"));
        assertEquals("COMPLETED", col("SELECT status FROM cargo WHERE cargo_id=?", c));
    }
    // Test 16
    @Test void impossibleTaskIsReportedAndLongWaitsTimeOut() throws Exception {
        profile(ctx, "SPECIAL", 600, new String[]{"A", "UNLOAD", "10", "", "CRANE:HEAVY_LIFT"});
        res(ctx, "CR", "CRANE", 1);
        long c = cargoWithWorkflow(ctx, "x", "SPECIAL", 1, 3, null);
        long t0 = System.currentTimeMillis();
        runToIdle();
        assertTrue(System.currentTimeMillis() - t0 < 5000, "reported quickly, not waited on");
        assertEquals("NO_COMPATIBLE_RESOURCE", col("SELECT block_code FROM task WHERE cargo_id=?", c));
    }
    @Test void maximumWaitingTimeIsEnforced() throws Exception {
        profile(ctx, "LONG", 30, new String[]{"A", "UNLOAD", "300", "", "CRANE"});            // runs 300 min; others may wait only 30 min
        res(ctx, "CR", "CRANE", 1);
        long a = cargoWithWorkflow(ctx, "first", "LONG", 1, 3, null);
        long b = cargoWithWorkflow(ctx, "second", "LONG", 1, 3, null);
        runToIdle();
        assertEquals("COMPLETED", stateOf(ctx, a, "A"));
        assertEquals("BLOCKED", stateOf(ctx, b, "A"));
        assertEquals("WAIT_TIMEOUT", col("SELECT block_code FROM task WHERE cargo_id=?", b));
    }
    // Test 17
    @Test void failedPrerequisiteBlocksDependantAndOperatorRetryRecoversIt() throws Exception {
        profile(ctx, "TWO", 600, new String[]{"A", "UNLOAD", "20", "", "CRANE"}, new String[]{"B", "STORE", "20", "A", "FORKLIFT"});
        res(ctx, "CR", "CRANE", 1); res(ctx, "FK", "FORKLIFT", 1);
        long c = cargoWithWorkflow(ctx, "x", "TWO", 1, 3, null);
        long a = tid(c, "A");
        ctx.injector.failTask(a, FailureInjector.PERMANENT);
        runToIdle();
        assertEquals("FAILED", stateOf(ctx, c, "A"));
        assertEquals("BLOCKED", stateOf(ctx, c, "B"));
        assertEquals("PREREQUISITE_FAILED", col("SELECT block_code FROM task WHERE cargo_id=? AND step_code='B'", c));
        assertNotEquals("COMPLETED", stateOf(ctx, c, "B"));
        ctx.injector.failTask(a, 0);
        ctx.admin.retry(a);
        runToIdle();
        assertEquals("COMPLETED", stateOf(ctx, c, "A")); assertEquals("COMPLETED", stateOf(ctx, c, "B"));
        assertEquals(1, count(ctx, "SELECT COUNT(*) FROM task a JOIN task b ON a.step_code='A' AND b.step_code='B' AND a.cargo_id=b.cargo_id WHERE b.started_at >= a.completed_at"), "B started after A completed");
    }
    // Test 21
    @Test void missedDeadlineIsFlaggedAndLogged() throws Exception {
        profile(ctx, "ONE", 600, new String[]{"A", "UNLOAD", "60", "", "CRANE"});
        res(ctx, "CR", "CRANE", 1);
        long c = cargoWithWorkflow(ctx, "x", "ONE", 1, 3, ctx.now().plusMinutes(20));          // 60 min task, 20 min deadline
        runToIdle();
        assertEquals("COMPLETED", stateOf(ctx, c, "A"));
        assertEquals("1", col("SELECT deadline_missed+0 FROM task WHERE cargo_id=?", c));
        assertTrue(count(ctx, "SELECT COUNT(*) FROM event_log WHERE event_type='TASK_DEADLINE_MISSED'") >= 1);
        assertEquals(1, ctx.metrics.compute("m", 0).deadlineMisses());
    }
    @Test void cargoExpiringWhileWaitingIsExpiredNotProcessed() throws Exception {
        profile(ctx, "LONG", 600, new String[]{"A", "UNLOAD", "300", "", "CRANE"});
        res(ctx, "CR", "CRANE", 1);
        ctx.setPolicy("FCFS");                      // (the perishability policy would correctly serve the expiring cargo first)
        long a = cargoWithWorkflow(ctx, "first", "LONG", 1, 3, null);
        var reg = ctx.cargo.register(draft("perishable", "LONG", 1, 3, ctx.now(), ctx.now().minusHours(1), ctx.now().plusMinutes(60), null));
        ctx.workflows.createWorkflow(reg.cargoId());
        runToIdle();
        assertEquals("EXPIRED", stateOf(ctx, reg.cargoId(), "A"));
    }

    // Test 22
    @Test void policiesCompareOnIdenticalInputsAndEdfBeatsFcfsOnDeadlines() throws Exception {
        ctx.close();
        ctx = fresh(SimClock.scaled(T0, 1200));                       // 1 sim minute = 50 ms: less timing noise
        profile(ctx, "J20", 600, new String[]{"A", "UNLOAD", "20", "", "CRANE"});
        PolicyComparator.Setup setup = c -> {
            res(c, "CR", "CRANE", 1);
            int[][] jobs = {{1, 200}, {2, 200}, {5, 45}, {3, 65}};                    // {priority, deadline minutes}
            for (int[] j : jobs) cargoWithWorkflow(c, "job" + j[1] + "p" + j[0], "J20", 1, j[0], c.now().plusMinutes(j[1]));
        };
        List<RunMetrics> res = PolicyComparator.compare(ctx, setup, PolicyRegistry.names(), 40000);
        assertEquals(4, res.size());
        for (RunMetrics m : res) { assertEquals(4, m.tasks(), m.label()); assertEquals(4, m.completed(), m.label()); }
        RunMetrics fcfs = res.stream().filter(m -> m.label().startsWith("FCFS")).findFirst().get();
        RunMetrics edf = res.stream().filter(m -> m.label().startsWith("EDF")).findFirst().get();
        assertTrue(fcfs.deadlineMisses() >= 1, "FCFS serves the tight-deadline jobs late: " + fcfs.deadlineMisses());
        assertTrue(edf.deadlineMisses() < fcfs.deadlineMisses(), "EDF misses " + edf.deadlineMisses() + " vs FCFS " + fcfs.deadlineMisses());
    }
}
