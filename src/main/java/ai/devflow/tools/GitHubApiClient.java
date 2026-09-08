package ai.devflow.tools;

import ai.devflow.workspace.Workspace;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * The one production {@link GitHubClient}: JGit for the push, a plain
 * {@link RestClient} call to GitHub's REST API for PR creation. No new
 * dependency -- both JGit and spring-web are already on this project's
 * classpath.
 */
public class GitHubApiClient implements GitHubClient {

    private static final String API_BASE = "https://api.github.com";

    private final String token;
    private final RestClient restClient;

    public GitHubApiClient(String token, RestClient restClient) {
        this.token = token;
        this.restClient = restClient;
    }

    @Override
    public void push(Workspace workspace, String branchName) throws GitHubClientException {
        requireToken();
        try (Git git = Git.open(workspace.root().toFile())) {
            Iterable<PushResult> results = git.push()
                    .setRemote("origin")
                    .setRefSpecs(new RefSpec(branchName + ":" + branchName))
                    .setCredentialsProvider(new UsernamePasswordCredentialsProvider("x-access-token", token))
                    .call();
            for (PushResult result : results) {
                for (RemoteRefUpdate update : result.getRemoteUpdates()) {
                    RemoteRefUpdate.Status status = update.getStatus();
                    if (status != RemoteRefUpdate.Status.OK && status != RemoteRefUpdate.Status.UP_TO_DATE) {
                        throw new GitHubClientException("Push rejected: " + status
                                + (update.getMessage() != null ? " (" + update.getMessage() + ")" : ""));
                    }
                }
            }
        } catch (GitHubClientException e) {
            throw e;
        } catch (Exception e) {
            throw new GitHubClientException("Push failed: " + e.getMessage(), e);
        }
    }

    @Override
    public PullRequestResult openPullRequest(String repoUrl, String branchName, String title, String body)
            throws GitHubClientException {
        requireToken();
        OwnerRepo or = parseOwnerRepo(repoUrl);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> repoInfo = restClient.get()
                    .uri(API_BASE + "/repos/{owner}/{repo}", or.owner(), or.repo())
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github+json")
                    .retrieve()
                    .body(Map.class);
            String defaultBranch = String.valueOf(repoInfo.get("default_branch"));

            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.post()
                    .uri(API_BASE + "/repos/{owner}/{repo}/pulls", or.owner(), or.repo())
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github+json")
                    .body(Map.of("title", title, "body", body, "head", branchName, "base", defaultBranch))
                    .retrieve()
                    .body(Map.class);

            return new PullRequestResult((String) response.get("html_url"), (Integer) response.get("number"));
        } catch (RuntimeException e) {
            throw new GitHubClientException("Opening the pull request failed: " + e.getMessage(), e);
        }
    }

    private void requireToken() throws GitHubClientException {
        if (token == null || token.isBlank()) {
            throw new GitHubClientException("DEVFLOWAI_GITHUB_TOKEN is not set");
        }
    }

    record OwnerRepo(String owner, String repo) {}

    static OwnerRepo parseOwnerRepo(String repoUrl) {
        String s = repoUrl.substring("https://".length());
        if (s.startsWith("github.com/")) s = s.substring("github.com/".length());
        if (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        if (s.endsWith(".git")) s = s.substring(0, s.length() - 4);
        String[] parts = s.split("/", 2);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new IllegalArgumentException("Not a valid GitHub repo URL: " + repoUrl);
        }
        return new OwnerRepo(parts[0], parts[1]);
    }
}
