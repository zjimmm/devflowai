package ai.devflow.orchestrator;

/** Which RunExecutor a run uses — Strategy B (default) or Strategy A (spec §22). */
public enum RunStrategy {
    ORCHESTRATED,
    DIRECT
}
