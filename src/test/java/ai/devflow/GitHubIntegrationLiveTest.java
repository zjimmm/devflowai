package ai.devflow;

import ai.devflow.orchestrator.RunHandle;
import ai.devflow.orchestrator.RunPhase;
import ai.devflow.orchestrator.RunRegistry;
import ai.devflow.orchestrator.ApprovalDecision;
import ai.devflow.orchestrator.ApprovalGate;
import ai.devflow.orchestrator.CiStatus;
import ai.devflow.orchestrator.ReleaseCoordinator;
import ai.devflow.orchestrator.ReleaseDispatcher;
import ai.devflow.orchestrator.ReleaseStatus;
import ai.devflow.orchestrator.RunState;
import ai.devflow.history.SdlcRunRepository;
import ai.devflow.tools.GitHubClient;
import ai.devflow.web.StartRunRequest;
import ai.devflow.workspace.PathGuard;
import ai.devflow.workspace.Workspace;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The one place a real GitHub write happens. Requires a small, disposable
 * scratch repo you control -- never devflowai's own repo -- so its test
 * branches and PRs don't clutter a real project. See the PR Creation spec
 * §6 for why this repo must be separate.
 */
@SpringBootTest(properties = "devflowai.ci.timeout-minutes=3")
@AutoConfigureMockMvc
@Tag("live")
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "DEVFLOWAI_GITHUB_TOKEN", matches = ".+")
@EnabledIfEnvironmentVariable(named = "DEVFLOWAI_LIVETEST_REPO", matches = ".+")
class GitHubIntegrationLiveTest {

    @Autowired MockMvc mvc;
    @Autowired RunRegistry registry;
    @Autowired SdlcRunRepository runsRepo;
    @Autowired GitHubClient gitHubClient;
    @Autowired ReleaseDispatcher releaseDispatcher;
    ObjectMapper json = new ObjectMapper();

    @Test
    void aDirectStrategyRunOpensARealPullRequestAndRecordsCiStatus() throws Exception {
        String repoUrl = System.getenv("DEVFLOWAI_LIVETEST_REPO");

        String body = mvc.perform(post("/api/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                new StartRunRequest("add a small class", repoUrl, "direct", true))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String runId = json.readTree(body).get("runId").asText();
        RunHandle handle = registry.find(runId);
        assertThat(handle).isNotNull();

        long deadline = System.currentTimeMillis() + 240_000;
        while (!handle.task().isDone()) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("run never finished");
            Thread.sleep(50);
        }

        assertThat(handle.state().phase()).isEqualTo(RunPhase.DONE);
        var run = runsRepo.findById(runId).orElseThrow();
        assertThat(run.prUrl()).isNotBlank();
        assertThat(run.ciStatus()).isIn("PASSED", "FAILED", "TIMED_OUT");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "DEVFLOWAI_LIVETEST_MERGED_PR", matches = "[1-9][0-9]*")
    @EnabledIfEnvironmentVariable(named = "DEVFLOWAI_RELEASE_WORKFLOW", matches = ".+")
    void aMergedPullRequestDispatchesTheConfiguredReleaseWorkflow() throws Exception {
        String repoUrl = System.getenv("DEVFLOWAI_LIVETEST_REPO");
        int pullRequestNumber = Integer.parseInt(System.getenv("DEVFLOWAI_LIVETEST_MERGED_PR"));
        String prUrl = (repoUrl.endsWith(".git") ? repoUrl.substring(0, repoUrl.length() - 4) : repoUrl)
                + "/pull/" + pullRequestNumber;
        var state = new RunState("release-live", "release", releaseWorkspace(repoUrl), "release-live", true, true);
        var gate = new ApprovalGate(Duration.ofSeconds(30));
        var pool = Executors.newSingleThreadExecutor();
        try {
            var future = pool.submit(() -> new ReleaseCoordinator(gitHubClient, releaseDispatcher).dispatchIfRequested(
                    state, gate, prUrl, pullRequestNumber, CiStatus.PASSED,
                    event -> { }));
            long deadline = System.currentTimeMillis() + 30_000;
            while (gate.pending() != ai.devflow.orchestrator.Gate.BEFORE_RELEASE) {
                if (System.currentTimeMillis() > deadline) throw new AssertionError("release gate was never reached");
                Thread.sleep(10);
            }
            gate.decide(ApprovalDecision.approve());

            assertThat(future.get().orElseThrow().status()).isEqualTo(ReleaseStatus.DISPATCHED);
        } finally {
            pool.shutdownNow();
        }
    }

    private Workspace releaseWorkspace(String repoUrl) {
        return new Workspace() {
            @Override public Path root() { return Path.of("."); }
            @Override public String branchName() { return "main"; }
            @Override public PathGuard guard() { return null; }
            @Override public String repoUrl() { return repoUrl; }
            @Override public void prepare() { }
            @Override public void cleanup() { }
        };
    }
}
