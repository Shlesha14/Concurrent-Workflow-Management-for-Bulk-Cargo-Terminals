package com.portflow.model;

import java.math.BigDecimal;

public record TaskRequirement(long id, long taskId, String resourceType, String capability, BigDecimal amount, String holdUntilStep) {}
