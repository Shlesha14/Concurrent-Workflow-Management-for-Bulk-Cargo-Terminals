package com.portflow.simulation;

import com.portflow.config.PortFlowContext;
import com.portflow.config.SchemaInstaller;
import com.portflow.dao.TaskDao;
import com.portflow.model.*;
import com.portflow.monitoring.MetricsService.RunMetrics;
import com.portflow.resource.AllocationResult;
import com.portflow.scheduler.PolicyRegistry;
import com.portflow.service.ServiceException;
import com.portflow.util.Jdbc;
import com.portflow.util.TableData;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

/**
 * Ready-to-run demonstration scenarios A-H (see scenarios/README.md). Each one RESETS operational data, builds its own terminal
 * and cargo through the normal services, runs the real engine, and derives the verdict from what the database recorded.
 */
public final class ScenarioRunner {
    public static final Map<String, String> TITLES = new LinkedHashMap<>();
    static {
        TITLES.put("A", "Perishable cargo urgency and incompatible storage");
        TITLES.put("B", "Electronics inspection before storage");
        TITLES.put("C", "Coal operations with limited stockyard capacity");
        TITLES.put("D", "Concurrent execution of independent tasks");
        TITLES.put("E", "Resource failure, blocking and recovery");
        TITLES.put("F", "Dependency cycle rejected before execution");
        TITLES.put("G", "Deadline pressure: scheduling policy comparison");
        TITLES.put("H", "Database concurrency on a constrained resource");
    }

    private final PortFlowContext x;
    private final long timeoutMs;
    public ScenarioRunner(PortFlowContext ctx, long timeoutMs) { this.x = ctx; this.timeoutMs = timeoutMs; }

    /** Simulated seconds per real second used while a scenario runs (restored afterwards) so scenarios finish in seconds. */
    public static final double SCENARIO_SPEED = 3000;

    public ScenarioResult run(String id) throws Exception {
        double previous = x.clock.speed();
        x.clock.setSpeed(SCENARIO_SPEED);
        try { return runInternal(id); } finally { x.engine.stop(); x.clock.setSpeed(previous); }
    }

    private ScenarioResult runInternal(String id) throws Exception {
        x.engine.stop();
        SchemaInstaller.resetOperationalData(x.db, true);
        x.resourceManager.resetStats();
        x.injector.failTask(-1, 0);
        return switch (id.toUpperCase()) {
            case "A" -> a(); case "B" -> b(); case "C" -> c(); case "D" -> d(); case "E" -> e(); case "F" -> f(); case "G" -> g(); case "H" -> h();
            default -> throw new IllegalArgumentException("Unknown scenario " + id);
        };
    }

    public List<ScenarioResult> runAll() throws Exception {
        List<ScenarioResult> out = new ArrayList<>();
        for (String id : TITLES.keySet()) out.add(run(id));
        return out;
    }

    // ------------------------------------------------------------------ helpers
    private long cargo(String name, String profile, double qty, int prio, Integer expiryMin, Integer deadlineMin) throws Exception {
        LocalDateTime now = x.now();
        var r = x.cargo.register(new Cargo(null, name, profile, BigDecimal.valueOf(qty), "TONNE", now, expiryMin == null ? null : now.minusHours(2), expiryMin == null ? null : now.plusMinutes(expiryMin),
            prio, deadlineMin == null ? null : now.plusMinutes(deadlineMin), null, null, "REGISTERED", null, null));
        if (r.cargoId() == null) throw new IllegalStateException(name + ": " + r.issues());
        x.workflows.createWorkflow(r.cargoId());
        return r.cargoId();
    }
    private void runEngine() throws Exception { x.engine.start(); x.engine.awaitIdle(timeoutMs); x.engine.stop(); }
    private int n(String sql, Object... p) throws Exception { return x.db.read(c -> Jdbc.one(c, sql, rs -> rs.getInt(1), p).orElse(0)); }
    private String s(String sql, Object... p) throws Exception { return x.db.read(c -> Jdbc.one(c, sql, rs -> rs.getString(1), p).orElse("?")); }
    private LocalDateTime t(String sql, Object... p) throws Exception { return x.db.read(c -> Jdbc.one(c, sql, rs -> rs.getObject(1, LocalDateTime.class), p).orElse(null)); }
    private List<String> timeline(String where) throws Exception {
        return x.db.read(c -> Jdbc.query(c, "SELECT CONCAT('task #', t.task_id, ' ', g.name, ' / ', t.step_code, ' [', t.state, '] ', COALESCE(DATE_FORMAT(t.started_at,'%H:%i:%s'),'-'), ' -> ', COALESCE(DATE_FORMAT(t.completed_at,'%H:%i:%s'),'-'), ' thread=', COALESCE(t.worker_thread,'-'), COALESCE(CONCAT(' blocked=', t.block_code),'')) " +
            "FROM task t JOIN cargo g ON g.cargo_id=t.cargo_id " + where + " ORDER BY COALESCE(t.started_at, t.created_at), t.task_id", rs -> rs.getString(1)));
    }

