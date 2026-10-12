package edu.portflow.engine;

import edu.portflow.db.ActivityLogDao;
import edu.portflow.db.AllocationDao;
import edu.portflow.db.TaskDao;
import edu.portflow.db.WorkflowDao;
import edu.portflow.model.*;
import edu.portflow.scheduler.TaskScheduler;
import edu.portflow.workflow.DependencyGraph;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

public final class WorkflowEngine implements AutoCloseable {
    private final ExecutorService workers;
    private final ResourceManager resources;
    private final TaskScheduler scheduler = new TaskScheduler();
    private final DependencyGraph graph = new DependencyGraph();
    private final List<WorkflowTask> tasks = new CopyOnWriteArrayList<>();
    private final Map<Long,Cargo> cargoById = new ConcurrentHashMap<>();
    private final Set<Long> submitted = ConcurrentHashMap.newKeySet();
    private final List<ActivityEvent> events = new CopyOnWriteArrayList<>();
    private final Consumer<ActivityEvent> eventListener;
    private final ActivityLogDao logDao = new ActivityLogDao();
    private final AllocationDao allocationDao = new AllocationDao();
    private final TaskDao taskDao = new TaskDao();
    private final WorkflowDao workflowDao = new WorkflowDao();
    private volatile boolean running;
    private volatile SchedulingPolicy currentPolicy = SchedulingPolicy.PRIORITY;

    public WorkflowEngine(int workerCount, ResourceManager resources, Consumer<ActivityEvent> eventListener) {
        this.workers=Executors.newFixedThreadPool(Math.max(1,workerCount));
        this.resources=resources; this.eventListener=eventListener==null?e->{}:eventListener;
    }
    public synchronized void registerCargo(Cargo cargo, List<WorkflowTask> generatedTasks) {
        graph.validate(concat(tasks, generatedTasks));
        try {
            workflowDao.insertCargoAndWorkflow(cargo, generatedTasks);
            log("DATABASE_COMMIT",null,"Cargo and workflow committed to MySQL.");
        } catch (SQLException ex) {
            logWithoutDb("DATABASE_NOT_WRITTEN",null,"MySQL insert unavailable; this cargo exists only in the current demo session. "+ex.getMessage());
        }
        cargoById.put(cargo.id(),cargo); tasks.addAll(generatedTasks);
        log("CARGO_REGISTERED",null,"Registered "+cargo.name()+" ("+cargo.category()+")");
        generatedTasks.forEach(t -> log("TASK_CREATED",t.id(),t.name()));
    }
    private List<WorkflowTask> concat(List<WorkflowTask> existing, List<WorkflowTask> added) {
        List<WorkflowTask> all = new ArrayList<>(existing); all.addAll(added); return all;
    }
    public List<WorkflowTask> tasks(){return List.copyOf(tasks);}
    public List<ActivityEvent> events(){return List.copyOf(events);}
    public Map<Long,Cargo> cargos(){return Map.copyOf(cargoById);}
    public ResourceManager resources(){return resources;}

