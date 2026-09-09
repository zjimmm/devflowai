package ai.devflow.orchestrator;

/**
 * The three points where a run pauses for a human (spec §5.2).
 *
 * <p>{@code PRE_FLIGHT} is a start confirmation, not a filesystem guard —
 * {@link ai.devflow.workspace.PathGuard} is the filesystem guard, and the
 * workspace is a disposable temp copy. What PRE_FLIGHT actually confirms is
 * "spend Opus 5 tokens on this repo, for this task".
 */
public enum Gate {
    PRE_FLIGHT,
    BEFORE_BUILD,
    BEFORE_COMMIT,
    BEFORE_STAGING,
    BEFORE_RELEASE,
    BEFORE_ROLLBACK
}
