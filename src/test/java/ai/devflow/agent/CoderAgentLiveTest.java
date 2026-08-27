package ai.devflow.agent;

import ai.devflow.orchestrator.RunState;
import ai.devflow.tools.GitTools;
import ai.devflow.workspace.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Tag("live")
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
class CoderAgentLiveTest {

    @Autowired @Qualifier("coder") ChatClient coderClient;

    @Test
    void producesRealChangesOnTheFixture() throws Exception {
        Workspace ws = new FixtureWorkspace(Path.of("src/test/resources/fixture"), "live-coder");
        ws.prepare();
        try {
            var agent = new CoderAgent(coderClient, new GitTools(ws));
            var state = new RunState("live-coder",
                "Add bean validation to UserController so a null or blank email is rejected.", ws);

            AgentResult result = agent.run(state);

            assertThat(result.filesTouched()).isNotEmpty();
        } finally {
            ws.cleanup();
        }
    }
}
