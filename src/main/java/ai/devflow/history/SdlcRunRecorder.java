package ai.devflow.history;

import ai.devflow.event.RunRecorded;
import ai.devflow.orchestrator.RunPhase;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
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

    public SdlcRunRecorder(SdlcRunRepository runs, StageExecutionRepository stages) {
        this.runs = runs;
        this.stages = stages;
    }

    @EventListener
    public void onRunRecorded(RunRecorded recorded) {
        String runId = recorded.runId();
        Map<String, Object> data = recorded.event().data();

        maybeCreateRun(runId, data);
        maybeRecordStage(runId, data);
        maybeFinishRun(runId, recorded.event().type(), recorded.event().message(), data);
    }

    private void maybeCreateRun(String runId, Map<String, Object> data) {
        if (data.get("task") instanceof String task
                && data.get("repoSlug") instanceof String repoSlug
                && runs.findById(runId).isEmpty()) {
            runs.save(new SdlcRun(runId, task, repoSlug, Instant.now()));
        }
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
