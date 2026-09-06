package ai.devflow.orchestrator;

/**
 * The shape any top-level run strategy implements. {@link Orchestrator}
 * (Strategy B, the full pipeline) and {@link DirectExecutor} (Strategy A, a
 * bare coder pass) are the two implementations {@link RunRegistry} dispatches
 * between (spec §22).
 */
public interface RunExecutor {
    Orchestrator.RunOutcome run(RunState state, ApprovalGate gate);
}
