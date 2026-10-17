package com.portflow;

import com.portflow.config.AppConfig;
import com.portflow.model.*;
import com.portflow.resource.AllocationResult;
import com.portflow.resource.ResourceSelector;
import com.portflow.scheduler.*;
import com.portflow.service.CargoValidator;
import com.portflow.simulation.SimClock;
import com.portflow.util.Database;
import com.portflow.util.SqlScriptRunner;
import com.portflow.workflow.DagValidator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

import static com.portflow.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Tests that need no database. */
class PureLogicTests {
    private static final CargoProfile VEG = new CargoProfile("LEAFY_VEG", "Leafy vegetables", "FRESH_PRODUCE", true, 0.0, 4.0, 90, "REJECT", "", "", List.of());
    private static final CargoProfile COAL = new CargoProfile("COAL", "Coal", "DRY_BULK", false, null, null, 720, "REJECT", "", "", List.of());

    // ---- Test 2 / 3 / 4: validation
    @Test void invalidQuantityAndDatesAreReportedAsInvalid() {
        Cargo g = draft("Spinach", "LEAFY_VEG", -5, 3, T0, T0.plusHours(5), T0.plusHours(2), null);
        List<CargoValidator.Issue> issues = CargoValidator.validate(g, VEG, T0);
        assertTrue(issues.stream().anyMatch(i -> i.field().equals("quantity") && i.kind() == CargoValidator.Kind.INVALID));
        assertTrue(issues.stream().anyMatch(i -> i.field().equals("expiry") && i.kind() == CargoValidator.Kind.INVALID), "expiry before manufacturing must be INVALID");
    }
    @Test void missingExpiryForPerishableIsMissingNotInvalid() {
        Cargo g = draft("Spinach", "LEAFY_VEG", 10, 3, T0, null, null, null);
        List<CargoValidator.Issue> issues = CargoValidator.validate(g, VEG, T0);
        assertTrue(issues.stream().anyMatch(i -> i.field().equals("expiry") && i.kind() == CargoValidator.Kind.MISSING));
    }
    @Test void coalNeedsNoExpiry() { assertTrue(CargoValidator.validate(draft("Coal", "COAL", 500, 3, T0, null, null, null), COAL, T0).isEmpty()); }
    @Test void alreadyExpiredIsDetectedByPolicyNotValidation() {
        Cargo g = draft("Old milk", "LEAFY_VEG", 10, 3, T0.minusDays(2), T0.minusDays(3), T0.minusDays(1), null);
        assertTrue(CargoValidator.validate(g, VEG, T0).isEmpty());
        assertTrue(CargoValidator.isExpired(g, T0));
    }
    @Test void contradictoryDeadlineAfterExpiry() {
        Cargo g = draft("X", "LEAFY_VEG", 10, 3, T0, T0.minusHours(1), T0.plusHours(2), T0.plusHours(5));
        assertTrue(CargoValidator.validate(g, VEG, T0).stream().anyMatch(i -> i.field().equals("deadline")));
    }

    // ---- Test 5 / 6: DAG validation
    @Test void unknownDependencyRejected() {
        var errs = DagValidator.validate(List.of(1, 2), Map.of(2, List.of(99)));
        assertFalse(errs.isEmpty()); assertTrue(errs.get(0).contains("unknown"));
    }
    @Test void cycleRejected() {
        var errs = DagValidator.validate(List.of(1, 2, 3), Map.of(1, List.of(3), 2, List.of(1), 3, List.of(2)));
        assertEquals(1, errs.size()); assertTrue(errs.get(0).contains("cycle"));
    }
    @Test void validDagAcceptedAndOrdered() {
        Map<String, List<String>> m = Map.of("B", List.of("A"), "C", List.of("A"), "D", List.of("B", "C"));
        assertTrue(DagValidator.validate(List.of("A", "B", "C", "D"), m).isEmpty());
        List<String> order = DagValidator.topologicalOrder(List.of("A", "B", "C", "D"), m);
        assertTrue(order.indexOf("A") < order.indexOf("B") && order.indexOf("C") < order.indexOf("D"));
    }
    @Test void selfDependencyRejected() { assertFalse(DagValidator.validate(List.of(1), Map.of(1, List.of(1))).isEmpty()); }

