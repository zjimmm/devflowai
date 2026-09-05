package ai.devflow.agent;

import ai.devflow.skill.SkillIndexEntry;

import java.util.List;

/**
 * Selects 0-3 skills relevant to a task from the cheap frontmatter index
 * (spec §6.2). Not an {@link Agent}: its shape is {@code (task, index) ->
 * names}, not {@code RunState -> AgentResult} -- there is no file-changing
 * work to report. An interface (not just the concrete {@code
 * SkillPickerAgent}) so tests can supply a trivial lambda double.
 */
public interface SkillPicker {
    List<String> pick(String task, List<SkillIndexEntry> index);
}
