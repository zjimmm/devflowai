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
                                 String rollbackUrl, OperationalHealthStatus healthStatus, String healthUrl,
                                 StagingStatus stagingStatus, String stagingUrl,
                                 VerificationStatus stagingVerificationStatus, SmokeTestStatus smokeStatus,
                                 String smokeUrl) {}

    public record ReleaseEvent(String type, String message, Map<String, Object> data) {}

    private final GitHubClient gitHubClient;
    private final ReleaseDispatcher releaseDispatcher;
    private final ReleaseObserver releaseObserver;
    private final RollbackDispatcher rollbackDispatcher;
    private final OperationalHealthObserver operationalHealthObserver;
    private final StagingDispatcher stagingDispatcher;
    private final SmokeTestRunner smokeTestRunner;

    public ReleaseCoordinator(GitHubClient gitHubClient, ReleaseDispatcher releaseDispatcher) {
        this(gitHubClient, releaseDispatcher,
                (repoUrl, workflowRunId, onUpdate) -> { throw new GitHubClientException("Release verification is not configured"); },
                RollbackDispatcher.disabled(), OperationalHealthObserver.disabled(), StagingDispatcher.disabled(),
                SmokeTestRunner.disabled());
    }

    public ReleaseCoordinator(GitHubClient gitHubClient, ReleaseDispatcher releaseDispatcher,
                              ReleaseObserver releaseObserver) {
        this(gitHubClient, releaseDispatcher, releaseObserver, RollbackDispatcher.disabled(),
                OperationalHealthObserver.disabled(), StagingDispatcher.disabled());
    }

    public ReleaseCoordinator(GitHubClient gitHubClient, ReleaseDispatcher releaseDispatcher,
                              ReleaseObserver releaseObserver, RollbackDispatcher rollbackDispatcher) {
        this(gitHubClient, releaseDispatcher, releaseObserver, rollbackDispatcher, OperationalHealthObserver.disabled(),
                StagingDispatcher.disabled(), SmokeTestRunner.disabled());
    }

    public ReleaseCoordinator(GitHubClient gitHubClient, ReleaseDispatcher releaseDispatcher,
                              ReleaseObserver releaseObserver, RollbackDispatcher rollbackDispatcher,
                              OperationalHealthObserver operationalHealthObserver) {
        this(gitHubClient, releaseDispatcher, releaseObserver, rollbackDispatcher, operationalHealthObserver,
                StagingDispatcher.disabled(), SmokeTestRunner.disabled());
    }

    public ReleaseCoordinator(GitHubClient gitHubClient, ReleaseDispatcher releaseDispatcher,
                              ReleaseObserver releaseObserver, RollbackDispatcher rollbackDispatcher,
                              OperationalHealthObserver operationalHealthObserver, StagingDispatcher stagingDispatcher) {
        this(gitHubClient, releaseDispatcher, releaseObserver, rollbackDispatcher, operationalHealthObserver,
                stagingDispatcher, SmokeTestRunner.disabled());
    }

    public ReleaseCoordinator(GitHubClient gitHubClient, ReleaseDispatcher releaseDispatcher,
                              ReleaseObserver releaseObserver, RollbackDispatcher rollbackDispatcher,
                              OperationalHealthObserver operationalHealthObserver, StagingDispatcher stagingDispatcher,
                              SmokeTestRunner smokeTestRunner) {
        this.gitHubClient = gitHubClient;
        this.releaseDispatcher = releaseDispatcher;
        this.releaseObserver = releaseObserver;
        this.rollbackDispatcher = rollbackDispatcher;
        this.operationalHealthObserver = operationalHealthObserver;
        this.stagingDispatcher = stagingDispatcher;
        this.smokeTestRunner = smokeTestRunner;
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

        StagingOutcome stagingOutcome = prepareStagingIfConfigured(state, gate, prUrl, prNumber, publish);
        if (stagingOutcome.blocksRelease()) {
            return Optional.of(blockedWithStaging(publish,
                    "Release blocked: staging did not complete successfully.", stagingOutcome));
        }
        SmokeOutcome smokeOutcome = runSmokeTests(state, stagingOutcome, publish);
        if (smokeOutcome.blocksRelease()) {
            return Optional.of(blockedWithDeliveryChecks(publish,
                    "Release blocked: smoke tests did not complete successfully.", stagingOutcome, smokeOutcome));
        }

        state.setPhase(RunPhase.WAITING_FOR_RELEASE_APPROVAL);
        String gateMessage = smokeOutcome.status() == SmokeTestStatus.PASSED
                ? "Staging and smoke tests passed. Approve production release workflow dispatch."
                : stagingOutcome.status() == StagingStatus.DISPATCHED
                    ? "Staging verification passed. Approve production release workflow dispatch."
                : "Merge the pull request, then approve release workflow dispatch.";
        publish.accept(new ReleaseEvent("gate", gateMessage, Map.of(
                "gate", Gate.BEFORE_RELEASE.name(), "prUrl", prUrl,
                "workflow", releaseDispatcher.workflow(), "ref", releaseDispatcher.ref())));
        if (!gate.await(Gate.BEFORE_RELEASE).approved()) {
            publish.accept(new ReleaseEvent("step", "Release was not approved; no workflow was dispatched.",
                    Map.of("releaseStatus", ReleaseStatus.SKIPPED.name())));
            return Optional.of(outcome(ReleaseStatus.SKIPPED, null, null, null, null, null,
                    null, null, stagingOutcome, smokeOutcome));
        }

        try {
            if (!stagingOutcome.prMerged()) {
                state.setPhase(RunPhase.VERIFYING_RELEASE_PR);
            }
            if (!stagingOutcome.prMerged() && !gitHubClient.isPullRequestMerged(state.workspace().repoUrl(), prNumber)) {
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
            HealthOutcome healthOutcome = observeOperationalHealth(state, verificationStatus, publish);
            RollbackOutcome rollbackOutcome = dispatchRollbackIfNeeded(state, gate, verificationStatus, healthOutcome.status(), publish);
            return Optional.of(outcome(ReleaseStatus.DISPATCHED, result.htmlUrl(), result.workflowRunId(),
                    verificationStatus, rollbackOutcome.status(), rollbackOutcome.url(), healthOutcome.status(),
                    healthOutcome.url(), stagingOutcome, smokeOutcome));
        } catch (GitHubClientException | RuntimeException e) {
            publish.accept(new ReleaseEvent("warn", "Release dispatch failed: " + e.getMessage()
                    + ". Inspect GitHub Actions manually.", Map.of("releaseStatus", ReleaseStatus.FAILED.name())));
            return Optional.of(outcome(ReleaseStatus.FAILED, null, null, null, null, null,
                    null, null, stagingOutcome, smokeOutcome));
        }
    }

    private SmokeOutcome runSmokeTests(RunState state, StagingOutcome stagingOutcome,
                                       Consumer<ReleaseEvent> publish) {
        if (!smokeTestRunner.isConfigured()) return SmokeOutcome.notRun();
        if (stagingOutcome.status() != StagingStatus.DISPATCHED
                || stagingOutcome.verificationStatus() != VerificationStatus.PASSED) {
            publish.accept(new ReleaseEvent("warn", "Smoke tests require a successfully verified staging deployment.",
                    Map.of("smokeStatus", SmokeTestStatus.BLOCKED.name())));
            return new SmokeOutcome(SmokeTestStatus.BLOCKED, null);
        }

        state.setPhase(RunPhase.RUNNING_SMOKE_TESTS);
        SmokeTestObservation observation;
        try {
            observation = smokeTestRunner.run();
        } catch (RuntimeException e) {
            publish.accept(new ReleaseEvent("warn", "Smoke tests are unavailable: " + e.getMessage()
                    + ". Production release remains blocked.", Map.of("smokeStatus", SmokeTestStatus.UNAVAILABLE.name())));
            return new SmokeOutcome(SmokeTestStatus.UNAVAILABLE, null);
        }

        for (OperationalHealthObservation result : observation.results()) {
            Map<String, Object> data = new HashMap<>();
            data.put("smokeStatus", result.status().name());
            if (result.endpoint() != null) data.put("smokeUrl", result.endpoint());
            if (result.statusCode() != null) data.put("smokeStatusCode", result.statusCode());
            publish.accept(new ReleaseEvent(result.status() == OperationalHealthStatus.PASSED ? "step" : "warn",
                    smokeResultMessage(result), data));
        }
        String representativeUrl = observation.results().stream()
                .filter(result -> result.status() != OperationalHealthStatus.PASSED)
                .findFirst()
                .or(() -> observation.results().stream().findFirst())
                .map(OperationalHealthObservation::endpoint)
                .orElse(null);
        publish.accept(new ReleaseEvent(observation.status() == SmokeTestStatus.PASSED ? "step" : "warn",
                smokeSummaryMessage(observation.status()), Map.of("smokeStatus", observation.status().name())));
        return new SmokeOutcome(observation.status(), representativeUrl);
    }

    private StagingOutcome prepareStagingIfConfigured(RunState state, ApprovalGate gate, String prUrl, int prNumber,
                                                      Consumer<ReleaseEvent> publish) {
        if (!stagingDispatcher.isConfigured()) return StagingOutcome.notRequired();

        state.setPhase(RunPhase.WAITING_FOR_STAGING_APPROVAL);
        publish.accept(new ReleaseEvent("gate", "Merge the pull request, then approve staging workflow dispatch.", Map.of(
                "gate", Gate.BEFORE_STAGING.name(), "prUrl", prUrl,
                "workflow", stagingDispatcher.workflow(), "ref", stagingDispatcher.ref())));
        if (!gate.await(Gate.BEFORE_STAGING).approved()) {
            publish.accept(new ReleaseEvent("step", "Staging was not approved; no staging workflow was dispatched.",
                    Map.of("stagingStatus", StagingStatus.SKIPPED.name())));
            return new StagingOutcome(StagingStatus.SKIPPED, null, null, false);
        }

        try {
            state.setPhase(RunPhase.VERIFYING_STAGING_PR);
            if (!gitHubClient.isPullRequestMerged(state.workspace().repoUrl(), prNumber)) {
                publish.accept(new ReleaseEvent("warn", "Staging blocked: pull request #" + prNumber + " is not merged.",
                        Map.of("stagingStatus", StagingStatus.BLOCKED.name())));
                return new StagingOutcome(StagingStatus.BLOCKED, null, null, false);
            }

            state.setPhase(RunPhase.DISPATCHING_STAGING);
            WorkflowDispatchResult result = stagingDispatcher.dispatch(state.workspace().repoUrl());
            Map<String, Object> data = new HashMap<>();
            data.put("stagingStatus", StagingStatus.DISPATCHED.name());
            data.put("workflow", stagingDispatcher.workflow());
            data.put("ref", stagingDispatcher.ref());
            if (result.htmlUrl() != null) data.put("stagingUrl", result.htmlUrl());
            publish.accept(new ReleaseEvent("step", "Staging workflow dispatched.", data));
            VerificationStatus verificationStatus = observeStaging(state, result, publish);
            return new StagingOutcome(StagingStatus.DISPATCHED, result.htmlUrl(), verificationStatus, true);
        } catch (GitHubClientException | RuntimeException e) {
            publish.accept(new ReleaseEvent("warn", "Staging dispatch failed: " + e.getMessage()
                    + ". Inspect GitHub Actions manually.", Map.of("stagingStatus", StagingStatus.FAILED.name())));
            return new StagingOutcome(StagingStatus.FAILED, null, null, true);
        }
    }

    private VerificationStatus observeStaging(RunState state, WorkflowDispatchResult result,
                                              Consumer<ReleaseEvent> publish) {
        if (result.workflowRunId() == null) {
            publish.accept(new ReleaseEvent("warn", "Staging was dispatched but GitHub returned no workflow run ID.",
                    Map.of("stagingVerificationStatus", VerificationStatus.UNAVAILABLE.name())));
            return VerificationStatus.UNAVAILABLE;
        }

        state.setPhase(RunPhase.VERIFYING_STAGING);
        try {
            return releaseObserver.observe(state.workspace().repoUrl(), result.workflowRunId(), observation -> {
                Map<String, Object> data = new HashMap<>();
                data.put("stagingVerificationStatus", observation.status().name());
                if (result.htmlUrl() != null) data.put("stagingUrl", result.htmlUrl());
                publish.accept(new ReleaseEvent("step", stagingVerificationMessage(observation.status()), data));
            }).status();
        } catch (GitHubClientException | RuntimeException e) {
            publish.accept(new ReleaseEvent("warn", "Staging verification failed: " + e.getMessage()
                    + ". Inspect GitHub Actions manually.",
                    Map.of("stagingVerificationStatus", VerificationStatus.UNAVAILABLE.name())));
            return VerificationStatus.UNAVAILABLE;
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
                                                     OperationalHealthStatus healthStatus,
                                                     Consumer<ReleaseEvent> publish) {
        String failureSource = verificationStatus == VerificationStatus.FAILED
                ? "Release workflow verification"
                : healthStatus == OperationalHealthStatus.FAILED ? "Operational health check" : null;
        if (failureSource == null) return RollbackOutcome.notRequired();
        if (!rollbackDispatcher.isConfigured()) {
            publish.accept(new ReleaseEvent("warn",
                    failureSource + " failed and no rollback workflow is configured. Intervene manually.",
                    Map.of("rollbackStatus", RollbackStatus.UNAVAILABLE.name())));
            return new RollbackOutcome(RollbackStatus.UNAVAILABLE, null);
        }

        state.setPhase(RunPhase.WAITING_FOR_ROLLBACK_APPROVAL);
        publish.accept(new ReleaseEvent("gate",
                failureSource + " failed. Approve rollback workflow dispatch.",
                Map.of("gate", Gate.BEFORE_ROLLBACK.name(), "workflow", rollbackDispatcher.workflow(),
                        "ref", rollbackDispatcher.ref(), "failureSource", failureSource)));
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

    private String stagingVerificationMessage(VerificationStatus status) {
        return switch (status) {
            case PENDING -> "Waiting for staging workflow verification…";
            case PASSED -> "Staging workflow verification passed";
            case FAILED -> "Staging workflow verification failed";
            case TIMED_OUT -> "Timed out waiting for staging workflow verification";
            case UNAVAILABLE -> "Staging workflow verification is unavailable";
        };
    }

    private String smokeResultMessage(OperationalHealthObservation result) {
        String statusCode = result.statusCode() == null ? "" : " (HTTP " + result.statusCode() + ")";
        return switch (result.status()) {
            case PASSED -> "Smoke endpoint passed" + statusCode;
            case FAILED -> "Smoke endpoint failed" + statusCode;
            case UNAVAILABLE -> "Smoke endpoint is unavailable";
        };
    }

    private String smokeSummaryMessage(SmokeTestStatus status) {
        return switch (status) {
            case PASSED -> "All configured smoke tests passed";
            case FAILED -> "One or more configured smoke tests failed; production release remains blocked";
            case UNAVAILABLE -> "One or more configured smoke tests were unavailable; production release remains blocked";
            case BLOCKED -> "Smoke tests could not run; production release remains blocked";
        };
    }

    private HealthOutcome observeOperationalHealth(RunState state, VerificationStatus verificationStatus,
                                                   Consumer<ReleaseEvent> publish) {
        if (verificationStatus != VerificationStatus.PASSED || !operationalHealthObserver.isConfigured()) {
            return HealthOutcome.notRun();
        }

        state.setPhase(RunPhase.CHECKING_OPERATIONAL_HEALTH);
        try {
            OperationalHealthObservation observation = operationalHealthObserver.observe();
            Map<String, Object> data = new HashMap<>();
            data.put("healthStatus", observation.status().name());
            if (observation.endpoint() != null) data.put("healthUrl", observation.endpoint());
            if (observation.statusCode() != null) data.put("healthStatusCode", observation.statusCode());
            publish.accept(new ReleaseEvent(observation.status() == OperationalHealthStatus.UNAVAILABLE ? "warn" : "step",
                    healthMessage(observation), data));
            return new HealthOutcome(observation.status(), observation.endpoint());
        } catch (RuntimeException e) {
            publish.accept(new ReleaseEvent("warn", "Operational health check is unavailable: " + e.getMessage()
                    + ". Investigate manually.", Map.of("healthStatus", OperationalHealthStatus.UNAVAILABLE.name())));
            return new HealthOutcome(OperationalHealthStatus.UNAVAILABLE, null);
        }
    }

    private String healthMessage(OperationalHealthObservation observation) {
        String statusCode = observation.statusCode() == null ? "" : " (HTTP " + observation.statusCode() + ")";
        return switch (observation.status()) {
            case PASSED -> "Operational health check passed" + statusCode;
            case FAILED -> "Operational health check failed" + statusCode;
            case UNAVAILABLE -> "Operational health check is unavailable" + statusCode;
        };
    }

    private ReleaseOutcome blocked(Consumer<ReleaseEvent> publish, String message) {
        publish.accept(new ReleaseEvent("warn", message, Map.of("releaseStatus", ReleaseStatus.BLOCKED.name())));
        return outcome(ReleaseStatus.BLOCKED, null, null, null, null, null,
                null, null, StagingOutcome.notRequired(), SmokeOutcome.notRun());
    }

    private ReleaseOutcome blockedWithStaging(Consumer<ReleaseEvent> publish, String message,
                                              StagingOutcome stagingOutcome) {
        publish.accept(new ReleaseEvent("warn", message, Map.of("releaseStatus", ReleaseStatus.BLOCKED.name())));
        return outcome(ReleaseStatus.BLOCKED, null, null, null, null, null, null, null,
                stagingOutcome, SmokeOutcome.notRun());
    }

    private ReleaseOutcome blockedWithDeliveryChecks(Consumer<ReleaseEvent> publish, String message,
                                                     StagingOutcome stagingOutcome, SmokeOutcome smokeOutcome) {
        publish.accept(new ReleaseEvent("warn", message, Map.of("releaseStatus", ReleaseStatus.BLOCKED.name())));
        return outcome(ReleaseStatus.BLOCKED, null, null, null, null, null, null, null,
                stagingOutcome, smokeOutcome);
    }

    private ReleaseOutcome outcome(ReleaseStatus releaseStatus, String releaseUrl, Long workflowRunId,
                                   VerificationStatus verificationStatus, RollbackStatus rollbackStatus,
                                   String rollbackUrl, OperationalHealthStatus healthStatus, String healthUrl,
                                   StagingOutcome stagingOutcome, SmokeOutcome smokeOutcome) {
        return new ReleaseOutcome(releaseStatus, releaseUrl, workflowRunId, verificationStatus,
                rollbackStatus, rollbackUrl, healthStatus, healthUrl, stagingOutcome.status(), stagingOutcome.url(),
                stagingOutcome.verificationStatus(), smokeOutcome.status(), smokeOutcome.url());
    }

    private record RollbackOutcome(RollbackStatus status, String url) {
        private static RollbackOutcome notRequired() {
            return new RollbackOutcome(null, null);
        }
    }

    private record HealthOutcome(OperationalHealthStatus status, String url) {
        private static HealthOutcome notRun() {
            return new HealthOutcome(null, null);
        }
    }

    private record StagingOutcome(StagingStatus status, String url, VerificationStatus verificationStatus,
                                  boolean prMerged) {
        private static StagingOutcome notRequired() {
            return new StagingOutcome(null, null, null, false);
        }

        private boolean blocksRelease() {
            return status != null && (status != StagingStatus.DISPATCHED
                    || verificationStatus != VerificationStatus.PASSED);
        }
    }

    private record SmokeOutcome(SmokeTestStatus status, String url) {
        private static SmokeOutcome notRun() {
            return new SmokeOutcome(null, null);
        }

        private boolean blocksRelease() {
            return status != null && status != SmokeTestStatus.PASSED;
        }
    }
}
