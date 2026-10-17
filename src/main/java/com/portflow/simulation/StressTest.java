package com.portflow.simulation;

import com.portflow.config.AppConfig;
import com.portflow.config.PortFlowContext;
import com.portflow.config.SchemaInstaller;
import com.portflow.monitoring.MetricsService.RunMetrics;
import com.portflow.service.Settings;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

/**
 * Reproducible load test. Creates N general-cargo workflows against a configurable terminal and measures what really happened.
 * CLI:  java -cp portflow.jar com.portflow.simulation.StressTest [cargo=40] [cranes=2] [forklifts=2] [trucks=2] [workers=4] [policy=PERISHABILITY] [speed=6000]
 * WARNING: resets operational data (cargo, tasks, allocations, resources) in the configured database.
 */
public final class StressTest {
    private StressTest() {}

    public static RunMetrics run(PortFlowContext x, int cargoCount, int cranes, int forklifts, int trucks, int workers, String policy, long timeoutMs) throws Exception {
        x.engine.stop();
        x.clock.setSpeed(Math.max(x.clock.speed(), 3000));
        SchemaInstaller.resetOperationalData(x.db, true);
        x.resourceManager.resetStats();
        x.db.inTransaction(c -> { Settings.save(c, "engine.workers", String.valueOf(workers)); return null; });
        DemoData.add(x, "CRANE-S", "CRANE", cranes, "Test", null, null);
        DemoData.add(x, "FORK-S", "FORKLIFT", forklifts, "Test", null, null);
        DemoData.add(x, "TRUCK-S", "TRUCK", trucks, "Test", null, null);
        DemoData.add(x, "DRY-S", "DRY_STORE", 1_000_000, "Test", null, null);
        for (int i = 0; i < cargoCount; i++) {
            var r = x.cargo.register(new com.portflow.model.Cargo(null, "Stress lot " + i, "GENERAL", BigDecimal.valueOf(10 + (i % 5) * 5), "TONNE", x.now(), null, null, 1 + (i % 5),
                x.now().plusMinutes(240 + (i % 7) * 30), null, null, "REGISTERED", null, null));
            x.workflows.createWorkflow(r.cargoId());
        }
        x.setPolicy(policy);
        long t0 = System.currentTimeMillis();
        x.engine.start();
        boolean idle = x.engine.awaitIdle(timeoutMs);
        x.engine.stop();
        RunMetrics m = x.metrics.compute(policy + " n=" + cargoCount + " cr=" + cranes + " fk=" + forklifts + " tr=" + trucks + " w=" + workers + (idle ? "" : " TIMEOUT"), x.engine.maxConcurrentObserved());
        System.out.println("wall-clock " + (System.currentTimeMillis() - t0) + " ms");
        return m;
    }

    public static void main(String[] args) throws Exception {
        int[] v = {40, 2, 2, 2, 4}; String policy = "PERISHABILITY"; double speed = 6000;
        for (String a : args) {
            String[] kv = a.split("=");
            switch (kv[0]) { case "cargo" -> v[0] = Integer.parseInt(kv[1]); case "cranes" -> v[1] = Integer.parseInt(kv[1]); case "forklifts" -> v[2] = Integer.parseInt(kv[1]);
                case "trucks" -> v[3] = Integer.parseInt(kv[1]); case "workers" -> v[4] = Integer.parseInt(kv[1]); case "policy" -> policy = kv[1]; case "speed" -> speed = Double.parseDouble(kv[1]);
                default -> throw new IllegalArgumentException("Unknown argument " + a); }
        }
        try (PortFlowContext x = new PortFlowContext(AppConfig.load(), SimClock.scaled(LocalDateTime.of(2026, 1, 1, 8, 0), speed))) {
            String problem = x.db.checkConnection();
            if (problem != null) { System.err.println(problem); System.exit(2); }
            RunMetrics m = run(x, v[0], v[1], v[2], v[3], v[4], policy, 600_000);
            System.out.println(RunMetrics.header()); System.out.println(m.toCsv());
            Path out = Path.of("reports"); Files.createDirectories(out);
            Path f = out.resolve("stress_" + System.currentTimeMillis() + ".csv");
            Files.writeString(f, RunMetrics.header() + "\n" + m.toCsv() + "\n");
            System.out.println("written " + f);
        }
    }
}
