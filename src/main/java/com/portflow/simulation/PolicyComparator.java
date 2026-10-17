package com.portflow.simulation;

import com.portflow.config.PortFlowContext;
import com.portflow.config.SchemaInstaller;
import com.portflow.monitoring.MetricsService.RunMetrics;

import java.util.ArrayList;
import java.util.List;

/** Runs the SAME scenario setup once per scheduling policy (database reset in between) and collects real metrics. */
public final class PolicyComparator {
    private PolicyComparator() {}

    @FunctionalInterface public interface Setup { void apply(PortFlowContext ctx) throws Exception; }

    public static List<RunMetrics> compare(PortFlowContext ctx, Setup setup, List<String> policies, long timeoutMsPerRun) throws Exception {
        List<RunMetrics> out = new ArrayList<>();
        PortFlowContext.class.getName();
        String previous = ctx.policy().name();
        try {
            for (String p : policies) {
                ctx.engine.stop();
                SchemaInstaller.resetOperationalData(ctx.db, true);
                ctx.resourceManager.resetStats();
                setup.apply(ctx);                       // identical inputs for every policy
                ctx.setPolicy(p);
                ctx.engine.start();
                boolean idle = ctx.engine.awaitIdle(timeoutMsPerRun);
                ctx.engine.stop();
                RunMetrics m = ctx.metrics.compute(p + (idle ? "" : " (TIMEOUT)"), ctx.engine.maxConcurrentObserved());
                out.add(m);
            }
        } finally { ctx.setPolicy(previous); ctx.engine.stop(); }
        return out;
    }
}
