package ai.devflow;

import ai.devflow.agent.*;
import ai.devflow.orchestrator.*;
import ai.devflow.workspace.*;
import ai.devflow.worker.SpringAiCodingWorker;
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
    @Autowired @Qualifier("planner")  ChatClient plannerClient;

    @Test
    void addsValidationToTheFixtureAndPassesReview() throws Exception {
        Workspace ws = new FixtureWorkspace(Path.of("src/test/resources/fixture"), "e2e");
        ws.prepare();
        try {
            var orchestrator = new Orchestrator(
                    new CoderAgent(new SpringAiCodingWorker(coderClient)),
                    new ReviewerAgent(new SpringAiCodingWorker(reviewerClient)),
                    new PlannerAgent(new SpringAiCodingWorker(plannerClient)),
                    (task, index) -> java.util.List.of(),
                    (state, findings, reason) -> ai.devflow.skill.ScribeDraft.EMPTY,
                    new ai.devflow.skill.SkillStore() {
                        @Override public java.util.List<ai.devflow.skill.SkillIndexEntry> index(String repoSlug) { return java.util.List.of(); }
                        @Override public String readFull(String repoSlug, String name) { return ""; }
                        @Override public String write(String repoSlug, String runId, ai.devflow.skill.SkillDraft draft) { return ""; }
                    },
                    new ai.devflow.memory.MemoryStore() {
                        @Override public String read(String repoSlug) { return ""; }
                        @Override public String append(String repoSlug, String fact) { return ""; }
                    },
                    new ai.devflow.event.RunEventPublisher(),
                    3, 5, java.time.Duration.ofMinutes(5),
                    ctx -> ai.devflow.policy.PolicyResult.ok(),
                    new ai.devflow.tools.GitHubClient() {
                        @Override public void push(ai.devflow.workspace.Workspace workspace, String branchName) {
                            throw new UnsupportedOperationException("this live test never opens a PR");
                        }
                        @Override public ai.devflow.tools.PullRequestResult openPullRequest(String repoUrl, String branchName, String title, String body) {
                            throw new UnsupportedOperationException("this live test never opens a PR");
                        }
                    });

            var state = new RunState("e2e", "Add input validation to UserController so a null or "
                    + "blank email is rejected with HTTP 400. Cover it with a test.", ws);

            var gate = new ApprovalGate(java.time.Duration.ofSeconds(30));
            var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
            java.util.concurrent.Future<Orchestrator.RunOutcome> f =
                    pool.submit(() -> orchestrator.run(state, gate));
            while (!f.isDone()) {
                if (gate.pending() != null) gate.decide(ApprovalDecision.approve());
                Thread.sleep(10);
            }
            var outcome = f.get();
            pool.shutdownNow();

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
