package ai.devflow.workspace;

import org.eclipse.jgit.api.Git;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;

/**
 * Populates a workspace by shallow-cloning a real, public git repository
 * (spec §4, Phase 6) -- "public repos, shallow clone, run, delete."
 *
 * <p>The constructor validates the URL before any network call, not after:
 * spec §4.1 records the live-verified reason a bare https:// prefix
 * allowlist is load-bearing here rather than defensive overkill -- JGit's
 * {@code ext::} transport executes an arbitrary subprocess, and both
 * {@code file://} and a schemeless bare path silently clone from the
 * server's own filesystem.
 */
public class ClonedWorkspace extends AbstractGitWorkspace {

    private static final String ALLOWED_SCHEME = "https://";

    private final String repoUrl;
    private final Duration cloneTimeout;

    public ClonedWorkspace(String repoUrl, String runId, Duration cloneTimeout) {
        super(runId);
        if (repoUrl == null || !repoUrl.startsWith(ALLOWED_SCHEME)) {
            throw new IllegalArgumentException("Repo must be an https:// URL, got: " + repoUrl);
        }
        this.repoUrl = repoUrl;
        this.cloneTimeout = cloneTimeout;
    }

    @Override
    public String repoUrl() {
        return repoUrl;
    }

    @Override
    public void prepare() throws IOException {
        root = Files.createTempDirectory("devflowai-" + runId + "-");
        try (Git git = Git.cloneRepository()
                .setURI(repoUrl)
                .setDirectory(root.toFile())
                .setDepth(1)
                .setTimeout((int) cloneTimeout.toSeconds())
                .call()) {
            configureIdentity(git);
            git.checkout().setCreateBranch(true).setName(branchName()).call();
        } catch (Exception e) {
            throw new IOException("Failed to clone " + repoUrl, e);
        }
        guard = new PathGuard(root);
    }
}
