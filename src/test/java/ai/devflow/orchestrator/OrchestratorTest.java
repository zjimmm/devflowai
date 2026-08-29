package ai.devflow.orchestrator;

import ai.devflow.agent.*;
import ai.devflow.workspace.*;
import org.junit.jupiter.api.*;
import java.nio.file.Path;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class OrchestratorTest {

    Workspace workspace;

    @BeforeEach
    void setUp() throws Exception {
        workspace = new FixtureWorkspace(Path.of("src/test/resources/fixture"), "orch-test");
        workspace.prepare();
    }

    @AfterEach
    void tearDown() throws Exception { workspace.cleanup(); }

    /** A stub agent that returns a scripted sequence of results. */
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

    @Test
    void stopsWhenTheReviewerApproves() {
        var coder = new ScriptedAgent("coder",
                List.of(AgentResult.ok("coder", "done", List.of("A.java"), TokenUsage.NONE)));
        var reviewer = new ScriptedAgent("reviewer",
                List.of(AgentResult.ok("reviewer", "looks good", List.of(), TokenUsage.NONE)));

        var state = new RunState("orch-test", "do a thing", workspace);
        var outcome = new Orchestrator(coder, reviewer, 3, 5).run(state);

        assertThat(outcome.approved()).isTrue();
        assertThat(coder.calls).isEqualTo(1);
        assertThat(reviewer.calls).isEqualTo(1);
    }

    @Test
    void loopsBackToTheCoderThenStopsAtTheCap() {
        var bounce = AgentResult.needsWork("reviewer", "nope",
                List.of(new Finding(Finding.Origin.REVIEWER, Finding.Severity.HIGH,
                        "A.java", 1, "still wrong")), TokenUsage.NONE);

        var coder = new ScriptedAgent("coder",
                List.of(AgentResult.ok("coder", "attempt", List.of("A.java"), TokenUsage.NONE)));
        var reviewer = new ScriptedAgent("reviewer", List.of(bounce));

        var state = new RunState("orch-test", "do a thing", workspace);
        var outcome = new Orchestrator(coder, reviewer, 3, 5).run(state);

        assertThat(outcome.approved()).isFalse();
        assertThat(coder.calls).isEqualTo(3);       // exactly the cap, not 4
        assertThat(state.reviewIterations()).isEqualTo(3);
    }

    @Test
    void aFailedReviewIsNotTreatedAsApproval() {
        var coder = new ScriptedAgent("coder",
                List.of(AgentResult.ok("coder", "attempt", List.of("A.java"), TokenUsage.NONE)));
        var reviewer = new ScriptedAgent("reviewer",
                List.of(new AgentResult("reviewer", AgentResult.Status.FAILED,
                        "unparseable", List.of(), List.of(), TokenUsage.NONE)));

        var outcome = new Orchestrator(coder, reviewer, 3, 5)
                .run(new RunState("orch-test", "t", workspace));

        assertThat(outcome.approved()).isFalse();
    }

    @Test
    void orchestratorNeverHoldsFileContents() {
        var coder = new ScriptedAgent("coder",
                List.of(AgentResult.ok("coder", "done", List.of("A.java"), TokenUsage.NONE)));
        var reviewer = new ScriptedAgent("reviewer",
                List.of(AgentResult.ok("reviewer", "ok", List.of(), TokenUsage.NONE)));

        var state = new RunState("orch-test", "t", workspace);
        new Orchestrator(coder, reviewer, 3, 5).run(state);

        // Every recorded summary is short — no diff or file body smuggled through.
        assertThat(state.history()).allSatisfy(r ->
                assertThat(r.summary().length()).isLessThan(2_000));
    }
}
