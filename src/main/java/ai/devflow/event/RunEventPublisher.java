package ai.devflow.event;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns one {@link SseEmitter} per run and pushes events to it.
 *
 * <p>Publishing is best-effort by design. A run is a real process doing real
 * work; nobody watching, or a browser that closed its tab, must never be able
 * to break it. Every send failure drops that subscription and returns quietly.
 */
@Component
public class RunEventPublisher {

    /** Long, but not infinite: a browser left open overnight eventually releases the connection. */
    private static final long EMITTER_TIMEOUT_MS = Duration.ofHours(2).toMillis();

    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();

    public SseEmitter subscribe(String runId) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        register(runId, emitter);
        return emitter;
    }

    /** Visible for tests, which supply their own (possibly failing) emitter. */
    public void register(String runId, SseEmitter emitter) {
        emitter.onCompletion(() -> emitters.remove(runId, emitter));
        emitter.onTimeout(() -> emitters.remove(runId, emitter));
        emitter.onError(e -> emitters.remove(runId, emitter));
        emitters.put(runId, emitter);
    }

    public boolean isSubscribed(String runId) {
        return emitters.containsKey(runId);
    }

    public void publish(String runId, RunEvent event) {
        SseEmitter emitter = emitters.get(runId);
        if (emitter == null) return;
        try {
            emitter.send(SseEmitter.event().name(event.type()).data(event));
        } catch (IOException | IllegalStateException e) {
            // Client is gone or the response is already committed. Drop it and
            // let the run continue.
            emitters.remove(runId, emitter);
        }
    }

    public void complete(String runId) {
        SseEmitter emitter = emitters.remove(runId);
        if (emitter == null) return;
        try {
            emitter.complete();
        } catch (RuntimeException ignored) {
            // Already closed by the container; nothing to do.
        }
    }

    public void fail(String runId, String message) {
        publish(runId, RunEvent.of("error", message));
        complete(runId);
    }
}
