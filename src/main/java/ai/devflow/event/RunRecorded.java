package ai.devflow.event;

/** Fired by {@link RunEventPublisher#publish} alongside its SSE push, for SdlcRunRecorder to persist. */
public record RunRecorded(String runId, RunEvent event) {}
