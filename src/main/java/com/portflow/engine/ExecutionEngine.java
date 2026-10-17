package com.portflow.engine;

import com.portflow.dao.*;
import com.portflow.model.*;
import com.portflow.monitoring.TemperatureSimulator;
import com.portflow.resource.AllocationResult;
import com.portflow.resource.DeadlockDetector;
import com.portflow.resource.ResourceManager;
import com.portflow.scheduler.*;
import com.portflow.service.Settings;
import com.portflow.service.WorkflowService;
import com.portflow.simulation.SimClock;
import com.portflow.util.Database;
import com.portflow.util.Jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Workflow execution engine (OS core).
 *  - ONE dispatcher thread ("portflow-dispatcher") repeatedly: housekeeps (state machine, expiry, timeouts, failure propagation),
 *    orders READY tasks with the selected SchedulingPolicy, asks ResourceManager for resources and hands granted tasks to a
 *    FIXED-SIZE worker pool (ExecutorService) - never one thread per task.
 *  - Workers run the simulated work and persist the result in a NEW transaction; no DB transaction is open while a task "runs".
 *  - The dispatcher sleeps on a Semaphore with a timeout (bounded poll) and is woken when a worker finishes: no busy-wait.
 *  - Every acquired resource is released in a finally path, so failures cannot leak resources.
 */
public final class ExecutionEngine implements AutoCloseable {
    public record RunningInfo(long taskId, String thread, LocalDateTime simStart) {}

    private final Database db; private final SimClock clock; private final EventLog log; private final ResourceManager rm;
    private final WorkflowService workflows; private final DeadlockDetector detector; private final TemperatureSimulator temps;
    private final FailureInjector injector;
    private volatile SchedulingPolicy policy;
    private volatile Settings settings;
    private ExecutorService workers;
    private Thread dispatcher;
    private volatile boolean running;
    private final Semaphore wake = new Semaphore(0);
    private final AtomicInteger inFlight = new AtomicInteger();
    private final ConcurrentHashMap<Long, RunningInfo> active = new ConcurrentHashMap<>();
    private final ReentrantLock tickLock = new ReentrantLock();
    private final Set<Long> graphChecked = ConcurrentHashMap.newKeySet();
    private final Set<Long> deadlineLogged = ConcurrentHashMap.newKeySet();
    private final AtomicInteger maxConcurrent = new AtomicInteger();
    private volatile String lastError;
    private volatile Map<String, CargoProfile> profileCache = Map.of();
    private volatile long profileCacheAt;
    private int tickCount;

    public ExecutionEngine(Database db, SimClock clock, EventLog log, ResourceManager rm, WorkflowService workflows, FailureInjector injector, SchedulingPolicy policy) {
        this.db = db; this.clock = clock; this.log = log; this.rm = rm; this.workflows = workflows; this.injector = injector; this.policy = policy;
        this.detector = new DeadlockDetector(log, clock);
        this.temps = new TemperatureSimulator(clock, log);
    }

    public void setPolicy(SchedulingPolicy p) { this.policy = p; }
    public SchedulingPolicy policy() { return policy; }
    public boolean isRunning() { return running; }
    public String lastError() { return lastError; }
    public int maxConcurrentObserved() { return maxConcurrent.get(); }
    public List<RunningInfo> runningSnapshot() { return new ArrayList<>(active.values()); }

    public synchronized void start() throws SQLException {
        if (running) return;
        settings = db.read(Settings::load);
        rm.setLockTimeoutMs(settings.lockTimeoutMs());
        AtomicInteger n = new AtomicInteger();
        workers = Executors.newFixedThreadPool(settings.workers(), r -> { Thread t = new Thread(r, "pf-worker-" + n.incrementAndGet()); t.setDaemon(true); return t; });
        graphChecked.clear(); deadlineLogged.clear(); maxConcurrent.set(0); inFlight.set(0); active.clear(); wake.drainPermits();
        running = true;
        dispatcher = new Thread(this::loop, "portflow-dispatcher");
        dispatcher.setDaemon(true);
        dispatcher.start();
    }

