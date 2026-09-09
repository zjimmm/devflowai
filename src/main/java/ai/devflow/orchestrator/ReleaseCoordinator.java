package ai.devflow.orchestrator;

import ai.devflow.tools.GitHubClient;
import ai.devflow.tools.GitHubClientException;
import ai.devflow.tools.WorkflowDispatchResult;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

public class ReleaseCoordinator {

    public record ReleaseOutcome(ReleaseStatus status, String url, Long workflowRunId,
                                 VerificationStatus verificationStatus, RollbackStatus rollbackStatus,
                                 String rollbackUrl) {}

    public record ReleaseEvent(String type, String message, Map<String, Object> data) {}

    private final GitHubClient gitHubClient;
    private final ReleaseDispatcher releaseDispatcher;
    private final ReleaseObserver releaseObserver;
    private final RollbackDispatcher rollbackDispatcher;

    public ReleaseCoordinator(GitHubClient gitHubClient, ReleaseDispatcher releaseDispatcher) {
        this(gitHubClient, releaseDispatcher,
                (repoUrl, workflowRunId, onUpdate) -> { throw new GitHubClientException("Release verification is not configured"); },
                RollbackDispatcher.disabled());
    }

    public ReleaseCoordinator(GitHubClient gitHubClient, ReleaseDispatcher releaseDispatcher,
                              ReleaseObserver releaseObserver) {
        this(gitHubClient, releaseDispatcher, releaseObserver, RollbackDispatcher.disabled());
    }

    public ReleaseCoordinator(GitHubClient gitHubClient, ReleaseDispatcher releaseDispatcher,
                              ReleaseObserver releaseObserver, RollbackDispatcher rollbackDispatcher) {
        this.gitHubClient = gitHubClient;
        this.releaseDispatcher = releaseDispatcher;
        this.releaseObserver = releaseObserver;
        this.rollbackDispatcher = rollbackDispatcher;
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
            return Optional.of(new ReleaseOutcome(ReleaseStatus.SKIPPED, null, null, null, null, null));
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
            VerificationStatus verificationStatus = observeDeployment(state, result, publish);
            RollbackOutcome rollbackOutcome = dispatchRollbackIfNeeded(state, gate, verificationStatus, publish);
            return Optional.of(new ReleaseOutcome(ReleaseStatus.DISPATCHED, result.htmlUrl(),
                    result.workflowRunId(), verificationStatus, rollbackOutcome.status(), rollbackOutcome.url()));
        } catch (GitHubClientException | RuntimeException e) {
            publish.accept(new ReleaseEvent("warn", "Release dispatch failed: " + e.getMessage()
                    + ". Inspect GitHub Actions manually.", Map.of("releaseStatus", ReleaseStatus.FAILED.name())));
            return Optional.of(new ReleaseOutcome(ReleaseStatus.FAILED, null, null, null, null, null));
        }
    }

    private VerificationStatus observeDeployment(RunState state, WorkflowDispatchResult result,
                                                 Consumer<ReleaseEvent> publish) {
        if (result.workflowRunId() == null) {
            publish.accept(new ReleaseEvent("warn", "Release was dispatched but GitHub returned no workflow run ID.",
                    Map.of("verificationStatus", VerificationStatus.UNAVAILABLE.name())));
            return VerificationStatus.UNAVAILABLE;
        }

        state.setPhase(RunPhase.VERIFYING_DEPLOYMENT);
        try {
            return releaseObserver.observe(state.workspace().repoUrl(), result.workflowRunId(), observation -> {
                Map<String, Object> data = new HashMap<>();
                data.put("verificationStatus", observation.status().name());
                if (result.htmlUrl() != null) data.put("releaseUrl", result.htmlUrl());
                publish.accept(new ReleaseEvent("step", verificationMessage(observation.status()), data));
            }).status();
        } catch (GitHubClientException | RuntimeException e) {
            publish.accept(new ReleaseEvent("warn", "Release verification failed: " + e.getMessage()
                    + ". Inspect GitHub Actions manually.",
                    Map.of("verificationStatus", VerificationStatus.UNAVAILABLE.name())));
            return VerificationStatus.UNAVAILABLE;
        }
    }

    private RollbackOutcome dispatchRollbackIfNeeded(RunState state, ApprovalGate gate,
                                                     VerificationStatus verificationStatus,
                                                     Consumer<ReleaseEvent> publish) {
        if (verificationStatus != VerificationStatus.FAILED) return RollbackOutcome.notRequired();
        if (!rollbackDispatcher.isConfigured()) {
            publish.accept(new ReleaseEvent("warn",
                    "Deployment verification failed and no rollback workflow is configured. Intervene manually.",
                    Map.of("rollbackStatus", RollbackStatus.UNAVAILABLE.name())));
            return new RollbackOutcome(RollbackStatus.UNAVAILABLE, null);
        }

        state.setPhase(RunPhase.WAITING_FOR_ROLLBACK_APPROVAL);
        publish.accept(new ReleaseEvent("gate",
                "Deployment verification failed. Approve rollback workflow dispatch.",
                Map.of("gate", Gate.BEFORE_ROLLBACK.name(), "workflow", rollbackDispatcher.workflow(),
                        "ref", rollbackDispatcher.ref(), "verificationStatus", VerificationStatus.FAILED.name())));
        if (!gate.await(Gate.BEFORE_ROLLBACK).approved()) {
            publish.accept(new ReleaseEvent("step", "Rollback was not approved; no rollback workflow was dispatched.",
                    Map.of("rollbackStatus", RollbackStatus.SKIPPED.name())));
            return new RollbackOutcome(RollbackStatus.SKIPPED, null);
        }

        state.setPhase(RunPhase.DISPATCHING_ROLLBACK);
        try {
            WorkflowDispatchResult result = rollbackDispatcher.dispatch(state.workspace().repoUrl());
            Map<String, Object> data = new HashMap<>();
            data.put("rollbackStatus", RollbackStatus.DISPATCHED.name());
            data.put("workflow", rollbackDispatcher.workflow());
            data.put("ref", rollbackDispatcher.ref());
            if (result.htmlUrl() != null) data.put("rollbackUrl", result.htmlUrl());
            publish.accept(new ReleaseEvent("step", "Rollback workflow dispatched.", data));
            return new RollbackOutcome(RollbackStatus.DISPATCHED, result.htmlUrl());
        } catch (GitHubClientException | RuntimeException e) {
            publish.accept(new ReleaseEvent("warn", "Rollback dispatch failed: " + e.getMessage()
                    + ". Intervene manually.", Map.of("rollbackStatus", RollbackStatus.FAILED.name())));
            return new RollbackOutcome(RollbackStatus.FAILED, null);
        }
    }

    private String verificationMessage(VerificationStatus status) {
        return switch (status) {
            case PENDING -> "Waiting for release workflow verification…";
            case PASSED -> "Release workflow verification passed";
            case FAILED -> "Release workflow verification failed";
            case TIMED_OUT -> "Timed out waiting for release workflow verification";
            case UNAVAILABLE -> "Release workflow verification is unavailable";
        };
    }

    private ReleaseOutcome blocked(Consumer<ReleaseEvent> publish, String message) {
        publish.accept(new ReleaseEvent("warn", message, Map.of("releaseStatus", ReleaseStatus.BLOCKED.name())));
        return new ReleaseOutcome(ReleaseStatus.BLOCKED, null, null, null, null, null);
    }

    private record RollbackOutcome(RollbackStatus status, String url) {
        private static RollbackOutcome notRequired() {
            return new RollbackOutcome(null, null);
        }
    }
}
