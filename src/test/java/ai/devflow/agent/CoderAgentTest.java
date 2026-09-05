package ai.devflow.agent;

import ai.devflow.orchestrator.RunState;
import ai.devflow.workspace.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.nio.file.*;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CoderAgentTest {

    Workspace workspace;

    @BeforeEach
    void setUp() throws Exception {
        workspace = new FixtureWorkspace(Path.of("src/test/resources/fixture"), "coder-test");
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
    void filesTouchedComesFromGitNotFromTheModel() throws Exception {
        // The model claims nothing; git sees a real edit.
        Files.writeString(workspace.root().resolve("Touched.java"), "class Touched {}");

        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(client.prompt().user(any(String.class)).tools(any(Object[].class)).call().chatResponse())
                .thenReturn(responseWith("I made no changes.", 10, 5));

        var agent = new CoderAgent(client);
        var state = new RunState("coder-test", "add a class", workspace);

        AgentResult result = agent.run(state);

        assertThat(result.filesTouched()).contains("Touched.java");
        assertThat(result.agent()).isEqualTo("coder");
        // Confirms the model's (false) claim really did flow through the stub
        // into the summary -- proving this test genuinely exercises "model
        // says nothing changed, git disagrees," rather than accidentally
        // passing because filesTouched()/agent() don't depend on the stub at
        // all. any(Object[].class) is required here, not bare any(): the real
        // call site is the two-arg vararg `.tools(new FileTools(...),
        // gitTools)`, and Mockito's bare any() only matches a single-vararg
        // invocation -- verified empirically (see task-12-report.md).
        assertThat(result.summary()).isEqualTo("I made no changes.");
    }

    @Test
    void openFindingsAreIncludedInThePrompt() throws Exception {
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(client.prompt().user(any(String.class)).tools(any(Object[].class)).call().chatResponse())
                .thenReturn(responseWith("done", 10, 5));

        var agent = new CoderAgent(client);
        var state = new RunState("coder-test", "fix it", workspace);
        state.addFindings(List.of(Finding.fromHuman("use a DTO, don't annotate the entity")));

        agent.run(state);

        // client.prompt() is a no-arg call, so RETURNS_DEEP_STUBS hands back the
        // same cached deep-stub instance every time -- including here, after the
        // fact -- which is what makes verifying against it valid. atLeastOnce()
        // (rather than the default times(1)) is required because the when(...)
        // setup above is itself an invocation of .user(any(String.class)) on
        // that same deep stub and Mockito counts it as a real invocation;
        // getValue() then returns the *last* captured argument, i.e. the prompt
        // actually sent by agent.run(state), which is what we care about.
        var promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(client.prompt(), atLeastOnce()).user(promptCaptor.capture());
        assertThat(promptCaptor.getValue()).contains("use a DTO");
    }

    @Test
    void memoryIsIncludedInThePromptAsItsOwnSection() throws Exception {
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(client.prompt().user(any(String.class)).tools(any(Object[].class)).call().chatResponse())
                .thenReturn(responseWith("done", 10, 5));

        var agent = new CoderAgent(client);
        var state = new RunState("coder-test", "fix it", workspace);
        state.setMemory("tests use JUnit 5 + AssertJ");

        agent.run(state);

        var promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(client.prompt(), atLeastOnce()).user(promptCaptor.capture());
        assertThat(promptCaptor.getValue()).contains("tests use JUnit 5 + AssertJ");
    }

    @Test
    void reportsRealTokenUsageFromTheResponse() throws Exception {
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(client.prompt().user(any(String.class)).tools(any(Object[].class)).call().chatResponse())
                .thenReturn(responseWith("done", 321, 123));

        var agent = new CoderAgent(client);
        var result = agent.run(new RunState("usage-test", "t", workspace));

        assertThat(result.tokens()).isEqualTo(new TokenUsage(321, 123));
    }
}
