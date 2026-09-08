package ai.devflow;

import ai.devflow.orchestrator.RunHandle;
import ai.devflow.orchestrator.RunPhase;
import ai.devflow.orchestrator.RunRegistry;
import ai.devflow.history.SdlcRunRepository;
import ai.devflow.web.StartRunRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

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
}
