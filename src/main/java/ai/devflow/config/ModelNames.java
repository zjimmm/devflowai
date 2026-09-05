package ai.devflow.config;

public final class ModelNames {
    /** Cheap: routing, skill selection, scribe. */
    public static final String HAIKU  = "claude-haiku-4-5";
    /** Strong: code generation and review, called repeatedly inside the review loop. */
    public static final String SONNET = "claude-sonnet-5";
    /** Strongest: the once-per-run Planner call. */
    public static final String OPUS   = "claude-opus-5";
    private ModelNames() {}
}
