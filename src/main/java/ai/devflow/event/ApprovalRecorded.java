package ai.devflow.event;

/**
 * Fired by {@code RunController.approve} when a gate decision is accepted.
 * Plain fields, not {@code Gate}/{@code ApprovalDecision} — see the plan's
 * design decision 2 on why {@code ai.devflow.event} must not depend on
 * {@code ai.devflow.orchestrator}.
 */
public record ApprovalRecorded(String runId, String gate, boolean approved, String reason) {}
