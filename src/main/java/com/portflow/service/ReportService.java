package com.portflow.service;

import com.portflow.dao.ReportDao;
import com.portflow.dao.ReportDao.Dashboard;
import com.portflow.simulation.SimClock;
import com.portflow.util.Database;
import com.portflow.util.TableData;

import java.sql.SQLException;

/** Thin facade the UI uses for every read-only screen (all numbers come from the database). */
public final class ReportService {
    private final Database db; private final SimClock clock;
    public ReportService(Database db, SimClock clock) { this.db = db; this.clock = clock; }
    public Dashboard dashboard() throws SQLException { return db.read(c -> ReportDao.dashboard(c, clock.now(), 120)); }
    public TableData cargo(String text) throws SQLException { return db.read(c -> ReportDao.cargoTable(c, text, clock.now())); }
    public TableData resources() throws SQLException { return db.read(ReportDao::resourceTable); }
    public TableData allocations(int limit) throws SQLException { return db.read(c -> ReportDao.allocationTable(c, limit)); }
    public TableData tasks() throws SQLException { return db.read(ReportDao::taskTable); }
    public TableData dependencies() throws SQLException { return db.read(ReportDao::dependencyTable); }
    public TableData running() throws SQLException { return db.read(ReportDao::running); }
    public TableData recentEvents(int n) throws SQLException { return db.read(c -> ReportDao.recentEvents(c, n)); }
    public TableData alerts() throws SQLException { return db.read(ReportDao::alerts); }
    public TableData utilization() throws SQLException { return db.read(ReportDao::utilizationReport); }
    public TableData urgent() throws SQLException { return db.read(c -> ReportDao.urgentCargo(c, clock.now(), 120)); }
    public TableData revisions() throws SQLException { return db.read(ReportDao::revisions); }
    public TableData planEntries(long rev) throws SQLException { return db.read(c -> ReportDao.planEntries(c, rev)); }
    public TableData profiles() throws SQLException { return db.read(ReportDao::profiles); }
    public TableData readings(int n) throws SQLException { return db.read(c -> ReportDao.readings(c, n)); }
}
