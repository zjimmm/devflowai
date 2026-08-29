package ai.devflow.agent;

import ai.devflow.orchestrator.RunState;
import ai.devflow.workspace.*;
import org.junit.jupiter.api.*;
import org.springframework.ai.chat.client.ChatClient;

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

    @Test
    void filesTouchedComesFromGitNotFromTheModel() throws Exception {
        // The model claims nothing; git sees a real edit.
        Files.writeString(workspace.root().resolve("Touched.java"), "class Touched {}");

        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(client.prompt().user(any(String.class)).tools(any(Object[].class)).call().content())
                .thenReturn("I made no changes.");

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
        when(client.prompt().user(any(String.class)).tools(any(Object[].class)).call().content()).thenReturn("done");

        var agent = new CoderAgent(client);
        var state = new RunState("coder-test", "fix it", workspace);
        state.addFindings(List.of(Finding.fromHuman("use a DTO, don't annotate the entity")));

        agent.run(state);

        assertThat(agent.lastPrompt()).contains("use a DTO");
    }
}