    // ------------------------------------------------------------------ A
    private record AOutcome(LocalDateTime lettuceStore, LocalDateTime spinachStore, String iceCream, int denials, long iceCargo) {}

    private AOutcome runA(String policy) throws Exception {
        SchemaInstaller.resetOperationalData(x.db, true);
        x.resourceManager.resetStats();
        DemoData.add(x, "CR1", "CRANE", 1, "Terminal B", null, null); DemoData.add(x, "FORK1", "FORKLIFT", 1, "Yard", null, null); DemoData.add(x, "FORK2", "FORKLIFT", 1, "Yard", null, null);
        DemoData.add(x, "TRUCK1", "TRUCK", 1, "Gate", null, null); DemoData.add(x, "INSP1", "INSPECTION_STATION", 2, "Shed 1", null, null);
        DemoData.add(x, "COLD-A", "COLD_ROOM", 100, "Cold block", 1.0, 3.0);          // scarce: fits only ONE of the 60 t produce lots
        // deliberately NO frozen store in this scenario. Registration order: spinach (6 h shelf life) BEFORE lettuce (3 h).
        long spinach = cargo("Spinach (6 h shelf life)", "LEAFY_VEG", 60, 3, 360, null);
        long lettuce = cargo("Lettuce (3 h shelf life)", "LEAFY_VEG", 60, 3, 180, null);
        long iceCream = cargo("Ice cream", "FROZEN_FOOD", 50, 3, 6000, null);
        x.setPolicy(policy);
        runEngine();
        return new AOutcome(t("SELECT started_at FROM task WHERE cargo_id=? AND step_code='STORE'", lettuce), t("SELECT started_at FROM task WHERE cargo_id=? AND step_code='STORE'", spinach),
            s("SELECT CONCAT(state,' / ',COALESCE(block_code,'')) FROM task WHERE cargo_id=? AND step_code='STORE'", iceCream), n("SELECT COUNT(*) FROM event_log WHERE event_type='ALLOCATION_DENIED'"), iceCream);
    }

    private ScenarioResult a() throws Exception {
        AOutcome fcfs = runA("FCFS");
        AOutcome per = runA("PERISHABILITY");
        List<String> obs = new ArrayList<>();
        obs.add("FCFS:          spinach STORE started " + fcfs.spinachStore() + ", lettuce STORE started " + fcfs.lettuceStore() + " (registration order wins)");
        obs.add("PERISHABILITY: lettuce STORE started " + per.lettuceStore() + ", spinach STORE started " + per.spinachStore() + " (earlier expiry wins the scarce cold room)");
        obs.add("Ice cream STORE task: " + per.iceCream() + " (the only cold room is 1-3 C, not a frozen store); allocation denials: " + per.denials());
        boolean ok = fcfs.spinachStore() != null && fcfs.lettuceStore() != null && fcfs.spinachStore().isBefore(fcfs.lettuceStore())
            && per.lettuceStore() != null && per.spinachStore() != null && per.lettuceStore().isBefore(per.spinachStore())
            && per.iceCream().startsWith("BLOCKED / NO_COMPATIBLE_RESOURCE")
            && n("SELECT COUNT(*) FROM allocation a JOIN resource r ON r.resource_id=a.resource_id JOIN task t ON t.task_id=a.task_id WHERE t.cargo_id=? AND r.type_code='COLD_ROOM'", per.iceCargo()) == 0;
        return new ScenarioResult("A", TITLES.get("A"), "Under FCFS spinach (registered first) is served first; under PERISHABILITY the earlier-expiring lettuce gets the single cool room first. Ice cream is never placed in the cool room (BLOCKED, NO_COMPATIBLE_RESOURCE).", obs, ok, null);
    }

