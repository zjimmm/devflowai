package ai.devflow.orchestrator;

import ai.devflow.tools.GitHubClientException;
import ai.devflow.tools.WorkflowDispatchResult;

public interface RollbackDispatcher {

    boolean isConfigured();

    String workflow();

    String ref();

    WorkflowDispatchResult dispatch(String repoUrl) throws GitHubClientException;

    static RollbackDispatcher disabled() {
        return new RollbackDispatcher() {
            @Override public boolean isConfigured() { return false; }
            @Override public String workflow() { return ""; }
            @Override public String ref() { return ""; }
            @Override public WorkflowDispatchResult dispatch(String repoUrl) throws GitHubClientException {
                throw new GitHubClientException("Rollback dispatch is not configured");
            }
        };
    }
}
