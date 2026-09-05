package ai.devflow.web;

import ai.devflow.event.RunEventPublisher;
import ai.devflow.orchestrator.ApprovalDecision;
import ai.devflow.orchestrator.RunHandle;
import ai.devflow.orchestrator.RunRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/**
 * The entire HTTP surface: start a run, watch it, answer a gate.
 *
 * <p>Three endpoints and nothing else — the page reaches no other route
 * (spec §5.3).
 */
@RestController
@RequestMapping("/api/runs")
public class RunController {

    private final RunRegistry registry;
    private final RunEventPublisher events;

    public RunController(RunRegistry registry, RunEventPublisher events) {
        this.registry = registry;
        this.events = events;
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> start(@RequestBody StartRunRequest request) {
        if (request.task() == null || request.task().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "task must not be blank"));
        }
        String repo = (request.repo() == null || request.repo().isBlank()) ? "fixture" : request.repo();
        RunHandle handle;
        try {
            handle = registry.start(request.task().trim(), repo);
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

        // False means nothing was parked: a stale click, a double submit, or a
        // gate that already timed out. Report it rather than pretending.
        if (!handle.gate().decide(decision)) {
            return ResponseEntity.status(409)
                    .body(Map.of("error", "no gate is currently awaiting a decision"));
        }
        return ResponseEntity.ok(Map.of("status", "accepted"));
    }
}
