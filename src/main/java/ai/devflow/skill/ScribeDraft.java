package ai.devflow.skill;

/**
 * What the Scribe decided to keep from one correction (spec §6.3): a skill,
 * a durable memory fact, both, or neither if the correction taught nothing
 * generalizable.
 */
public record ScribeDraft(SkillDraft skill, String memoryFact) {

    public static final ScribeDraft EMPTY = new ScribeDraft(null, null);

    public boolean isEmpty() {
        return skill == null && (memoryFact == null || memoryFact.isBlank());
    }
}
