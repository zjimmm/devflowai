package ai.devflow;

import ai.devflow.agent.*;
import ai.devflow.orchestrator.*;
import ai.devflow.workspace.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Tag("live")
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
class EndToEndLiveTest {

    @Autowired @Qualifier("coder")    ChatClient coderClient;
    @Autowired @Qualifier("reviewer") ChatClient reviewerClient;

    @Test
    void addsValidationToTheFixtureAndPassesReview() throws Exception {
        Workspace ws = new FixtureWorkspace(Path.of("src/test/resources/fixture"), "e2e");
        ws.prepare();
        try {
            var orchestrator = new Orchestrator(
                    new CoderAgent(coderClient),
                    new ReviewerAgent(reviewerClient),
                    3, 5);

            var state = new RunState("e2e",
                    "Add input validation to UserController so a null or blank email is rejected "
                    + "with HTTP 400. Cover it with a test.", ws);

            var outcome = orchestrator.run(state);

            assertThat(outcome.approved())
                    .as("reason: %s", outcome.reason())
                    .isTrue();

            String controller = Files.readString(
                    ws.root().resolve("src/main/java/com/example/UserController.java"));
            assertThat(controller).contains("@Valid");
        } finally {
            ws.cleanup();
        }
    }
}
