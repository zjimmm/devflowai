package ai.devflow.orchestrator;

import ai.devflow.agent.Agent;
import ai.devflow.agent.AgentResult;

public class Orchestrator {

    public record RunOutcome(boolean approved, String reason, RunState state) {}

    private final Agent coder;
    private final Agent reviewer;
    private final int maxReviewIterations;
    private final int maxHumanIterations;

    public Orchestrator(Agent coder, Agent reviewer,
                        int maxReviewIterations, int maxHumanIterations) {
        this.coder = coder;
        this.reviewer = reviewer;
        this.maxReviewIterations = maxReviewIterations;
        this.maxHumanIterations = maxHumanIterations;
    }

    public RunOutcome run(RunState state) {
        while (state.reviewIterations() < maxReviewIterations) {
            state.incrementReviewIterations();

            AgentResult coded = coder.run(state);
            state.record(coded);
            state.clearFindings();

            if (coded.status() == AgentResult.Status.FAILED) {
                return new RunOutcome(false, "Coder failed: " + coded.summary(), state);
            }

            AgentResult reviewed = reviewer.run(state);
            state.record(reviewed);

            switch (reviewed.status()) {
                case OK -> {
                    return new RunOutcome(true, "Approved by reviewer", state);
                }
                case FAILED -> {
                    // Fail closed — an unparseable review is not an approval.
                    return new RunOutcome(false, "Review failed: " + reviewed.summary(), state);
                }
                case NEEDS_WORK -> state.addFindings(reviewed.findings());
            }
        }
        return new RunOutcome(false,
                "Review loop hit the cap of " + maxReviewIterations + " iterations", state);
    }

    public int maxHumanIterations() { return maxHumanIterations; }
}
