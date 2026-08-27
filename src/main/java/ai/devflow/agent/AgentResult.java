package ai.devflow.agent;

import java.util.List;

public record AgentResult(
        String agent,
        Status status,
        String summary,
        List<String> filesTouched,
        List<Finding> findings,
        TokenUsage tokens) {

    public enum Status { OK, NEEDS_WORK, FAILED }

    public static AgentResult ok(String agent, String summary, List<String> filesTouched, TokenUsage tokens) {
        return new AgentResult(agent, Status.OK, summary, filesTouched, List.of(), tokens);
    }

    public static AgentResult needsWork(String agent, String summary, List<Finding> findings, TokenUsage tokens) {
        return new AgentResult(agent, Status.NEEDS_WORK, summary, List.of(), findings, tokens);
    }
}