    // ------------------------------------------------------------------ B
    private ScenarioResult b() throws Exception {
        DemoData.add(x, "CR2", "CRANE", 1, "Terminal B", null, null, "FRAGILE_HANDLING"); DemoData.add(x, "FORK2", "FORKLIFT", 1, "Yard", null, null, "FRAGILE_HANDLING");
        DemoData.add(x, "INSP1", "INSPECTION_STATION", 1, "Shed 1", null, null); DemoData.add(x, "DRY-A", "DRY_STORE", 300, "Warehouse", null, null, "FRAGILE_SAFE");
        DemoData.add(x, "TRUCK1", "TRUCK", 1, "Gate", null, null);
        cargo("Laptops", "ELECTRONICS", 120, 4, null, null); cargo("Mobile phones", "ELECTRONICS", 100, 3, null, null);
        runEngine();
        int violations = n("SELECT COUNT(*) FROM task i JOIN task s ON s.cargo_id=i.cargo_id AND s.step_code='STORE' WHERE i.step_code='INSPECT' AND (s.started_at IS NULL OR s.started_at < i.completed_at)");
        List<String> obs = new ArrayList<>(timeline("WHERE t.step_code IN ('INSPECT','STORE')"));
        obs.add("STORE tasks that started before their INSPECT finished: " + violations);
        return new ScenarioResult("B", TITLES.get("B"), "No STORE/DISPATCH starts before INSPECT has completed; everything completes.", obs, violations == 0 && n("SELECT COUNT(*) FROM task WHERE state='COMPLETED'") == 8, null);
    }

    // ------------------------------------------------------------------ C
    private ScenarioResult c() throws Exception {
        DemoData.add(x, "CR1", "CRANE", 2, "Terminal B", null, null, "BULK_GRAB"); DemoData.add(x, "CONV1", "CONVEYOR", 1, "Terminal B", null, null, "DUST_SUPPRESSED");
        int stock = DemoData.add(x, "STOCK1", "STOCKYARD", 3000, "Stockyard", null, null); DemoData.add(x, "RECL1", "RECLAIMER", 1, "Stockyard", null, null);
        cargo("Coal lot 1", "COAL", 2500, 3, null, null); cargo("Coal lot 2", "COAL", 2000, 3, null, null);       // 4500 t into a 3000 t yard
        runEngine();
        int peak = n("SELECT COALESCE(MAX(s),0) FROM (SELECT (SELECT SUM(b.amount) FROM allocation b WHERE b.resource_id=a.resource_id AND b.start_time<=a.start_time AND (b.end_time IS NULL OR b.end_time>a.start_time)) s FROM allocation a WHERE a.resource_id=?) q", stock);
        int denied = n("SELECT COUNT(*) FROM event_log WHERE event_type='ALLOCATION_DENIED'");
        List<String> obs = new ArrayList<>(timeline("WHERE t.step_code IN ('UNLOAD','STACK','RECLAIM')"));
        obs.add("Peak tonnes in stockyard at any allocation instant: " + peak + " (capacity 3000); allocation denials (contention): " + denied);
        return new ScenarioResult("C", TITLES.get("C"), "Lot 2 waits for stockyard space until lot 1 is reclaimed; stockyard never exceeds 3000 t; both lots complete.", obs,
            peak <= 3000 && denied >= 1 && n("SELECT COUNT(*) FROM task WHERE state='COMPLETED'") == 8, null);
    }

    // ------------------------------------------------------------------ D
    private ScenarioResult d() throws Exception {
        DemoData.add(x, "CR1", "CRANE", 2, "Terminal B", null, null); DemoData.add(x, "FORK1", "FORKLIFT", 1, "Yard", null, null); DemoData.add(x, "FORK2", "FORKLIFT", 1, "Yard", null, null);
        DemoData.add(x, "INSP1", "INSPECTION_STATION", 2, "Shed 1", null, null); DemoData.add(x, "COLD-B", "COLD_ROOM", 200, "Cold block", 11.0, 13.0);
        DemoData.add(x, "TRUCK1", "TRUCK", 1, "Gate", null, null); DemoData.add(x, "TRUCK2", "TRUCK", 1, "Gate", null, null); DemoData.add(x, "DRY-B", "DRY_STORE", 700, "Warehouse", null, null);
        cargo("Tomatoes", "TOMATO", 40, 3, 600, null); cargo("Pallets A", "GENERAL", 50, 3, null, null); cargo("Pallets B", "GENERAL", 50, 3, null, null);
        runEngine();
        int pairs = n("SELECT COUNT(*) FROM task a JOIN task b ON a.task_id<b.task_id WHERE a.started_at<b.completed_at AND b.started_at<a.completed_at");
        List<String> obs = new ArrayList<>(timeline("WHERE t.started_at IS NOT NULL"));
        obs.add("Peak tasks running at once: " + x.engine.maxConcurrentObserved() + "; distinct worker threads used: " + n("SELECT COUNT(DISTINCT worker_thread) FROM task") + "; overlapping task pairs: " + pairs);
        return new ScenarioResult("D", TITLES.get("D"), "At least 2 tasks run simultaneously on different worker threads (overlapping start/end timestamps).", obs, x.engine.maxConcurrentObserved() >= 2 && pairs >= 1, null);
    }

