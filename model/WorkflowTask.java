package edu.portflow.model;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

public class WorkflowTask {
    private final long id;
    private final long cargoId;
    private final String name;
    private final String taskType;
    private final int priority;
    private final int estimatedMinutes;
    private final LocalDateTime createdAt;
    private final LocalDateTime deadline;
    private final LocalDateTime expiry;
    private final String requiredResourceType;
    private final String requiredStorageClass;
    private final double requiredCapacity;
    private final Set<Long> dependencies;
    private final AtomicReference<TaskStatus> status = new AtomicReference<>(TaskStatus.CREATED);
    private volatile String workerName;
    private volatile String failureReason;
    private volatile LocalDateTime actualStart;
    private volatile LocalDateTime completedAt;

    public WorkflowTask(long id, long cargoId, String name, String taskType, int priority, int estimatedMinutes,
                        LocalDateTime createdAt, LocalDateTime deadline, LocalDateTime expiry,
                        String requiredResourceType, String requiredStorageClass, double requiredCapacity,
                        Set<Long> dependencies) {
        if (estimatedMinutes < 1) throw new IllegalArgumentException("Estimated duration must be at least one minute.");
        this.id=id; this.cargoId=cargoId; this.name=name; this.taskType=taskType; this.priority=priority;
        this.estimatedMinutes=estimatedMinutes; this.createdAt=createdAt; this.deadline=deadline; this.expiry=expiry;
        this.requiredResourceType=requiredResourceType; this.requiredStorageClass=requiredStorageClass;
        this.requiredCapacity=requiredCapacity; this.dependencies=Set.copyOf(dependencies);
    }
    public long id(){return id;} public long cargoId(){return cargoId;} public String name(){return name;}
    public String taskType(){return taskType;} public int priority(){return priority;}
    public int estimatedMinutes(){return estimatedMinutes;} public LocalDateTime createdAt(){return createdAt;}
    public LocalDateTime deadline(){return deadline;} public LocalDateTime expiry(){return expiry;}
    public String requiredResourceType(){return requiredResourceType;}
    public String requiredStorageClass(){return requiredStorageClass;} public double requiredCapacity(){return requiredCapacity;}
    public Set<Long> dependencies(){return dependencies;} public TaskStatus status(){return status.get();}
    public boolean transition(TaskStatus expected, TaskStatus next) {
        if (!isAllowed(expected, next)) throw new IllegalStateException("Invalid task transition: " + expected + " -> " + next);
        return status.compareAndSet(expected,next);
    }
    public synchronized void forceStatus(TaskStatus next) {
        TaskStatus current = status.get();
        if (!isAllowed(current, next)) throw new IllegalStateException("Invalid task transition: " + current + " -> " + next);
        status.set(next);
    }
    private boolean isAllowed(TaskStatus from, TaskStatus to) {
        if (from == to) return true;
        return switch (from) {
            case CREATED -> to == TaskStatus.VALIDATED || to == TaskStatus.READY || to == TaskStatus.BLOCKED || to == TaskStatus.FAILED || to == TaskStatus.CANCELLED;
            case VALIDATED -> to == TaskStatus.READY || to == TaskStatus.BLOCKED || to == TaskStatus.CANCELLED;
            case READY -> to == TaskStatus.WAITING_FOR_RESOURCE || to == TaskStatus.RUNNING || to == TaskStatus.BLOCKED || to == TaskStatus.FAILED || to == TaskStatus.EXPIRED || to == TaskStatus.CANCELLED;
            case WAITING_FOR_RESOURCE -> to == TaskStatus.RUNNING || to == TaskStatus.BLOCKED || to == TaskStatus.FAILED || to == TaskStatus.CANCELLED;
            case RUNNING -> to == TaskStatus.COMPLETED || to == TaskStatus.FAILED || to == TaskStatus.BLOCKED;
            case BLOCKED -> to == TaskStatus.READY || to == TaskStatus.CANCELLED || to == TaskStatus.FAILED || to == TaskStatus.EXPIRED;
            case COMPLETED, FAILED, CANCELLED, EXPIRED -> false;
        };
    }
    public String workerName(){return workerName;} public void setWorkerName(String v){workerName=v;}
    public String failureReason(){return failureReason;} public void setFailureReason(String v){failureReason=v;}
    public LocalDateTime actualStart(){return actualStart;} public void setActualStart(LocalDateTime v){actualStart=v;}
    public LocalDateTime completedAt(){return completedAt;} public void setCompletedAt(LocalDateTime v){completedAt=v;}
}