    // ---- state machine
    @Test void onlyLegalTransitionsAllowed() {
        assertTrue(TaskState.READY.canGoTo(TaskState.RUNNING));
        assertFalse(TaskState.COMPLETED.canGoTo(TaskState.RUNNING));
        assertFalse(TaskState.WAITING_FOR_RESOURCE.canGoTo(TaskState.COMPLETED), "a task must run before it completes");
        assertFalse(TaskState.BLOCKED.canGoTo(TaskState.RUNNING));
        assertTrue(TaskState.FAILED.canGoTo(TaskState.READY));
    }

    // ---- Test 11: incompatible storage; temperature ranges per profile
    private static Resource store(String name, String type, double cap, Double tMin, Double tMax, String... caps) {
        return new Resource(name.hashCode(), name, type, BigDecimal.valueOf(cap), "AVAILABLE", "x", tMin, tMax, Set.of(caps), "QUANTITY");
    }
    private static TaskRequirement req(String type, double amount, String cap) { return new TaskRequirement(1, 1, type, cap, BigDecimal.valueOf(amount), null); }

    @Test void frozenRoomIsIncompatibleWithLeafyVegetables() {
        Resource frozen = store("FRZ", "COLD_ROOM", 100, -24.0, -20.0);
        assertFalse(ResourceSelector.structurallyCompatible(frozen, req("COLD_ROOM", 10, null), VEG));
        AllocationResult r = ResourceSelector.explainFailure(List.of(frozen), req("COLD_ROOM", 10, null), VEG);
        assertEquals(AllocationResult.Outcome.BLOCKED, r.outcome());
        assertEquals(BlockReason.NO_COMPATIBLE_RESOURCE, r.reason());
    }
    @Test void roomWiderThanProfileRangeIsRejected() {
        assertFalse(ResourceSelector.structurallyCompatible(store("W", "COLD_ROOM", 100, -5.0, 10.0), req("COLD_ROOM", 10, null), VEG));
        assertTrue(ResourceSelector.structurallyCompatible(store("OK", "COLD_ROOM", 100, 1.0, 3.0), req("COLD_ROOM", 10, null), VEG));
    }
    @Test void bestFitPicksTightestStorage() {
        Resource big = new Resource(1, "BIG", "DRY_STORE", BigDecimal.valueOf(700), "AVAILABLE", "x", null, null, Set.of(), "QUANTITY");
        Resource small = new Resource(2, "SMALL", "DRY_STORE", BigDecimal.valueOf(300), "AVAILABLE", "x", null, null, Set.of(), "QUANTITY");
        Resource pick = ResourceSelector.choose(List.of(big, small), Map.of(), Map.of(), req("DRY_STORE", 250, null), COAL).orElseThrow();
        assertEquals("SMALL", pick.name());
        pick = ResourceSelector.choose(List.of(big, small), Map.of(), Map.of(), req("DRY_STORE", 400, null), COAL).orElseThrow();
        assertEquals("BIG", pick.name(), "300 does not fit 400");
    }
    @Test void capabilityRequiredForFragileEquipment() {
        Resource plain = new Resource(1, "CR1", "CRANE", BigDecimal.ONE, "AVAILABLE", "x", null, null, Set.of(), "SLOTS");
        assertFalse(ResourceSelector.structurallyCompatible(plain, req("CRANE", 1, "FRAGILE_HANDLING"), COAL));
    }
    @Test void unavailableResourceReportedDifferentlyFromMissing() {
        Resource down = new Resource(1, "CR1", "CRANE", BigDecimal.ONE, "MAINTENANCE", "x", null, null, Set.of(), "SLOTS");
        assertEquals(BlockReason.RESOURCE_UNAVAILABLE, ResourceSelector.explainFailure(List.of(down), req("CRANE", 1, null), COAL).reason());
        assertEquals(BlockReason.NO_COMPATIBLE_RESOURCE, ResourceSelector.explainFailure(List.of(), req("CRANE", 1, null), COAL).reason());
    }
    @Test void fullResourceIsContentionNotBlocked() {
        Resource r = new Resource(1, "DRY", "DRY_STORE", BigDecimal.valueOf(100), "AVAILABLE", "x", null, null, Set.of(), "QUANTITY");
        assertEquals(AllocationResult.Outcome.WAIT, ResourceSelector.explainFailure(List.of(r), req("DRY_STORE", 50, null), COAL).outcome());
        assertEquals(BlockReason.CAPACITY_EXCEEDS_MAX, ResourceSelector.explainFailure(List.of(r), req("DRY_STORE", 500, null), COAL).reason());
    }

