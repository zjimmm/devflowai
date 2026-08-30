package ai.devflow.orchestrator;

import ai.devflow.agent.*;
import ai.devflow.event.RunEventPublisher;
import ai.devflow.workspace.*;
import org.junit.jupiter.api.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

class OrchestratorTest {

    Workspace workspace;
    RunEventPublisher events;
    ExecutorService pool;

    @BeforeEach
    void setUp() throws Exception {
        workspace = new FixtureWorkspace(Path.of("src/test/resources/fixture"), "orch-test");
        workspace.prepare();
        events = new RunEventPublisher();
        pool = Executors.newCachedThreadPool();
    }

    @AfterEach
    void tearDown() throws Exception {
        pool.shutdownNow();
        workspace.cleanup();
    }

    /** Returns a scripted sequence of results; the last entry repeats. */
    static class ScriptedAgent implements Agent {
        private final String name;
        private final List<AgentResult> script;
        private int i = 0;
        int calls = 0;
        ScriptedAgent(String name, List<AgentResult> script) { this.name = name; this.script = script; }
        @Override public String name() { return name; }
        @Override public AgentResult run(RunState s) {
            calls++;
            return script.get(Math.min(i++, script.size() - 1));
        }
    }

    /** Throws on every call — stands in for a 429 or a dropped connection. */
    static class ExplodingAgent implements Agent {
        private final String name;
        ExplodingAgent(String name) { this.name = name; }
        @Override public String name() { return name; }
        @Override public AgentResult run(RunState s) {
            throw new RuntimeException("429 rate limited");
        }
    }

    private Orchestrator orchestrator(Agent coder, Agent reviewer) {
        return new Orchestrator(coder, reviewer, events, 3, 5, Duration.ofMinutes(1));
    }

    /** Writes a real file so changedFiles() is non-empty, as a real coder would. */
    private ScriptedAgent writingCoder(String summary) {
        return new ScriptedAgent("coder", List.of(
                AgentResult.ok("coder", summary, List.of("scratch.txt"), TokenUsage.NONE))) {
            @Override public AgentResult run(RunState s) {
                try {
                    Files.writeString(s.workspace().root().resolve("scratch.txt"), "written " + calls);
                } catch (Exception e) { throw new RuntimeException(e); }
                return super.run(s);
            }
        };
    }

    /** Runs the orchestrator on a pool, approving every gate as it appears. */
    private Orchestrator.RunOutcome runApprovingAll(Orchestrator orchestrator, RunState state,
                                                    ApprovalGate gate) throws Exception {
        Future<Orchestrator.RunOutcome> outcome = pool.submit(() -> orchestrator.run(state, gate));
        approveGatesUntilDone(gate, outcome);
        return outcome.get(20, TimeUnit.SECONDS);
    }

