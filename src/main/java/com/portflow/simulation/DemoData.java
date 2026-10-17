package com.portflow.simulation;

import com.portflow.config.PortFlowContext;
import com.portflow.service.ServiceException;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;

/** The demo terminal (same resources as database/04_seed.sql) created through the normal service layer. */
public final class DemoData {
    private DemoData() {}

    public static void addTerminalResources(PortFlowContext x) throws SQLException, ServiceException {
        add(x, "CR1", "CRANE", 2, "Terminal B", null, null, "BULK_GRAB");
        add(x, "CR2", "CRANE", 1, "Terminal B", null, null, "FRAGILE_HANDLING");
        add(x, "CONV1", "CONVEYOR", 1, "Terminal B", null, null, "DUST_SUPPRESSED");
        add(x, "CONV2", "CONVEYOR", 1, "Terminal B", null, null);
        add(x, "FORK1", "FORKLIFT", 1, "Yard", null, null);
        add(x, "FORK2", "FORKLIFT", 1, "Yard", null, null, "FRAGILE_HANDLING");
        add(x, "TRUCK1", "TRUCK", 1, "Gate", null, null);
        add(x, "TRUCK2", "TRUCK", 1, "Gate", null, null);
        add(x, "INSP1", "INSPECTION_STATION", 2, "Shed 1", null, null);
        add(x, "RECL1", "RECLAIMER", 1, "Stockyard", null, null);
        add(x, "COLD-A", "COLD_ROOM", 300, "Cold block", 1.0, 3.0);
        add(x, "COLD-B", "COLD_ROOM", 200, "Cold block", 11.0, 13.0);
        add(x, "COLD-C", "COLD_ROOM", 150, "Cold block", 13.5, 14.5);
        add(x, "CHILL1", "CHILLED_STORE", 250, "Cold block", 1.0, 4.0);
        add(x, "FRZ1", "FROZEN_STORE", 400, "Cold block", -24.0, -20.0);
        add(x, "DRY-A", "DRY_STORE", 300, "Warehouse", null, null, "FRAGILE_SAFE", "MOISTURE_PROOF");
        add(x, "DRY-B", "DRY_STORE", 700, "Warehouse", null, null, "MOISTURE_PROOF");
        add(x, "STOCK1", "STOCKYARD", 5000, "Stockyard", null, null);
        add(x, "STOCK2", "STOCKYARD", 3000, "Stockyard", null, null);
    }

    public static int add(PortFlowContext x, String name, String type, double cap, String loc, Double tMin, Double tMax, String... caps) throws SQLException, ServiceException {
        return x.resources.add(name, type, BigDecimal.valueOf(cap), loc, tMin, tMax, List.of(caps));
    }
}
