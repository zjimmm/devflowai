package ai.devflow.orchestrator;

import ai.devflow.event.RunEvent;
import ai.devflow.event.RunEventPublisher;
import ai.devflow.util.Slug;
import ai.devflow.workspace.ClonedWorkspace;
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
    private final RunExecutor directExecutor;
    private final RunEventPublisher events;
    private final ExecutorService executor;
    private final Path fixtureSource;
    private final Duration gateTimeout;
    private final Duration cloneTimeout;

    private final Map<String, RunHandle> runs = new ConcurrentHashMap<>();

    public RunRegistry(Orchestrator orchestrator, RunExecutor directExecutor, RunEventPublisher events,
                       ExecutorService executor, Path fixtureSource, Duration gateTimeout,
                       Duration cloneTimeout) {
        this.orchestrator = orchestrator;
        this.directExecutor = directExecutor;
        this.events = events;
        this.executor = executor;
        this.fixtureSource = fixtureSource;
        this.gateTimeout = gateTimeout;
        this.cloneTimeout = cloneTimeout;
    }

    /**
     * Prepares a workspace and starts the orchestrator on the executor.
     *
     * @param repo {@code "fixture"} for the bundled fixture, or an
     *             {@code https://} git URL to clone (spec §4.1). A rejected
     *             URL throws {@link IllegalArgumentException} synchronously
     *             from this method -- {@link ClonedWorkspace}'s constructor
     *             validates before any network call, so the caller (
     *             {@code RunController}) gets an immediate, clean error
     *             rather than an async failure event after the run has
     *             already been reported started.
     */
    public RunHandle start(String task, String repo, RunStrategy strategy) {
        String runId = UUID.randomUUID().toString().substring(0, 8);
        Workspace workspace;
        String repoSlug;
        if ("fixture".equals(repo)) {
            workspace = new FixtureWorkspace(fixtureSource, runId);
            repoSlug = "fixture";
        } else {
            workspace = new ClonedWorkspace(repo, runId, cloneTimeout);
            repoSlug = Slug.of(normalizeRepoUrl(repo));
        }
        RunState state = new RunState(runId, task, workspace, repoSlug);
        ApprovalGate gate = new ApprovalGate(gateTimeout);
        RunExecutor selectedExecutor = strategy == RunStrategy.DIRECT ? directExecutor : orchestrator;

        var future = executor.submit(() -> {
            try {
                workspace.prepare();
                selectedExecutor.run(state, gate);
            } catch (Exception e) {
                state.setPhase(RunPhase.FAILED);
                events.publish(runId, RunEvent.of("error",
                        "Could not prepare the workspace: " + e.getMessage(),
                        Map.of("task", task, "repoSlug", repoSlug, "strategy", strategy.name())));
                // workspace.prepare() can throw partway through (e.g. after
                // copying files but before git init completes), leaking a temp
                // directory on disk. Orchestrator.run's own cleanup is never
                // reached here because orchestrator.run was never called.
                try {
                    workspace.cleanup();
                } catch (Exception cleanupFailure) {
                    events.publish(runId, RunEvent.of("warn",
                            "Workspace cleanup also failed: " + cleanupFailure.getMessage(), Map.of()));
                }
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

    /** Package-private for RunRegistryTest. Strips the parts of an https:// repo
     * URL that don't identify the repo itself, so "https://host/o/r.git" and
     * "https://host/o/r" -- both plausible pastes for the same repo -- derive
     * the same repoSlug rather than two disjoint learning-loop identities. */
    static String normalizeRepoUrl(String url) {
        String s = url.substring("https://".length());
        if (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        if (s.endsWith(".git")) s = s.substring(0, s.length() - 4);
        return s;
    }
}
