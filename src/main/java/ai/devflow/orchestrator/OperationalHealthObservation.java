package ai.devflow.orchestrator;

public record OperationalHealthObservation(OperationalHealthStatus status, String endpoint, Integer statusCode) {
}
