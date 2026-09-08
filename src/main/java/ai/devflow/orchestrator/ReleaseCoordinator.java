package ai.devflow.orchestrator;

import ai.devflow.tools.GitHubClient;
import ai.devflow.tools.GitHubClientException;
import ai.devflow.tools.WorkflowDispatchResult;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

public class ReleaseCoordinator {

    public record ReleaseOutcome(ReleaseStatus status, String url) {}

    public record ReleaseEvent(String type, String message, Map<String, Object> data) {}

    private final GitHubClient gitHubClient;
    private final ReleaseDispatcher releaseDispatcher;

    public ReleaseCoordinator(GitHubClient gitHubClient, ReleaseDispatcher releaseDispatcher) {
        this.gitHubClient = gitHubClient;
        this.releaseDispatcher = releaseDispatcher;
    }

    public Optional<ReleaseOutcome> dispatchIfRequested(RunState state, ApprovalGate gate,
                                                         String prUrl, Integer prNumber, CiStatus ciStatus,
                                                         Consumer<ReleaseEvent> publish) {
        if (!state.release()) return Optional.empty();
        if (prUrl == null || prNumber == null) {
            return Optional.of(blocked(publish, "Release blocked: no pull request was created."));
        }
        if (ciStatus != CiStatus.PASSED) {
            return Optional.of(blocked(publish, "Release blocked: remote CI must pass before release."));
        }
        if (!releaseDispatcher.isConfigured()) {
            return Optional.of(blocked(publish, "Release blocked: no GitHub Actions release workflow is configured."));
        }

        state.setPhase(RunPhase.WAITING_FOR_RELEASE_APPROVAL);
        publish.accept(new ReleaseEvent("gate", "Merge the pull request, then approve release workflow dispatch.", Map.of(
                "gate", Gate.BEFORE_RELEASE.name(), "prUrl", prUrl,
                "workflow", releaseDispatcher.workflow(), "ref", releaseDispatcher.ref())));
        if (!gate.await(Gate.BEFORE_RELEASE).approved()) {
            publish.accept(new ReleaseEvent("step", "Release was not approved; no workflow was dispatched.",
                    Map.of("releaseStatus", ReleaseStatus.SKIPPED.name())));
            return Optional.of(new ReleaseOutcome(ReleaseStatus.SKIPPED, null));
        }

        try {
            state.setPhase(RunPhase.VERIFYING_RELEASE_PR);
            if (!gitHubClient.isPullRequestMerged(state.workspace().repoUrl(), prNumber)) {
                return Optional.of(blocked(publish,
                        "Release blocked: pull request #" + prNumber + " is not merged."));
            }

            state.setPhase(RunPhase.DISPATCHING_RELEASE);
            WorkflowDispatchResult result = releaseDispatcher.dispatch(state.workspace().repoUrl());
            Map<String, Object> data = new HashMap<>();
            data.put("releaseStatus", ReleaseStatus.DISPATCHED.name());
            data.put("workflow", releaseDispatcher.workflow());
            data.put("ref", releaseDispatcher.ref());
            if (result.htmlUrl() != null) data.put("releaseUrl", result.htmlUrl());
            publish.accept(new ReleaseEvent("step", "Release workflow dispatched.", data));
            return Optional.of(new ReleaseOutcome(ReleaseStatus.DISPATCHED, result.htmlUrl()));
        } catch (GitHubClientException | RuntimeException e) {
            publish.accept(new ReleaseEvent("warn", "Release dispatch failed: " + e.getMessage()
                    + ". Inspect GitHub Actions manually.", Map.of("releaseStatus", ReleaseStatus.FAILED.name())));
            return Optional.of(new ReleaseOutcome(ReleaseStatus.FAILED, null));
        }
    }

    private ReleaseOutcome blocked(Consumer<ReleaseEvent> publish, String message) {
        publish.accept(new ReleaseEvent("warn", message, Map.of("releaseStatus", ReleaseStatus.BLOCKED.name())));
        return new ReleaseOutcome(ReleaseStatus.BLOCKED, null);
    }
}
