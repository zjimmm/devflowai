package ai.devflow.orchestrator;

import ai.devflow.tools.GitHubClientException;
import ai.devflow.tools.WorkflowDispatchResult;

public interface StagingDispatcher {

    boolean isConfigured();

    String workflow();

    String ref();

    WorkflowDispatchResult dispatch(String repoUrl) throws GitHubClientException;

    static StagingDispatcher disabled() {
        return new StagingDispatcher() {
            @Override public boolean isConfigured() { return false; }
            @Override public String workflow() { return ""; }
            @Override public String ref() { return ""; }
            @Override public WorkflowDispatchResult dispatch(String repoUrl) throws GitHubClientException {
                throw new GitHubClientException("Staging dispatch is not configured");
            }
        };
    }
}
