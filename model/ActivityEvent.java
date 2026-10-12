package edu.portflow.model;

import java.time.LocalDateTime;

public record ActivityEvent(LocalDateTime time, String type, Long taskId, String message, String threadName) {
    @Override public String toString() {
        return time.toString().replace('T',' ') + " | " + type + (taskId == null ? "" : " | Task " + taskId)
                + " | " + message + (threadName == null ? "" : " [" + threadName + "]");
    }
}
