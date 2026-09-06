package ai.devflow.agent;

public record Finding(
        Origin origin,
        Severity severity,
        String file,      // null for HUMAN and POLICY findings
        Integer line,     // null for HUMAN and POLICY findings
        String message) {

    public enum Origin { REVIEWER, HUMAN, POLICY }
    public enum Severity { LOW, MEDIUM, HIGH }

    public static Finding fromHuman(String message) {
        return new Finding(Origin.HUMAN, Severity.HIGH, null, null, message);
    }

    public static Finding fromPolicy(String message) {
        return new Finding(Origin.POLICY, Severity.HIGH, null, null, message);
    }
}