    public synchronized void stop() {
        if (!running) return;
        running = false;
        wake.release();
        try { dispatcher.join(3000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        workers.shutdownNow();
        try { workers.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    @Override public void close() { stop(); }

    /** Blocks until nothing is pending/running or the timeout passes. Returns true when idle. */
    public boolean awaitIdle(long timeoutMs) throws SQLException, InterruptedException {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < end) {
            int open = db.read(c -> Jdbc.one(c, "SELECT COUNT(*) FROM task WHERE state IN ('VALIDATED','READY','WAITING_FOR_RESOURCE','RUNNING')", rs -> rs.getInt(1)).orElse(0));
            if (open == 0 && inFlight.get() == 0) { tick(); return true; }   // one more housekeeping pass settles workflow states
            Thread.sleep(25);
        }
        return false;
    }

    private void loop() {
        while (running) {
            try { tick(); lastError = null; }
            catch (SQLException | RuntimeException e) {          // e.g. DB temporarily down: report, back off, keep the engine alive
                lastError = Database.friendly(e);
                try { Thread.sleep(Math.max(250, settings.pollMs() * 5L)); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
            }
            try { wake.tryAcquire(settings.pollMs(), TimeUnit.MILLISECONDS); wake.drainPermits(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }

    /** One scheduling cycle. Serialised by tickLock so tests/awaitIdle can call it safely. */
    public void tick() throws SQLException {
        tickLock.lock();
        try {
            if (tickCount++ % 10 == 0) settings = db.read(Settings::load);
            Settings s = settings;
            LocalDateTime now = clock.now();
            Map<String, CargoProfile> profiles = profiles();
            db.inTransaction(c -> { housekeeping(c, now, profiles); return null; });
            List<Candidate> ready = db.read(c -> readyCandidates(c, now));
            SchedulingContext ctx = new SchedulingContext(now, s.wPriority(), s.wUrgency(), s.wWait(), s.wShort(), s.horizonMin());
            for (Candidate cand : policy.order(ready, ctx)) {
                if (inFlight.get() >= s.workers()) break;
                dispatch(cand, profiles);
            }
            if (tickCount % 5 == 0) db.inTransaction(c -> detector.detectAndResolve(c));
        } finally { tickLock.unlock(); }
    }

    private Map<String, CargoProfile> profiles() throws SQLException {
        if (System.currentTimeMillis() - profileCacheAt > 2000 || profileCache.isEmpty()) {
            Map<String, CargoProfile> m = new HashMap<>();
            for (CargoProfile p : db.read(ProfileDao::loadAll)) m.put(p.code(), p);
            profileCache = m; profileCacheAt = System.currentTimeMillis();
        }
        return profileCache;
    }

    // ---------------------------------------------------------------- housekeeping
    private void housekeeping(Connection c, LocalDateTime now, Map<String, CargoProfile> profiles) throws SQLException {
        // A) validate each workflow graph once before any of its tasks may run (cycle => nothing can wait forever)
        for (WorkflowDao.Workflow wf : WorkflowDao.active(c)) {
            if (graphChecked.add(wf.id())) {
                List<String> errs = workflows.storedGraphErrors(c, wf.id());
                if (!errs.isEmpty()) {
                    for (Task t : TaskDao.byWorkflow(c, wf.id()))
                        if (!t.state().isTerminal() && t.state() != TaskState.BLOCKED && t.state() != TaskState.RUNNING) TaskDao.transition(c, log, clock, t, t.state(), TaskState.BLOCKED, BlockReason.INVALID_WORKFLOW, errs.toString());
                    log.log(c, EventType.WORKFLOW_REJECTED, null, wf.cargoId(), null, wf.id(), null, null, "Workflow #" + wf.id() + " blocked: " + errs);
                }
            }
        }
        Map<Long, Cargo> cargo = new HashMap<>();
        for (Task t : TaskDao.byState(c, TaskState.VALIDATED, TaskState.READY, TaskState.WAITING_FOR_RESOURCE, TaskState.BLOCKED)) {
            try {
            Cargo g = cargo.computeIfAbsent(t.cargoId(), id -> { try { return CargoDao.find(c, id).orElseThrow(); } catch (SQLException e) { throw new IllegalStateException(e); } });
            TaskState st = t.state();
            // B) expiry: expired cargo is never processed as normal cargo
            if (g.expiresAt() != null && !g.expiresAt().isAfter(now)) {
                TaskDao.transition(c, log, clock, t, st, TaskState.EXPIRED, null, "cargo expired at " + g.expiresAt());
                continue;
            }
            // C) failed prerequisites: dependants can never complete
            if (st != TaskState.BLOCKED && TaskDao.hasFailedPrerequisite(c, t.id())) {
                TaskDao.transition(c, log, clock, t, st, TaskState.BLOCKED, BlockReason.PREREQUISITE_FAILED, "prerequisite failed/cancelled/expired/blocked");
                continue;
            }
            if (st == TaskState.BLOCKED && BlockReason.PREREQUISITE_FAILED.name().equals(t.blockCode()) && !TaskDao.hasFailedPrerequisite(c, t.id())) {
                TaskDao.transition(c, log, clock, t, st, TaskState.VALIDATED, null, "prerequisite recovered");
                st = TaskState.VALIDATED; t = TaskDao.find(c, t.id()).orElseThrow();
            }
            // D) promotion VALIDATED -> READY when all prerequisites are COMPLETED and the cargo has arrived
            if (st == TaskState.VALIDATED && !g.arrivalTime().isAfter(now) && TaskDao.prerequisitesDone(c, t.id())) {
                TaskDao.transition(c, log, clock, t, st, TaskState.READY, null, "prerequisites complete");
                continue;
            }
            // E) bounded waiting: no task waits for a resource longer than the profile allows
            if (st == TaskState.WAITING_FOR_RESOURCE) {
                CargoProfile p = profiles.get(g.profileCode());
                LocalDateTime since = t.readyAt() == null ? t.createdAt() : t.readyAt();
                if (p != null && SimClock.minutesBetween(since, now) > p.maxWaitMin())
                    TaskDao.transition(c, log, clock, t, st, TaskState.BLOCKED, BlockReason.WAIT_TIMEOUT, "waited " + Math.round(SimClock.minutesBetween(since, now)) + " min, limit " + p.maxWaitMin());
            }
            // F) deadline pressure: record once
            if (t.deadlineAt() != null && now.isAfter(t.deadlineAt()) && !t.deadlineMissed() && st != TaskState.BLOCKED) {
                TaskDao.flagDeadlineMissed(c, t.id());
                log.log(c, EventType.TASK_DEADLINE_MISSED, t.id(), t.cargoId(), null, t.workflowId(), null, null, "Task #" + t.id() + " " + t.stepCode() + " passed its deadline " + t.deadlineAt() + " before completing");
            }
            } catch (TaskDao.StateConflictException e) { /* an operator or another service changed this task meanwhile; it is re-evaluated next tick */ }
        }
        // G) workflow completion
        for (WorkflowDao.Workflow wf : WorkflowDao.active(c)) {
            List<Task> ts = TaskDao.byWorkflow(c, wf.id());
            if (ts.stream().allMatch(t -> t.state() == TaskState.COMPLETED)) {
                WorkflowDao.setState(c, wf.id(), "COMPLETED", now);
                CargoDao.setStatus(c, wf.cargoId(), "COMPLETED", null);
                log.log(c, EventType.WORKFLOW_COMPLETED, null, wf.cargoId(), null, wf.id(), null, "COMPLETED", "Workflow #" + wf.id() + " completed");
            } else {
                boolean stuck = ts.stream().anyMatch(t -> t.state() == TaskState.BLOCKED || t.state() == TaskState.FAILED || t.state() == TaskState.EXPIRED || t.state() == TaskState.CANCELLED);
                boolean moving = ts.stream().anyMatch(t -> t.state() == TaskState.RUNNING || t.state() == TaskState.READY || t.state() == TaskState.WAITING_FOR_RESOURCE);
                String want = stuck && !moving ? "BLOCKED" : "RUNNING";
                if (!want.equals(wf.state())) WorkflowDao.setState(c, wf.id(), want, null);
            }
        }
    }

    private List<Candidate> readyCandidates(Connection c, LocalDateTime now) throws SQLException {
        return Jdbc.query(c, "SELECT t.task_id, t.workflow_id, t.cargo_id, t.step_code, t.priority, COALESCE(t.ready_at, t.created_at) AS r, t.deadline_at, g.expires_at, t.duration_min, p.max_wait_min " +
            "FROM task t JOIN cargo g ON g.cargo_id=t.cargo_id JOIN handling_profile p ON p.profile_code=g.profile_code " +
            "WHERE t.state IN ('READY','WAITING_FOR_RESOURCE') AND (t.not_before IS NULL OR t.not_before<=?) AND g.arrival_time<=? ORDER BY t.task_id",
            rs -> new Candidate(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4), rs.getInt(5), Jdbc.ts(rs, "r"), Jdbc.ts(rs, "deadline_at"), Jdbc.ts(rs, "expires_at"), rs.getInt(9), rs.getInt(10)), now, now);
    }

    // ---------------------------------------------------------------- dispatch
    private void dispatch(Candidate cand, Map<String, CargoProfile> profiles) throws SQLException {
        Task task = db.read(c -> TaskDao.find(c, cand.taskId())).orElse(null);
        if (task == null || (task.state() != TaskState.READY && task.state() != TaskState.WAITING_FOR_RESOURCE)) return;
        Cargo cargo = db.read(c -> CargoDao.find(c, task.cargoId())).orElseThrow();
        CargoProfile profile = profiles.get(cargo.profileCode());
        try {
            AllocationResult r = rm.allocateAndStart(task, cargo, profile);
            switch (r.outcome()) {
                case GRANTED -> {
                    inFlight.incrementAndGet();
                    try { workers.submit(() -> runTask(task.id())); }
                    catch (RejectedExecutionException e) { inFlight.decrementAndGet(); failAfterDispatchRejection(task); }
                }
                case WAIT -> { if (task.state() == TaskState.READY) db.inTransaction(c -> { TaskDao.transition(c, log, clock, task, TaskState.READY, TaskState.WAITING_FOR_RESOURCE, null, r.message()); return null; }); }
                case BLOCKED -> db.inTransaction(c -> { TaskDao.transition(c, log, clock, task, task.state(), TaskState.BLOCKED, r.reason(), r.message()); return null; });
            }
        } catch (TaskDao.StateConflictException e) { /* another actor changed the task; re-evaluated next tick */ }
    }

    private void failAfterDispatchRejection(Task task) throws SQLException {
        db.inTransaction(c -> { Task t = TaskDao.find(c, task.id()).orElseThrow(); rm.releaseOnFailure(c, t, "engine stopping"); TaskDao.transition(c, log, clock, t, TaskState.RUNNING, TaskState.FAILED, null, "engine stopping"); return null; });
    }

    // ---------------------------------------------------------------- worker
    private void runTask(long taskId) {
        try {
            Task t = db.read(c -> TaskDao.find(c, taskId)).orElseThrow();
            String thread = Thread.currentThread().getName();
            LocalDateTime start = clock.now();
            active.put(taskId, new RunningInfo(taskId, thread, start));
            maxConcurrent.accumulateAndGet(active.size(), Math::max);
            db.inTransaction(c -> {
                TaskDao.markStarted(c, taskId, start, thread);
                log.log(c, EventType.TASK_STARTED, taskId, t.cargoId(), null, t.workflowId(), null, null, "Task #" + taskId + " " + t.stepCode() + " started on " + thread);
                return null;
            });
            try {
                if ("MONITOR".equals(t.type())) db.inTransaction(c -> temps.sample(c, t, settings.excursionProbability()));
                Thread.sleep(clock.realMillis(t.durationMin()));                      // simulated processing time (no DB transaction open)
                int f = injector.consume(taskId);
                if (f != 0) throw new SimulatedFailure("Injected " + (f == 2 ? "permanent" : "transient") + " failure", f == 2);
                complete(t);
            } catch (SimulatedFailure e) { fail(t, e.getMessage(), e.permanent);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); fail(t, "interrupted (engine stopping)", false); }
        } catch (Exception e) {
            lastError = Database.friendly(e);
        } finally {
            safetyRelease(taskId);
            active.remove(taskId);
            inFlight.decrementAndGet();
            wake.release();
        }
    }

    private void complete(Task t) throws SQLException {
        LocalDateTime now = clock.now();
        db.inTransaction(c -> {
            Task cur = TaskDao.find(c, t.id()).orElseThrow();
            boolean missed = cur.deadlineAt() != null && now.isAfter(cur.deadlineAt());
            TaskDao.transition(c, log, clock, cur, TaskState.RUNNING, TaskState.COMPLETED, null, null);
            TaskDao.markCompleted(c, t.id(), now, missed);
            rm.releaseOnCompletion(c, cur);
            log.log(c, EventType.TASK_COMPLETED, t.id(), t.cargoId(), null, t.workflowId(), null, null, "Task #" + t.id() + " " + t.stepCode() + " completed" + (missed ? " AFTER its deadline" : ""));
            if (missed) log.log(c, EventType.TASK_DEADLINE_MISSED, t.id(), t.cargoId(), null, t.workflowId(), null, null, "Task #" + t.id() + " finished after deadline " + cur.deadlineAt());
            return null;
        });
    }

    /** Failure path: release resources, record FAILED, then retry with exponential backoff unless retries are exhausted. */
    private void fail(Task t, String why, boolean permanent) throws SQLException {
        Settings s = settings;
        db.inTransaction(c -> {
            Task cur = TaskDao.find(c, t.id()).orElseThrow();
            if (cur.state() != TaskState.RUNNING) return null;
            rm.releaseOnFailure(c, cur, "task failed");
            boolean retry = !permanent && cur.retryCount() < s.maxRetries();
            TaskDao.transition(c, log, clock, cur, TaskState.RUNNING, TaskState.FAILED, retry ? null : BlockReason.RETRIES_EXHAUSTED, why);
            log.log(c, EventType.TASK_FAILED, t.id(), t.cargoId(), null, t.workflowId(), "RUNNING", "FAILED", "Task #" + t.id() + " " + t.stepCode() + " failed: " + why);
            if (retry) {
                long backoffSimSeconds = Math.max(1, (long) (s.backoffMs() * Math.pow(2, cur.retryCount()) / 1000.0 * clock.speed()));
                TaskDao.scheduleRetry(c, t.id(), clock.now().plusSeconds(backoffSimSeconds));
                Task failed = TaskDao.find(c, t.id()).orElseThrow();
                TaskDao.transition(c, log, clock, failed, TaskState.FAILED, TaskState.READY, null, "automatic retry " + (cur.retryCount() + 1) + "/" + s.maxRetries());
                log.log(c, EventType.TASK_RETRY_SCHEDULED, t.id(), t.cargoId(), null, t.workflowId(), null, null, "Task #" + t.id() + " retry " + (cur.retryCount() + 1) + " scheduled after backoff");
            }
            return null;
        });
    }

    /** finally-path guarantee: whatever happened, no ACTIVE allocation of a non-running task survives. */
    private void safetyRelease(long taskId) {
        try {
            db.inTransaction(c -> {
                Task t = TaskDao.find(c, taskId).orElse(null);
                if (t == null) return null;
                if (t.state() == TaskState.RUNNING) {          // still RUNNING here means completion/failure persistence itself failed
                    rm.releaseOnFailure(c, t, "safety release");
                    TaskDao.transition(c, log, clock, t, TaskState.RUNNING, TaskState.FAILED, null, "result could not be saved; will retry");
                    Task f = TaskDao.find(c, taskId).orElseThrow();
                    TaskDao.transition(c, log, clock, f, TaskState.FAILED, TaskState.READY, null, "recovered after persistence failure");
                } else if (t.state() != TaskState.COMPLETED) rm.releaseOnFailure(c, t, "safety release");
                return null;
            });
        } catch (SQLException | RuntimeException e) { lastError = Database.friendly(e); }
    }

    private static final class SimulatedFailure extends Exception {
        final boolean permanent;
        SimulatedFailure(String m, boolean permanent) { super(m); this.permanent = permanent; }
    }
}
