package com.portflow.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record Cargo(Long id, String name, String profileCode, BigDecimal quantity, String unit, LocalDateTime arrivalTime,
                    LocalDateTime manufacturedAt, LocalDateTime expiresAt, int priority, LocalDateTime deadlineAt,
                    String specialHandling, String extraInfo, String status, String statusReason, LocalDateTime createdAt) {}
