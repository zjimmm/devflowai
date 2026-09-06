package ai.devflow.orchestrator;

import ai.devflow.agent.AgentResult;
import ai.devflow.agent.Finding;

/**
 * Assembles PR title/body from data a run already produced -- no new LLM
 * call (spec §4.4). One assembler, not two: the strategy is a parameter so
 * the two run kinds' PR bodies stay structurally identical except for the
 * one section that's genuinely strategy-dependent (there is no review to
 * report for a Direct run).
 */
public final class PullRequestContent {

    public record Content(String title, String body) {}

    private PullRequestContent() {}

    public static Content build(RunState state, RunStrategy strategy, boolean buildSucceeded, String buildOutput) {
        String title = title(state.task());

        StringBuilder body = new StringBuilder();
        body.append("Summary:\n").append(lastCoderSummary(state)).append("\n\n");

        body.append("Testing:\n").append(buildSucceeded ? "Build passed." : "Build failed.");
        if (!buildSucceeded && buildOutput != null && !buildOutput.isBlank()) {
            body.append("\n\n").append(buildOutput);
        }
        body.append("\n\n");

        if (strategy == RunStrategy.ORCHESTRATED) {
            long reviewFindings = state.allFindings().stream()
                    .filter(f -> f.origin() == Finding.Origin.REVIEWER)
                    .count();
            if (reviewFindings > 0) {
                body.append("AI Review:\n").append(reviewFindings)
                        .append(" finding(s) identified and resolved.\n\n");
            }
        }

        long policyFindings = state.allFindings().stream()
                .filter(f -> f.origin() == Finding.Origin.POLICY)
                .count();
        body.append("Security:\n");
        if (policyFindings == 0) {
            body.append("No policy violations.");
        } else {
            body.append(policyFindings).append(" policy violation(s) flagged and resolved.");
        }

        body.append("\n\ndevflowai run: ").append(state.runId());

        return new Content(title, body.toString());
    }

    private static String title(String task) {
        String firstLine = task.lines().findFirst().orElse(task).trim();
        return firstLine.length() > 72 ? firstLine.substring(0, 72) : firstLine;
    }

    private static String lastCoderSummary(RunState state) {
        String summary = "";
        for (AgentResult result : state.history()) {
            if ("coder".equals(result.agent())) summary = result.summary();
        }
        return summary;
    }
}
