package ai.devflow.web;

import ai.devflow.orchestrator.*;
import ai.devflow.event.RunEventPublisher;
import ai.devflow.workspace.FixtureWorkspace;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Executors;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RunControllerTest {

    MockMvc mvc;
    RunRegistry registry;
    RunEventPublisher events;
    ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        registry = mock(RunRegistry.class);
        events = new RunEventPublisher();
        mvc = MockMvcBuilders.standaloneSetup(new RunController(registry, events)).build();
    }

    private RunHandle handleFor(String runId) throws Exception {
        var workspace = new FixtureWorkspace(Path.of("src/test/resources/fixture"), runId);
        workspace.prepare();
        var state = new RunState(runId, "a task", workspace);
        return new RunHandle(runId, state, new ApprovalGate(Duration.ofSeconds(5)), null);
    }

    @Test
    void startingARunReturnsItsId() throws Exception {
        when(registry.start(any(), any())).thenReturn(handleFor("abc123"));

        mvc.perform(post("/api/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new StartRunRequest("do a thing", "fixture"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value("abc123"));

        verify(registry).start("do a thing", "fixture");
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
}
