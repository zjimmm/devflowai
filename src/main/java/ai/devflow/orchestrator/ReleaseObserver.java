package ai.devflow.orchestrator;

import ai.devflow.tools.GitHubClientException;

import java.util.function.Consumer;

public interface ReleaseObserver {
    ReleaseObservation observe(String repoUrl, long workflowRunId, Consumer<ReleaseObservation> onUpdate)
            throws GitHubClientException;
}
