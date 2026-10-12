package edu.portflow.model;

import java.time.LocalDateTime;

public record Cargo(long id, String name, CargoCategory category, double quantity, String unit,
                    LocalDateTime arrivalTime, LocalDateTime manufacturingTime,
                    LocalDateTime expiryTime, LocalDateTime deadlineTime, int priority,
                    HandlingProfile profile, String notes) {
    public Cargo {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Cargo name is required.");
        if (quantity <= 0) throw new IllegalArgumentException("Quantity must be greater than zero.");
        if (priority < 1 || priority > 5) throw new IllegalArgumentException("Priority must be 1–5.");
        if (arrivalTime == null) throw new IllegalArgumentException("Arrival time is required.");
        if (manufacturingTime != null && expiryTime != null && manufacturingTime.isAfter(expiryTime))
            throw new IllegalArgumentException("Manufacturing time cannot be after expiry time.");
        if (profile == null) throw new IllegalArgumentException("Handling profile is required.");
    }
}
