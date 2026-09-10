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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
    @Autowired RunAuditEntryRepository auditEntries;
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
    void aRunEventCreatesAnImmutableAuditEntry() {
        recorder.onRunRecorded(new RunRecorded("r-audit", new RunEvent("step", "Workspace ready",
                Map.of("task", "t", "repoSlug", "fixture", "phase", "PREPARING", "strategy", "DIRECT"))));

        var entry = auditEntries.findByRunIdOrderByOccurredAtAscIdAsc("r-audit").getFirst();
        assertThat(entry.actor()).isEqualTo("SYSTEM");
        assertThat(entry.action()).isEqualTo("STEP");
        assertThat(entry.phase()).isEqualTo("PREPARING");
        assertThat(entry.message()).isEqualTo("Workspace ready");
        assertThat(entry.dataJson()).contains("repoSlug");
    }

    @Test
    void oversizedAuditMetadataIsReducedToItsKeys() {
        recorder.onRunRecorded(new RunRecorded("r-audit-large", new RunEvent("step", "Large event",
                Map.of("details", "x".repeat(8_100)))));

        var entry = auditEntries.findByRunIdOrderByOccurredAtAscIdAsc("r-audit-large").getFirst();
        assertThat(entry.dataJson()).contains("truncated").contains("details");
        assertThat(entry.dataJson().length()).isLessThanOrEqualTo(8_000);
    }

    @Test
    void anAuditWriteFailureDoesNotPreventRunHistoryRecording() {
        var localRuns = mock(SdlcRunRepository.class);
        var localStages = mock(StageExecutionRepository.class);
        var localAudits = mock(RunAuditEntryRepository.class);
        when(localRuns.findById("r-audit-failure")).thenReturn(java.util.Optional.empty());
        doThrow(new RuntimeException("database unavailable")).when(localAudits).save(any(RunAuditEntry.class));
        var localRecorder = new SdlcRunRecorder(localRuns, localStages, mock(ReviewFindingRepository.class),
                mock(ApprovalRepository.class), localAudits);

        assertThatCode(() -> localRecorder.onRunRecorded(new RunRecorded("r-audit-failure", new RunEvent("step", "start",
                Map.of("task", "t", "repoSlug", "fixture", "strategy", "DIRECT"))))).doesNotThrowAnyException();

        verify(localRuns).save(any(SdlcRun.class));
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
    void stagingEventsRecordDispatchAndVerification() {
        recorder.onRunRecorded(new RunRecorded("r-staging", new RunEvent("step", "start",
                Map.of("task", "t", "repoSlug", "fixture", "phase", "PREPARING", "strategy", "DIRECT"))));

        recorder.onRunRecorded(new RunRecorded("r-staging", new RunEvent("step", "dispatched",
                Map.of("phase", "DISPATCHING_STAGING", "stagingStatus", "DISPATCHED",
                        "stagingUrl", "https://github.com/o/r/actions/runs/6"))));
        recorder.onRunRecorded(new RunRecorded("r-staging", new RunEvent("step", "verified",
                Map.of("phase", "VERIFYING_STAGING", "stagingVerificationStatus", "PASSED"))));

        var run = runs.findById("r-staging").orElseThrow();
        assertThat(run.stagingStatus()).isEqualTo("DISPATCHED");
        assertThat(run.stagingUrl()).isEqualTo("https://github.com/o/r/actions/runs/6");
        assertThat(run.stagingVerificationStatus()).isEqualTo("PASSED");
    }

    @Test
    void requirementAnalysisRecordsStatusAndAcceptanceCriteriaCount() {
        recorder.onRunRecorded(new RunRecorded("r-requirements", new RunEvent("step", "start",
                Map.of("task", "t", "repoSlug", "fixture", "phase", "PREPARING", "strategy", "ORCHESTRATED"))));

        recorder.onRunRecorded(new RunRecorded("r-requirements", new RunEvent("step", "analyzed",
                Map.of("phase", "ANALYZING_REQUIREMENTS", "requirementStatus", "READY",
                        "acceptanceCriteriaCount", 4))));

        var run = runs.findById("r-requirements").orElseThrow();
        assertThat(run.requirementStatus()).isEqualTo("READY");
        assertThat(run.acceptanceCriteriaCount()).isEqualTo(4);
    }

    @Test
    void smokeEventsRecordStatusAndEndpoint() {
        recorder.onRunRecorded(new RunRecorded("r-smoke", new RunEvent("step", "start",
                Map.of("task", "t", "repoSlug", "fixture", "phase", "PREPARING", "strategy", "DIRECT"))));

        recorder.onRunRecorded(new RunRecorded("r-smoke", new RunEvent("step", "smoke passed",
                Map.of("phase", "RUNNING_SMOKE_TESTS", "smokeStatus", "PASSED",
                        "smokeUrl", "https://service.example/smoke"))));

        var run = runs.findById("r-smoke").orElseThrow();
        assertThat(run.smokeStatus()).isEqualTo("PASSED");
        assertThat(run.smokeUrl()).isEqualTo("https://service.example/smoke");
    }

    @Test
    void aReleaseEventRecordsItsStatusAndUrl() {
        recorder.onRunRecorded(new RunRecorded("r-release", new RunEvent("step", "start",
                Map.of("task", "t", "repoSlug", "fixture", "phase", "PREPARING", "strategy", "DIRECT"))));

        recorder.onRunRecorded(new RunRecorded("r-release", new RunEvent("step", "dispatched",
                Map.of("phase", "DISPATCHING_RELEASE", "releaseStatus", "DISPATCHED",
                        "releaseUrl", "https://github.com/o/r/actions/runs/7"))));

        var run = runs.findById("r-release").orElseThrow();
        assertThat(run.releaseStatus()).isEqualTo("DISPATCHED");
        assertThat(run.releaseUrl()).isEqualTo("https://github.com/o/r/actions/runs/7");
    }

    @Test
    void aVerificationEventRecordsItsLatestStatus() {
        recorder.onRunRecorded(new RunRecorded("r-verification", new RunEvent("step", "start",
                Map.of("task", "t", "repoSlug", "fixture", "phase", "PREPARING", "strategy", "DIRECT"))));

        recorder.onRunRecorded(new RunRecorded("r-verification", new RunEvent("step", "waiting",
                Map.of("phase", "VERIFYING_DEPLOYMENT", "verificationStatus", "PENDING"))));
        recorder.onRunRecorded(new RunRecorded("r-verification", new RunEvent("step", "passed",
                Map.of("phase", "VERIFYING_DEPLOYMENT", "verificationStatus", "PASSED"))));

        assertThat(runs.findById("r-verification").orElseThrow().verificationStatus()).isEqualTo("PASSED");
    }

    @Test
    void aHealthEventRecordsItsStatusAndUrl() {
        recorder.onRunRecorded(new RunRecorded("r-health", new RunEvent("step", "start",
                Map.of("task", "t", "repoSlug", "fixture", "phase", "PREPARING", "strategy", "DIRECT"))));

        recorder.onRunRecorded(new RunRecorded("r-health", new RunEvent("step", "checked",
                Map.of("phase", "CHECKING_OPERATIONAL_HEALTH", "healthStatus", "PASSED",
                        "healthUrl", "https://service.example/health"))));

        var run = runs.findById("r-health").orElseThrow();
        assertThat(run.healthStatus()).isEqualTo("PASSED");
        assertThat(run.healthUrl()).isEqualTo("https://service.example/health");
    }

    @Test
    void aRollbackEventRecordsItsStatusAndUrl() {
        recorder.onRunRecorded(new RunRecorded("r-rollback", new RunEvent("step", "start",
                Map.of("task", "t", "repoSlug", "fixture", "phase", "PREPARING", "strategy", "DIRECT"))));

        recorder.onRunRecorded(new RunRecorded("r-rollback", new RunEvent("step", "dispatched",
                Map.of("phase", "DISPATCHING_ROLLBACK", "rollbackStatus", "DISPATCHED",
                        "rollbackUrl", "https://github.com/o/r/actions/runs/8"))));

        var run = runs.findById("r-rollback").orElseThrow();
        assertThat(run.rollbackStatus()).isEqualTo("DISPATCHED");
        assertThat(run.rollbackUrl()).isEqualTo("https://github.com/o/r/actions/runs/8");
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
        var audit = auditEntries.findByRunIdOrderByOccurredAtAscIdAsc("r9").getFirst();
        assertThat(audit.actor()).isEqualTo("OPERATOR");
        assertThat(audit.action()).isEqualTo("REJECTED");
        assertThat(audit.message()).contains("use a DTO");
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
