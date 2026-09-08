package ai.devflow.web;

import ai.devflow.history.RunAuditEntry;

import java.time.Instant;

public record RunAuditSummary(String actor, String action, String phase, String message, Instant occurredAt) {

    public static RunAuditSummary from(RunAuditEntry entry) {
        return new RunAuditSummary(entry.actor(), entry.action(), entry.phase(), entry.message(), entry.occurredAt());
    }
}
