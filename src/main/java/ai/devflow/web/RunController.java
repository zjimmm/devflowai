package ai.devflow.web;

import ai.devflow.event.ApprovalRecorded;
import ai.devflow.event.RunEventPublisher;
import ai.devflow.history.SdlcRunRepository;
import ai.devflow.orchestrator.ApprovalDecision;
import ai.devflow.orchestrator.Gate;
import ai.devflow.orchestrator.RunHandle;
import ai.devflow.orchestrator.RunRegistry;
import ai.devflow.orchestrator.RunStrategy;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/**
 * The entire HTTP surface: start a run, watch it, answer a gate, and retrieve history.
 *
 * <p>Four endpoints and nothing else — the page reaches no other route
 * (spec §5.3).
 */
@RestController
@RequestMapping("/api/runs")
public class RunController {

    private final RunRegistry registry;
    private final RunEventPublisher events;
    private final ApplicationEventPublisher applicationEvents;
    private final SdlcRunRepository history;

    public RunController(RunRegistry registry, RunEventPublisher events, ApplicationEventPublisher applicationEvents, SdlcRunRepository history) {
        this.registry = registry;
        this.events = events;
        this.applicationEvents = applicationEvents;
        this.history = history;
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> start(@RequestBody StartRunRequest request) {
        if (request.task() == null || request.task().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "task must not be blank"));
        }
        String rawRepo = request.repo() == null ? "" : request.repo().trim();
        String repo = rawRepo.isBlank() ? "fixture" : rawRepo;
        String rawStrategy = request.strategy() == null ? "" : request.strategy().trim();
        RunStrategy strategy = "direct".equalsIgnoreCase(rawStrategy) ? RunStrategy.DIRECT : RunStrategy.ORCHESTRATED;
        boolean openPr = Boolean.TRUE.equals(request.openPr());
        if (openPr) {
            if ("fixture".equals(repo)) {
                return ResponseEntity.badRequest().body(
                        Map.of("error", "Opening a PR requires a real repository, not the bundled fixture"));
            }
            if (!repo.startsWith("https://github.com/")) {
                return ResponseEntity.badRequest().body(
                        Map.of("error", "Opening a PR is only supported for github.com repositories, got: " + repo));
            }
        }
        RunHandle handle;
        try {
            handle = registry.start(request.task().trim(), repo, strategy, openPr);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
        return ResponseEntity.ok(Map.of("runId", handle.runId()));
    }

    @GetMapping("/{runId}/stream")
    public ResponseEntity<SseEmitter> stream(@PathVariable String runId) {
        if (registry.find(runId) == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(events.subscribe(runId));
    }

    @PostMapping("/{runId}/approve")
    public ResponseEntity<Map<String, String>> approve(@PathVariable String runId,
                                                       @RequestBody ApproveRequest request) {
        RunHandle handle = registry.find(runId);
        if (handle == null) {
            return ResponseEntity.notFound().build();
        }
        ApprovalDecision decision = request.approved()
                ? ApprovalDecision.approve()
                : ApprovalDecision.rejectWith(request.reason());

        Gate pendingGate = handle.gate().pending();
        // False means nothing was parked: a stale click, a double submit, or a
        // gate that already timed out. Report it rather than pretending.
        if (!handle.gate().decide(decision)) {
            return ResponseEntity.status(409)
                    .body(Map.of("error", "no gate is currently awaiting a decision"));
        }
        if (pendingGate != null) {
            applicationEvents.publishEvent(new ApprovalRecorded(
                    runId, pendingGate.name(), decision.approved(), decision.reason()));
        }
        return ResponseEntity.ok(Map.of("status", "accepted"));
    }

    @GetMapping("/history")
    public List<RunSummary> history() {
        return history.findTop50ByOrderByStartedAtDesc().stream().map(RunSummary::from).toList();
    }
}
