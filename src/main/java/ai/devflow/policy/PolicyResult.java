package ai.devflow.policy;

public record PolicyResult(boolean passed, String reason) {
    public PolicyResult {
        if (!passed && (reason == null || reason.isBlank())) {
            throw new IllegalArgumentException("a failing PolicyResult must carry a non-blank reason");
        }
    }

    public static PolicyResult ok() {
        return new PolicyResult(true, null);
    }
}
