package ai.devflow.agent;

import ai.devflow.orchestrator.RunState;
import ai.devflow.worker.SpringAiCodingWorker;
import ai.devflow.workspace.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
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

class PlannerAgentTest {

    Workspace workspace;

    @BeforeEach
    void setUp() throws Exception {
        workspace = new FixtureWorkspace(Path.of("src/test/resources/fixture"), "planner-test");
        workspace.prepare();
    }

    @AfterEach
    void tearDown() throws Exception { workspace.cleanup(); }

    private static ChatResponse responseWith(String text, int promptTokens, int completionTokens) {
        var generation = new Generation(new AssistantMessage(text));
        var metadata = ChatResponseMetadata.builder()
                .usage(new DefaultUsage(promptTokens, completionTokens))
                .build();
        return new ChatResponse(List.of(generation), metadata);
    }

    @Test
    void planTextBecomesTheSummary() {
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(client.prompt().user(any(String.class)).tools(any(Object[].class)).call().chatResponse())
                .thenReturn(responseWith("1. Add @Valid\n2. Add a test", 10, 5));

        var agent = new PlannerAgent(new SpringAiCodingWorker(client));
        var state = new RunState("planner-test", "add validation", workspace);

        AgentResult result = agent.run(state);

        assertThat(result.status()).isEqualTo(AgentResult.Status.OK);
        assertThat(result.summary()).isEqualTo("1. Add @Valid\n2. Add a test");
        assertThat(result.filesTouched()).isEmpty();
    }

    @Test
    void memoryAndLoadedSkillsAreIncludedInThePrompt() {
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(client.prompt().user(any(String.class)).tools(any(Object[].class)).call().chatResponse())
                .thenReturn(responseWith("a plan", 10, 5));

        var agent = new PlannerAgent(new SpringAiCodingWorker(client));
        var state = new RunState("planner-test", "add validation", workspace);
        state.setMemory("tests use JUnit 5 + AssertJ");
        state.addLoadedSkill("## Steps\n1. Add @Valid\n");

        agent.run(state);

        var promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(client.prompt(), atLeastOnce()).user(promptCaptor.capture());
        assertThat(promptCaptor.getValue()).contains("tests use JUnit 5 + AssertJ");
        assertThat(promptCaptor.getValue()).contains("Add @Valid");
    }

    @Test
    void structuredRequirementsAndAcceptanceCriteriaAreIncludedInThePrompt() {
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(client.prompt().user(any(String.class)).tools(any(Object[].class)).call().chatResponse())
                .thenReturn(responseWith("a plan", 10, 5));

        var state = new RunState("planner-test", "add validation", workspace);
        state.setRequirementAnalysis(new RequirementAnalysis(
                List.of("FR-1 Reject blank email"),
                List.of("The endpoint already exists"),
                List.of("Whitespace-only email"),
                List.of("Given blank email, when submitted, then return 400"),
                List.of(), false, ""));

        new PlannerAgent(new SpringAiCodingWorker(client)).run(state);

        var promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(client.prompt(), atLeastOnce()).user(promptCaptor.capture());
        assertThat(promptCaptor.getValue())
                .contains("FR-1 Reject blank email")
                .contains("Given blank email, when submitted, then return 400")
                .contains("The endpoint already exists")
                .contains("Whitespace-only email");
    }

    @Test
    void reportsRealTokenUsageFromTheResponse() {
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(client.prompt().user(any(String.class)).tools(any(Object[].class)).call().chatResponse())
                .thenReturn(responseWith("a plan", 321, 123));

        var result = new PlannerAgent(new SpringAiCodingWorker(client)).run(new RunState("u", "t", workspace));

        assertThat(result.tokens()).isEqualTo(new TokenUsage(321, 123));
    }
}
