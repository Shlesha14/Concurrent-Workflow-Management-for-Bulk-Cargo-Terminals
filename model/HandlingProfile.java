package edu.portflow.model;

public record HandlingProfile(
        String code, String displayName, String storageClass,
        Double minTemperatureC, Double maxTemperatureC,
        Integer maxWaitMinutes, String handlingClass, boolean requiresInspection) {
}
