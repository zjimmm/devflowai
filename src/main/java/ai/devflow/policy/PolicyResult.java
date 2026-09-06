package ai.devflow.policy;

public record PolicyResult(boolean passed, String reason) {
    public static PolicyResult ok() {
        return new PolicyResult(true, null);
    }
}
