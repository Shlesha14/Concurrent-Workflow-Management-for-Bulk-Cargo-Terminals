package com.portflow.service;

import com.portflow.dao.*;
import com.portflow.model.*;
import com.portflow.simulation.SimClock;
import com.portflow.util.Database;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

public final class CargoService {
    public record Registration(Long cargoId, String status, String message, List<CargoValidator.Issue> issues) {
        public boolean accepted() { return cargoId != null && ("REGISTERED".equals(status)); }
    }

    private final Database db; private final SimClock clock; private final EventLog log;
    public CargoService(Database db, SimClock clock, EventLog log) { this.db = db; this.clock = clock; this.log = log; }

    /** Validate -> apply expiry policy -> insert cargo + audit row in one transaction. */
    public Registration register(Cargo draft) throws SQLException {
        return db.inTransaction(c -> {
            CargoProfile p = draft.profileCode() == null ? null : ProfileDao.find(c, draft.profileCode()).orElse(null);
            Cargo g = normalise(draft);
            List<CargoValidator.Issue> issues = CargoValidator.validate(g, p, clock.now());
            if (!issues.isEmpty()) return new Registration(null, "INVALID", "Cargo not registered: " + issues.size() + " problem(s)", issues);
            String status = "REGISTERED"; String reason = null;
            if (CargoValidator.isExpired(g, clock.now())) {
                status = "QUARANTINE".equals(p.expiredPolicy()) ? "QUARANTINED" : "REJECTED";
                reason = "Expired before scheduling (expiry " + g.expiresAt() + "); policy " + p.expiredPolicy() + " - operator review required";
            }
            Cargo toSave = new Cargo(null, g.name().trim(), g.profileCode(), g.quantity(), g.unit(), g.arrivalTime(), g.manufacturedAt(), g.expiresAt(),
                g.priority(), g.deadlineAt(), g.specialHandling(), g.extraInfo(), status, reason, clock.now());
            long id = CargoDao.insert(c, toSave);
            EventType et = status.equals("REGISTERED") ? EventType.CARGO_REGISTERED : status.equals("REJECTED") ? EventType.CARGO_REJECTED : EventType.CARGO_QUARANTINED;
            log.log(c, et, null, id, null, null, null, status, "Cargo #" + id + " " + toSave.name() + " [" + g.profileCode() + "] " + status + (reason != null ? ": " + reason : ""));
            return new Registration(id, status, reason != null ? reason : "Cargo registered", List.of());
        });
    }

    /** KG is converted to TONNE so all quantities use one storage-capacity unit. */
    private static Cargo normalise(Cargo g) {
        if (g.quantity() != null && "KG".equalsIgnoreCase(g.unit()))
            return new Cargo(g.id(), g.name(), g.profileCode(), g.quantity().divide(BigDecimal.valueOf(1000), 2, RoundingMode.HALF_UP), "TONNE", g.arrivalTime(),
                g.manufacturedAt(), g.expiresAt(), g.priority(), g.deadlineAt(), g.specialHandling(), g.extraInfo(), g.status(), g.statusReason(), g.createdAt());
        return g.unit() == null ? g : new Cargo(g.id(), g.name(), g.profileCode(), g.quantity(), g.unit().toUpperCase(), g.arrivalTime(), g.manufacturedAt(), g.expiresAt(),
            g.priority(), g.deadlineAt(), g.specialHandling(), g.extraInfo(), g.status(), g.statusReason(), g.createdAt());
    }

    /** Editing is allowed only before a workflow exists (afterwards tasks already copied the values). */
    public Registration update(Cargo edited) throws SQLException {
        return db.inTransaction(c -> {
            Optional<Cargo> old = CargoDao.find(c, edited.id());
            if (old.isEmpty()) return new Registration(null, "INVALID", "Cargo not found", List.of());
            if (WorkflowDao.byCargo(c, edited.id()).isPresent()) return new Registration(null, "INVALID", "Cargo already has a workflow and can no longer be edited", List.of());
            CargoProfile p = ProfileDao.find(c, edited.profileCode()).orElse(null);
            Cargo g = normalise(edited);
            List<CargoValidator.Issue> issues = CargoValidator.validate(g, p, clock.now());
            if (!issues.isEmpty()) return new Registration(null, "INVALID", "Cargo not updated", issues);
            CargoDao.update(c, new Cargo(g.id(), g.name(), g.profileCode(), g.quantity(), g.unit(), g.arrivalTime(), g.manufacturedAt(), g.expiresAt(), g.priority(),
                g.deadlineAt(), g.specialHandling(), g.extraInfo(), old.get().status(), old.get().statusReason(), old.get().createdAt()));
            log.log(c, EventType.OPERATOR_ACTION, null, g.id(), null, null, null, null, "Cargo #" + g.id() + " edited");
            return new Registration(g.id(), old.get().status(), "Cargo updated", List.of());
        });
    }

    public List<Cargo> search(String text) throws SQLException { return db.read(c -> CargoDao.search(c, text)); }
    public Optional<Cargo> find(long id) throws SQLException { return db.read(c -> CargoDao.find(c, id)); }
    public List<CargoProfile> profiles() throws SQLException { return db.read(ProfileDao::loadAll); }

    /** Settings screen: adjust a profile's constraints within safe bounds (max wait 1-10080 min, temperature -60..60 C, min <= max). */
    public void updateProfileConstraints(String code, int maxWaitMin, Double tMin, Double tMax, boolean requiresExpiry) throws SQLException, ServiceException {
        if (maxWaitMin < 1 || maxWaitMin > 10080) throw new ServiceException("Maximum waiting time must be between 1 and 10080 minutes");
        if ((tMin == null) != (tMax == null)) throw new ServiceException("Give both temperatures or neither");
        if (tMin != null && (tMin > tMax || tMin < -60 || tMax > 60)) throw new ServiceException("Temperature range must satisfy -60 <= min <= max <= 60 C");
        db.inTransaction(c -> {
            ProfileDao.updateConstraints(c, code, maxWaitMin, tMin, tMax, requiresExpiry);
            log.log(c, EventType.OPERATOR_ACTION, null, null, null, null, null, null, "Profile " + code + " constraints changed: max wait " + maxWaitMin + " min, temp " + tMin + ".." + tMax + ", expiry required " + requiresExpiry);
            return null;
        });
    }
}
