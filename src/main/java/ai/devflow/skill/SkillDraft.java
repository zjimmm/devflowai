package ai.devflow.skill;

import ai.devflow.util.Slug;

import java.util.List;

public record SkillDraft(String name, String description, List<String> triggers, String body) {

    /** Filename-safe slug, used to match "an existing skill by name" (spec §6.3). */
    public String slug() {
        return slugify(name);
    }

    /**
     * Filename-safe slug for an arbitrary name. Shared by {@link #slug()} and
     * by {@code FileSkillStore.readFull}, which must resolve the same file
     * that {@code FileSkillStore.write} created -- the name reported back by
     * {@code index()}/the picker is the raw frontmatter {@code name:} field,
     * not the slug the file was actually written under. Delegates to the
     * shared {@link Slug} utility (Phase 6 needs the identical rule for
     * repo-URL-to-repoSlug derivation).
     */
    public static String slugify(String name) {
        return Slug.of(name);
    }
}
