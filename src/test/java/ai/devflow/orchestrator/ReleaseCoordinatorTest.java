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
        awaitGate(gate);
        gate.decide(ApprovalDecision.approve());

        var outcome = future.get().orElseThrow();
        assertThat(outcome.status()).isEqualTo(ReleaseStatus.DISPATCHED);
        assertThat(outcome.url()).isEqualTo("https://github.com/o/r/actions/runs/7");
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
        awaitGate(gate);
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
        awaitGate(gate);
        gate.decide(ApprovalDecision.approve());

        assertThat(future.get().orElseThrow().status()).isEqualTo(ReleaseStatus.BLOCKED);
        assertThat(unmergedClient.mergeChecks).isEqualTo(1);
        assertThat(unmergedClient.dispatches).isZero();
    }

    private ReleaseCoordinator coordinator(RecordingClient client) {
        return new ReleaseCoordinator(client, new GitHubActionsReleaseDispatcher(client, "release.yml", "main"));
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

    private void awaitGate(ApprovalGate gate) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (gate.pending() != Gate.BEFORE_RELEASE) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("release gate was never reached");
            Thread.sleep(5);
        }
    }

    private static class RecordingClient implements GitHubClient {
        private final boolean merged;
        int mergeChecks;
        int dispatches;

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
            return new WorkflowDispatchResult(7L, "https://github.com/o/r/actions/runs/7");
        }
    }
}
