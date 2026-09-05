package ai.devflow.history;

import ai.devflow.event.RunEvent;
import ai.devflow.event.RunRecorded;
import ai.devflow.orchestrator.RunPhase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// @DataJpaTest does not exist in this project's resolved Spring Boot 4.1.1
// dependencies (confirmed in Task 1) -- @SpringBootTest + @Transactional on
// the class gives the same per-test-method rollback isolation against the
// shared in-memory H2 test database (see HistoryRepositoriesTest).
@SpringBootTest
@Transactional
@Import(SdlcRunRecorder.class)
class SdlcRunRecorderTest {

    @Autowired SdlcRunRepository runs;
    @Autowired StageExecutionRepository stages;
    @Autowired SdlcRunRecorder recorder;

    @Test
    void aStartEventCreatesARunningSdlcRun() {
        recorder.onRunRecorded(new RunRecorded("r1", new RunEvent("step", "Workspace ready — branch devflowai/r1",
                Map.of("branch", "devflowai/r1", "task", "add validation", "repoSlug", "fixture", "phase", "PREPARING"))));

        var run = runs.findById("r1").orElseThrow();
        assertThat(run.task()).isEqualTo("add validation");
        assertThat(run.repoSlug()).isEqualTo("fixture");
        assertThat(run.status()).isEqualTo(SdlcRunStatus.RUNNING);
    }

    @Test
    void aStartEventIsIdempotentIfSeenTwice() {
        var start = new RunRecorded("r2", new RunEvent("step", "Workspace ready", Map.of(
                "task", "t", "repoSlug", "fixture", "phase", "PREPARING")));
        recorder.onRunRecorded(start);
        recorder.onRunRecorded(start);

        assertThat(runs.count()).isEqualTo(1);
    }

    @Test
    void aDoneEventFinishesTheRunWithTokens() {
        recorder.onRunRecorded(new RunRecorded("r3", new RunEvent("step", "start",
                Map.of("task", "t", "repoSlug", "fixture", "phase", "PREPARING"))));

        recorder.onRunRecorded(new RunRecorded("r3", new RunEvent("done", "Approved and committed",
                Map.of("phase", "DONE", "inputTokens", 100L, "outputTokens", 40L))));

        var run = runs.findById("r3").orElseThrow();
        assertThat(run.status()).isEqualTo(SdlcRunStatus.DONE);
        assertThat(run.reason()).isEqualTo("Approved and committed");
        assertThat(run.finishedAt()).isNotNull();
        assertThat(run.inputTokens()).isEqualTo(100L);
        assertThat(run.outputTokens()).isEqualTo(40L);
    }

    @Test
    void anAbortedEventMarksTheRunAborted() {
        recorder.onRunRecorded(new RunRecorded("r4", new RunEvent("step", "start",
                Map.of("task", "t", "repoSlug", "fixture", "phase", "PREPARING"))));
        recorder.onRunRecorded(new RunRecorded("r4", new RunEvent("aborted", "Rejected at pre-flight", Map.of())));

        assertThat(runs.findById("r4").orElseThrow().status()).isEqualTo(SdlcRunStatus.ABORTED);
    }

    @Test
    void anErrorEventOnItsOwnStillCreatesAFailedRun() {
        // The RunRegistry workspace-prepare-failure path: task/repoSlug arrive
        // on the SAME error event that ends the run (design decision 5).
        recorder.onRunRecorded(new RunRecorded("r5", new RunEvent("error", "Could not prepare the workspace: boom",
                Map.of("task", "t", "repoSlug", "fixture"))));

        var run = runs.findById("r5").orElseThrow();
        assertThat(run.status()).isEqualTo(SdlcRunStatus.FAILED);
        assertThat(run.reason()).isEqualTo("Could not prepare the workspace: boom");
    }

    @Test
    void aPhaseChangeOpensANewStageAndClosesThePrevious() {
        recorder.onRunRecorded(new RunRecorded("r6", new RunEvent("step", "start",
                Map.of("task", "t", "repoSlug", "fixture", "phase", "PREPARING"))));
        recorder.onRunRecorded(new RunRecorded("r6", new RunEvent("step", "coding",
                Map.of("phase", "CODING"))));

        var open = stages.findByRunIdAndEndedAtIsNull("r6").orElseThrow();
        assertThat(open.phase()).isEqualTo(RunPhase.CODING);
        assertThat(stages.findAll()).filteredOn(s -> s.runId().equals("r6") && s.phase() == RunPhase.PREPARING)
                .allMatch(s -> s.endedAt() != null);
    }

    @Test
    void repeatingTheSamePhaseDoesNotOpenARedundantStage() {
        recorder.onRunRecorded(new RunRecorded("r7", new RunEvent("step", "start",
                Map.of("task", "t", "repoSlug", "fixture", "phase", "PREPARING"))));
        recorder.onRunRecorded(new RunRecorded("r7", new RunEvent("gate", "about to run", Map.of("phase", "PREPARING"))));

        assertThat(stages.findAll()).filteredOn(s -> s.runId().equals("r7")).hasSize(1);
    }
}
