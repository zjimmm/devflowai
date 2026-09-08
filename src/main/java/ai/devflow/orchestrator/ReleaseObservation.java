package ai.devflow.orchestrator;

import ai.devflow.tools.WorkflowRun;

public record ReleaseObservation(VerificationStatus status, WorkflowRun workflowRun) {}
