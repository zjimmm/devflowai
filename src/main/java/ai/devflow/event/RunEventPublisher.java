package ai.devflow.event;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns one {@link SseEmitter} per run and pushes events to it.
 *
 * <p>Publishing is best-effort by design. A run is a real process doing real
 * work; nobody watching, or a browser that closed its tab, must never be able
 * to break it. Every send failure drops that subscription and returns quietly.
 *
 * <p>A bounded per-run history is also kept and replayed to a newly-subscribing
 * emitter. Without this, a run that fails (or finishes) before the browser's
 * {@code EventSource} has connected — a real, deterministic race on the
 * fast-failure path, not just a slow one — would have its events silently
 * dropped by {@link #publish}, leaving the operator with no indication
 * anything happened.
 */
@Component
public class RunEventPublisher {

    /** Long, but not infinite: a browser left open overnight eventually releases the connection. */
    private static final long EMITTER_TIMEOUT_MS = Duration.ofHours(2).toMillis();

    /** Generous headroom for a real run's lifecycle: a handful of step events, three gates, one terminal event. */
    private static final int MAX_HISTORY_PER_RUN = 200;

    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();
    private final Map<String, List<RunEvent>> history = new ConcurrentHashMap<>();

    public SseEmitter subscribe(String runId) {
        return subscribe(runId, new SseEmitter(EMITTER_TIMEOUT_MS));
    }

    /** Visible for tests, to inject a capturing emitter and assert on the replay below. */
    SseEmitter subscribe(String runId, SseEmitter emitter) {
        register(runId, emitter);
        // Replay whatever already happened before this subscriber connected --
        // closes the gap where a fast failure (or a merely slow subscriber)
        // would otherwise be invisible: the run could finish or fail before
        // anyone was listening.
        for (RunEvent past : snapshotHistory(runId)) {
            sendQuietly(runId, emitter, past);
        }
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
        recordHistory(runId, event);
        SseEmitter emitter = emitters.get(runId);
        if (emitter == null) return;
        sendQuietly(runId, emitter, event);
    }

    public void complete(String runId) {
        history.remove(runId);
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

    /** Sends one event to one emitter, dropping the subscription on failure rather than breaking the run. */
    private void sendQuietly(String runId, SseEmitter emitter, RunEvent event) {
        try {
            emitter.send(SseEmitter.event().name(event.type()).data(event));
        } catch (IOException | IllegalStateException e) {
            // Client is gone or the response is already committed. Drop it and
            // let the run continue.
            emitters.remove(runId, emitter);
        }
    }

    /** Appends to the run's bounded history, trimming the oldest entry once over the cap. */
    private void recordHistory(String runId, RunEvent event) {
        List<RunEvent> events = history.computeIfAbsent(runId, k -> Collections.synchronizedList(new ArrayList<>()));
        synchronized (events) {
            events.add(event);
            while (events.size() > MAX_HISTORY_PER_RUN) {
                events.remove(0);
            }
        }
    }

    private List<RunEvent> snapshotHistory(String runId) {
        List<RunEvent> events = history.get(runId);
        if (events == null) return List.of();
        synchronized (events) {
            return new ArrayList<>(events);
        }
    }
}
