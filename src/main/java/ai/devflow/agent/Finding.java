package ai.devflow.agent;

public record Finding(
        Origin origin,
        Severity severity,
        String file,      // null for HUMAN findings
        Integer line,     // null for HUMAN findings
        String message) {

    public enum Origin { REVIEWER, HUMAN }
    public enum Severity { LOW, MEDIUM, HIGH }

    public static Finding fromHuman(String message) {
        return new Finding(Origin.HUMAN, Severity.HIGH, null, null, message);
    }
}
