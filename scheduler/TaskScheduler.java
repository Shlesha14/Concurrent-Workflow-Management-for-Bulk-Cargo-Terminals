package edu.portflow.scheduler;

import edu.portflow.model.*;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

public final class TaskScheduler {
    public List<WorkflowTask> order(List<WorkflowTask> tasks, SchedulingPolicy policy, LocalDateTime now) {
        Comparator<WorkflowTask> comparator = switch (policy) {
            case FCFS -> Comparator.comparing(WorkflowTask::createdAt).thenComparingLong(WorkflowTask::id);
            case PRIORITY -> Comparator.comparingInt(WorkflowTask::priority).reversed()
                    .thenComparing(WorkflowTask::createdAt).thenComparingLong(WorkflowTask::id);
            case EARLIEST_DEADLINE -> Comparator.comparing(
                    (WorkflowTask t) -> t.deadline() == null ? LocalDateTime.MAX : t.deadline())
                    .thenComparing(WorkflowTask::createdAt).thenComparingLong(WorkflowTask::id);
            case PERISHABILITY_AWARE -> Comparator.comparing(
                    (WorkflowTask t) -> t.expiry() == null ? LocalDateTime.MAX : t.expiry())
                    .thenComparing(Comparator.comparingInt(WorkflowTask::priority).reversed())
                    .thenComparing(WorkflowTask::createdAt);
        };
        return tasks.stream().sorted(comparator).toList();
    }
}
