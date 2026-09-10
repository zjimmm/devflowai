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
    private final CiObserver ciObserver;
    private final ReleaseCoordinator releaseCoordinator;

    public DirectExecutor(Agent coder, RunEventPublisher events, Duration buildTimeout, GitHubClient gitHubClient) {
        this(coder, events, buildTimeout, gitHubClient,
                (repoUrl, ref, onUpdate) -> { throw new GitHubClientException("CI check observation is not configured"); },
                ReleaseDispatcher.disabled());
    }

    public DirectExecutor(Agent coder, RunEventPublisher events, Duration buildTimeout, GitHubClient gitHubClient,
                          CiObserver ciObserver) {
        this(coder, events, buildTimeout, gitHubClient, ciObserver, ReleaseDispatcher.disabled());
    }

    public DirectExecutor(Agent coder, RunEventPublisher events, Duration buildTimeout, GitHubClient gitHubClient,
                          CiObserver ciObserver, ReleaseDispatcher releaseDispatcher) {
        this(coder, events, buildTimeout, gitHubClient, ciObserver, releaseDispatcher,
                (repoUrl, workflowRunId, onUpdate) -> { throw new GitHubClientException("Release verification is not configured"); });
    }

    public DirectExecutor(Agent coder, RunEventPublisher events, Duration buildTimeout, GitHubClient gitHubClient,
                          CiObserver ciObserver, ReleaseDispatcher releaseDispatcher, ReleaseObserver releaseObserver) {
        this(coder, events, buildTimeout, gitHubClient, ciObserver, releaseDispatcher, releaseObserver,
                RollbackDispatcher.disabled());
    }

    public DirectExecutor(Agent coder, RunEventPublisher events, Duration buildTimeout, GitHubClient gitHubClient,
                          CiObserver ciObserver, ReleaseDispatcher releaseDispatcher, ReleaseObserver releaseObserver,
                          RollbackDispatcher rollbackDispatcher) {
        this(coder, events, buildTimeout, gitHubClient, ciObserver, releaseDispatcher, releaseObserver,
                rollbackDispatcher, OperationalHealthObserver.disabled());
    }

    public DirectExecutor(Agent coder, RunEventPublisher events, Duration buildTimeout, GitHubClient gitHubClient,
                          CiObserver ciObserver, ReleaseDispatcher releaseDispatcher, ReleaseObserver releaseObserver,
                          RollbackDispatcher rollbackDispatcher, OperationalHealthObserver operationalHealthObserver) {
        this(coder, events, buildTimeout, gitHubClient, ciObserver, releaseDispatcher, releaseObserver,
                rollbackDispatcher, operationalHealthObserver, StagingDispatcher.disabled());
    }

    public DirectExecutor(Agent coder, RunEventPublisher events, Duration buildTimeout, GitHubClient gitHubClient,
                          CiObserver ciObserver, ReleaseDispatcher releaseDispatcher, ReleaseObserver releaseObserver,
                          RollbackDispatcher rollbackDispatcher, OperationalHealthObserver operationalHealthObserver,
                          StagingDispatcher stagingDispatcher) {
        this(coder, events, buildTimeout, gitHubClient, ciObserver, releaseDispatcher, releaseObserver,
                rollbackDispatcher, operationalHealthObserver, stagingDispatcher, SmokeTestRunner.disabled());
    }

    public DirectExecutor(Agent coder, RunEventPublisher events, Duration buildTimeout, GitHubClient gitHubClient,
                          CiObserver ciObserver, ReleaseDispatcher releaseDispatcher, ReleaseObserver releaseObserver,
                          RollbackDispatcher rollbackDispatcher, OperationalHealthObserver operationalHealthObserver,
                          StagingDispatcher stagingDispatcher, SmokeTestRunner smokeTestRunner) {
        this.coder = coder;
        this.events = events;
        this.buildTimeout = buildTimeout;
        this.gitHubClient = gitHubClient;
        this.ciObserver = ciObserver;
        this.releaseCoordinator = new ReleaseCoordinator(gitHubClient, releaseDispatcher, releaseObserver,
                rollbackDispatcher, operationalHealthObserver, stagingDispatcher, smokeTestRunner);
    }

    @Override
    public Orchestrator.RunOutcome run(RunState state, ApprovalGate gate) {
        String runId = state.runId();
        try {
            return execute(state, gate);
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

    private Orchestrator.RunOutcome execute(RunState state, ApprovalGate gate) {
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
        Integer prNumber = null;
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
                prNumber = result.number();
                emit(state, "step", "Pull request opened: " + prUrl, Map.of());
            } catch (GitHubClientException | RuntimeException e) {
                emit(state, "warn", "Branch pushed, but opening the PR failed: " + e.getMessage()
                        + ". Open it manually from " + state.workspace().branchName() + ".", Map.of());
            }
        }

        CiStatus ciStatus = observeCi(state, prUrl);
        var releaseOutcome = releaseCoordinator.dispatchIfRequested(state, gate, prUrl, prNumber, ciStatus,
                event -> emit(state, event.type(), event.message(), event.data()));

        state.setPhase(RunPhase.DONE);
        Map<String, Object> doneData = new HashMap<>();
        doneData.put("branch", state.workspace().branchName());
        doneData.put("inputTokens", state.totalTokens().input());
        doneData.put("outputTokens", state.totalTokens().output());
        if (prUrl != null) doneData.put("prUrl", prUrl);
        if (ciStatus != null) doneData.put("ciStatus", ciStatus.name());
        releaseOutcome.ifPresent(outcome -> {
            doneData.put("releaseStatus", outcome.status().name());
            if (outcome.url() != null) doneData.put("releaseUrl", outcome.url());
            if (outcome.verificationStatus() != null) {
                doneData.put("verificationStatus", outcome.verificationStatus().name());
            }
            if (outcome.healthStatus() != null) {
                doneData.put("healthStatus", outcome.healthStatus().name());
            }
            if (outcome.healthUrl() != null) doneData.put("healthUrl", outcome.healthUrl());
            if (outcome.stagingStatus() != null) {
                doneData.put("stagingStatus", outcome.stagingStatus().name());
            }
            if (outcome.stagingUrl() != null) doneData.put("stagingUrl", outcome.stagingUrl());
            if (outcome.stagingVerificationStatus() != null) {
                doneData.put("stagingVerificationStatus", outcome.stagingVerificationStatus().name());
            }
            if (outcome.smokeStatus() != null) doneData.put("smokeStatus", outcome.smokeStatus().name());
            if (outcome.smokeUrl() != null) doneData.put("smokeUrl", outcome.smokeUrl());
            if (outcome.rollbackStatus() != null) {
                doneData.put("rollbackStatus", outcome.rollbackStatus().name());
            }
            if (outcome.rollbackUrl() != null) doneData.put("rollbackUrl", outcome.rollbackUrl());
        });
        emit(state, "done", "Direct run committed on " + state.workspace().branchName(), doneData);
        return new Orchestrator.RunOutcome(true, "Direct run committed, no review", state);
    }

    private CiStatus observeCi(RunState state, String prUrl) {
        if (prUrl == null) return null;

        state.setPhase(RunPhase.VALIDATING_CI);
        try {
            return ciObserver.observe(state.workspace().repoUrl(), state.workspace().branchName(),
                    observation -> emit(state, "step", ciMessage(observation.status()), Map.of(
                            "ciStatus", observation.status().name(), "ciChecks", observation.checks())))
                    .status();
        } catch (GitHubClientException | RuntimeException e) {
            CiStatus unavailable = CiStatus.UNAVAILABLE;
            emit(state, "warn", "Pull request opened, but CI observation failed: " + e.getMessage()
                    + ". Inspect it manually from " + prUrl + ".", Map.of("ciStatus", unavailable.name()));
            return unavailable;
        }
    }

    private String ciMessage(CiStatus status) {
        return switch (status) {
            case PENDING -> "Waiting for CI checks…";
            case PASSED -> "CI checks passed";
            case FAILED -> "CI checks failed";
            case TIMED_OUT -> "Timed out waiting for CI checks";
            case UNAVAILABLE -> "CI checks are unavailable";
        };
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