    // ------------------------------------------------------------------ E
    private ScenarioResult e() throws Exception {
        DemoData.add(x, "CR1", "CRANE", 2, "Terminal B", null, null); int fork = DemoData.add(x, "FORK1", "FORKLIFT", 1, "Yard", null, null);
        DemoData.add(x, "DRY-B", "DRY_STORE", 700, "Warehouse", null, null); DemoData.add(x, "TRUCK1", "TRUCK", 1, "Gate", null, null);
        for (int i = 1; i <= 3; i++) cargo("Pallets " + i, "GENERAL", 40, 3, null, null);
        x.engine.start();
        long end = System.currentTimeMillis() + timeoutMs;
        while (n("SELECT COUNT(*) FROM task WHERE state='COMPLETED'") < 1 && System.currentTimeMillis() < end) Thread.sleep(20);
        var failure = x.resources.setStatus(fork, "UNAVAILABLE", "simulated forklift breakdown");
        x.engine.awaitIdle(timeoutMs);
        int blocked = n("SELECT COUNT(*) FROM task WHERE state='BLOCKED' AND block_code='RESOURCE_UNAVAILABLE'");
        List<String> obs = new ArrayList<>();
        obs.add("After forklift failure: revision #" + failure.revisionId() + ", newly blocked " + failure.blockedTasks() + ", running tasks allowed to drain " + failure.runningDrained());
        obs.add("Tasks BLOCKED with RESOURCE_UNAVAILABLE after the engine settled: " + blocked + "; active allocations still held: " + n("SELECT COUNT(*) FROM allocation WHERE status='ACTIVE'") + " (must be 0)");
        var rec = x.resources.setStatus(fork, "AVAILABLE", "repaired");
        obs.add("After repair: revision #" + rec.revisionId() + ", recovered tasks " + rec.recoveredTasks());
        x.engine.awaitIdle(timeoutMs); x.engine.stop();
        obs.add("Final: completed tasks " + n("SELECT COUNT(*) FROM task WHERE state='COMPLETED'") + "/9; schedule revisions kept: " + n("SELECT COUNT(*) FROM schedule_revision"));
        boolean ok = blocked >= 1 && rec.recoveredTasks() >= 1 && n("SELECT COUNT(*) FROM task WHERE state='COMPLETED'") == 9 && n("SELECT COUNT(*) FROM allocation WHERE status IN ('ACTIVE','HELD')") == 0;
        return new ScenarioResult("E", TITLES.get("E"), "Waiting tasks that need the forklift become BLOCKED (not lost), a revision is recorded, resources are not leaked, and after repair all work completes.", obs, ok, null);
    }

    // ------------------------------------------------------------------ F
    private ScenarioResult f() throws Exception {
        DemoData.addTerminalResources(x);
        long c = cargo("Machinery", "GENERAL", 30, 3, null, null);
        long unload = x.db.read(cn -> Jdbc.one(cn, "SELECT task_id FROM task WHERE cargo_id=? AND step_code='UNLOAD'", rs -> rs.getLong(1), c).get());
        long dispatch = x.db.read(cn -> Jdbc.one(cn, "SELECT task_id FROM task WHERE cargo_id=? AND step_code='DISPATCH'", rs -> rs.getLong(1), c).get());
        String rejected;
        try { x.workflows.addDependency(unload, dispatch); rejected = "ACCEPTED (unexpected)"; }
        catch (ServiceException e) { rejected = e.getMessage() + " " + e.details(); }
        runEngine();
        List<String> obs = List.of("Attempted extra dependency UNLOAD -> DISPATCH (closes a loop): " + rejected,
            "Dependencies stored: " + n("SELECT COUNT(*) FROM task_dependency") + " (unchanged = 2); workflow completed tasks: " + n("SELECT COUNT(*) FROM task WHERE state='COMPLETED'") + "/3");
        return new ScenarioResult("F", TITLES.get("F"), "The cyclic dependency is rejected at validation time; the valid workflow still runs to completion (no endless wait).", obs, rejected.contains("cycle") && n("SELECT COUNT(*) FROM task_dependency") == 2 && n("SELECT COUNT(*) FROM task WHERE state='COMPLETED'") == 3, null);
    }

