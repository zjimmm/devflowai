package ai.devflow.orchestrator;

import ai.devflow.agent.Agent;
import ai.devflow.agent.AgentResult;
import ai.devflow.agent.TokenUsage;
import ai.devflow.event.RunEventPublisher;
import ai.devflow.tools.GitHubClient;
import ai.devflow.tools.GitHubClientException;
import ai.devflow.tools.PullRequestResult;
import ai.devflow.workspace.FixtureWorkspace;
import ai.devflow.workspace.Workspace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class DirectExecutorTest {

    Workspace workspace;
    RunEventPublisher events;

    @BeforeEach
    void setUp() throws Exception {
        workspace = new FixtureWorkspace(Path.of("src/test/resources/fixture"), "direct-test");
        workspace.prepare();
        events = new RunEventPublisher();
    }

    @AfterEach
    void tearDown() throws Exception { workspace.cleanup(); }

    static class ScriptedAgent implements Agent {
        private final String name;
        private final AgentResult result;
        int calls = 0;
        ScriptedAgent(String name, AgentResult result) { this.name = name; this.result = result; }
        @Override public String name() { return name; }
        @Override public AgentResult run(RunState s) { calls++; return result; }
    }

    /** Writes a real file so changedFiles() is non-empty, as a real coder would. */
    private ScriptedAgent writingCoder(String summary) {
        return new ScriptedAgent("coder", AgentResult.ok("coder", summary, List.of("scratch.txt"), TokenUsage.NONE)) {
            @Override public AgentResult run(RunState s) {
                try {
                    Files.writeString(s.workspace().root().resolve("scratch.txt"), "written " + calls);
                } catch (Exception e) { throw new RuntimeException(e); }
                return super.run(s);
            }
        };
    }

    private DirectExecutor executor(Agent coder) {
        return executor(coder, defaultGitHubClient());
    }

    private DirectExecutor executor(Agent coder, GitHubClient gitHubClient) {
        return new DirectExecutor(coder, events, Duration.ofMinutes(1), gitHubClient);
    }

    static GitHubClient defaultGitHubClient() {
        return new GitHubClient() {
            @Override public void push(ai.devflow.workspace.Workspace workspace, String branchName) {
                throw new UnsupportedOperationException("no test using this default expects a push");
            }
            @Override public PullRequestResult openPullRequest(String repoUrl, String branchName, String title, String body) {
                throw new UnsupportedOperationException("no test using this default expects a PR");
            }
        };
    }

    @Test
    void aSuccessfulCoderRunCommitsAndReachesDone() throws Exception {
        var coder = writingCoder("done");
        var state = new RunState("d1", "add a class", workspace);
        var gate = new ApprovalGate(Duration.ofSeconds(10));

        var outcome = executor(coder).run(state, gate);

        assertThat(outcome.approved()).isTrue();
        assertThat(coder.calls).isEqualTo(1);
        assertThat(state.phase()).isEqualTo(RunPhase.DONE);
    }

    @Test
    void noGateIsEverAwaited() throws Exception {
        var coder = writingCoder("done");
        var state = new RunState("d2", "add a class", workspace);
        var gate = new ApprovalGate(Duration.ofSeconds(10));

        executor(coder).run(state, gate);

        assertThat(gate.pending()).isNull();
    }

    @Test
    void anEmptyChangesetIsNeverApproved() throws Exception {
        var idleCoder = new ScriptedAgent("coder", AgentResult.ok("coder", "did nothing", List.of(), TokenUsage.NONE));
        var state = new RunState("d3", "t", workspace);
        var gate = new ApprovalGate(Duration.ofSeconds(10));

        var outcome = executor(idleCoder).run(state, gate);

        assertThat(outcome.approved()).isFalse();
        assertThat(outcome.reason()).containsIgnoringCase("no changes");
    }

    @Test
    void aFailedCoderResultEndsTheRunWithoutCrashing() throws Exception {
        var failingCoder = new ScriptedAgent("coder",
                new AgentResult("coder", AgentResult.Status.FAILED, "boom", List.of(), List.of(), TokenUsage.NONE));
        var state = new RunState("d4", "t", workspace);
        var gate = new ApprovalGate(Duration.ofSeconds(10));

        var outcome = executor(failingCoder).run(state, gate);

        assertThat(outcome.approved()).isFalse();
        assertThat(outcome.reason()).contains("Coder failed");
    }

    @Test
    void workspaceIsCleanedUpOnEveryExitPath() throws Exception {
        var coder = writingCoder("done");
        var state = new RunState("d5", "t", workspace);
        var gate = new ApprovalGate(Duration.ofSeconds(10));

        executor(coder).run(state, gate);

        assertThat(workspace.root()).doesNotExist();
    }

    @Test
    void openPrPushesThenOpensAPrAndTheDoneEventCarriesItsUrl() throws Exception {
        var coder = writingCoder("done");
        var captured = new ArrayList<Object>();
        var recordingEvents = new RunEventPublisher(captured::add);
        var pushed = new AtomicInteger(0);
        GitHubClient fakeClient = new GitHubClient() {
            @Override public void push(ai.devflow.workspace.Workspace workspace, String branchName) {
                pushed.incrementAndGet();
            }
            @Override public PullRequestResult openPullRequest(String repoUrl, String branchName, String title, String body) {
                return new PullRequestResult("https://github.com/o/r/pull/9", 9);
            }
        };
        var state = new RunState("dpr1", "t", workspace, "fixture", true);
        var gate = new ApprovalGate(Duration.ofSeconds(10));

        CiObserver passedObserver = (repoUrl, ref, onUpdate) -> {
            var observation = new CiObservation(CiStatus.PASSED, List.of());
            onUpdate.accept(observation);
            return observation;
        };
        var outcome = new DirectExecutor(coder, recordingEvents, Duration.ofMinutes(1), fakeClient, passedObserver)
                .run(state, gate);

        assertThat(outcome.approved()).isTrue();
        assertThat(pushed.get()).isEqualTo(1);
        var doneEvent = recordedEvents(captured).stream()
                .filter(e -> "done".equals(e.type()))
                .findFirst().orElseThrow();
        assertThat(doneEvent.data()).containsEntry("prUrl", "https://github.com/o/r/pull/9")
                .containsEntry("ciStatus", "PASSED");
        assertThat(recordedEvents(captured)).anyMatch(event ->
                "VALIDATING_CI".equals(event.data().get("phase")));
    }

    @Test
    void aPushFailureIsReportedAsALostCommitAndTheRunFails() throws Exception {
        var coder = writingCoder("done");
        GitHubClient failingClient = new GitHubClient() {
            @Override public void push(ai.devflow.workspace.Workspace workspace, String branchName) throws GitHubClientException {
                throw new GitHubClientException("network unreachable");
            }
            @Override public PullRequestResult openPullRequest(String repoUrl, String branchName, String title, String body) {
                throw new UnsupportedOperationException("push already failed");
            }
        };
        var state = new RunState("dpr2", "t", workspace, "fixture", true);
        var gate = new ApprovalGate(Duration.ofSeconds(10));

        var outcome = new DirectExecutor(coder, events, Duration.ofMinutes(1), failingClient).run(state, gate);

        assertThat(outcome.approved()).isFalse();
        assertThat(outcome.reason()).contains("Push failed").contains("now lost");
    }

    @Test
    void aPrCreationFailureStillEndsTheRunDone() throws Exception {
        var coder = writingCoder("done");
        var captured = new ArrayList<Object>();
        var recordingEvents = new RunEventPublisher(captured::add);
        GitHubClient pushOnlyClient = new GitHubClient() {
            @Override public void push(ai.devflow.workspace.Workspace workspace, String branchName) { }
            @Override public PullRequestResult openPullRequest(String repoUrl, String branchName, String title, String body)
                    throws GitHubClientException {
                throw new GitHubClientException("insufficient permissions");
            }
        };
        var state = new RunState("dpr3", "t", workspace, "fixture", true);
        var gate = new ApprovalGate(Duration.ofSeconds(10));

        var outcome = new DirectExecutor(coder, recordingEvents, Duration.ofMinutes(1), pushOnlyClient).run(state, gate);

        assertThat(outcome.approved()).isTrue();
        var doneEvent = recordedEvents(captured).stream()
                .filter(e -> "done".equals(e.type()))
                .findFirst().orElseThrow();
        assertThat(doneEvent.data()).doesNotContainKey("prUrl");
    }

    @Test
    void unavailableCiStillCompletesTheAlreadyOpenedPullRequest() throws Exception {
        var coder = writingCoder("done");
        var captured = new ArrayList<Object>();
        var recordingEvents = new RunEventPublisher(captured::add);
        GitHubClient fakeClient = new GitHubClient() {
            @Override public void push(ai.devflow.workspace.Workspace workspace, String branchName) { }
            @Override public PullRequestResult openPullRequest(String repoUrl, String branchName, String title, String body) {
                return new PullRequestResult("https://github.com/o/r/pull/10", 10);
            }
        };
        CiObserver unavailableObserver = (repoUrl, ref, onUpdate) -> {
            throw new GitHubClientException("GitHub is unavailable");
        };
        var state = new RunState("dpr4", "t", workspace, "fixture", true);

        var outcome = new DirectExecutor(coder, recordingEvents, Duration.ofMinutes(1), fakeClient, unavailableObserver)
                .run(state, new ApprovalGate(Duration.ofSeconds(10)));

        assertThat(outcome.approved()).isTrue();
        var runEvents = recordedEvents(captured);
        assertThat(runEvents).anyMatch(event -> "warn".equals(event.type()) && event.message().contains("CI observation failed"));
        assertThat(runEvents).filteredOn(event -> "done".equals(event.type()))
                .singleElement().extracting(event -> event.data().get("ciStatus")).isEqualTo("UNAVAILABLE");
    }

    private List<ai.devflow.event.RunEvent> recordedEvents(List<Object> captured) {
        return captured.stream()
                .filter(ai.devflow.event.RunRecorded.class::isInstance)
                .map(ai.devflow.event.RunRecorded.class::cast)
                .map(ai.devflow.event.RunRecorded::event)
                .toList();
    }
}
