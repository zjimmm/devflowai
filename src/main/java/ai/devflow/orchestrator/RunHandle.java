package ai.devflow.orchestrator;

import java.util.concurrent.Future;

/** Everything the web layer needs to observe or steer one in-flight run. */
public record RunHandle(String runId, RunState state, ApprovalGate gate, Future<?> task) {}
