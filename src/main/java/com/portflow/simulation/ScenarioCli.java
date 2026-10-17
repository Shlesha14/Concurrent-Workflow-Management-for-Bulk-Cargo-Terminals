package com.portflow.simulation;

import com.portflow.config.AppConfig;
import com.portflow.config.PortFlowContext;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Runs demonstration scenarios without the GUI:  java -cp ... com.portflow.simulation.ScenarioCli [A|B|...|H|ALL]
 * WARNING: each scenario RESETS cargo, tasks, allocations, history and resources in the configured database.
 */
public final class ScenarioCli {
    private ScenarioCli() {}
    public static void main(String[] args) throws Exception {
        String which = args.length == 0 ? "ALL" : args[0];
        try (PortFlowContext x = new PortFlowContext(AppConfig.load(), SimClock.scaled(LocalDateTime.now().withNano(0), ScenarioRunner.SCENARIO_SPEED))) {
            String problem = x.db.checkConnection();
            if (problem != null) { System.err.println(problem); System.exit(2); }
            ScenarioRunner r = new ScenarioRunner(x, 300_000);
            List<ScenarioResult> rs = which.equalsIgnoreCase("ALL") ? r.runAll() : List.of(r.run(which));
            rs.forEach(s -> System.out.println(s.report()));
            boolean ok = rs.stream().allMatch(ScenarioResult::passed);
            System.out.println(ok ? "ALL SELECTED SCENARIOS PASSED" : "SOME SCENARIOS FAILED");
            System.exit(ok ? 0 : 1);
        }
    }
}
