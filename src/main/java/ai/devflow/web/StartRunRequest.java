package ai.devflow.web;

/**
 * @param repo "fixture" today, or an https:// git URL (Phase 6).
 * @param strategy "orchestrated" (default) or "direct" (spec §22) — null or
 *                 blank defaults to orchestrated. The 2-arg constructor
 *                 below exists so every pre-existing caller (tests included)
 *                 that constructs this directly with just task/repo keeps
 *                 compiling unmodified.
 */
public record StartRunRequest(String task, String repo, String strategy) {
    public StartRunRequest(String task, String repo) {
        this(task, repo, null);
    }
}
