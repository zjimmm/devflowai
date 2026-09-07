package ai.devflow.orchestrator;

import ai.devflow.agent.Agent;
import ai.devflow.agent.AgentResult;
import ai.devflow.event.RunEvent;
import ai.devflow.event.RunEventPublisher;
import ai.devflow.tools.BuildTools;
import ai.devflow.tools.GitHubClient;
import ai.devflow.tools.GitHubClientException;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Strategy A (spec §22): task -> coder -> build (metrics only) -> commit.
 * Fully autonomous — no planner, no reviewer, no gates, no learning loop.
 * Deliberately does not share Orchestrator's private emit/cleanUp helpers:
 * two small helpers do not justify coupling two independent, simple
 * executors before there's a third one to justify the abstraction.
 */
public class DirectExecutor implements RunExecutor {

    private final Agent coder;
    private final RunEventPublisher events;
    private final Duration buildTimeout;
    private final GitHubClient gitHubClient;

    public DirectExecutor(Agent coder, RunEventPublisher events, Duration buildTimeout, GitHubClient gitHubClient) {
        this.coder = coder;
        this.events = events;
        this.buildTimeout = buildTimeout;
        this.gitHubClient = gitHubClient;
    }

    @Override
    public Orchestrator.RunOutcome run(RunState state, ApprovalGate gate) {
        String runId = state.runId();
        try {
            return execute(state);
        } catch (RuntimeException e) {
            state.setPhase(RunPhase.FAILED);
            String reason = "Run failed: " + e;
            events.publish(runId, RunEvent.of("error", reason));
            return new Orchestrator.RunOutcome(false, reason, state);
        } finally {
            cleanUp(state);
            events.complete(runId);
        }
    }

    private Orchestrator.RunOutcome execute(RunState state) {
        emit(state, "step", "Workspace ready — branch " + state.workspace().branchName(),
                Map.of("branch", state.workspace().branchName(),
                       "task", state.task(),
                       "repoSlug", state.repoSlug(),
                       "strategy", RunStrategy.DIRECT.name()));

        state.setPhase(RunPhase.CODING);
        emit(state, "step", "Coder — working…", Map.of());
        AgentResult coded = coder.run(state);
        state.record(coded);
        emit(state, "step", "Coder — " + coded.summary(), Map.of("filesTouched", coded.filesTouched()));

        if (coded.status() == AgentResult.Status.FAILED) {
            return failed(state, "Coder failed: " + coded.summary());
        }

        List<String> changed = state.gitTools().changedFiles();
        if (changed.isEmpty()) {
            return failed(state, "Refusing to approve: the coder made no changes");
        }

        state.setPhase(RunPhase.BUILDING);
        emit(state, "step", "Running the target repository's build…", Map.of());
        var build = new BuildTools(state.workspace(), buildTimeout).build("test");
        emit(state, "step", build.success() ? "Build passed" : "Build failed",
                Map.of("success", build.success()));

        state.setPhase(RunPhase.COMMITTING);
        String committed = state.gitTools().commit("devflowai (direct): " + state.task());
        emit(state, "step", committed, Map.of());

        String prUrl = null;
        if (state.openPr()) {
            state.setPhase(RunPhase.OPENING_PR);
            try {
                gitHubClient.push(state.workspace(), state.workspace().branchName());
            } catch (GitHubClientException e) {
                return failed(state, "Push failed: " + e.getMessage()
                        + ". The commit was made locally but never reached the remote — it is now lost.");
            }
            try {
                var content = PullRequestContent.build(state, RunStrategy.DIRECT, build.success(), build.output());
                var result = gitHubClient.openPullRequest(
                        state.workspace().repoUrl(), state.workspace().branchName(),
                        content.title(), content.body());
                prUrl = result.url();
                emit(state, "step", "Pull request opened: " + prUrl, Map.of());
            } catch (GitHubClientException e) {
                emit(state, "warn", "Branch pushed, but opening the PR failed: " + e.getMessage()
                        + ". Open it manually from " + state.workspace().branchName() + ".", Map.of());
            }
        }

        state.setPhase(RunPhase.DONE);
        Map<String, Object> doneData = new HashMap<>();
        doneData.put("branch", state.workspace().branchName());
        doneData.put("inputTokens", state.totalTokens().input());
        doneData.put("outputTokens", state.totalTokens().output());
        if (prUrl != null) doneData.put("prUrl", prUrl);
        emit(state, "done", "Direct run committed on " + state.workspace().branchName(), doneData);
        return new Orchestrator.RunOutcome(true, "Direct run committed, no review", state);
    }

    private Orchestrator.RunOutcome failed(RunState state, String reason) {
        state.setPhase(RunPhase.FAILED);
        emit(state, "error", reason, Map.of(
                "inputTokens", state.totalTokens().input(),
                "outputTokens", state.totalTokens().output()));
        return new Orchestrator.RunOutcome(false, reason, state);
    }

    private void emit(RunState state, String type, String message, Map<String, Object> data) {
        Map<String, Object> withPhase = new HashMap<>(data);
        withPhase.put("phase", state.phase().name());
        events.publish(state.runId(), RunEvent.of(type, message, withPhase));
    }

    private void cleanUp(RunState state) {
        try {
            state.workspace().cleanup();
        } catch (Exception e) {
            events.publish(state.runId(),
                    RunEvent.of("warn", "Workspace cleanup failed: " + e.getMessage()));
        }
    }
}
