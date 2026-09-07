package ai.devflow.orchestrator;

import ai.devflow.agent.*;
import ai.devflow.event.RunEventPublisher;
import ai.devflow.memory.MemoryStore;
import ai.devflow.policy.PolicyResult;
import ai.devflow.skill.ScribeDraft;
import ai.devflow.skill.SkillDraft;
import ai.devflow.skill.SkillIndexEntry;
import ai.devflow.skill.SkillStore;
import ai.devflow.tools.GitHubClient;
import ai.devflow.tools.PullRequestResult;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunRegistryTest {

    RunRegistry registry;

    static class StubAgent implements Agent {
        private final String name;
        StubAgent(String name) { this.name = name; }
        @Override public String name() { return name; }
        @Override public AgentResult run(RunState s) {
            return AgentResult.ok(name, "stub", List.of(), TokenUsage.NONE);
        }
    }

    /** No skills/memory on disk for this test -- nothing to load, nothing to persist. */
    static class NoOpSkillStore implements SkillStore {
        @Override public List<SkillIndexEntry> index(String repoSlug) { return List.of(); }
        @Override public String readFull(String repoSlug, String name) { return ""; }
        @Override public String write(String repoSlug, String runId, SkillDraft draft) { return ""; }
    }

    static class NoOpMemoryStore implements MemoryStore {
        @Override public String read(String repoSlug) { return ""; }
        @Override public String append(String repoSlug, String fact) { return ""; }
    }

    static GitHubClient stubGitHubClient() {
        return new GitHubClient() {
            @Override public void push(ai.devflow.workspace.Workspace workspace, String branchName) {
                throw new UnsupportedOperationException("no test in this file expects a push");
            }

            @Override public PullRequestResult openPullRequest(String repoUrl, String branchName, String title, String body) {
                throw new UnsupportedOperationException("no test in this file expects a PR");
            }
        };
    }

    @BeforeEach
    void setUp() {
        var events = new RunEventPublisher();
        var orchestrator = new Orchestrator(new StubAgent("coder"), new StubAgent("reviewer"), new StubAgent("planner"),
                (task, index) -> List.of(), (state, findings, reason) -> ScribeDraft.EMPTY,
                new NoOpSkillStore(), new NoOpMemoryStore(),
                events, 3, 5, Duration.ofMinutes(1), context -> PolicyResult.ok(), stubGitHubClient());
        RunExecutor stubDirectExecutor = (state, gate) -> new Orchestrator.RunOutcome(true, "stub", state);
        registry = new RunRegistry(orchestrator, stubDirectExecutor, events, Executors.newCachedThreadPool(),
                java.nio.file.Path.of("src/test/resources/fixture"), Duration.ofSeconds(2),
                Duration.ofSeconds(2));
    }

    @Test
    void startingARunGivesItAUniqueIdAndRegistersIt() {
        RunHandle a = registry.start("task one", "fixture", RunStrategy.ORCHESTRATED, false);
        RunHandle b = registry.start("task two", "fixture", RunStrategy.ORCHESTRATED, false);

        assertThat(a.runId()).isNotBlank();
        assertThat(b.runId()).isNotEqualTo(a.runId());
        assertThat(registry.find(a.runId())).isSameAs(a);
        assertThat(registry.find(b.runId())).isSameAs(b);
    }

    @Test
    void findingAnUnknownRunReturnsNull() {
        assertThat(registry.find("no-such-run")).isNull();
    }

    @Test
    void theHandleExposesTheRunsStateAndGate() {
        RunHandle handle = registry.start("a task", "fixture", RunStrategy.ORCHESTRATED, false);
        assertThat(handle.state().task()).isEqualTo("a task");
        assertThat(handle.state().runId()).isEqualTo(handle.runId());
        assertThat(handle.gate()).isNotNull();
    }

    @Test
    void aNonHttpsRepoIsRejectedSynchronouslyWithoutStartingAnyWork() {
        assertThatThrownBy(() -> registry.start("task", "ext::sh -c \"true\"", RunStrategy.ORCHESTRATED, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void directStrategyDispatchesToTheDirectExecutorNotTheOrchestrator() {
        java.util.concurrent.atomic.AtomicBoolean directCalled = new java.util.concurrent.atomic.AtomicBoolean(false);
        var events = new RunEventPublisher();
        var orchestrator = new Orchestrator(new StubAgent("coder"), new StubAgent("reviewer"), new StubAgent("planner"),
                (task, index) -> List.of(), (state, findings, reason) -> ScribeDraft.EMPTY,
                new NoOpSkillStore(), new NoOpMemoryStore(),
                events, 3, 5, Duration.ofMinutes(1), context -> PolicyResult.ok(), stubGitHubClient());
        RunExecutor recordingDirectExecutor = (state, gate) -> {
            directCalled.set(true);
            return new Orchestrator.RunOutcome(true, "stub", state);
        };
        var directRegistry = new RunRegistry(orchestrator, recordingDirectExecutor, events, Executors.newCachedThreadPool(),
                java.nio.file.Path.of("src/test/resources/fixture"), Duration.ofSeconds(2),
                Duration.ofSeconds(2));

        RunHandle handle = directRegistry.start("a task", "fixture", RunStrategy.DIRECT, false);
        while (!handle.task().isDone()) {
            try { Thread.sleep(5); } catch (InterruptedException e) { throw new RuntimeException(e); }
        }

        assertThat(directCalled.get()).isTrue();
    }

    @Test
    void normalizeRepoUrlStripsTrailingGitAndSlashSoBothFormsMatch() {
        assertThat(RunRegistry.normalizeRepoUrl("https://github.com/o/r.git"))
                .isEqualTo(RunRegistry.normalizeRepoUrl("https://github.com/o/r"));
        assertThat(RunRegistry.normalizeRepoUrl("https://github.com/o/r.git"))
                .isEqualTo("github.com/o/r");
        assertThat(RunRegistry.normalizeRepoUrl("https://github.com/o/r/"))
                .isEqualTo("github.com/o/r");
    }
}
