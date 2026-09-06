package ai.devflow.tools;

import ai.devflow.workspace.Workspace;

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
}
