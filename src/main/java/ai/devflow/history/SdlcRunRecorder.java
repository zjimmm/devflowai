package ai.devflow.history;

import ai.devflow.agent.Finding;
import ai.devflow.event.ApprovalRecorded;
import ai.devflow.event.RunRecorded;
import ai.devflow.orchestrator.Gate;
import ai.devflow.orchestrator.RunPhase;
import ai.devflow.orchestrator.RunStrategy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Write-behind persistence for the run history (spec §3). Stateless: every
 * decision reads the database first, never an instance field, so this stays
 * a safe singleton bean.
 */
@Component
public class SdlcRunRecorder {

    private final SdlcRunRepository runs;
    private final StageExecutionRepository stages;
    private final ReviewFindingRepository findings;
    private final ApprovalRepository approvals;

    public SdlcRunRecorder(SdlcRunRepository runs, StageExecutionRepository stages,
                            ReviewFindingRepository findings, ApprovalRepository approvals) {
        this.runs = runs;
        this.stages = stages;
        this.findings = findings;
        this.approvals = approvals;
    }

    @EventListener
    public void onRunRecorded(RunRecorded recorded) {
        // Write-behind: this reacts synchronously, in the publisher's thread,
        // to every step/gate/done/error event Orchestrator.emit() fires -- a
        // persistence hiccup (or an unexpected phase string) here must never
        // propagate out and abort the live run it's only trying to record.
        // Same drop-and-continue precedent as RunEventPublisher.sendQuietly.
        try {
            String runId = recorded.runId();
            Map<String, Object> data = recorded.event().data();

            maybeCreateRun(runId, data);
            maybeRecordStage(runId, data);
            maybeRecordFindings(runId, data);
            maybeRecordBuildResult(runId, data);
            maybeRecordPrUrl(runId, data);
            maybeRecordCiStatus(runId, data);
            maybeRecordRelease(runId, data);
            maybeFinishRun(runId, recorded.event().type(), recorded.event().message(), data);
        } catch (RuntimeException e) {
            System.err.println("SdlcRunRecorder failed to record an event for run " + recorded.runId() + ": " + e);
        }
    }

    @EventListener
    public void onApprovalRecorded(ApprovalRecorded recorded) {
        // Same drop-and-continue precedent as onRunRecorded above: this is
        // invoked synchronously from RunController.approve's calling thread,
        // so a persistence hiccup (or an unexpected gate string) here must
        // never propagate out and abort the live run it's only recording.
        try {
            approvals.save(new Approval(recorded.runId(), Gate.valueOf(recorded.gate()),
                    recorded.approved(), recorded.reason(), Instant.now()));
        } catch (RuntimeException e) {
            System.err.println("SdlcRunRecorder failed to record an approval for run " + recorded.runId() + ": " + e);
        }
    }

    private void maybeCreateRun(String runId, Map<String, Object> data) {
        if (data.get("task") instanceof String task
                && data.get("repoSlug") instanceof String repoSlug
                && data.get("strategy") instanceof String strategyName
                && runs.findById(runId).isEmpty()) {
            runs.save(new SdlcRun(runId, task, repoSlug, RunStrategy.valueOf(strategyName), Instant.now()));
        }
    }

    private void maybeRecordBuildResult(String runId, Map<String, Object> data) {
        if (!(data.get("success") instanceof Boolean succeeded)) return;
        runs.findById(runId).ifPresent(run -> {
            run.recordBuildResult(succeeded);
            runs.save(run);
        });
    }

    private void maybeRecordPrUrl(String runId, Map<String, Object> data) {
        if (!(data.get("prUrl") instanceof String prUrl)) return;
        runs.findById(runId).ifPresent(run -> {
            run.recordPrUrl(prUrl);
            runs.save(run);
        });
    }

    private void maybeRecordCiStatus(String runId, Map<String, Object> data) {
        if (!(data.get("ciStatus") instanceof String ciStatus)) return;
        runs.findById(runId).ifPresent(run -> {
            run.recordCiStatus(ciStatus);
            runs.save(run);
        });
    }

    private void maybeRecordRelease(String runId, Map<String, Object> data) {
        if (!(data.get("releaseStatus") instanceof String releaseStatus)) return;
        String releaseUrl = data.get("releaseUrl") instanceof String url ? url : null;
        runs.findById(runId).ifPresent(run -> {
            run.recordRelease(releaseStatus, releaseUrl);
            runs.save(run);
        });
    }

    private void maybeRecordStage(String runId, Map<String, Object> data) {
        if (!(data.get("phase") instanceof String phaseName)) return;
        RunPhase phase = RunPhase.valueOf(phaseName);

        Optional<StageExecution> open = stages.findByRunIdAndEndedAtIsNull(runId);
        if (open.isPresent() && open.get().phase() == phase) return;

        Instant now = Instant.now();
        open.ifPresent(s -> { s.close(now); stages.save(s); });
        stages.save(new StageExecution(runId, phase, now));
    }

    private void maybeRecordFindings(String runId, Map<String, Object> data) {
        if (!(data.get("findingsDetail") instanceof List<?> list)) return;
        int iteration = data.get("reviewIteration") instanceof Integer i ? i : 0;
        for (Object o : list) {
            if (o instanceof Finding f) {
                findings.save(new ReviewFinding(runId, iteration, f));
            }
        }
    }

    private void maybeFinishRun(String runId, String type, String message, Map<String, Object> data) {
        SdlcRunStatus status = switch (type) {
            case "done" -> SdlcRunStatus.DONE;
            case "error" -> SdlcRunStatus.FAILED;
            case "aborted" -> SdlcRunStatus.ABORTED;
            default -> null;
        };
        if (status == null) return;

        runs.findById(runId).ifPresent(run -> {
            run.finish(status, message, Instant.now(), longOrZero(data, "inputTokens"), longOrZero(data, "outputTokens"));
            runs.save(run);
        });
        stages.findByRunIdAndEndedAtIsNull(runId).ifPresent(s -> { s.close(Instant.now()); stages.save(s); });
    }

    private long longOrZero(Map<String, Object> data, String key) {
        return data.get(key) instanceof Long l ? l : 0L;
    }
}
