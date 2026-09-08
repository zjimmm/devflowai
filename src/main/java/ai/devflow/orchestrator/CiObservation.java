package ai.devflow.orchestrator;

import ai.devflow.tools.CiCheck;

import java.util.List;

/** A point-in-time aggregate and its safe check summaries. */
public record CiObservation(CiStatus status, List<CiCheck> checks) {
    public CiObservation {
        checks = List.copyOf(checks);
    }
}
