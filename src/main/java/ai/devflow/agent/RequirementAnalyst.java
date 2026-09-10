package ai.devflow.agent;

import ai.devflow.orchestrator.RunState;

public interface RequirementAnalyst {

    boolean isConfigured();

    RequirementAnalysisResult analyze(RunState state);

    static RequirementAnalyst disabled() {
        return new RequirementAnalyst() {
            @Override public boolean isConfigured() { return false; }
            @Override public RequirementAnalysisResult analyze(RunState state) {
                throw new IllegalStateException("Requirement analysis is not configured");
            }
        };
    }
}
