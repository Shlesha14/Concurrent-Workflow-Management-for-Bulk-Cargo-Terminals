package com.portflow;

import com.portflow.config.PortFlowContext;
import com.portflow.dao.TaskDao;
import com.portflow.model.*;
import com.portflow.resource.AllocationResult;
import com.portflow.resource.DeadlockDetector;
import com.portflow.util.Jdbc;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static com.portflow.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class ConcurrencyAndTransactionDbTests {
    PortFlowContext ctx;
    @BeforeEach void setUp() throws Exception { ctx = fresh(fastClock()); }
    @AfterEach void tearDown() { if (ctx != null) ctx.close(); }

    /** n cargo items, each with one READY task needing one CRANE slot. */
    List<Task> readyTasks(int n) throws Exception {
        profile(ctx, "ONE", 600, new String[]{"A", "UNLOAD", "10", "", "CRANE"});
        for (int i = 0; i < n; i++) cargoWithWorkflow(ctx, "c" + i, "ONE", 1, 3, null);
        sql(ctx, "UPDATE task SET state='READY', ready_at=NOW()");
        return ctx.db.read(TaskDao::all);
    }

    List<AllocationResult> raceForResource(List<Task> tasks) throws Exception {
        CargoProfileHolder h = new CargoProfileHolder();
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<AllocationResult>> fs = new ArrayList<>();
        for (Task t : tasks) fs.add(pool.submit(() -> {
            Cargo g = ctx.cargo.find(t.cargoId()).get();
            CargoProfile p = ctx.cargo.profiles().stream().filter(x -> x.code().equals(g.profileCode())).findFirst().get();
            go.await();
            return ctx.resourceManager.allocateAndStart(t, g, p);
        }));
        go.countDown();
        List<AllocationResult> out = new ArrayList<>();
        for (var f : fs) out.add(f.get(30, TimeUnit.SECONDS));
        pool.shutdownNow();
        return out;
    }
    static final class CargoProfileHolder { }

    // Test 19 - the DATABASE alone (Java lock disabled) must stop over-allocation
    @Test void concurrentReservationsOfOneCraneGrantExactlyOne_databaseLockOnly() throws Exception {
        res(ctx, "CR", "CRANE", 1);
        ctx.resourceManager.setUseJavaLock(false);
        var results = raceForResource(readyTasks(8));
        assertEquals(1, results.stream().filter(AllocationResult::isGranted).count());
        assertEquals(7, results.stream().filter(r -> r.outcome() == AllocationResult.Outcome.WAIT).count(), "losers get a meaningful WAIT result");
        assertTrue(results.stream().filter(r -> !r.isGranted()).allMatch(r -> r.message().contains("full")), results.toString());
        assertEquals(1, count(ctx, "SELECT COUNT(*) FROM allocation WHERE status='ACTIVE'"));
        assertEquals(1, count(ctx, "SELECT COUNT(*) FROM task WHERE state='RUNNING'"));
    }
    @Test void concurrentReservationsRespectCapacityThree_javaLockAndDatabase() throws Exception {
        res(ctx, "CR", "CRANE", 3);
        ctx.resourceManager.setUseJavaLock(true);
        var results = raceForResource(readyTasks(10));
        assertEquals(3, results.stream().filter(AllocationResult::isGranted).count());
        assertEquals(3, count(ctx, "SELECT COUNT(*) FROM allocation WHERE status='ACTIVE'"));
        assertTrue(ctx.resourceManager.stats().conflicts() >= 7);
    }
    @Test void databaseUniqueKeyForbidsDuplicateLiveAllocationOfOneRequirement() throws Exception {
        res(ctx, "CR", "CRANE", 5);
        Task t = readyTasks(1).get(0);
        long req = ctx.db.read(c -> TaskDao.requirements(c, t.id())).get(0).id();
        int crane = count(ctx, "SELECT resource_id FROM resource LIMIT 1");
        sql(ctx, "INSERT INTO allocation (task_id, requirement_id, resource_id, amount, status, start_time) VALUES (?,?,?,1,'ACTIVE',NOW())", t.id(), req, crane);
        assertThrows(java.sql.SQLException.class, () -> sql(ctx, "INSERT INTO allocation (task_id, requirement_id, resource_id, amount, status, start_time) VALUES (?,?,?,1,'ACTIVE',NOW())", t.id(), req, crane));
    }

    // Test 20 - rollback after an injected failure
    @Test void transactionRollsBackCompletelyWhenFailureIsInjectedBeforeCommit() throws Exception {
        res(ctx, "CR", "CRANE", 1);
        Task t = readyTasks(1).get(0);
        Cargo g = ctx.cargo.find(t.cargoId()).get();
        CargoProfile p = ctx.cargo.profiles().stream().filter(x -> x.code().equals("ONE")).findFirst().get();
        ctx.resourceManager.setFaultInjector(() -> { throw new IllegalStateException("injected failure after INSERT, before COMMIT"); });
        assertThrows(IllegalStateException.class, () -> ctx.resourceManager.allocateAndStart(t, g, p));
        assertEquals(0, count(ctx, "SELECT COUNT(*) FROM allocation"), "allocation insert rolled back");
        assertEquals(0, count(ctx, "SELECT COUNT(*) FROM event_log WHERE event_type='RESOURCE_ACQUIRED'"), "its audit row rolled back too (same transaction)");
        assertEquals("READY", stateOf(ctx, t.cargoId(), "A"), "task state unchanged");
        ctx.resourceManager.setFaultInjector(null);
        assertTrue(ctx.resourceManager.allocateAndStart(t, g, p).isGranted(), "system fully usable after the rollback");
    }
    @Test void stateMachineRejectsIllegalTransitionAndConflictingWriters() throws Exception {
        res(ctx, "CR", "CRANE", 1);
        Task t = readyTasks(1).get(0);
        assertThrows(IllegalStateException.class, () -> ctx.db.inTransaction(c -> { TaskDao.transition(c, ctx.log, ctx.clock, t, TaskState.READY, TaskState.COMPLETED, null, null); return null; }));
        assertThrows(TaskDao.StateConflictException.class, () -> ctx.db.inTransaction(c -> { TaskDao.transition(c, ctx.log, ctx.clock, t, TaskState.WAITING_FOR_RESOURCE, TaskState.BLOCKED, BlockReason.WAIT_TIMEOUT, null); return null; }));
    }

    // Deadlock detection / recovery (wait-for graph)
    @Test void circularWaitBetweenWorkflowsIsDetectedAndResolvedByVictim() throws Exception {
        int s1 = res(ctx, "S1", "DRY_STORE", 100), s2 = res(ctx, "S2", "DRY_STORE", 100);
        profile(ctx, "X", 600, new String[]{"HOLD", "STORE", "10", "", ""}, new String[]{"NEED", "STORE", "10", "HOLD", ""});
        long w1 = cargoWithWorkflow(ctx, "w1", "X", 1, 4, null), w2 = cargoWithWorkflow(ctx, "w2", "X", 1, 2, null);
        // craft: workflow1 holds all of S1 and waits for S2; workflow2 holds all of S2 and waits for S1
        Map<Long, long[]> t = new HashMap<>();
        for (long c : new long[]{w1, w2}) t.put(c, new long[]{taskId(c, "HOLD"), taskId(c, "NEED")});
        sql(ctx, "UPDATE task SET state='COMPLETED', completed_at=NOW(), started_at=NOW() WHERE step_code='HOLD'");
        long r1 = ins("INSERT INTO task_requirement (task_id, resource_type, amount) VALUES (?, 'DRY_STORE', 100)", t.get(w1)[1]);
        long r2 = ins("INSERT INTO task_requirement (task_id, resource_type, amount) VALUES (?, 'DRY_STORE', 100)", t.get(w2)[1]);
        long rh1 = ins("INSERT INTO task_requirement (task_id, resource_type, amount) VALUES (?, 'DRY_STORE', 100)", t.get(w1)[0]);
        long rh2 = ins("INSERT INTO task_requirement (task_id, resource_type, amount) VALUES (?, 'DRY_STORE', 100)", t.get(w2)[0]);
        sql(ctx, "INSERT INTO allocation (task_id, requirement_id, resource_id, amount, status, start_time) VALUES (?,?,?,100,'HELD',?)", t.get(w1)[0], rh1, s1, ctx.now());
        sql(ctx, "INSERT INTO allocation (task_id, requirement_id, resource_id, amount, status, start_time) VALUES (?,?,?,100,'HELD',?)", t.get(w2)[0], rh2, s2, ctx.now());
        sql(ctx, "UPDATE task SET state='WAITING_FOR_RESOURCE', ready_at=NOW() WHERE step_code='NEED'");
        // each waiter needs 100 of DRY_STORE but both stores are full -> mutual wait
        DeadlockDetector d = new DeadlockDetector(ctx.log, ctx.clock);
        var graph = ctx.db.read(d::waitForGraph);
        assertEquals(Set.of(2L), graph.get(1L)); assertEquals(Set.of(1L), graph.get(2L));
        assertEquals(1, d.findCycles(graph).size());
        List<Long> victims = ctx.db.inTransaction(d::detectAndResolve);
        assertEquals(List.of(2L), victims, "lowest-priority workflow is the victim");
        assertEquals("BLOCKED", stateOf(ctx, w2, "NEED"));
        assertEquals("DEADLOCK_VICTIM", ctx.db.read(c -> Jdbc.one(c, "SELECT block_code FROM task WHERE task_id=?", rs -> rs.getString(1), t.get(w2)[1]).get()));
        assertEquals(1, count(ctx, "SELECT COUNT(*) FROM allocation WHERE status='REVOKED'"));
        assertEquals(1, count(ctx, "SELECT COUNT(*) FROM event_log WHERE event_type='DEADLOCK_SUSPECTED'"));
    }
    long ins(String sql, Object... p) throws Exception { return ctx.db.inTransaction(c -> Jdbc.insert(c, sql, p)); }
    long taskId(long cargo, String step) throws Exception { return ctx.db.read(cn -> Jdbc.one(cn, "SELECT task_id FROM task WHERE cargo_id=? AND step_code=?", rs -> rs.getLong(1), cargo, step).orElseThrow()); }
}
