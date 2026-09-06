package ai.devflow.orchestrator;

import ai.devflow.agent.Agent;
import ai.devflow.agent.AgentResult;
import ai.devflow.agent.TokenUsage;
import ai.devflow.event.RunEventPublisher;
import ai.devflow.workspace.FixtureWorkspace;
import ai.devflow.workspace.Workspace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

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
        return new DirectExecutor(coder, events, Duration.ofMinutes(1));
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
}