    public synchronized void start(SchedulingPolicy policy) {
        currentPolicy = Objects.requireNonNull(policy);
        graph.validate(tasks);
        for (WorkflowTask t : tasks) if (t.status() == TaskStatus.BLOCKED) submitted.remove(t.id());
        running=true;
        scheduleEligible();
        log("ENGINE_STARTED",null,"Scheduler started with policy "+policy);
    }
    private synchronized void scheduleEligible() {
        List<WorkflowTask> ordered=scheduler.order(tasks,currentPolicy,LocalDateTime.now());
        for (WorkflowTask task:ordered) {
            if (submitted.contains(task.id()) || task.status()==TaskStatus.COMPLETED
                    || task.status()==TaskStatus.FAILED || task.status()==TaskStatus.EXPIRED
                    || task.status()==TaskStatus.CANCELLED) continue;
            boolean prerequisiteFailed = task.dependencies().stream().map(id -> tasks.stream()
                    .filter(candidate -> candidate.id() == id).findFirst().orElse(null))
                    .filter(Objects::nonNull).anyMatch(parent -> parent.status()==TaskStatus.FAILED
                            || parent.status()==TaskStatus.EXPIRED || parent.status()==TaskStatus.CANCELLED);
            if (prerequisiteFailed) {
                task.forceStatus(TaskStatus.BLOCKED);
                task.setFailureReason("A prerequisite failed, expired, or was cancelled.");
                persistState(task);
                log("DEPENDENCY_BLOCKED",task.id(),task.failureReason());
                submitted.add(task.id());
            } else if (graph.prerequisitesCompleted(task,tasks)) {
                submitted.add(task.id());
                task.forceStatus(TaskStatus.READY);
                workers.submit(()->execute(task));
            } else if (task.status()==TaskStatus.CREATED || task.status()==TaskStatus.READY) {
                task.forceStatus(TaskStatus.READY);
            }
        }
        boolean unfinished = tasks.stream().anyMatch(t -> t.status()!=TaskStatus.COMPLETED
                && t.status()!=TaskStatus.FAILED && t.status()!=TaskStatus.EXPIRED && t.status()!=TaskStatus.CANCELLED);
        boolean allSubmittedFinished = submitted.stream().map(id -> tasks.stream().filter(t->t.id()==id).findFirst().orElse(null))
                .filter(Objects::nonNull).noneMatch(t -> t.status()==TaskStatus.RUNNING || t.status()==TaskStatus.WAITING_FOR_RESOURCE || t.status()==TaskStatus.READY);
        if (!unfinished && allSubmittedFinished) running=false;
    }
    private void execute(WorkflowTask task) {
        Cargo cargo=cargoById.get(task.cargoId());
        if(cargo==null){fail(task,"Cargo record is missing.");finishTask();return;}
        if(cargo.expiryTime()!=null && !LocalDateTime.now().isBefore(cargo.expiryTime()) && !task.taskType().equals("DISPATCH")) {
            task.forceStatus(TaskStatus.EXPIRED); task.setFailureReason("Cargo expiry time has passed."); persistState(task);
            log("TASK_EXPIRED",task.id(),task.failureReason()); finishTask(); return;
        }
        task.forceStatus(TaskStatus.WAITING_FOR_RESOURCE);
        ResourceUnit lease=null;
        for(int attempt=1;attempt<=3 && lease==null;attempt++) {
            lease=resources.acquire(task,cargo.category());
            if(lease==null) {
                log("RESOURCE_WAIT",task.id(),"No compatible available resource; attempt "+attempt+"/3");
                try { Thread.sleep(250L*attempt); } catch(InterruptedException e){Thread.currentThread().interrupt(); fail(task,"Interrupted while waiting for resource."); finishTask();return;}
            }
        }
        if(lease==null) {
            task.forceStatus(TaskStatus.BLOCKED); task.setFailureReason("No compatible resource available after bounded retries.");
            persistState(task); log("TASK_BLOCKED",task.id(),task.failureReason()); finishTask(); return;
        }
        long allocationId = -1;
        try {
            task.forceStatus(TaskStatus.RUNNING); task.setWorkerName(Thread.currentThread().getName()); task.setActualStart(LocalDateTime.now());
            persistState(task);
            try { allocationId = allocationDao.open(task.id(),lease.id(),LocalDateTime.now(),(task.taskType().equals("INSPECT") ? 1 : task.requiredCapacity())); }
            catch(SQLException ex) { logWithoutDb("ALLOCATION_DB_WARNING",task.id(),"Resource is reserved in the simulation, but allocation history was not persisted: "+ex.getMessage()); }
            log("RESOURCE_ACQUIRED",task.id(),"Acquired "+lease.name());
            log("TASK_STARTED",task.id(),task.name());
            Thread.sleep(Math.max(250L,Math.min(1800L,task.estimatedMinutes()*100L)));
            task.forceStatus(TaskStatus.COMPLETED); task.setCompletedAt(LocalDateTime.now()); persistState(task);
            log("TASK_COMPLETED",task.id(),task.name()+" completed using "+lease.name());
            if(cargo.profile().minTemperatureC()!=null) {
                double mid=(cargo.profile().minTemperatureC()+cargo.profile().maxTemperatureC())/2.0;
                log("SIMULATED_TEMPERATURE",task.id(),"Simulated storage reading "+mid+"°C (not a real sensor)");
            }
        } catch(InterruptedException ex) {
            Thread.currentThread().interrupt(); fail(task,"Execution interrupted.");
        } finally {
            if (allocationId > 0) {
                try { allocationDao.close(allocationId,LocalDateTime.now(),task.status()==TaskStatus.COMPLETED?"COMPLETED":"RELEASED"); }
                catch(SQLException ex) { logWithoutDb("ALLOCATION_CLOSE_WARNING",task.id(),"Could not close allocation history: "+ex.getMessage()); }
            }
            resources.release(lease);
            log("RESOURCE_RELEASED",task.id(),"Released "+lease.name());
            finishTask();
        }
    }
    private void finishTask() {
        synchronized(this) {
            scheduleEligible();
        }
    }
    private void fail(WorkflowTask task,String reason) {
        task.forceStatus(TaskStatus.FAILED); task.setFailureReason(reason); persistState(task); log("TASK_FAILED",task.id(),reason);
    }
    private void persistState(WorkflowTask task) {
        try { taskDao.updateState(task.id(),task.status().name(),task.workerName(),task.failureReason()); }
        catch(SQLException ex) { logWithoutDb("DB_WRITE_WARNING",task.id(),"Task state was not persisted: "+ex.getMessage()); }
    }
    private void log(String type,Long taskId,String message) {
        ActivityEvent event=new ActivityEvent(LocalDateTime.now(),type,taskId,message,Thread.currentThread().getName());
        events.add(event); eventListener.accept(event);
        try { logDao.insert(event); } catch(SQLException ex) { /* Demo mode remains usable without a DB. */ }
    }
    private void logWithoutDb(String type,Long taskId,String message) {
        ActivityEvent event=new ActivityEvent(LocalDateTime.now(),type,taskId,message,Thread.currentThread().getName());
        events.add(event); eventListener.accept(event);
    }
    public boolean isRunning(){return running;}
    @Override public void close(){workers.shutdownNow();}
}
