package com.portflow.simulation;

import com.portflow.monitoring.MetricsService.RunMetrics;

import java.util.List;

/** Outcome of a demonstration scenario. "observed" lines are computed from the database after the run, never scripted. */
public record ScenarioResult(String id, String title, String expected, List<String> observed, boolean passed, List<RunMetrics> metrics) {
    public String report() {
        StringBuilder sb = new StringBuilder("Scenario ").append(id).append(" - ").append(title).append("\nExpected: ").append(expected).append("\nObserved:\n");
        observed.forEach(l -> sb.append("  ").append(l).append('\n'));
        if (metrics != null) { sb.append("  metrics:\n    ").append(RunMetrics.header()).append('\n'); metrics.forEach(m -> sb.append("    ").append(m.toCsv()).append('\n')); }
        return sb.append("RESULT: ").append(passed ? "PASS" : "FAIL").append('\n').toString();
    }
}