    // ---- policies (Test 22 ordering part)
    private static Candidate cand(long id, int prio, int readyMin, Integer deadlineMin, Integer expiryMin, int dur) {
        return new Candidate(id, id, id, "S", prio, T0.plusMinutes(readyMin), deadlineMin == null ? null : T0.plusMinutes(deadlineMin), expiryMin == null ? null : T0.plusMinutes(expiryMin), dur, 600);
    }
    private static List<Long> ids(List<Candidate> l) { return l.stream().map(Candidate::taskId).toList(); }

    @Test void policiesOrderTheSameInputDifferently() {
        List<Candidate> in = List.of(cand(1, 1, 0, 500, null, 10), cand(2, 5, 5, 400, null, 10), cand(3, 3, 2, 60, null, 10));
        SchedulingContext ctx = new SchedulingContext(T0.plusMinutes(10), .3, .4, .2, .1, 480);
        assertEquals(List.of(1L, 3L, 2L), ids(new FcfsPolicy().order(in, ctx)));
        assertEquals(List.of(2L, 3L, 1L), ids(new PriorityPolicy().order(in, ctx)));
        assertEquals(List.of(3L, 2L, 1L), ids(new EarliestDeadlinePolicy().order(in, ctx)));
    }
    @Test void perishabilityPolicyPrefersCargoClosestToExpiry() {
        List<Candidate> in = List.of(cand(1, 3, 0, null, 600, 10), cand(2, 3, 0, null, 40, 10));
        assertEquals(2L, new PerishabilityAwarePolicy().order(in, new SchedulingContext(T0, .3, .4, .2, .1, 480)).get(0).taskId());
    }
    @Test void policiesAreDeterministicOnTies() {
        List<Candidate> in = List.of(cand(2, 3, 0, null, null, 10), cand(1, 3, 0, null, null, 10));
        for (SchedulingPolicy p : PolicyRegistry.all()) assertEquals(List.of(1L, 2L), ids(p.order(in, SchedulingContext.of(T0))), p.name());
    }
    @Test void scoreIsBoundedAndIncreasesWithUrgency() {
        SchedulingContext ctx = new SchedulingContext(T0, .3, .4, .2, .1, 480);
        double relaxed = PerishabilityAwarePolicy.score(cand(1, 3, 0, null, 2000, 10), ctx), urgent = PerishabilityAwarePolicy.score(cand(2, 3, 0, null, 15, 10), ctx);
        assertTrue(urgent > relaxed); assertTrue(urgent <= 1.0 && relaxed >= 0.0);
    }

    // ---- misc infrastructure
    @Test void simClockIsDeterministicWhenFixed() {
        SimClock c = SimClock.fixed(T0);
        assertEquals(T0, c.now()); c.advance(java.time.Duration.ofHours(3)); assertEquals(T0.plusHours(3), c.now());
    }
    @Test void scriptSplitterHandlesDelimiterBlocks() {
        var parts = SqlScriptRunner.split("SELECT 1;\nDELIMITER $$\nCREATE TRIGGER t BEGIN\n SELECT 2;\nEND$$\nDELIMITER ;\nSELECT 3;");
        assertEquals(3, parts.size()); assertTrue(parts.get(1).contains("SELECT 2;"));
    }

    // ---- Test 24: connection failure is reported in plain language
    @Test void unreachableDatabaseGivesFriendlyMessage() {
        String scheme = TestSupport.URL.startsWith("jdbc:mariadb") ? "jdbc:mariadb" : "jdbc:mysql";
        Database db = new Database(new AppConfig(scheme + "://127.0.0.1:1/none?connectTimeout=500", "x", "y", 2, 500));
        String msg = db.checkConnection();
        assertNotNull(msg);
        assertTrue(msg.contains("Cannot reach the MySQL server"), msg);
        assertFalse(msg.contains("com.mysql") || msg.contains("Exception"), "no stack-trace jargon: " + msg);
    }
}
