package ai.devflow.workspace;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.StoredConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Shared plumbing between workspace implementations that populate a
 * directory with a real, on-disk git repository ({@link FixtureWorkspace},
 * {@link ClonedWorkspace}, Phase 6): the temp-dir lifecycle, the branch
 * name, and the self-contained commit identity every such repo needs (see
 * {@link #configureIdentity} -- without it, JGit falls back to ambient host
 * git config, which is environment-dependent and undesirable for a
 * workspace whose commits should be reproducible).
 */
abstract class AbstractGitWorkspace implements Workspace {

    protected final String runId;
    protected Path root;
    protected PathGuard guard;

    protected AbstractGitWorkspace(String runId) {
        this.runId = runId;
    }

    @Override public Path root() { return root; }
    @Override public String branchName() { return "devflowai/" + runId; }
    @Override public PathGuard guard() { return guard; }
    @Override public String repoUrl() { return null; }

    @Override
    public void cleanup() throws IOException {
        if (root == null || !Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) { }
            });
        }
    }

    protected void configureIdentity(Git git) throws IOException {
        StoredConfig config = git.getRepository().getConfig();
        config.setString("user", null, "name", "devflowai");
        config.setString("user", null, "email", "devflowai@localhost");
        config.save();
    }
}
