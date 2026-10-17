package com.portflow.model;

import java.time.LocalDateTime;

public record Task(long id, long workflowId, long cargoId, String stepCode, String type, int priority, TaskState state,
                   String blockCode, String blockReason, LocalDateTime createdAt, LocalDateTime readyAt, LocalDateTime deadlineAt,
                   int durationMin, LocalDateTime startedAt, LocalDateTime completedAt, int retryCount, LocalDateTime notBefore,
                   String workerThread, boolean deadlineMissed) {}
