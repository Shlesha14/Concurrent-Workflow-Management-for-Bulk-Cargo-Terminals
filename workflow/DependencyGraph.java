package edu.portflow.workflow;

import edu.portflow.model.WorkflowTask;
import java.util.*;

public final class DependencyGraph {
    public void validate(Collection<WorkflowTask> tasks) {
        Map<Long, WorkflowTask> byId = new HashMap<>();
        for (WorkflowTask task : tasks) {
            if (byId.put(task.id(), task) != null) throw new IllegalArgumentException("Duplicate task ID: " + task.id());
        }
        for (WorkflowTask task : tasks) {
            for (Long dependency : task.dependencies()) {
                if (!byId.containsKey(dependency)) throw new IllegalArgumentException(
                        "Task " + task.id() + " depends on missing task " + dependency);
                if (dependency.equals(task.id())) throw new IllegalArgumentException("Task cannot depend on itself.");
            }
        }
        Map<Long, Integer> indegree = new HashMap<>();
        Map<Long, List<Long>> children = new HashMap<>();
        for (WorkflowTask task : tasks) {
            indegree.put(task.id(), task.dependencies().size());
            for (Long dependency : task.dependencies())
                children.computeIfAbsent(dependency, k -> new ArrayList<>()).add(task.id());
        }
        Deque<Long> queue = new ArrayDeque<>();
        indegree.forEach((id, degree) -> { if (degree == 0) queue.add(id); });
        int visited = 0;
        while (!queue.isEmpty()) {
            long id = queue.removeFirst(); visited++;
            for (long child : children.getOrDefault(id, List.of())) {
                int next = indegree.merge(child, -1, Integer::sum);
                if (next == 0) queue.addLast(child);
            }
        }
        if (visited != tasks.size()) throw new IllegalArgumentException("Workflow contains a dependency cycle.");
    }

    public boolean prerequisitesCompleted(WorkflowTask task, Collection<WorkflowTask> all) {
        Map<Long, WorkflowTask> map = new HashMap<>();
        all.forEach(t -> map.put(t.id(), t));
        return task.dependencies().stream().allMatch(id -> map.containsKey(id)
                && map.get(id).status() == edu.portflow.model.TaskStatus.COMPLETED);
    }
}
