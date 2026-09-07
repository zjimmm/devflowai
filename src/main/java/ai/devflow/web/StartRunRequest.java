package ai.devflow.web;

/**
 * @param repo "fixture" today, or an https:// git URL (Phase 6).
 * @param strategy "orchestrated" (default) or "direct" (spec §22) — null or
 *                 blank defaults to orchestrated.
 * @param openPr when true, push the branch and open a GitHub pull request
 *               after a successful commit. Null or false is the default —
 *               never inferred. Rejected by RunController when combined
 *               with the fixture repo or a non-github.com URL. The
 *               delegating constructors below exist so every pre-existing
 *               caller (tests included) that constructs this directly with
 *               fewer arguments keeps compiling unmodified.
 */
public record StartRunRequest(String task, String repo, String strategy, Boolean openPr) {
    public StartRunRequest(String task, String repo, String strategy) {
        this(task, repo, strategy, null);
    }

    public StartRunRequest(String task, String repo) {
        this(task, repo, null, null);
    }
}
