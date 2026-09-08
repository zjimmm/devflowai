package ai.devflow.tools;

import ai.devflow.workspace.FixtureWorkspace;
import ai.devflow.workspace.Workspace;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.URIish;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitHubApiClientTest {

    Workspace workspace;
    Path bareRepo;

    @BeforeEach
    void setUp() throws Exception {
        workspace = new FixtureWorkspace(Path.of("src/test/resources/fixture"), "gh-push-test");
        workspace.prepare();

        bareRepo = Files.createTempDirectory("bare-remote-");
        Git.init().setDirectory(bareRepo.toFile()).setBare(true).call();

        try (Git git = Git.open(workspace.root().toFile())) {
            git.remoteAdd().setName("origin")
                    .setUri(new URIish(bareRepo.toUri().toString()))
                    .call();
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        workspace.cleanup();
        try (var walk = Files.walk(bareRepo)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    private GitHubApiClient client(String token) {
        return new GitHubApiClient(token, RestClient.builder().build());
    }

    @Test
    void pushSendsTheBranchToTheConfiguredRemote() throws Exception {
        client("unused-for-a-local-remote").push(workspace, workspace.branchName());

        try (Git bare = Git.open(bareRepo.toFile())) {
            assertThat(bare.getRepository().findRef(workspace.branchName())).isNotNull();
        }
    }

    @Test
    void pushWrapsATransportFailureAsAGitHubClientException() throws Exception {
        try (Git git = Git.open(workspace.root().toFile())) {
            git.remoteRemove().setRemoteName("origin").call();
            git.remoteAdd().setName("origin")
                    .setUri(new URIish("file:///no/such/path/at/all"))
                    .call();
        }

        assertThatThrownBy(() -> client("unused-for-a-local-remote").push(workspace, workspace.branchName()))
                .isInstanceOf(GitHubClientException.class);
    }

    @Test
    void pushFailsFastWhenTheTokenIsMissing() {
        assertThatThrownBy(() -> client(null).push(workspace, workspace.branchName()))
                .isInstanceOf(GitHubClientException.class)
                .hasMessageContaining("DEVFLOWAI_GITHUB_TOKEN");
    }

    @Test
    void pushFailsFastWhenTheTokenIsBlank() {
        assertThatThrownBy(() -> client("  ").push(workspace, workspace.branchName()))
                .isInstanceOf(GitHubClientException.class)
                .hasMessageContaining("DEVFLOWAI_GITHUB_TOKEN");
    }

    @Test
    void openPullRequestFailsFastWhenTheTokenIsMissing() {
        assertThatThrownBy(() -> client(null).openPullRequest(
                "https://github.com/o/r", "b", "title", "body"))
                .isInstanceOf(GitHubClientException.class)
                .hasMessageContaining("DEVFLOWAI_GITHUB_TOKEN");
    }

    @Test
    void listCiChecksFailsFastWhenTheTokenIsMissing() {
        assertThatThrownBy(() -> client(null).listCiChecks("https://github.com/o/r", "branch"))
                .isInstanceOf(GitHubClientException.class)
                .hasMessageContaining("DEVFLOWAI_GITHUB_TOKEN");
    }

    @Test
    void parseCheckRunsMapsTheSafeFields() {
        var checks = GitHubApiClient.parseCheckRuns(Map.of("check_runs", List.of(Map.of(
                "name", "Build", "status", "completed", "conclusion", "success",
                "details_url", "https://github.com/o/r/actions/runs/1"))));

        assertThat(checks).containsExactly(new CiCheck("Build", "completed", "success",
                "https://github.com/o/r/actions/runs/1"));
    }

    @Test
    void parseCheckRunsRejectsMalformedResponses() {
        assertThatThrownBy(() -> GitHubApiClient.parseCheckRuns(Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("check_runs");
    }

    @Test
    void parseOwnerRepoHandlesTrailingGitAndSlash() {
        assertThat(GitHubApiClient.parseOwnerRepo("https://github.com/o/r").owner()).isEqualTo("o");
        assertThat(GitHubApiClient.parseOwnerRepo("https://github.com/o/r").repo()).isEqualTo("r");
        assertThat(GitHubApiClient.parseOwnerRepo("https://github.com/o/r.git").repo()).isEqualTo("r");
        assertThat(GitHubApiClient.parseOwnerRepo("https://github.com/o/r/").repo()).isEqualTo("r");
    }

    @Test
    void parseOwnerRepoRejectsAUrlWithoutBothSegments() {
        assertThatThrownBy(() -> GitHubApiClient.parseOwnerRepo("https://github.com/onlyowner"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
