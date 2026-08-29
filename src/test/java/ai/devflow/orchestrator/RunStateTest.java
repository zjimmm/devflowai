package ai.devflow.orchestrator;

import ai.devflow.agent.AgentResult;
import ai.devflow.agent.Finding;
import ai.devflow.agent.TokenUsage;
import ai.devflow.workspace.FixtureWorkspace;
import ai.devflow.workspace.Workspace;
import org.junit.jupiter.api.*;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

class RunStateTest {

    Workspace workspace;

    @BeforeEach
    void setUp() throws Exception {
        workspace = new FixtureWorkspace(Path.of("src/test/resources/fixture"), "runstate-test");
        workspace.prepare();
    }

    @AfterEach
    void tearDown() throws Exception { workspace.cleanup(); }

    @Test
    void exposesGitToolsBoundToItsOwnWorkspace() {
        var state = new RunState("r1", "a task", workspace);
        assertThat(state.gitTools()).isNotNull();
        // Same instance every call — not rebuilt per access.
        assertThat(state.gitTools()).isSameAs(state.gitTools());
        // Bound to THIS run's workspace: a clean fixture reports no changes.
        assertThat(state.gitTools().changedFiles()).isEmpty();
    }

    @Test
    void concurrentRecordsAreAllRetained() throws Exception {
        var state = new RunState("r2", "a task", workspace);
        int threads = 8, perThread = 50;
        var pool = Executors.newFixedThreadPool(threads);
        var start = new CountDownLatch(1);

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                start.await();
                for (int i = 0; i < perThread; i++) {
                    state.record(AgentResult.ok("coder", "s", List.of(), new TokenUsage(1, 1)));
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        assertThat(state.history()).hasSize(threads * perThread);
        assertThat(state.totalTokens()).isEqualTo(new TokenUsage(threads * perThread, threads * perThread));
    }

    @Test
    void concurrentIterationIncrementsAreNotLost() throws Exception {
        var state = new RunState("r3", "a task", workspace);
        int threads = 8, perThread = 50;
        var pool = Executors.newFixedThreadPool(threads);
        var start = new CountDownLatch(1);

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                start.await();
                for (int i = 0; i < perThread; i++) state.incrementHumanIterations();
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        assertThat(state.humanIterations()).isEqualTo(threads * perThread);
    }

    @Test
    void historySnapshotIsNotAffectedByLaterWrites() {
        var state = new RunState("r4", "a task", workspace);
        state.record(AgentResult.ok("coder", "first", List.of(), TokenUsage.NONE));
        List<AgentResult> snapshot = state.history();
        state.record(AgentResult.ok("coder", "second", List.of(), TokenUsage.NONE));
        assertThat(snapshot).hasSize(1);
    }

    @Test
    void tracksPhase() {
        var state = new RunState("r5", "a task", workspace);
        assertThat(state.phase()).isEqualTo(RunPhase.PREPARING);
        state.setPhase(RunPhase.CODING);
        assertThat(state.phase()).isEqualTo(RunPhase.CODING);
    }
}
