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
import static org.assertj.core.api.Assertions.assertThatCode;

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
    @Autowired ReviewFindingRepository findings;
    @Autowired ApprovalRepository approvals;
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

        assertThat(runs.findAll()).filteredOn(r -> r.id().equals("r2")).hasSize(1);
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

    @Test
    void aRecordingFailureIsSwallowedRatherThanPropagatingToTheLiveRun() {
        // An unexpected phase string (RunPhase.valueOf throws IllegalArgumentException)
        // stands in for any persistence hiccup here -- write-behind history
        // bookkeeping must never be able to abort the run it's recording.
        var event = new RunRecorded("r8", new RunEvent("step", "bogus",
                Map.of("task", "t", "repoSlug", "fixture", "phase", "NOT_A_REAL_PHASE")));

        assertThatCode(() -> recorder.onRunRecorded(event)).doesNotThrowAnyException();
    }

    @Test
    void needsWorkEventWithFindingsDetailWritesReviewFindingRows() {
        var f = new ai.devflow.agent.Finding(ai.devflow.agent.Finding.Origin.REVIEWER,
                ai.devflow.agent.Finding.Severity.HIGH, "A.java", 14, "missing @Valid");

        recorder.onRunRecorded(new RunRecorded("r8", new RunEvent("step", "Reviewer — needs work: nope",
                Map.of("phase", "REVIEWING", "findings", 1, "findingsDetail", java.util.List.of(f), "reviewIteration", 1))));

        var saved = findings.findByRunId("r8");
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).message()).isEqualTo("missing @Valid");
        assertThat(saved.get(0).reviewIteration()).isEqualTo(1);
    }

    @Test
    void approvalRecordedEventWritesAnApprovalRow() {
        recorder.onApprovalRecorded(new ai.devflow.event.ApprovalRecorded("r9", "BEFORE_BUILD", false, "use a DTO"));

        var saved = approvals.findByRunId("r9");
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).gate()).isEqualTo(ai.devflow.orchestrator.Gate.BEFORE_BUILD);
        assertThat(saved.get(0).approved()).isFalse();
        assertThat(saved.get(0).reason()).isEqualTo("use a DTO");
    }

    @Test
    void anApprovalRecordingFailureIsSwallowedRatherThanPropagatingToTheLiveRun() {
        // A malformed gate string (Gate.valueOf throws IllegalArgumentException)
        // stands in for any persistence hiccup here -- this listener is invoked
        // synchronously from RunController.approve's calling thread and must
        // never be able to abort a live run either.
        var event = new ai.devflow.event.ApprovalRecorded("r10", "NOT_A_REAL_GATE", true, null);

        assertThatCode(() -> recorder.onApprovalRecorded(event)).doesNotThrowAnyException();
        assertThat(approvals.findByRunId("r10")).isEmpty();
    }
}
