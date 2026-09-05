package ai.devflow.web;

import ai.devflow.orchestrator.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import ai.devflow.agent.*;
import ai.devflow.memory.FileMemoryStore;
import ai.devflow.memory.MemoryStore;
import ai.devflow.skill.FileSkillStore;
import ai.devflow.skill.ScribeDraft;
import ai.devflow.skill.SkillDraft;
import ai.devflow.skill.SkillIndexEntry;
import ai.devflow.skill.SkillStore;

import java.nio.file.Files;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Proves the whole path — HTTP start, SSE-backing state, a gate decision over
 * HTTP, completion — through the real {@link RunController}, {@link RunRegistry},
 * {@link Orchestrator} and {@link ApprovalGate}, wired by the real
 * {@code OrchestrationConfig}. Only the two LLM-calling agents are replaced,
 * so this costs nothing and runs in CI.
 *
 * <p><b>Deviation from the task brief:</b> the brief's approach (a
 * {@code @TestConfiguration} with {@code @Bean @Primary} stub methods,
 * imported via {@code @Import}) does not work even after renaming the
 * methods to {@code stubCoderAgent}/{@code stubReviewerAgent} to dodge the
 * bean-definition-name collision with {@code OrchestrationConfig}'s real
 * {@code coderAgent}/{@code reviewerAgent} beans. Renaming trades that
 * collision for a different failure: with both stub beans marked
 * {@code @Primary}, the context fails to start with
 * {@code NoUniqueBeanDefinitionException: No qualifying bean of type
 * 'ai.devflow.agent.Agent' available: more than one 'primary' bean found
 * among candidates: [coderAgent, reviewerAgent, stubCoderAgent,
 * stubReviewerAgent]}. Spring's primary-candidate resolution
 * ({@code DefaultListableBeanFactory#determinePrimaryCandidate}) looks at
 * the full set of type-matching candidates for {@code Orchestrator}'s
 * {@code Agent coderAgent}/{@code Agent reviewerAgent} constructor
 * parameters — both stub beans are {@code Agent}s, so both show up as
 * candidates for *each* parameter — and throws as soon as more than one
 * candidate is primary. It never reaches parameter-name matching to break
 * that tie, so two distinctly-named {@code @Primary} beans of the same
 * interface can never disambiguate two same-typed parameters this way.
 *
 * <p>The fix used here is Spring's dedicated bean-override mechanism,
 * {@link TestBean} (Spring Framework 6.2+), which replaces an existing
 * named bean definition — {@code coderAgent} / {@code reviewerAgent} from
 * {@code OrchestrationConfig} — outright, by name, via its own
 * {@code BeanOverrideBeanFactoryPostProcessor} pathway. That sidesteps
 * {@code allow-bean-definition-overriding} entirely (it isn't a second
 * definition competing for the same name, it's a sanctioned replacement of
 * one), so there is never more than one {@code coderAgent} bean or more
 * than one {@code reviewerAgent} bean in the context, and thus no ambiguity
 * for {@code Orchestrator}'s constructor to resolve. The stub {@link Agent}
 * implementations and their behavior are unchanged from the brief.
 */
@SpringBootTest(properties = {
        "spring.ai.anthropic.api-key=test-key-not-used",
        "devflowai.gate.timeout-minutes=1"
})
@AutoConfigureMockMvc
class RunFlowIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired RunRegistry registry;
    ObjectMapper json = new ObjectMapper();

    // Overrides OrchestrationConfig's real "coderAgent"/"reviewerAgent" beans
    // by name — see the class Javadoc for why @TestConfiguration + @Primary
    // (the brief's original approach) cannot work here.
    @TestBean(name = "coderAgent", methodName = "stubCoderAgent")
    Agent coderAgentOverride;

    @TestBean(name = "reviewerAgent", methodName = "stubReviewerAgent")
    Agent reviewerAgentOverride;

    @TestBean(name = "plannerAgent", methodName = "stubPlannerAgent")
    Agent plannerAgentOverride;

    @TestBean(name = "scribeAgent", methodName = "stubScribeAgent")
    Scribe scribeAgentOverride;

    @TestBean(name = "skillStore", methodName = "stubSkillStore")
    SkillStore skillStoreOverride;

    @TestBean(name = "memoryStore", methodName = "stubMemoryStore")
    MemoryStore memoryStoreOverride;

    /** Writes a real file so changedFiles() is non-empty, like a real coder. */
    static Agent stubCoderAgent() {
        return new Agent() {
            @Override public String name() { return "coder"; }
            @Override public AgentResult run(RunState s) {
                try {
                    Files.writeString(s.workspace().root().resolve("Added.java"), "class Added {}");
                } catch (Exception e) { throw new RuntimeException(e); }
                return AgentResult.ok("coder", "added a class",
                        s.gitTools().changedFiles(), TokenUsage.NONE);
            }
        };
    }

    static Agent stubReviewerAgent() {
        return new Agent() {
            @Override public String name() { return "reviewer"; }
            @Override public AgentResult run(RunState s) {
                boolean firstReviewForThisRun = s.history().stream().noneMatch(r -> r.agent().equals("reviewer"));
                if (firstReviewForThisRun && s.task().contains("needs-a-correction")) {
                    return AgentResult.needsWork("reviewer", "found an issue",
                            List.of(new Finding(Finding.Origin.REVIEWER, Finding.Severity.HIGH,
                                    "Added.java", 1, "missing validation")),
                            TokenUsage.NONE);
                }
                return AgentResult.ok("reviewer", "looks correct", List.of(), TokenUsage.NONE);
            }
        };
    }

    static Agent stubPlannerAgent() {
        return new Agent() {
            @Override public String name() { return "planner"; }
            @Override public AgentResult run(RunState s) {
                return AgentResult.ok("planner", "1. Make the change\n2. Verify it", List.of(), TokenUsage.NONE);
            }
        };
    }

    static Scribe stubScribeAgent() {
        return (state, findings, humanGuidance) -> new ScribeDraft(
                new SkillDraft("validation-fixture", "Validating input on the fixture controller",
                        List.of("validation"), "## Steps\n1. Add @Valid to the controller parameter\n"),
                "tests use JUnit 5");
    }

    static SkillStore stubSkillStore() throws java.io.IOException {
        return new FileSkillStore(Files.createTempDirectory("skills-test"));
    }

    static MemoryStore stubMemoryStore() throws java.io.IOException {
        return new FileMemoryStore(Files.createTempDirectory("memory-test"));
    }

    @Test
    void aRunStartsPausesAtEachGateAndCompletesWhenApproved() throws Exception {
        String body = mvc.perform(post("/api/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new StartRunRequest("add a class", "fixture"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String runId = json.readTree(body).get("runId").asText();

        RunHandle handle = registry.find(runId);
        assertThat(handle).isNotNull();

        // Approve each gate as it appears, over HTTP — the real path.
        long deadline = System.currentTimeMillis() + 60_000;
        while (!handle.task().isDone()) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("run never finished");
            if (handle.gate().pending() != null) {
                mvc.perform(post("/api/runs/" + runId + "/approve")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(new ApproveRequest(true, null))))
                        .andExpect(status().isOk());
            }
            Thread.sleep(10);
        }

        assertThat(handle.state().phase()).isEqualTo(RunPhase.DONE);
        assertThat(handle.state().history()).isNotEmpty();
    }

    @Test
    void rejectingPreFlightEndsTheRun() throws Exception {
        String body = mvc.perform(post("/api/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new StartRunRequest("add a class", "fixture"))))
                .andReturn().getResponse().getContentAsString();
        String runId = json.readTree(body).get("runId").asText();
        RunHandle handle = registry.find(runId);

        while (handle.gate().pending() == null) Thread.sleep(5);
        mvc.perform(post("/api/runs/" + runId + "/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new ApproveRequest(false, null))))
                .andExpect(status().isOk());

        long deadline = System.currentTimeMillis() + 30_000;
        while (!handle.task().isDone()) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("run never ended");
            Thread.sleep(10);
        }
        assertThat(handle.state().phase()).isEqualTo(RunPhase.FAILED);
    }

    @Test
    void aBounceThenFixWritesASkillIntoTheHostSideStore() throws Exception {
        String body = mvc.perform(post("/api/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new StartRunRequest("add validation, needs-a-correction", "fixture"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String runId = json.readTree(body).get("runId").asText();
        RunHandle handle = registry.find(runId);
        assertThat(handle).isNotNull();

        long deadline = System.currentTimeMillis() + 60_000;
        while (!handle.task().isDone()) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("run never finished");
            if (handle.gate().pending() != null) {
                mvc.perform(post("/api/runs/" + runId + "/approve")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(new ApproveRequest(true, null))))
                        .andExpect(status().isOk());
            }
            Thread.sleep(10);
        }

        assertThat(handle.state().phase()).isEqualTo(RunPhase.DONE);
        assertThat(handle.state().reviewIterations()).isEqualTo(2);

        List<SkillIndexEntry> index = skillStoreOverride.index("fixture");
        assertThat(index).extracting(SkillIndexEntry::name).contains("validation-fixture");
    }

    @Test
    @Tag("live")
    void aClonedRepoFlowsThroughGate1AndAbortsCleanlyAtGate2() throws Exception {
        String body = mvc.perform(post("/api/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                new StartRunRequest("add a class", "https://github.com/zjimmm/devflowai.git"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String runId = json.readTree(body).get("runId").asText();
        RunHandle handle = registry.find(runId);
        assertThat(handle).isNotNull();

        // Approve Gate 1 (pre-flight) so the coder/reviewer stubs run against
        // the REAL cloned repo, then reject Gate 2 without a reason. Reaching
        // Gate 1 at all is itself the proof the clone succeeded -- if it
        // hadn't, RunRegistry's own catch block would have published an
        // error and completed the run before Orchestrator.run (and so Gate 1)
        // was ever reached. This deliberately never lets BuildTools run a
        // real build against devflowai's own test suite inside its own clone.
        long gate1Deadline = System.currentTimeMillis() + 120_000; // a real network clone can be slow
        while (handle.gate().pending() != Gate.PRE_FLIGHT) {
            if (System.currentTimeMillis() > gate1Deadline) {
                throw new AssertionError("clone never reached Gate 1 (pending=" + handle.gate().pending() + ")");
            }
            Thread.sleep(5);
        }
        mvc.perform(post("/api/runs/" + runId + "/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new ApproveRequest(true, null))))
                .andExpect(status().isOk());

        long gate2Deadline = System.currentTimeMillis() + 30_000;
        while (handle.gate().pending() != Gate.BEFORE_BUILD) {
            if (System.currentTimeMillis() > gate2Deadline) {
                throw new AssertionError("run never reached Gate 2 (pending=" + handle.gate().pending() + ")");
            }
            Thread.sleep(5);
        }
        mvc.perform(post("/api/runs/" + runId + "/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new ApproveRequest(false, null))))
                .andExpect(status().isOk());

        long deadline = System.currentTimeMillis() + 30_000;
        while (!handle.task().isDone()) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("run never ended");
            Thread.sleep(10);
        }

        assertThat(handle.state().phase()).isEqualTo(RunPhase.FAILED);
    }
}
