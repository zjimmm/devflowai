package ai.devflow.orchestrator;

import java.util.List;

public record SmokeTestObservation(SmokeTestStatus status, List<OperationalHealthObservation> results) {
    public SmokeTestObservation {
        results = List.copyOf(results);
    }
}