    // ------------------------------------------------------------------ G
    private ScenarioResult g() throws Exception {
        PolicyComparator.Setup setup = c -> {
            DemoData.add(c, "CR1", "CRANE", 1, "Terminal B", null, null); DemoData.add(c, "FORK1", "FORKLIFT", 2, "Yard", null, null);
            DemoData.add(c, "TRUCK1", "TRUCK", 2, "Gate", null, null); DemoData.add(c, "DRY-B", "DRY_STORE", 2000, "Warehouse", null, null);
            int[][] jobs = {{1, 400}, {2, 400}, {5, 40}, {3, 55}, {4, 70}, {1, 400}};       // {priority, deadline minutes after arrival}
            for (int[] j : jobs) { var r = c.cargo.register(new Cargo(null, "Lot p" + j[0] + " d" + j[1], "GENERAL", BigDecimal.valueOf(100), "TONNE", c.now(), null, null, j[0], c.now().plusMinutes(j[1]), null, null, "REGISTERED", null, null)); c.workflows.createWorkflow(r.cargoId()); }
        };
        List<RunMetrics> res = PolicyComparator.compare(x, setup, PolicyRegistry.names(), timeoutMs);
        List<String> obs = new ArrayList<>();
        for (RunMetrics m : res) obs.add(String.format("%-14s completed %d/%d, deadline misses %d, avg wait %.1f sim-min, makespan %.1f sim-min", m.label(), m.completed(), m.tasks(), m.deadlineMisses(), m.avgWaitMin(), m.makespanMin()));
        boolean ok = res.size() == 4 && res.stream().allMatch(m -> m.tasks() == 18 && m.completed() == 18);
        return new ScenarioResult("G", TITLES.get("G"), "Identical input run under 4 policies: every run completes all 18 tasks; deadline misses and waiting time differ by policy (compare the metrics).", obs, ok, res);
    }

    // ------------------------------------------------------------------ H
    private ScenarioResult h() throws Exception {
        DemoData.add(x, "CR1", "CRANE", 1, "Terminal B", null, null); DemoData.add(x, "DRY-B", "DRY_STORE", 2000, "Warehouse", null, null);
        DemoData.add(x, "FORK1", "FORKLIFT", 1, "Yard", null, null); DemoData.add(x, "TRUCK1", "TRUCK", 1, "Gate", null, null);
        int contenders = 10;
        for (int i = 0; i < contenders; i++) cargo("Lot " + i, "GENERAL", 10, 3, null, null);
        x.db.inTransaction(cn -> Jdbc.update(cn, "UPDATE task SET state='READY', ready_at=? WHERE step_code='UNLOAD'", x.now()));
        x.resourceManager.setUseJavaLock(false);                       // let the DATABASE alone arbitrate
        List<Task> tasks = x.db.read(c -> TaskDao.findByStep(c, "UNLOAD"));
        List<CargoProfile> profiles = x.cargo.profiles();
        CargoProfile general = profiles.stream().filter(p -> p.code().equals("GENERAL")).findFirst().get();
        ExecutorService pool = Executors.newFixedThreadPool(contenders);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<AllocationResult>> fs = new ArrayList<>();
        for (Task t : tasks) fs.add(pool.submit(() -> { Cargo g = x.cargo.find(t.cargoId()).get(); go.await(); return x.resourceManager.allocateAndStart(t, g, general); }));
        go.countDown();
        int granted = 0, waited = 0; Set<String> messages = new TreeSet<>();
        for (var f : fs) { AllocationResult r = f.get(30, TimeUnit.SECONDS); if (r.isGranted()) granted++; else { waited++; messages.add(r.outcome() + ": " + r.message()); } }
        pool.shutdownNow();
        x.resourceManager.setUseJavaLock(true);
        List<String> obs = List.of(contenders + " threads raced for the single crane slot at the same instant: granted " + granted + ", refused " + waited, "Refusal messages: " + messages,
            "ACTIVE allocations on the crane in the database: " + n("SELECT COUNT(*) FROM allocation WHERE status='ACTIVE'") + " (must be 1)");
        return new ScenarioResult("H", TITLES.get("H"), "Exactly 1 of 10 simultaneous attempts is granted; the rest receive a meaningful WAIT message; no over-allocation.", obs, granted == 1 && waited == contenders - 1 && n("SELECT COUNT(*) FROM allocation WHERE status='ACTIVE'") == 1, null);
    }

    /** For the Simulation screen: scenario results as a table. */
    public static TableData asTable(List<ScenarioResult> rs) {
        List<List<Object>> rows = new ArrayList<>();
        for (ScenarioResult r : rs) rows.add(List.of(r.id(), r.title(), r.passed() ? "PASS" : "FAIL", String.join(" | ", r.observed())));
        return new TableData(List.of("id", "scenario", "result", "observed"), rows);
    }
}