    private void approveGatesUntilDone(ApprovalGate gate, Future<?> outcome) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while (!outcome.isDone()) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("run never finished");
            if (gate.pending() != null) gate.decide(ApprovalDecision.approve());
            Thread.sleep(5);
        }
    }

    @Test
    void approvedRunPassesThroughAllThreeGates() throws Exception {
        var coder = writingCoder("done");
        var reviewer = new ScriptedAgent("reviewer",
                List.of(AgentResult.ok("reviewer", "looks good", List.of(), TokenUsage.NONE)));
        var state = new RunState("g1", "do a thing", workspace);
        var gate = new ApprovalGate(Duration.ofSeconds(10));

        var outcome = runApprovingAll(orchestrator(coder, reviewer), state, gate);

        assertThat(outcome.approved()).isTrue();
        assertThat(coder.calls).isEqualTo(1);
        assertThat(reviewer.calls).isEqualTo(1);
        assertThat(state.phase()).isEqualTo(RunPhase.DONE);
    }

    @Test
    void rejectingPreFlightAbortsWithoutRunningTheCoder() throws Exception {
        var coder = writingCoder("should never run");
        var reviewer = new ScriptedAgent("reviewer",
                List.of(AgentResult.ok("reviewer", "ok", List.of(), TokenUsage.NONE)));
        var state = new RunState("g2", "t", workspace);
        var gate = new ApprovalGate(Duration.ofSeconds(10));

        Future<Orchestrator.RunOutcome> f = pool.submit(() -> orchestrator(coder, reviewer).run(state, gate));
        while (gate.pending() == null) Thread.sleep(5);
        gate.decide(ApprovalDecision.reject());

        assertThat(f.get(10, TimeUnit.SECONDS).approved()).isFalse();
        assertThat(coder.calls).isZero();
    }

    @Test
    void rejectingWithAReasonSendsAHumanFindingBackToTheCoder() throws Exception {
        var coder = writingCoder("attempt");
        var reviewer = new ScriptedAgent("reviewer",
                List.of(AgentResult.ok("reviewer", "ok", List.of(), TokenUsage.NONE)));
        var state = new RunState("g3", "t", workspace);
        var gate = new ApprovalGate(Duration.ofSeconds(10));

        Future<Orchestrator.RunOutcome> f = pool.submit(() -> orchestrator(coder, reviewer).run(state, gate));

        // Approve pre-flight, then reject the build gate with a reason once.
        while (gate.pending() != Gate.PRE_FLIGHT) Thread.sleep(5);
        gate.decide(ApprovalDecision.approve());
        while (gate.pending() != Gate.BEFORE_BUILD) Thread.sleep(5);
        gate.decide(ApprovalDecision.rejectWith("use a DTO"));

        // Then approve everything else so the run can finish.
        approveGatesUntilDone(gate, f);
        f.get(20, TimeUnit.SECONDS);

        assertThat(coder.calls)
                .as("human rejection sent work back to the coder")
                .isGreaterThanOrEqualTo(2);
        assertThat(state.humanIterations()).isEqualTo(1);
        assertThat(state.reviewIterations())
                .as("human steering must not consume the reviewer's cap")
                .isLessThanOrEqualTo(2);
    }

    @Test
    void loopsBackToTheCoderThenStopsAtTheReviewCap() throws Exception {
        var bounce = AgentResult.needsWork("reviewer", "nope",
                List.of(new Finding(Finding.Origin.REVIEWER, Finding.Severity.HIGH,
                        "A.java", 1, "still wrong")), TokenUsage.NONE);
        var coder = writingCoder("attempt");
        var reviewer = new ScriptedAgent("reviewer", List.of(bounce));
        var state = new RunState("g4", "t", workspace);
        var gate = new ApprovalGate(Duration.ofSeconds(10));

        var outcome = runApprovingAll(orchestrator(coder, reviewer), state, gate);

        assertThat(outcome.approved()).isFalse();
        assertThat(coder.calls).isEqualTo(3);
        assertThat(state.reviewIterations()).isEqualTo(3);
    }

    @Test
    void aFailedReviewIsNotTreatedAsApproval() throws Exception {
        var coder = writingCoder("attempt");
        var reviewer = new ScriptedAgent("reviewer",
                List.of(new AgentResult("reviewer", AgentResult.Status.FAILED,
                        "unparseable", List.of(), List.of(), TokenUsage.NONE)));
        var state = new RunState("g5", "t", workspace);
        var gate = new ApprovalGate(Duration.ofSeconds(10));

        assertThat(runApprovingAll(orchestrator(coder, reviewer), state, gate).approved()).isFalse();
    }

    @Test
    void anExceptionFromAnAgentBecomesAFailedOutcomeNotAnEscapedThrowable() throws Exception {
        var state = new RunState("g6", "t", workspace);
        var gate = new ApprovalGate(Duration.ofSeconds(10));
        var orchestrator = orchestrator(new ExplodingAgent("coder"),
                new ScriptedAgent("reviewer", List.of(
                        AgentResult.ok("reviewer", "ok", List.of(), TokenUsage.NONE))));

        var outcome = runApprovingAll(orchestrator, state, gate);

        assertThat(outcome.approved()).isFalse();
        assertThat(outcome.reason()).contains("429");
        assertThat(state.phase()).isEqualTo(RunPhase.FAILED);
    }

    @Test
    void anEmptyChangesetIsNeverApproved() throws Exception {
        // A coder that writes nothing: changedFiles() stays empty. Even if the
        // reviewer says OK (nothing to complain about), approving would claim a
        // change was made and reviewed when neither happened.
        var idleCoder = new ScriptedAgent("coder",
                List.of(AgentResult.ok("coder", "I did nothing", List.of(), TokenUsage.NONE)));
        var reviewer = new ScriptedAgent("reviewer",
                List.of(AgentResult.ok("reviewer", "nothing to review", List.of(), TokenUsage.NONE)));
        var state = new RunState("g7", "t", workspace);
        var gate = new ApprovalGate(Duration.ofSeconds(10));

        var outcome = runApprovingAll(orchestrator(idleCoder, reviewer), state, gate);

        assertThat(outcome.approved()).isFalse();
        assertThat(outcome.reason()).containsIgnoringCase("no changes");
    }

    @Test
    void orchestratorNeverHoldsFileContents() throws Exception {
        var coder = writingCoder("done");
        var reviewer = new ScriptedAgent("reviewer",
                List.of(AgentResult.ok("reviewer", "ok", List.of(), TokenUsage.NONE)));
        var state = new RunState("g8", "t", workspace);
        var gate = new ApprovalGate(Duration.ofSeconds(10));

        runApprovingAll(orchestrator(coder, reviewer), state, gate);

        assertThat(state.history()).allSatisfy(r ->
                assertThat(r.summary().length()).isLessThan(2_000));
    }
}
