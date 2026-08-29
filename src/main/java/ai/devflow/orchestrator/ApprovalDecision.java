package ai.devflow.orchestrator;

/**
 * A human's answer at a gate. A rejection may carry a reason, which becomes a
 * {@code Finding} with {@code origin = HUMAN} and re-enters the bounded loop —
 * making the operator a third reviewer rather than a kill switch (spec §5.2).
 */
public record ApprovalDecision(boolean approved, String reason) {

    public static ApprovalDecision approve() {
        return new ApprovalDecision(true, null);
    }

    public static ApprovalDecision reject() {
        return new ApprovalDecision(false, null);
    }

    public static ApprovalDecision rejectWith(String reason) {
        return new ApprovalDecision(false, reason);
    }

    /** A blank reason is not a reason — it must not be fed to the coder as guidance. */
    public boolean hasReason() {
        return reason != null && !reason.isBlank();
    }
}
