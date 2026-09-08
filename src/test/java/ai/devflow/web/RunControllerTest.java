package ai.devflow.web;

import ai.devflow.orchestrator.*;
import ai.devflow.event.RunEventPublisher;
import ai.devflow.orchestrator.RunStrategy;
import ai.devflow.workspace.FixtureWorkspace;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RunControllerTest {

    MockMvc mvc;
    RunRegistry registry;
    RunEventPublisher events;
    ai.devflow.history.SdlcRunRepository history;
    ai.devflow.history.RunAuditEntryRepository auditEntries;
    ObjectMapper json = new ObjectMapper();
    java.util.List<Object> publishedEvents = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() {
        registry = mock(RunRegistry.class);
        events = new RunEventPublisher();
        history = mock(ai.devflow.history.SdlcRunRepository.class);
        auditEntries = mock(ai.devflow.history.RunAuditEntryRepository.class);
        mvc = MockMvcBuilders.standaloneSetup(
                new RunController(registry, events, publishedEvents::add, history, auditEntries,
                        ReleaseDispatcher.disabled())).build();
    }

    private RunHandle handleFor(String runId) throws Exception {
        var workspace = new FixtureWorkspace(Path.of("src/test/resources/fixture"), runId);
        workspace.prepare();
        var state = new RunState(runId, "a task", workspace);
        return new RunHandle(runId, state, new ApprovalGate(Duration.ofSeconds(5)), null);
    }

    @Test
    void startingARunReturnsItsId() throws Exception {
        when(registry.start(any(), any(), any(), anyBoolean())).thenReturn(handleFor("abc123"));

        mvc.perform(post("/api/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new StartRunRequest("do a thing", "fixture"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value("abc123"));

        verify(registry).start("do a thing", "fixture", RunStrategy.ORCHESTRATED, false);
    }

    @Test
    void startingARunRejectsABlankTask() throws Exception {
        mvc.perform(post("/api/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new StartRunRequest("   ", "fixture"))))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(registry);
    }

    @Test
    void startingARunRejectsARepoStringThatIsNotAnHttpsUrl() throws Exception {
        when(registry.start(any(), any(), any(), anyBoolean()))
                .thenThrow(new IllegalArgumentException("Repo must be an https:// URL, got: ext::sh -c \"true\""));

        mvc.perform(post("/api/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new StartRunRequest("do a thing", "ext::sh -c \"true\""))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Repo must be an https:// URL, got: ext::sh -c \"true\""));
    }

    @Test
    void startingARunWithDirectStrategyPassesItThrough() throws Exception {
        when(registry.start(any(), any(), any(), anyBoolean())).thenReturn(handleFor("direct1"));

        mvc.perform(post("/api/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new StartRunRequest("do a thing", "fixture", "direct"))))
                .andExpect(status().isOk());

        verify(registry).start("do a thing", "fixture", RunStrategy.DIRECT, false);
    }

    @Test
    void startingARunWithOpenPrAgainstTheFixtureIsRejected() throws Exception {
        mvc.perform(post("/api/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new StartRunRequest("do a thing", "fixture", "direct", true))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Opening a PR requires a real repository, not the bundled fixture"));

        verifyNoInteractions(registry);
    }

    @Test
    void startingARunWithOpenPrAgainstANonGitHubUrlIsRejected() throws Exception {
        mvc.perform(post("/api/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                new StartRunRequest("do a thing", "https://gitlab.com/o/r", "direct", true))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        "Opening a PR is only supported for github.com repositories, got: https://gitlab.com/o/r"));

        verifyNoInteractions(registry);
    }

    @Test
    void startingARunWithOpenPrAgainstAGitHubUrlPassesItThrough() throws Exception {
        when(registry.start(any(), any(), any(), anyBoolean())).thenReturn(handleFor("pr1"));

        mvc.perform(post("/api/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                new StartRunRequest("do a thing", "https://github.com/o/r", "direct", true))))
                .andExpect(status().isOk());

        verify(registry).start("do a thing", "https://github.com/o/r", RunStrategy.DIRECT, true);
    }

    @Test
    void releaseRequiresOpeningAPullRequest() throws Exception {
        mvc.perform(post("/api/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new StartRunRequest("do a thing", "https://github.com/o/r",
                                "direct", false, true))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Release dispatch requires opening a pull request"));

        verifyNoInteractions(registry);
    }

    @Test
    void releaseRequiresAConfiguredWorkflow() throws Exception {
        mvc.perform(post("/api/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new StartRunRequest("do a thing", "https://github.com/o/r",
                                "direct", true, true))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Release dispatch is not configured for this DevFlowAI instance"));

        verifyNoInteractions(registry);
    }

    @Test
    void configuredReleasePassesTheOptInThroughToTheRegistry() throws Exception {
        mvc = MockMvcBuilders.standaloneSetup(
                new RunController(registry, events, publishedEvents::add, history, auditEntries,
                        configuredReleaseDispatcher())).build();
        when(registry.start(any(), any(), any(), anyBoolean(), anyBoolean())).thenReturn(handleFor("release1"));

        mvc.perform(post("/api/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new StartRunRequest("do a thing", "https://github.com/o/r",
                                "direct", true, true))))
                .andExpect(status().isOk());

        verify(registry).start("do a thing", "https://github.com/o/r", RunStrategy.DIRECT, true, true);
    }

    @Test
    void streamingAnUnknownRunIs404() throws Exception {
        when(registry.find("nope")).thenReturn(null);
        mvc.perform(get("/api/runs/nope/stream")).andExpect(status().isNotFound());
    }

    @Test
    void approvingAnUnknownRunIs404() throws Exception {
        when(registry.find("nope")).thenReturn(null);
        mvc.perform(post("/api/runs/nope/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new ApproveRequest(true, null))))
                .andExpect(status().isNotFound());
    }

    @Test
    void approvingWhenNoGateIsPendingIs409() throws Exception {
        RunHandle handle = handleFor("run-1");
        when(registry.find("run-1")).thenReturn(handle);

        // Nothing is parked, so the decision has nowhere to go.
        mvc.perform(post("/api/runs/run-1/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new ApproveRequest(true, null))))
                .andExpect(status().isConflict());

        handle.state().workspace().cleanup();
    }

    @Test
    void approvingAPendingGateSucceeds() throws Exception {
        RunHandle handle = handleFor("run-2");
        when(registry.find("run-2")).thenReturn(handle);

        var pool = Executors.newSingleThreadExecutor();
        pool.submit(() -> handle.gate().await(Gate.PRE_FLIGHT));
        while (handle.gate().pending() == null) Thread.sleep(5);

        mvc.perform(post("/api/runs/run-2/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new ApproveRequest(true, null))))
                .andExpect(status().isOk());

        pool.shutdownNow();
        handle.state().workspace().cleanup();
    }

    @Test
    void approvingAPendingGateFiresAnApprovalRecordedEvent() throws Exception {
        RunHandle handle = handleFor("run-3");
        when(registry.find("run-3")).thenReturn(handle);

        var pool = Executors.newSingleThreadExecutor();
        pool.submit(() -> handle.gate().await(Gate.PRE_FLIGHT));
        while (handle.gate().pending() == null) Thread.sleep(5);

        mvc.perform(post("/api/runs/run-3/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new ApproveRequest(true, null))))
                .andExpect(status().isOk());

        assertThat(publishedEvents).containsExactly(
                new ai.devflow.event.ApprovalRecorded("run-3", "PRE_FLIGHT", true, null));

        pool.shutdownNow();
        handle.state().workspace().cleanup();
    }

    @Test
    void historyReturnsRunsMostRecentFirst() throws Exception {
        var newer = new ai.devflow.history.SdlcRun("newer", "add a class", "fixture",
                ai.devflow.orchestrator.RunStrategy.ORCHESTRATED, java.time.Instant.now());
        var older = new ai.devflow.history.SdlcRun("older", "fix a bug", "fixture",
                ai.devflow.orchestrator.RunStrategy.DIRECT, java.time.Instant.now().minusSeconds(60));
        newer.recordCiStatus("PASSED");
        newer.recordRelease("DISPATCHED", "https://github.com/o/r/actions/runs/7");
        newer.recordVerificationStatus("PASSED");
        when(history.findTop50ByOrderByStartedAtDesc()).thenReturn(java.util.List.of(newer, older));

        mvc.perform(get("/api/runs/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].runId").value("newer"))
                .andExpect(jsonPath("$[0].task").value("add a class"))
                .andExpect(jsonPath("$[0].status").value("RUNNING"))
                .andExpect(jsonPath("$[0].strategy").value("ORCHESTRATED"))
                .andExpect(jsonPath("$[0].ciStatus").value("PASSED"))
                .andExpect(jsonPath("$[0].releaseStatus").value("DISPATCHED"))
                .andExpect(jsonPath("$[0].releaseUrl").value("https://github.com/o/r/actions/runs/7"))
                .andExpect(jsonPath("$[0].verificationStatus").value("PASSED"))
                .andExpect(jsonPath("$[1].runId").value("older"))
                .andExpect(jsonPath("$[1].strategy").value("DIRECT"));
    }

    @Test
    void auditReturnsChronologicalRunEvents() throws Exception {
        var startedAt = java.time.Instant.parse("2026-09-08T12:00:00Z");
        var first = new ai.devflow.history.RunAuditEntry("audit-1", "SYSTEM", "STEP", "PREPARING",
                "Workspace ready", "{\"branch\":\"devflowai/audit-1\"}", startedAt);
        var second = new ai.devflow.history.RunAuditEntry("audit-1", "OPERATOR", "APPROVED", "PRE_FLIGHT",
                "Operator approved PRE_FLIGHT", "{\"approved\":true}", startedAt.plusSeconds(10));
        when(history.existsById("audit-1")).thenReturn(true);
        when(auditEntries.findByRunIdOrderByOccurredAtAscIdAsc("audit-1")).thenReturn(java.util.List.of(first, second));

        mvc.perform(get("/api/runs/audit-1/audit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].actor").value("SYSTEM"))
                .andExpect(jsonPath("$[0].action").value("STEP"))
                .andExpect(jsonPath("$[0].phase").value("PREPARING"))
                .andExpect(jsonPath("$[1].actor").value("OPERATOR"))
                .andExpect(jsonPath("$[1].action").value("APPROVED"));
    }

    @Test
    void auditForAnUnknownRunIs404() throws Exception {
        mvc.perform(get("/api/runs/missing/audit")).andExpect(status().isNotFound());
    }

    private ReleaseDispatcher configuredReleaseDispatcher() {
        return new ReleaseDispatcher() {
            @Override public boolean isConfigured() { return true; }
            @Override public String workflow() { return "release.yml"; }
            @Override public String ref() { return "main"; }
            @Override public ai.devflow.tools.WorkflowDispatchResult dispatch(String repoUrl) {
                throw new UnsupportedOperationException();
            }
        };
    }
}
