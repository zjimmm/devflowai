package ai.devflow.orchestrator;

import ai.devflow.event.RunEvent;
import ai.devflow.event.RunEventPublisher;
import ai.devflow.workspace.FixtureWorkspace;
import ai.devflow.workspace.Workspace;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/**
 * Starts runs and keeps the in-flight ones addressable by id.
 *
 * <p>In-memory and single-node by design (spec §11): an interrupted run is
 * lost, and that is an accepted limitation for a single-user local tool.
 */
public class RunRegistry {

    private final Orchestrator orchestrator;
    private final RunEventPublisher events;
    private final ExecutorService executor;
    private final Path fixtureSource;
    private final Duration gateTimeout;

    private final Map<String, RunHandle> runs = new ConcurrentHashMap<>();

    public RunRegistry(Orchestrator orchestrator, RunEventPublisher events,
                       ExecutorService executor, Path fixtureSource, Duration gateTimeout) {
        this.orchestrator = orchestrator;
        this.events = events;
        this.executor = executor;
        this.fixtureSource = fixtureSource;
        this.gateTimeout = gateTimeout;
    }

    /**
     * Prepares a workspace and starts the orchestrator on the executor.
     *
     * @param repo currently only "fixture" — Phase 6 adds git-URL cloning
     *             behind the same {@link Workspace} interface.
     */
    public RunHandle start(String task, String repo) {
        String runId = UUID.randomUUID().toString().substring(0, 8);
        Workspace workspace = new FixtureWorkspace(fixtureSource, runId);
        RunState state = new RunState(runId, task, workspace);
        ApprovalGate gate = new ApprovalGate(gateTimeout);

        var future = executor.submit(() -> {
            try {
                workspace.prepare();
                orchestrator.run(state, gate);
            } catch (Exception e) {
                state.setPhase(RunPhase.FAILED);
                events.publish(runId, RunEvent.of("error",
                        "Could not prepare the workspace: " + e.getMessage(), Map.of()));
                events.complete(runId);
            } finally {
                runs.remove(runId);
            }
        });

        RunHandle handle = new RunHandle(runId, state, gate, future);
        runs.put(runId, handle);
        return handle;
    }

    public RunHandle find(String runId) {
        return runs.get(runId);
    }
}
