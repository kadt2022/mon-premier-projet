package com.example.sftp;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * In-memory audit trail used by the demo UI and the SFTP service.
 */
public class AuditTrail {

    private final int maxEntries;
    private final Deque<AuditEvent> events = new ArrayDeque<>();

    public AuditTrail(int maxEntries) {
        this.maxEntries = Math.max(10, maxEntries);
    }

    public synchronized void record(
            String actor,
            String channel,
            String action,
            String target,
            String outcome,
            String detail) {
        AuditEvent event = new AuditEvent(
                Instant.now().toString(),
                blankToSystem(actor),
                blankToSystem(channel),
                blankToSystem(action),
                blankToSystem(target),
                blankToSystem(outcome),
                detail == null ? "" : detail);
        events.addFirst(event);
        while (events.size() > maxEntries) {
            events.removeLast();
        }
    }

    public synchronized List<AuditEvent> snapshot() {
        return new ArrayList<>(events);
    }

    public synchronized int size() {
        return events.size();
    }

    private String blankToSystem(String value) {
        return value == null || value.isBlank() ? "system" : value;
    }

    public record AuditEvent(
            String timestamp,
            String actor,
            String channel,
            String action,
            String target,
            String outcome,
            String detail) {
    }
}
