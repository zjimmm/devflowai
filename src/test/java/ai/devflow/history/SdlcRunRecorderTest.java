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
                Map.of("branch", "devflowai/r1", "task", "add validation", "repoSlug", "fixture", "phase", "PREPARING",
                        "strategy", "ORCHESTRATED"))));

        var run = runs.findById("r1").orElseThrow();
        assertThat(run.task()).isEqualTo("add validation");
        assertThat(run.repoSlug()).isEqualTo("fixture");
        assertThat(run.status()).isEqualTo(SdlcRunStatus.RUNNING);
    }

    @Test
    void aStartEventWithStrategyCreatesARunWithThatStrategy() {
        recorder.onRunRecorded(new RunRecorded("r11", new RunEvent("step", "Workspace ready",
                Map.of("task", "t", "repoSlug", "fixture", "phase", "PREPARING", "strategy", "DIRECT"))));

        var run = runs.findById("r11").orElseThrow();
        assertThat(run.strategy()).isEqualTo(ai.devflow.orchestrator.RunStrategy.DIRECT);
    }

    @Test
    void aBuildStepEventRecordsBuildSucceededAndTheLatestOneWins() {
        recorder.onRunRecorded(new RunRecorded("r12", new RunEvent("step", "start",
                Map.of("task", "t", "repoSlug", "fixture", "phase", "PREPARING", "strategy", "ORCHESTRATED"))));

        recorder.onRunRecorded(new RunRecorded("r12", new RunEvent("step", "Build failed",
                Map.of("phase", "BUILDING", "success", false))));
        assertThat(runs.findById("r12").orElseThrow().buildSucceeded()).isFalse();

        recorder.onRunRecorded(new RunRecorded("r12", new RunEvent("step", "Build passed",
                Map.of("phase", "BUILDING", "success", true))));
        assertThat(runs.findById("r12").orElseThrow().buildSucceeded()).isTrue();
    }

    @Test
    void aDoneEventWithAPrUrlRecordsIt() {
        recorder.onRunRecorded(new RunRecorded("r13", new RunEvent("step", "start",
                Map.of("task", "t", "repoSlug", "fixture", "phase", "PREPARING", "strategy", "ORCHESTRATED"))));

        recorder.onRunRecorded(new RunRecorded("r13", new RunEvent("done", "done",
                Map.of("phase", "DONE", "prUrl", "https://github.com/o/r/pull/3"))));

        assertThat(runs.findById("r13").orElseThrow().prUrl()).isEqualTo("https://github.com/o/r/pull/3");
    }

    @Test
    void aCiObservationRecordsItsLatestStatus() {
        recorder.onRunRecorded(new RunRecorded("r-ci", new RunEvent("step", "start",
                Map.of("task", "t", "repoSlug", "fixture", "phase", "PREPARING", "strategy", "ORCHESTRATED"))));

        recorder.onRunRecorded(new RunRecorded("r-ci", new RunEvent("step", "waiting",
                Map.of("phase", "VALIDATING_CI", "ciStatus", "PENDING"))));
        recorder.onRunRecorded(new RunRecorded("r-ci", new RunEvent("step", "passed",
                Map.of("phase", "VALIDATING_CI", "ciStatus", "PASSED"))));

        assertThat(runs.findById("r-ci").orElseThrow().ciStatus()).isEqualTo("PASSED");
    }

    @Test
    void aDoneEventWithNoPrUrlLeavesItNull() {
        recorder.onRunRecorded(new RunRecorded("r14", new RunEvent("step", "start",
                Map.of("task", "t", "repoSlug", "fixture", "phase", "PREPARING", "strategy", "DIRECT"))));

        recorder.onRunRecorded(new RunRecorded("r14", new RunEvent("done", "done", Map.of("phase", "DONE"))));

        assertThat(runs.findById("r14").orElseThrow().prUrl()).isNull();
    }

    @Test
    void aStartEventIsIdempotentIfSeenTwice() {
        var start = new RunRecorded("r2", new RunEvent("step", "Workspace ready", Map.of(
                "task", "t", "repoSlug", "fixture", "phase", "PREPARING", "strategy", "ORCHESTRATED")));
        recorder.onRunRecorded(start);
        recorder.onRunRecorded(start);

        assertThat(runs.findAll()).filteredOn(r -> r.id().equals("r2")).hasSize(1);
    }

    @Test
    void aDoneEventFinishesTheRunWithTokens() {
        recorder.onRunRecorded(new RunRecorded("r3", new RunEvent("step", "start",
                Map.of("task", "t", "repoSlug", "fixture", "phase", "PREPARING", "strategy", "ORCHESTRATED"))));

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
                Map.of("task", "t", "repoSlug", "fixture", "phase", "PREPARING", "strategy", "ORCHESTRATED"))));
        recorder.onRunRecorded(new RunRecorded("r4", new RunEvent("aborted", "Rejected at pre-flight", Map.of())));

        assertThat(runs.findById("r4").orElseThrow().status()).isEqualTo(SdlcRunStatus.ABORTED);
    }

    @Test
    void anErrorEventOnItsOwnStillCreatesAFailedRun() {
        // The RunRegistry workspace-prepare-failure path: task/repoSlug arrive
        // on the SAME error event that ends the run (design decision 5).
        recorder.onRunRecorded(new RunRecorded("r5", new RunEvent("error", "Could not prepare the workspace: boom",
                Map.of("task", "t", "repoSlug", "fixture", "strategy", "ORCHESTRATED"))));

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
