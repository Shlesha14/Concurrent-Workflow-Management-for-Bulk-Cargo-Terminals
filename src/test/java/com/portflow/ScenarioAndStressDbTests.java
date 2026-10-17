package com.portflow;

import com.portflow.config.PortFlowContext;
import com.portflow.monitoring.MetricsService.RunMetrics;
import com.portflow.simulation.*;
import org.junit.jupiter.api.*;

import static com.portflow.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** The eight demonstration scenarios and a small stress run, executed as automated tests. */
class ScenarioAndStressDbTests {
    PortFlowContext ctx;
    @BeforeEach void setUp() throws Exception { ctx = fresh(SimClock.scaled(T0, 3000)); }
    @AfterEach void tearDown() { if (ctx != null) ctx.close(); }

    private void check(String id) throws Exception {
        ScenarioResult r = new ScenarioRunner(ctx, 60000).run(id);
        System.out.println(r.report());
        assertTrue(r.passed(), r.report());
    }
    @Test void scenarioA() throws Exception { check("A"); }
    @Test void scenarioB() throws Exception { check("B"); }
    @Test void scenarioC() throws Exception { check("C"); }
    @Test void scenarioD() throws Exception { check("D"); }
    @Test void scenarioE() throws Exception { check("E"); }
    @Test void scenarioF() throws Exception { check("F"); }
    @Test void scenarioG() throws Exception { check("G"); }
    @Test void scenarioH() throws Exception { check("H"); }

    @Test void stressRunCompletesEverythingWithoutOverAllocation() throws Exception {
        RunMetrics m = StressTest.run(ctx, 20, 2, 2, 2, 4, "PERISHABILITY", 120000);
        System.out.println(RunMetrics.header() + "\n" + m.toCsv());
        assertEquals(60, m.tasks()); assertEquals(60, m.completed());
        assertTrue(m.makespanMin() > 0 && m.throughputPerHour() > 0 && m.peakConcurrency() >= 2);
        assertEquals(0, count(ctx, "SELECT COUNT(*) FROM allocation WHERE status IN ('ACTIVE','HELD')"));
        assertEquals(0, count(ctx, "SELECT COUNT(*) FROM allocation a JOIN allocation b ON a.resource_id=b.resource_id AND a.allocation_id<b.allocation_id JOIN resource r ON r.resource_id=a.resource_id WHERE r.capacity=1 AND a.start_time<b.end_time AND b.start_time<a.end_time"));
    }
}
