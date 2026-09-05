package ai.devflow.agent;

import ai.devflow.orchestrator.RunState;
import ai.devflow.workspace.*;
import ai.devflow.worker.SpringAiCodingWorker;
import org.junit.jupiter.api.*;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.nio.file.Path;
import java.util.List;
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

    /** A ChatResponse carrying the given text and token counts. */
    private static ChatResponse responseWith(String text, int promptTokens, int completionTokens) {
        var generation = new Generation(new AssistantMessage(text));
        var metadata = ChatResponseMetadata.builder()
                .usage(new DefaultUsage(promptTokens, completionTokens))
                .build();
        return new ChatResponse(List.of(generation), metadata);
    }

    @Test
    void parsesFindingsIntoNeedsWork() {
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(client.prompt().user(any(String.class)).tools(any()).call().chatResponse())
                .thenReturn(responseWith("""
                    {"status":"NEEDS_WORK","summary":"validation missing",
                     "findings":[{"severity":"HIGH","file":"UserController.java","line":14,
                                  "message":"@Valid missing on the controller parameter"}]}
                    """, 10, 5));

        var agent = new ReviewerAgent(new SpringAiCodingWorker(client));
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
        when(client.prompt().user(any(String.class)).tools(any()).call().chatResponse())
                .thenReturn(responseWith("""
                    {"status":"OK","summary":"looks correct","findings":[]}
                    """, 10, 5));

        var agent = new ReviewerAgent(new SpringAiCodingWorker(client));
        AgentResult result = agent.run(new RunState("r", "t", workspace));

        assertThat(result.status()).isEqualTo(AgentResult.Status.OK);
        assertThat(result.findings()).isEmpty();
    }

    @Test
    void malformedModelOutputFailsClosed() {
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(client.prompt().user(any(String.class)).tools(any()).call().chatResponse())
                .thenReturn(responseWith("not json at all", 10, 5));

        var agent = new ReviewerAgent(new SpringAiCodingWorker(client));
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
        when(client.prompt().user(any(String.class)).tools(any()).call().chatResponse())
                .thenReturn(responseWith("""
                    ```json
                    {"status":"NEEDS_WORK","summary":"validation missing",
                     "findings":[{"severity":"HIGH","file":"UserController.java","line":14,
                                  "message":"@Valid missing on the controller parameter"}]}
                    ```
                    """, 10, 5));

        var agent = new ReviewerAgent(new SpringAiCodingWorker(client));
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
        when(client.prompt().user(any(String.class)).tools(any()).call().chatResponse())
                .thenReturn(responseWith("""
                    {"status":"OK","summary":"looks correct","findings":[]}

                    Let me know if you'd like me to also check the `if (x) { ... }` block \
                    in the caller, or a NEEDS_WORK example: {"status":"NEEDS_WORK","summary":"bad","findings":[]}
                    """, 10, 5));

        var agent = new ReviewerAgent(new SpringAiCodingWorker(client));
        AgentResult result = agent.run(new RunState("r", "t", workspace));

        assertThat(result.status()).isEqualTo(AgentResult.Status.OK);
        assertThat(result.findings()).isEmpty();
    }

    // Fix round 1: an empty-but-syntactically-valid JSON object used to be
    // read as an implicit approval, because "status" was never validated
    // against the two known literals -- missing status + empty findings both
    // defaulted to the OK branch. Confirmed empirically before the fix (see
    // task-13-report.md). A degenerate/truncated response must never be OK.
    @Test
    void emptyJsonObjectFailsClosed() {
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(client.prompt().user(any(String.class)).tools(any()).call().chatResponse())
                .thenReturn(responseWith("{}", 10, 5));

        var agent = new ReviewerAgent(new SpringAiCodingWorker(client));
        AgentResult result = agent.run(new RunState("r", "t", workspace));

        assertThat(result.status()).isEqualTo(AgentResult.Status.FAILED);
    }

    // Same gap, different shape: a recognized JSON envelope but a status
    // value that isn't one of the two known literals must also fail closed,
    // not be silently treated as approval (or as NEEDS_WORK).
    @Test
    void unrecognizedStatusValueFailsClosed() {
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(client.prompt().user(any(String.class)).tools(any()).call().chatResponse())
                .thenReturn(responseWith("""
                    {"status":"MAYBE","summary":"unsure","findings":[]}
                    """, 10, 5));

        var agent = new ReviewerAgent(new SpringAiCodingWorker(client));
        AgentResult result = agent.run(new RunState("r", "t", workspace));

        assertThat(result.status()).isEqualTo(AgentResult.Status.FAILED);
    }

    @Test
    void reportsRealTokenUsageFromTheResponse() {
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(client.prompt().user(any(String.class)).tools(any(Object[].class)).call().chatResponse())
                .thenReturn(responseWith("""
                    {"status":"NEEDS_WORK","summary":"nope",
                     "findings":[{"severity":"HIGH","file":"A.java","line":1,"message":"x"}]}
                    """, 500, 60));

        var result = new ReviewerAgent(new SpringAiCodingWorker(client)).run(new RunState("u", "t", workspace));

        assertThat(result.tokens()).isEqualTo(new TokenUsage(500, 60));
    }
}
