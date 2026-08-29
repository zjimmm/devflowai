package ai.devflow.orchestrator;

/** Coarse lifecycle position of a run, surfaced to the UI via SSE. */
public enum RunPhase {
    PREPARING,
    CODING,
    REVIEWING,
    BUILDING,
    COMMITTING,
    DONE,
    FAILED
}
