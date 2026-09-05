package ai.devflow.skill;

import java.util.List;

/** The cheap, frontmatter-only view of a skill file (spec §6.2) — no body. */
public record SkillIndexEntry(String name, String description, List<String> triggers) {}
