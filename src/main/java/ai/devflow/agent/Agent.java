package ai.devflow.agent;

import ai.devflow.orchestrator.RunState;

public interface Agent {
    String name();
    AgentResult run(RunState state);
}
