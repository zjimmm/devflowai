package ai.devflow.orchestrator;

import ai.devflow.tools.GitHubClient;
import ai.devflow.tools.PullRequestResult;
import ai.devflow.tools.WorkflowDispatchResult;
import ai.devflow.workspace.PathGuard;
import ai.devflow.workspace.Workspace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class ReleaseCoordinatorTest {

    private final ExecutorService pool = Executors.newCachedThreadPool();

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    @Test
    void approvedReleaseVerifiesMergeThenDispatches() throws Exception {
        var client = new RecordingClient(true);
        var coordinator = coordinator(client);
        var gate = new ApprovalGate(Duration.ofSeconds(5));
        var events = new ArrayList<ReleaseCoordinator.ReleaseEvent>();

        Future<Optional<ReleaseCoordinator.ReleaseOutcome>> future = pool.submit(() -> coordinator.dispatchIfRequested(
                releaseState(), gate, "https://github.com/o/r/pull/9", 9, CiStatus.PASSED, events::add));
        awaitGate(gate, Gate.BEFORE_RELEASE);
        gate.decide(ApprovalDecision.approve());

        var outcome = future.get().orElseThrow();
        assertThat(outcome.status()).isEqualTo(ReleaseStatus.DISPATCHED);
        assertThat(outcome.url()).isEqualTo("https://github.com/o/r/actions/runs/7");
        assertThat(outcome.verificationStatus()).isEqualTo(VerificationStatus.PASSED);
        assertThat(client.mergeChecks).isEqualTo(1);
        assertThat(client.dispatches).isEqualTo(1);
        assertThat(events).anyMatch(event -> event.message().contains("Release workflow dispatched"));
    }

    @Test
    void rejectedReleaseDoesNotCheckMergeOrDispatch() throws Exception {
        var client = new RecordingClient(true);
        var coordinator = coordinator(client);
        var gate = new ApprovalGate(Duration.ofSeconds(5));

        Future<Optional<ReleaseCoordinator.ReleaseOutcome>> future = pool.submit(() -> coordinator.dispatchIfRequested(
                releaseState(), gate, "https://github.com/o/r/pull/9", 9, CiStatus.PASSED, event -> { }));
        awaitGate(gate, Gate.BEFORE_RELEASE);
        gate.decide(ApprovalDecision.reject());

        assertThat(future.get().orElseThrow().status()).isEqualTo(ReleaseStatus.SKIPPED);
        assertThat(client.mergeChecks).isZero();
        assertThat(client.dispatches).isZero();
    }

    @Test
    void nonPassingCiOrAnUnmergedPrBlocksDispatch() throws Exception {
        var failedCiClient = new RecordingClient(true);
        var failedCi = coordinator(failedCiClient).dispatchIfRequested(releaseState(), new ApprovalGate(Duration.ofSeconds(5)),
                "https://github.com/o/r/pull/9", 9, CiStatus.FAILED, event -> { }).orElseThrow();

        assertThat(failedCi.status()).isEqualTo(ReleaseStatus.BLOCKED);
        assertThat(failedCiClient.mergeChecks).isZero();
        assertThat(failedCiClient.dispatches).isZero();

        var unmergedClient = new RecordingClient(false);
        var gate = new ApprovalGate(Duration.ofSeconds(5));
        Future<Optional<ReleaseCoordinator.ReleaseOutcome>> future = pool.submit(() -> coordinator(unmergedClient)
                .dispatchIfRequested(releaseState(), gate, "https://github.com/o/r/pull/9", 9, CiStatus.PASSED, event -> { }));
        awaitGate(gate, Gate.BEFORE_RELEASE);
        gate.decide(ApprovalDecision.approve());

        assertThat(future.get().orElseThrow().status()).isEqualTo(ReleaseStatus.BLOCKED);
        assertThat(unmergedClient.mergeChecks).isEqualTo(1);
        assertThat(unmergedClient.dispatches).isZero();
    }

    @Test
    void unavailableVerificationDoesNotUndoTheDispatchedRelease() throws Exception {
        var client = new RecordingClient(true);
        ReleaseObserver unavailableObserver = (repoUrl, workflowRunId, onUpdate) -> {
            throw new ai.devflow.tools.GitHubClientException("GitHub is unavailable");
        };
        var coordinator = new ReleaseCoordinator(client,
                new GitHubActionsReleaseDispatcher(client, "release.yml", "main"), unavailableObserver);
        var gate = new ApprovalGate(Duration.ofSeconds(5));
        var events = new ArrayList<ReleaseCoordinator.ReleaseEvent>();

        Future<Optional<ReleaseCoordinator.ReleaseOutcome>> future = pool.submit(() -> coordinator.dispatchIfRequested(
                releaseState(), gate, "https://github.com/o/r/pull/9", 9, CiStatus.PASSED, events::add));
        awaitGate(gate, Gate.BEFORE_RELEASE);
        gate.decide(ApprovalDecision.approve());

        var outcome = future.get().orElseThrow();
        assertThat(outcome.status()).isEqualTo(ReleaseStatus.DISPATCHED);
        assertThat(outcome.verificationStatus()).isEqualTo(VerificationStatus.UNAVAILABLE);
        assertThat(client.dispatches).isEqualTo(1);
        assertThat(events).anyMatch(event -> event.message().contains("Release verification failed"));
    }

    @Test
    void stagingMustPassBeforeProductionReleaseCanBeApproved() throws Exception {
        var client = new RecordingClient(true);
        var coordinator = new ReleaseCoordinator(client,
                new GitHubActionsReleaseDispatcher(client, "release.yml", "main"), passedObserver(),
                RollbackDispatcher.disabled(), OperationalHealthObserver.disabled(),
                new GitHubActionsStagingDispatcher(client, "staging.yml", "main"));
        var gate = new ApprovalGate(Duration.ofSeconds(5));

        Future<Optional<ReleaseCoordinator.ReleaseOutcome>> future = pool.submit(() -> coordinator.dispatchIfRequested(
                releaseState(), gate, "https://github.com/o/r/pull/9", 9, CiStatus.PASSED, event -> { }));
        awaitGate(gate, Gate.BEFORE_STAGING);
        gate.decide(ApprovalDecision.approve());
        awaitGate(gate, Gate.BEFORE_RELEASE);
        assertThat(client.workflows).containsExactly("staging.yml");
        gate.decide(ApprovalDecision.approve());

        var outcome = future.get().orElseThrow();
        assertThat(outcome.stagingStatus()).isEqualTo(StagingStatus.DISPATCHED);
        assertThat(outcome.stagingVerificationStatus()).isEqualTo(VerificationStatus.PASSED);
        assertThat(outcome.status()).isEqualTo(ReleaseStatus.DISPATCHED);
        assertThat(client.workflows).containsExactly("staging.yml", "release.yml");
    }

    @Test
    void failedStagingVerificationBlocksProductionRelease() throws Exception {
        var client = new RecordingClient(true);
        var coordinator = new ReleaseCoordinator(client,
                new GitHubActionsReleaseDispatcher(client, "release.yml", "main"), failedObserver(),
                RollbackDispatcher.disabled(), OperationalHealthObserver.disabled(),
                new GitHubActionsStagingDispatcher(client, "staging.yml", "main"));
        var gate = new ApprovalGate(Duration.ofSeconds(5));

        Future<Optional<ReleaseCoordinator.ReleaseOutcome>> future = pool.submit(() -> coordinator.dispatchIfRequested(
                releaseState(), gate, "https://github.com/o/r/pull/9", 9, CiStatus.PASSED, event -> { }));
        awaitGate(gate, Gate.BEFORE_STAGING);
        gate.decide(ApprovalDecision.approve());

        var outcome = future.get().orElseThrow();
        assertThat(outcome.stagingStatus()).isEqualTo(StagingStatus.DISPATCHED);
        assertThat(outcome.stagingVerificationStatus()).isEqualTo(VerificationStatus.FAILED);
        assertThat(outcome.status()).isEqualTo(ReleaseStatus.BLOCKED);
        assertThat(client.workflows).containsExactly("staging.yml");
    }

    @Test
    void failedDeploymentRequiresSeparateRollbackApprovalBeforeDispatch() throws Exception {
        var client = new RecordingClient(true);
        var coordinator = new ReleaseCoordinator(client,
                new GitHubActionsReleaseDispatcher(client, "release.yml", "main"), failedObserver(),
                new GitHubActionsRollbackDispatcher(client, "rollback.yml", "main"));
        var gate = new ApprovalGate(Duration.ofSeconds(5));
        var events = new ArrayList<ReleaseCoordinator.ReleaseEvent>();

        Future<Optional<ReleaseCoordinator.ReleaseOutcome>> future = pool.submit(() -> coordinator.dispatchIfRequested(
                releaseState(), gate, "https://github.com/o/r/pull/9", 9, CiStatus.PASSED, events::add));
        awaitGate(gate, Gate.BEFORE_RELEASE);
        gate.decide(ApprovalDecision.approve());
        awaitGate(gate, Gate.BEFORE_ROLLBACK);
        gate.decide(ApprovalDecision.approve());

        var outcome = future.get().orElseThrow();
        assertThat(outcome.status()).isEqualTo(ReleaseStatus.DISPATCHED);
        assertThat(outcome.verificationStatus()).isEqualTo(VerificationStatus.FAILED);
        assertThat(outcome.rollbackStatus()).isEqualTo(RollbackStatus.DISPATCHED);
        assertThat(outcome.rollbackUrl()).isEqualTo("https://github.com/o/r/actions/runs/7");
        assertThat(client.workflows).containsExactly("release.yml", "rollback.yml");
        assertThat(events).anyMatch(event -> Gate.BEFORE_ROLLBACK.name().equals(event.data().get("gate")));
    }

    @Test
    void failedOperationalHealthCheckRequiresSeparateRollbackApprovalBeforeDispatch() throws Exception {
        var client = new RecordingClient(true);
        OperationalHealthObserver failedHealthCheck = new OperationalHealthObserver() {
            @Override public boolean isConfigured() { return true; }
            @Override public OperationalHealthObservation observe() {
                return new OperationalHealthObservation(OperationalHealthStatus.FAILED,
                        "https://service.example/health", 503);
            }
        };
        var coordinator = new ReleaseCoordinator(client,
                new GitHubActionsReleaseDispatcher(client, "release.yml", "main"), passedObserver(),
                new GitHubActionsRollbackDispatcher(client, "rollback.yml", "main"), failedHealthCheck);
        var gate = new ApprovalGate(Duration.ofSeconds(5));
        var events = new ArrayList<ReleaseCoordinator.ReleaseEvent>();

        Future<Optional<ReleaseCoordinator.ReleaseOutcome>> future = pool.submit(() -> coordinator.dispatchIfRequested(
                releaseState(), gate, "https://github.com/o/r/pull/9", 9, CiStatus.PASSED, events::add));
        awaitGate(gate, Gate.BEFORE_RELEASE);
        gate.decide(ApprovalDecision.approve());
        awaitGate(gate, Gate.BEFORE_ROLLBACK);
        gate.decide(ApprovalDecision.approve());

        var outcome = future.get().orElseThrow();
        assertThat(outcome.verificationStatus()).isEqualTo(VerificationStatus.PASSED);
        assertThat(outcome.healthStatus()).isEqualTo(OperationalHealthStatus.FAILED);
        assertThat(outcome.healthUrl()).isEqualTo("https://service.example/health");
        assertThat(outcome.rollbackStatus()).isEqualTo(RollbackStatus.DISPATCHED);
        assertThat(events).anyMatch(event -> event.message().contains("Operational health check failed"));
    }

    @Test
    void unavailableOperationalHealthCheckDoesNotDispatchRollback() throws Exception {
        var client = new RecordingClient(true);
        OperationalHealthObserver unavailableHealthCheck = new OperationalHealthObserver() {
            @Override public boolean isConfigured() { return true; }
            @Override public OperationalHealthObservation observe() {
                throw new IllegalStateException("connection timed out");
            }
        };
        var coordinator = new ReleaseCoordinator(client,
                new GitHubActionsReleaseDispatcher(client, "release.yml", "main"), passedObserver(),
                new GitHubActionsRollbackDispatcher(client, "rollback.yml", "main"), unavailableHealthCheck);
        var gate = new ApprovalGate(Duration.ofSeconds(5));

        Future<Optional<ReleaseCoordinator.ReleaseOutcome>> future = pool.submit(() -> coordinator.dispatchIfRequested(
                releaseState(), gate, "https://github.com/o/r/pull/9", 9, CiStatus.PASSED, event -> { }));
        awaitGate(gate, Gate.BEFORE_RELEASE);
        gate.decide(ApprovalDecision.approve());

        var outcome = future.get().orElseThrow();
        assertThat(outcome.healthStatus()).isEqualTo(OperationalHealthStatus.UNAVAILABLE);
        assertThat(outcome.rollbackStatus()).isNull();
        assertThat(client.workflows).containsExactly("release.yml");
    }

    @Test
    void rejectedRollbackDoesNotDispatchIt() throws Exception {
        var client = new RecordingClient(true);
        var coordinator = new ReleaseCoordinator(client,
                new GitHubActionsReleaseDispatcher(client, "release.yml", "main"), failedObserver(),
                new GitHubActionsRollbackDispatcher(client, "rollback.yml", "main"));
        var gate = new ApprovalGate(Duration.ofSeconds(5));

        Future<Optional<ReleaseCoordinator.ReleaseOutcome>> future = pool.submit(() -> coordinator.dispatchIfRequested(
                releaseState(), gate, "https://github.com/o/r/pull/9", 9, CiStatus.PASSED, event -> { }));
        awaitGate(gate, Gate.BEFORE_RELEASE);
        gate.decide(ApprovalDecision.approve());
        awaitGate(gate, Gate.BEFORE_ROLLBACK);
        gate.decide(ApprovalDecision.reject());

        var outcome = future.get().orElseThrow();
        assertThat(outcome.rollbackStatus()).isEqualTo(RollbackStatus.SKIPPED);
        assertThat(client.workflows).containsExactly("release.yml");
    }

    @Test
    void failedDeploymentWithoutARollbackWorkflowRequiresManualIntervention() throws Exception {
        var client = new RecordingClient(true);
        var coordinator = new ReleaseCoordinator(client,
                new GitHubActionsReleaseDispatcher(client, "release.yml", "main"), failedObserver());
        var gate = new ApprovalGate(Duration.ofSeconds(5));
        var events = new ArrayList<ReleaseCoordinator.ReleaseEvent>();

        Future<Optional<ReleaseCoordinator.ReleaseOutcome>> future = pool.submit(() -> coordinator.dispatchIfRequested(
                releaseState(), gate, "https://github.com/o/r/pull/9", 9, CiStatus.PASSED, events::add));
        awaitGate(gate, Gate.BEFORE_RELEASE);
        gate.decide(ApprovalDecision.approve());

        var outcome = future.get().orElseThrow();
        assertThat(outcome.verificationStatus()).isEqualTo(VerificationStatus.FAILED);
        assertThat(outcome.rollbackStatus()).isEqualTo(RollbackStatus.UNAVAILABLE);
        assertThat(client.workflows).containsExactly("release.yml");
        assertThat(events).anyMatch(event -> event.message().contains("no rollback workflow is configured"));
    }

    private ReleaseCoordinator coordinator(RecordingClient client) {
        return new ReleaseCoordinator(client, new GitHubActionsReleaseDispatcher(client, "release.yml", "main"),
                passedObserver());
    }

    private ReleaseObserver passedObserver() {
        return (repoUrl, workflowRunId, onUpdate) -> {
            var observation = new ReleaseObservation(VerificationStatus.PASSED,
                    new ai.devflow.tools.WorkflowRun("completed", "success", "https://github.com/o/r/actions/runs/7"));
            onUpdate.accept(observation);
            return observation;
        };
    }

    private ReleaseObserver failedObserver() {
        return (repoUrl, workflowRunId, onUpdate) -> {
            var observation = new ReleaseObservation(VerificationStatus.FAILED,
                    new ai.devflow.tools.WorkflowRun("completed", "failure", "https://github.com/o/r/actions/runs/7"));
            onUpdate.accept(observation);
            return observation;
        };
    }

    private RunState releaseState() {
        return new RunState("release", "task", workspace(), "o-r", true, true);
    }

    private Workspace workspace() {
        return new Workspace() {
            @Override public Path root() { return Path.of("."); }
            @Override public String branchName() { return "devflowai/release"; }
            @Override public PathGuard guard() { return null; }
            @Override public String repoUrl() { return "https://github.com/o/r"; }
            @Override public void prepare() { }
            @Override public void cleanup() { }
        };
    }

    private void awaitGate(ApprovalGate gate, Gate expectedGate) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (gate.pending() != expectedGate) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError(expectedGate + " gate was never reached");
            Thread.sleep(5);
        }
    }

    private static class RecordingClient implements GitHubClient {
        private final boolean merged;
        int mergeChecks;
        int dispatches;
        final List<String> workflows = new ArrayList<>();

        RecordingClient(boolean merged) {
            this.merged = merged;
        }

        @Override public void push(Workspace workspace, String branchName) { }
        @Override public PullRequestResult openPullRequest(String repoUrl, String branchName, String title, String body) {
            throw new UnsupportedOperationException();
        }
        @Override public boolean isPullRequestMerged(String repoUrl, int pullRequestNumber) {
            mergeChecks++;
            return merged;
        }
        @Override public WorkflowDispatchResult dispatchWorkflow(String repoUrl, String workflow, String ref) {
            dispatches++;
            workflows.add(workflow);
            return new WorkflowDispatchResult(7L, "https://github.com/o/r/actions/runs/7");
        }
    }
}
