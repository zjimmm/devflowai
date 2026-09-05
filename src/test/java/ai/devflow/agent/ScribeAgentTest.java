package ai.devflow.agent;

import ai.devflow.orchestrator.RunState;
import ai.devflow.workspace.*;
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

class ScribeAgentTest {

    Workspace workspace;

    @BeforeEach
    void setUp() throws Exception {
        workspace = new FixtureWorkspace(Path.of("src/test/resources/fixture"), "scribe-test");
        workspace.prepare();
    }

    @AfterEach
    void tearDown() throws Exception { workspace.cleanup(); }

    private static ChatResponse responseWith(String text) {
        var generation = new Generation(new AssistantMessage(text));
        var metadata = ChatResponseMetadata.builder().usage(new DefaultUsage(10, 5)).build();
        return new ChatResponse(List.of(generation), metadata);
    }

    @Test
    void parsesASkillAndAMemoryFact() {
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(client.prompt().user(any(String.class)).call().chatResponse())
                .thenReturn(responseWith("""
                    {"skill": {"name":"spring-controller-validation","description":"d",
                               "triggers":["validation"],"body":"## Steps\\n1. Do it\\n"},
                     "memoryFact": "tests use JUnit 5"}
                    """));

        var state = new RunState("scribe-test", "add validation", workspace);
        var draft = new ScribeAgent(client).draft(state, List.of(Finding.fromHuman("use a DTO")), null);

        assertThat(draft.isEmpty()).isFalse();
        assertThat(draft.skill().name()).isEqualTo("spring-controller-validation");
        assertThat(draft.memoryFact()).isEqualTo("tests use JUnit 5");
    }

    @Test
    void bothFieldsNullIsAnEmptyDraft() {
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(client.prompt().user(any(String.class)).call().chatResponse())
                .thenReturn(responseWith("""
                    {"skill": null, "memoryFact": null}
                    """));

        var state = new RunState("scribe-test", "t", workspace);
        var draft = new ScribeAgent(client).draft(state, List.of(), null);

        assertThat(draft.isEmpty()).isTrue();
    }

    @Test
    void malformedResponseFailsClosedToEmpty() {
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(client.prompt().user(any(String.class)).call().chatResponse())
                .thenReturn(responseWith("not json at all"));

        var state = new RunState("scribe-test", "t", workspace);
        var draft = new ScribeAgent(client).draft(state, List.of(), null);

        assertThat(draft.isEmpty()).isTrue();
    }

    @Test
    void anUnnamedSkillIsDroppedButTheMemoryFactSurvives() {
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(client.prompt().user(any(String.class)).call().chatResponse())
                .thenReturn(responseWith("""
                    {"skill": {"name":"","description":"d","triggers":[],"body":"b"},
                     "memoryFact": "the build tool is Gradle"}
                    """));

        var state = new RunState("scribe-test", "t", workspace);
        var draft = new ScribeAgent(client).draft(state, List.of(), null);

        assertThat(draft.skill()).isNull();
        assertThat(draft.memoryFact()).isEqualTo("the build tool is Gradle");
    }

    @Test
    void humanGuidanceIsIncludedInThePromptOnARedraft() {
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(client.prompt().user(any(String.class)).call().chatResponse())
                .thenReturn(responseWith("""
                    {"skill": null, "memoryFact": null}
                    """));

        var state = new RunState("scribe-test", "t", workspace);
        new ScribeAgent(client).draft(state, List.of(), "that lesson was wrong, focus on the test instead");

        var promptCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(client.prompt(), atLeastOnce()).user(promptCaptor.capture());
        assertThat(promptCaptor.getValue()).contains("that lesson was wrong, focus on the test instead");
    }
}
