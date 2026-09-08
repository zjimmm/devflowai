package ai.devflow.orchestrator;

import ai.devflow.agent.AgentResult;
import ai.devflow.agent.Finding;
import ai.devflow.agent.TokenUsage;
import ai.devflow.workspace.FixtureWorkspace;
import ai.devflow.workspace.Workspace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PullRequestContentTest {

    Workspace workspace;

    @BeforeEach
    void setUp() throws Exception {
        workspace = new FixtureWorkspace(Path.of("src/test/resources/fixture"), "pr-content-test");
        workspace.prepare();
    }

    @AfterEach
    void tearDown() throws Exception { workspace.cleanup(); }

    @Test
    void titleIsTheTasksFirstLineCappedAt72Characters() {
        var state = new RunState("r1", "line one\nline two", workspace);

        var content = PullRequestContent.build(state, RunStrategy.DIRECT, true, "");

        assertThat(content.title()).isEqualTo("line one");
    }

    @Test
    void titleLongerThan72CharactersIsTruncated() {
        String longTask = "x".repeat(100);
        var state = new RunState("r2", longTask, workspace);

        var content = PullRequestContent.build(state, RunStrategy.DIRECT, true, "");

        assertThat(content.title()).hasSize(72);
    }

    @Test
    void summaryComesFromTheLastCoderResult() {
        var state = new RunState("r3", "t", workspace);
        state.record(AgentResult.ok("coder", "first pass", List.of(), TokenUsage.NONE));
        state.record(AgentResult.ok("reviewer", "looks fine", List.of(), TokenUsage.NONE));
        state.record(AgentResult.ok("coder", "second pass, addressed feedback", List.of(), TokenUsage.NONE));

        var content = PullRequestContent.build(state, RunStrategy.ORCHESTRATED, true, "");

        assertThat(content.body()).contains("second pass, addressed feedback");
        assertThat(content.body()).doesNotContain("first pass");
    }

    @Test
    void testingSectionReportsAPassedBuild() {
        var state = new RunState("r4", "t", workspace);

        var content = PullRequestContent.build(state, RunStrategy.DIRECT, true, "");

        assertThat(content.body()).contains("Build passed.");
    }

    @Test
    void testingSectionReportsAFailedBuildWithItsOutput() {
        var state = new RunState("r5", "t", workspace);

        var content = PullRequestContent.build(state, RunStrategy.DIRECT, false, "compile error on line 4");

        assertThat(content.body()).contains("Build failed.");
        assertThat(content.body()).contains("compile error on line 4");
    }

    @Test
    void aiReviewSectionCountsReviewerFindingsForOrchestratedOnly() {
        var state = new RunState("r6", "t", workspace);
        state.addFindings(List.of(
                new Finding(Finding.Origin.REVIEWER, Finding.Severity.HIGH, "F.java", 1, "missing null check"),
                new Finding(Finding.Origin.REVIEWER, Finding.Severity.LOW, "F.java", 2, "naming")));

        var orchestrated = PullRequestContent.build(state, RunStrategy.ORCHESTRATED, true, "");
        var direct = PullRequestContent.build(state, RunStrategy.DIRECT, true, "");

        assertThat(orchestrated.body()).contains("2 finding(s) identified and resolved.");
        assertThat(direct.body()).doesNotContain("AI Review");
    }

    @Test
    void securitySectionReportsNoPolicyViolationsWhenThereAreNone() {
        var state = new RunState("r7", "t", workspace);

        var content = PullRequestContent.build(state, RunStrategy.DIRECT, true, "");

        assertThat(content.body()).contains("No policy violations.");
    }

    @Test
    void securitySectionCountsPolicyFindings() {
        var state = new RunState("r8", "t", workspace);
        state.addFindings(List.of(Finding.fromPolicy("the build did not pass")));

        var content = PullRequestContent.build(state, RunStrategy.DIRECT, true, "");

        assertThat(content.body()).contains("1 policy violation(s) flagged and resolved.");
    }

    @Test
    void bodyEndsWithARunIdReference() {
        var state = new RunState("r9", "t", workspace);

        var content = PullRequestContent.build(state, RunStrategy.DIRECT, true, "");

        assertThat(content.body()).contains("devflowai run: r9");
    }
}
