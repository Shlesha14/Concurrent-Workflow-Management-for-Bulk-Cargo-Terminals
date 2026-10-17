package com.portflow.service;

import java.util.List;

/** A business-rule failure with a message safe to show to the operator. */
public class ServiceException extends Exception {
    private final List<String> details;
    public ServiceException(String message) { this(message, List.of()); }
    public ServiceException(String message, List<String> details) { super(message); this.details = details; }
    public List<String> details() { return details; }
}
