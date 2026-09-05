package ai.devflow.skill;

import java.util.List;

public record SkillDraft(String name, String description, List<String> triggers, String body) {

    /** Filename-safe slug, used to match "an existing skill by name" (spec §6.3). */
    public String slug() {
        String s = name.toLowerCase().trim().replaceAll("[^a-z0-9]+", "-");
        return s.replaceAll("(^-+|-+$)", "");
    }
}
