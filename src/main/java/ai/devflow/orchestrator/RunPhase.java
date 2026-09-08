package ai.devflow.orchestrator;

/** Coarse lifecycle position of a run, surfaced to the UI via SSE. */
public enum RunPhase {
    PREPARING,
    CODING,
    REVIEWING,
    BUILDING,
    COMMITTING,
    OPENING_PR,
    VALIDATING_CI,
    WAITING_FOR_RELEASE_APPROVAL,
    VERIFYING_RELEASE_PR,
    DISPATCHING_RELEASE,
    VERIFYING_DEPLOYMENT,
    DONE,
    FAILED
}
