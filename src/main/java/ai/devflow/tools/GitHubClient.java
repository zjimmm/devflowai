package ai.devflow.tools;

import ai.devflow.workspace.Workspace;

import java.util.List;

/**
 * Provider-neutral seam for the one place this codebase talks to a real
 * forge (mirrors how {@code CodingWorker} isolates the LLM provider). The
 * one production implementation is GitHub-only by design — a non-GitHub
 * repo URL is rejected before ever reaching this interface (see
 * {@code RunController}).
 */
public interface GitHubClient {

    /** Pushes {@code branchName} to the workspace's {@code origin} remote. */
    void push(Workspace workspace, String branchName) throws GitHubClientException;

    /** Opens a pull request from {@code branchName} against the repo's default branch. */
    PullRequestResult openPullRequest(String repoUrl, String branchName, String title, String body)
            throws GitHubClientException;

    /** Lists the latest GitHub check runs associated with the branch or commit {@code ref}. */
    default List<CiCheck> listCiChecks(String repoUrl, String ref) throws GitHubClientException {
        throw new GitHubClientException("CI check observation is not configured");
    }

    default boolean isPullRequestMerged(String repoUrl, int pullRequestNumber) throws GitHubClientException {
        throw new GitHubClientException("Pull request merge observation is not configured");
    }

    default WorkflowDispatchResult dispatchWorkflow(String repoUrl, String workflow, String ref)
            throws GitHubClientException {
        throw new GitHubClientException("Workflow dispatch is not configured");
    }

    default WorkflowRun getWorkflowRun(String repoUrl, long workflowRunId) throws GitHubClientException {
        throw new GitHubClientException("Workflow run observation is not configured");
    }
}
