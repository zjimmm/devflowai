package ai.devflow.agent;

import ai.devflow.orchestrator.RunState;
import ai.devflow.skill.ScribeDraft;

import java.util.List;

/**
 * Writes down what a corrected run just learned (spec §6.3). An interface
 * (not just the concrete {@code ScribeAgent}) so {@code OrchestratorTest} can
 * supply a trivial lambda double instead of mocking a {@code ChatClient}.
 */
public interface Scribe {
    /** {@code humanGuidance} is non-null only on a Gate-3 re-draft (spec §5.2). */
    ScribeDraft draft(RunState state, List<Finding> findings, String humanGuidance);
}
