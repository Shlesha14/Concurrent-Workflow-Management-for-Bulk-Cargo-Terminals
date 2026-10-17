package com.portflow.workflow;

import java.util.*;

/**
 * Validates a workflow graph BEFORE any task runs (OS: deadlock prevention - a dependency cycle would wait forever).
 * Uses Kahn's algorithm: O(V + E). Nodes left over after removing all zero-indegree nodes lie on, or behind, a cycle.
 */
public final class DagValidator {
    private DagValidator() {}

    /** @param prerequisites node -> nodes it depends on. Returns human-readable errors; empty list = valid DAG. */
    public static <T> List<String> validate(Collection<T> nodes, Map<T, ? extends Collection<T>> prerequisites) {
        List<String> errors = new ArrayList<>();
        Set<T> known = new LinkedHashSet<>(nodes);
        for (var e : prerequisites.entrySet()) {
            if (!known.contains(e.getKey())) errors.add("Unknown task " + e.getKey());
            for (T d : e.getValue()) {
                if (!known.contains(d)) errors.add("Task " + e.getKey() + " depends on unknown task " + d);
                if (d.equals(e.getKey())) errors.add("Task " + d + " depends on itself");
            }
        }
        if (!errors.isEmpty()) return errors;
        List<T> order = topologicalOrder(known, prerequisites);
        if (order.size() != known.size()) {
            List<T> stuck = new ArrayList<>(known);
            stuck.removeAll(order);
            errors.add("Dependency cycle detected involving tasks " + stuck);
        }
        return errors;
    }

    private static <T> Collection<T> deps(Map<T, ? extends Collection<T>> m, T n) { Collection<T> c = m.get(n); return c == null ? List.of() : c; }

    /** Stable topological order (ties keep the input order). Shorter than nodes.size() if there is a cycle. */
    public static <T> List<T> topologicalOrder(Collection<T> nodes, Map<T, ? extends Collection<T>> prerequisites) {
        Map<T, Integer> indeg = new LinkedHashMap<>();
        Map<T, List<T>> dependants = new HashMap<>();
        for (T n : nodes) indeg.put(n, 0);
        for (T n : nodes) for (T d : deps(prerequisites, n)) {
            if (!indeg.containsKey(d)) continue;
            indeg.merge(n, 1, Integer::sum);
            dependants.computeIfAbsent(d, k -> new ArrayList<>()).add(n);
        }
        Deque<T> queue = new ArrayDeque<>();
        indeg.forEach((k, v) -> { if (v == 0) queue.add(k); });
        List<T> order = new ArrayList<>();
        while (!queue.isEmpty()) {
            T n = queue.poll();
            order.add(n);
            for (T m : dependants.getOrDefault(n, List.of())) if (indeg.merge(m, -1, Integer::sum) == 0) queue.add(m);
        }
        return order;
    }
}
