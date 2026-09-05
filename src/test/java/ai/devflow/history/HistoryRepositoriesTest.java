package ai.devflow.history;

import ai.devflow.agent.Finding;
import ai.devflow.orchestrator.Gate;
import ai.devflow.orchestrator.RunPhase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class HistoryRepositoriesTest {

    @Autowired SdlcRunRepository runs;
    @Autowired StageExecutionRepository stages;
    @Autowired ReviewFindingRepository findings;
    @Autowired ApprovalRepository approvals;

    @Test
    void savesAndReadsBackASdlcRun() {
        var run = new SdlcRun("r1", "add validation", "fixture", Instant.now());
        runs.save(run);

        var found = runs.findById("r1").orElseThrow();
        assertThat(found.task()).isEqualTo("add validation");
        assertThat(found.status()).isEqualTo(SdlcRunStatus.RUNNING);

        found.finish(SdlcRunStatus.DONE, "Approved", Instant.now(), 100, 50);
        runs.save(found);
        assertThat(runs.findById("r1").orElseThrow().status()).isEqualTo(SdlcRunStatus.DONE);
    }

    @Test
    void findTop50OrdersByStartedAtDescending() {
        runs.save(new SdlcRun("older", "t", "fixture", Instant.now().minusSeconds(60)));
        runs.save(new SdlcRun("newer", "t", "fixture", Instant.now()));

        assertThat(runs.findTop50ByOrderByStartedAtDesc())
                .extracting(SdlcRun::id).containsExactly("newer", "older");
    }

    @Test
    void stageExecutionTracksTheOpenStageForARun() {
        var stage = new StageExecution("r2", RunPhase.CODING, Instant.now());
        stages.save(stage);

        var open = stages.findByRunIdAndEndedAtIsNull("r2").orElseThrow();
        assertThat(open.phase()).isEqualTo(RunPhase.CODING);

        open.close(Instant.now());
        stages.save(open);
        assertThat(stages.findByRunIdAndEndedAtIsNull("r2")).isEmpty();
    }

    @Test
    void reviewFindingCopiesFieldsFromAFinding() {
        var finding = new Finding(Finding.Origin.REVIEWER, Finding.Severity.HIGH, "A.java", 14, "missing @Valid");
        findings.save(new ReviewFinding("r3", 1, finding));

        var saved = findings.findByRunId("r3");
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).message()).isEqualTo("missing @Valid");
        assertThat(saved.get(0).severity()).isEqualTo(Finding.Severity.HIGH);
    }

    @Test
    void approvalRecordsTheGateAndDecision() {
        approvals.save(new Approval("r4", Gate.BEFORE_BUILD, false, "use a DTO", Instant.now()));

        var saved = approvals.findByRunId("r4");
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).gate()).isEqualTo(Gate.BEFORE_BUILD);
        assertThat(saved.get(0).approved()).isFalse();
    }
}
