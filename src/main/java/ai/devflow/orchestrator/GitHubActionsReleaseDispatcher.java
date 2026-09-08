package ai.devflow.orchestrator;

import ai.devflow.tools.GitHubClient;
import ai.devflow.tools.GitHubClientException;
import ai.devflow.tools.WorkflowDispatchResult;

public class GitHubActionsReleaseDispatcher implements ReleaseDispatcher {

    private final GitHubClient gitHubClient;
    private final String workflow;
    private final String ref;

    public GitHubActionsReleaseDispatcher(GitHubClient gitHubClient, String workflow, String ref) {
        this.gitHubClient = gitHubClient;
        this.workflow = workflow == null ? "" : workflow.trim();
        this.ref = ref == null ? "" : ref.trim();
    }

    @Override
    public boolean isConfigured() {
        return !workflow.isBlank() && !ref.isBlank();
    }

    @Override
    public String workflow() {
        return workflow;
    }

    @Override
    public String ref() {
        return ref;
    }

    @Override
    public WorkflowDispatchResult dispatch(String repoUrl) throws GitHubClientException {
        if (!isConfigured()) throw new GitHubClientException("Release dispatch is not configured");
        return gitHubClient.dispatchWorkflow(repoUrl, workflow, ref);
    }
}
