package ai.devflow.event;

import java.util.Map;

/**
 * One line in the run's live log.
 *
 * <p>Carries a summary and structured data only — never file contents or a
 * diff. That is the same context-isolation rule the orchestrator follows
 * (spec §3.2): the browser gets paths and summaries, and fetches nothing else.
 */
public record RunEvent(String type, String message, Map<String, Object> data) {

    public static RunEvent of(String type, String message) {
        return new RunEvent(type, message, Map.of());
    }

    public static RunEvent of(String type, String message, Map<String, Object> data) {
        return new RunEvent(type, message, Map.copyOf(data));
    }
}
