package ai.devflow.skill;

import java.util.List;

public interface SkillStore {

    /** Frontmatter-only, cheap (spec §6.2). Empty if the repo has no skills yet. */
    List<SkillIndexEntry> index(String repoSlug);

    /** Full file content for a skill already known to exist, by name. */
    String readFull(String repoSlug, String name);

    /**
     * Writes host-side, matched by {@code draft.slug()} (spec §6.3's "merges
     * into an existing one"). Returns the rendered markdown so the caller can
     * also write the same content into the run's workspace to be committed.
     */
    String write(String repoSlug, String runId, SkillDraft draft);
}
