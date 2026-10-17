package com.portflow.monitoring;

import com.portflow.dao.*;
import com.portflow.model.*;
import com.portflow.simulation.SimClock;
import com.portflow.util.Jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.Random;

/**
 * SIMULATED storage-temperature readings (there is no sensor). Readings are generated inside the storage's operating range,
 * with an optional excursion (probability from settings). Every row is stored with is_simulated = TRUE.
 * A reading outside the CARGO PROFILE's permitted range raises a TEMPERATURE_EXCURSION alert in the audit log.
 */
public final class TemperatureSimulator {
    private final SimClock clock; private final EventLog log;
    public TemperatureSimulator(SimClock clock, EventLog log) { this.clock = clock; this.log = log; }

    public int sample(Connection c, Task task, double excursionProbability) throws SQLException {
        Cargo cargo = CargoDao.find(c, task.cargoId()).orElseThrow();
        Optional<CargoProfile> profile = ProfileDao.find(c, cargo.profileCode());
        int n = 0;
        Random rnd = new Random(task.id() * 31 + 7);              // deterministic per task so tests are reproducible
        for (AllocationDao.Alloc a : AllocationDao.heldInWorkflow(c, task.workflowId())) {
            Resource r = ResourceDao.find(c, a.resourceId()).orElse(null);
            if (r == null || r.tempMinC() == null || r.tempMaxC() == null) continue;
            for (int k = 0; k < 3; k++) {
                double mid = (r.tempMinC() + r.tempMaxC()) / 2, half = (r.tempMaxC() - r.tempMinC()) / 2;
                double temp = mid + (rnd.nextDouble() * 2 - 1) * half;
                if (rnd.nextDouble() < excursionProbability) temp = r.tempMaxC() + 2 + rnd.nextDouble() * 3;
                temp = Math.round(temp * 10) / 10.0;
                boolean bad = profile.isPresent() && profile.get().hasTemperatureRange() && (temp < profile.get().tempMinC() || temp > profile.get().tempMaxC());
                Jdbc.update(c, "INSERT INTO storage_reading (resource_id, ts, temp_c, excursion, is_simulated) VALUES (?,?,?,?,TRUE)", r.id(), clock.now(), temp, bad);
                if (bad) log.log(c, EventType.TEMPERATURE_EXCURSION, task.id(), task.cargoId(), r.id(), task.workflowId(), null, null,
                    "ALERT (simulated reading) " + r.name() + " " + temp + " C outside permitted " + profile.get().tempMinC() + ".." + profile.get().tempMaxC() + " C for " + cargo.name());
                n++;
            }
        }
        return n;
    }
}
