package ai.devflow.util;

/**
 * Filename/identifier-safe slugging: lowercase, collapse any run of
 * non-alphanumeric characters into a single dash, trim leading/trailing
 * dashes. Shared by skill-name-to-filename derivation
 * ({@code ai.devflow.skill.SkillDraft}) and repo-URL-to-repoSlug derivation
 * ({@code ai.devflow.orchestrator.RunRegistry}, Phase 6) -- both need the
 * same safe, deterministic rule, and duplicating the regex would risk them
 * drifting apart.
 */
public final class Slug {

    private Slug() {}

    public static String of(String raw) {
        String s = raw.toLowerCase().trim().replaceAll("[^a-z0-9]+", "-");
        return s.replaceAll("(^-+|-+$)", "");
    }
}
