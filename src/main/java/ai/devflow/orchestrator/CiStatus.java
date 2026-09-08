package ai.devflow.orchestrator;

/** Aggregate state of remote CI, intentionally distinct from a local build result. */
public enum CiStatus {
    PENDING,
    PASSED,
    FAILED,
    TIMED_OUT,
    UNAVAILABLE
}
