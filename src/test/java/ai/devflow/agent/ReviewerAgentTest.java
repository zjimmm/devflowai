package ai.devflow.agent;

import ai.devflow.orchestrator.RunState;
import ai.devflow.workspace.*;
import org.junit.jupiter.api.*;
import org.springframework.ai.chat.client.ChatClient;

import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ReviewerAgentTest {

    Workspace workspace;

    @BeforeEach
    void setUp() throws Exception {
        workspace = new FixtureWorkspace(Path.of("src/test/resources/fixture"), "reviewer-test");
        workspace.prepare();
    }

    @AfterEach
    void tearDown() throws Exception { workspace.cleanup(); }

    @Test
    void parsesFindingsIntoNeedsWork() {
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(client.prompt().user(any(String.class)).tools(any()).call().content())
                .thenReturn("""
                    {"status":"NEEDS_WORK","summary":"validation missing",
                     "findings":[{"severity":"HIGH","file":"UserController.java","line":14,
                                  "message":"@Valid missing on the controller parameter"}]}
                    """);

        var agent = new ReviewerAgent(client);
        var state = new RunState("reviewer-test", "add validation", workspace);
        AgentResult result = agent.run(state);

        assertThat(result.status()).isEqualTo(AgentResult.Status.NEEDS_WORK);
        assertThat(result.findings()).hasSize(1);
        assertThat(result.findings().get(0).origin()).isEqualTo(Finding.Origin.REVIEWER);
        assertThat(result.findings().get(0).message()).contains("@Valid");
    }

    @Test
    void cleanReviewReturnsOk() {
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(client.prompt().user(any(String.class)).tools(any()).call().content())
                .thenReturn("""
                    {"status":"OK","summary":"looks correct","findings":[]}
                    """);

        var agent = new ReviewerAgent(client);
        AgentResult result = agent.run(new RunState("r", "t", workspace));

        assertThat(result.status()).isEqualTo(AgentResult.Status.OK);
        assertThat(result.findings()).isEmpty();
    }

    @Test
    void malformedModelOutputFailsClosed() {
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(client.prompt().user(any(String.class)).tools(any()).call().content())
                .thenReturn("not json at all");

        var agent = new ReviewerAgent(client);
        AgentResult result = agent.run(new RunState("r", "t", workspace));

        // A reviewer that cannot be parsed must NOT be read as approval.
        assertThat(result.status()).isEqualTo(AgentResult.Status.FAILED);
    }

    // Real models routinely ignore "reply with ONLY this JSON" and wrap the
    // answer in a markdown code fence anyway. Verified empirically (jshell,
    // see task-13-report.md) that Jackson's readTree parses exactly the first
    // complete top-level JSON value and does not require the fence markers
    // around it to be absent -- this is committed regression coverage for
    // that behavior, not a fix to the extraction logic (none was needed).
    @Test
    void markdownFencedOutputStillParses() {
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(client.prompt().user(any(String.class)).tools(any()).call().content())
                .thenReturn("""
                    ```json
                    {"status":"NEEDS_WORK","summary":"validation missing",
                     "findings":[{"severity":"HIGH","file":"UserController.java","line":14,
                                  "message":"@Valid missing on the controller parameter"}]}
                    ```
                    """);

        var agent = new ReviewerAgent(client);
        AgentResult result = agent.run(new RunState("r", "t", workspace));

        assertThat(result.status()).isEqualTo(AgentResult.Status.NEEDS_WORK);
        assertThat(result.findings()).hasSize(1);
        assertThat(result.findings().get(0).message()).contains("@Valid");
    }

    // Real models also routinely add trailing commentary after the JSON,
    // sometimes containing curly braces of its own (referencing a code block,
    // an "example of what NEEDS_WORK would look like", etc.). Verified
    // empirically that this does not corrupt the parsed result: Jackson stops
    // consuming input once the first top-level object is complete, so trailing
    // braces afterward are inert. Committed regression coverage.
    @Test
    void trailingProseWithBracesDoesNotCorruptTheParsedResult() {
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(client.prompt().user(any(String.class)).tools(any()).call().content())
                .thenReturn("""
                    {"status":"OK","summary":"looks correct","findings":[]}

                    Let me know if you'd like me to also check the `if (x) { ... }` block \
                    in the caller, or a NEEDS_WORK example: {"status":"NEEDS_WORK","summary":"bad","findings":[]}
                    """);

        var agent = new ReviewerAgent(client);
        AgentResult result = agent.run(new RunState("r", "t", workspace));

        assertThat(result.status()).isEqualTo(AgentResult.Status.OK);
        assertThat(result.findings()).isEmpty();
    }
}
