package com.portflow;

import com.portflow.config.AppConfig;
import com.portflow.config.PortFlowContext;
import com.portflow.config.SchemaInstaller;
import com.portflow.model.Cargo;
import com.portflow.service.Settings;
import com.portflow.simulation.SimClock;
import com.portflow.util.Jdbc;
import org.junit.jupiter.api.Assumptions;

import java.math.BigDecimal;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.List;

/** Shared fixtures. DB tests run against a scratch schema (portflow_test) that is DROPPED and recreated once per JVM. */
public final class TestSupport {
    private TestSupport() {}
    public static final LocalDateTime T0 = LocalDateTime.of(2026, 1, 1, 8, 0);
    public static final String URL = System.getProperty("portflow.test.url", "jdbc:mysql://localhost:3306/portflow_test?useSSL=false&allowPublicKeyRetrieval=true");
    public static final String USER = System.getProperty("portflow.test.user", "root");
    public static final String PASS = System.getProperty("portflow.test.password", "");
    private static boolean installed;

    public static AppConfig cfg() { return new AppConfig(URL, USER, PASS, 24, 8000); }

    /** Fresh context on an empty-but-installed schema. Skips (not fails) the test when no MySQL is reachable. */
    public static synchronized PortFlowContext fresh(SimClock clock) throws Exception {
        PortFlowContext ctx = new PortFlowContext(cfg(), clock);
        String problem = ctx.db.checkConnection();
        Assumptions.assumeTrue(problem == null, "MySQL not reachable for DB tests: " + problem);
        if (!installed) { SchemaInstaller.install(ctx.db, true); installed = true; }
        SchemaInstaller.resetOperationalData(ctx.db, true);
        ctx.db.inTransaction(c -> { for (String t : List.of("profile_step_req", "profile_step_dep", "profile_step")) Jdbc.update(c, "DELETE FROM " + t + " WHERE profile_code IN (SELECT profile_code FROM handling_profile WHERE category='TEST')");
            return Jdbc.update(c, "DELETE FROM handling_profile WHERE category='TEST'"); });
        ctx.db.inTransaction(c -> { Settings.save(c, "engine.poll_ms", "20"); Settings.save(c, "engine.workers", "4"); Settings.save(c, "engine.backoff_ms", "20");
            Settings.save(c, "engine.max_retries", "2"); Settings.save(c, "sim.excursion_probability", "0"); return null; });
        return ctx;
    }

    /** 1 simulated minute = 10 ms of real time (speed 6000 sim-seconds per second). */
    public static SimClock fastClock() { return SimClock.scaled(T0, 6000); }

    public static int res(PortFlowContext ctx, String name, String type, double cap, String... caps) throws Exception {
        try { return ctx.resources.add(name, type, BigDecimal.valueOf(cap), "Test", null, null, List.of(caps)); }
        catch (com.portflow.service.ServiceException e) { throw new IllegalStateException(e); }
    }
    public static int coldRes(PortFlowContext ctx, String name, String type, double cap, double tMin, double tMax) throws Exception {
        try { return ctx.resources.add(name, type, BigDecimal.valueOf(cap), "Test", tMin, tMax, List.of()); }
        catch (com.portflow.service.ServiceException e) { throw new IllegalStateException(e); }
    }

    public static void sql(PortFlowContext ctx, String sql, Object... p) throws Exception { ctx.db.inTransaction(c -> Jdbc.update(c, sql, p)); }

    /** Defines a throw-away profile. steps: {code, type, minutes, dependsOn(comma, or ""), "RESOURCE_TYPE[:CAPABILITY[:HOLD_UNTIL]]|..."} */
    public static void profile(PortFlowContext ctx, String code, int maxWait, String[]... steps) throws Exception {
        ctx.db.inTransaction(c -> {
            Jdbc.update(c, "INSERT INTO handling_profile (profile_code, display_name, category, max_wait_min, description) VALUES (?,?,?,?,?)", code, code, "TEST", maxWait, "test");
            for (String[] s : steps) Jdbc.update(c, "INSERT INTO profile_step (profile_code, step_code, task_type, base_min, per_unit_min) VALUES (?,?,?,?,0)", code, s[0], s[1], Integer.parseInt(s[2]));
            for (String[] s : steps) {
                if (!s[3].isBlank()) for (String d : s[3].split(",")) Jdbc.update(c, "INSERT INTO profile_step_dep VALUES (?,?,?)", code, s[0], d);
                if (!s[4].isBlank()) for (String r : s[4].split("\\|")) {
                    String[] f = r.split(":", -1);
                    boolean qty = f[0].endsWith("_STORE") || f[0].equals("COLD_ROOM") || f[0].equals("STOCKYARD");
                    Jdbc.update(c, "INSERT INTO profile_step_req (profile_code, step_code, resource_type, capability, amount_mode, hold_until_step) VALUES (?,?,?,?,?,?)",
                        code, s[0], f[0], f.length > 1 && !f[1].isEmpty() ? f[1] : null, qty ? "CARGO_QTY" : "SLOT", f.length > 2 && !f[2].isEmpty() ? f[2] : null);
                }
            }
            return null;
        });
    }

    public static Cargo draft(String name, String profile, double qty, int prio, LocalDateTime arrival, LocalDateTime mfg, LocalDateTime expiry, LocalDateTime deadline) {
        return new Cargo(null, name, profile, BigDecimal.valueOf(qty), "TONNE", arrival, mfg, expiry, prio, deadline, null, null, "REGISTERED", null, null);
    }

    public static long cargoWithWorkflow(PortFlowContext ctx, String name, String profile, double qty, int prio, LocalDateTime deadline) throws Exception {
        var reg = ctx.cargo.register(draft(name, profile, qty, prio, ctx.now(), null, null, deadline));
        if (reg.cargoId() == null) throw new IllegalStateException(reg.message() + reg.issues());
        ctx.workflows.createWorkflow(reg.cargoId());
        return reg.cargoId();
    }

    public static String stateOf(PortFlowContext ctx, long cargoId, String step) throws Exception {
        return ctx.db.read(c -> Jdbc.one(c, "SELECT state FROM task WHERE cargo_id=? AND step_code=?", rs -> rs.getString(1), cargoId, step).orElse("?"));
    }
    public static int count(PortFlowContext ctx, String sql, Object... p) throws Exception { return ctx.db.read(c -> Jdbc.one(c, sql, rs -> rs.getInt(1), p).orElse(0)); }
}
