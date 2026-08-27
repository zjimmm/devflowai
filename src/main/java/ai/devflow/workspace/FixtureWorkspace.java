package ai.devflow.workspace;

import org.eclipse.jgit.api.Git;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.Comparator;
import java.util.Set;
import java.util.stream.Stream;

public class FixtureWorkspace implements Workspace {

    private static final Set<String> SKIP_DIR_NAMES = Set.of(".git", ".gradle", "build");

    private final Path source;
    private final String runId;
    private Path root;
    private PathGuard guard;

    public FixtureWorkspace(Path source, String runId) {
        this.source = source;
        this.runId = runId;
    }

    @Override public Path root() { return root; }
    @Override public String branchName() { return "devflowai/" + runId; }
    @Override public PathGuard guard() { return guard; }

    @Override
    public void prepare() throws IOException {
        root = Files.createTempDirectory("devflowai-" + runId + "-");
        copyRecursively(source, root);
        try (Git git = Git.init().setDirectory(root.toFile()).call()) {
            // Give this workspace's repo its own self-contained identity rather
            // than relying on ambient host git config. Without this, JGit's
            // CommitCommand falls back to reading user.name/user.email from the
            // repo-local then global git config, and -- if neither is set --
            // to an implicit identity derived from the OS username and resolved
            // hostname. That implicit fallback is environment-dependent (varies
            // by machine/CI runner, and can behave differently if hostname
            // resolution is unavailable in a sandboxed environment), which is
            // undesirable for a workspace whose commits should be reproducible.
            // Setting it explicitly here also covers later commits made against
            // this same workspace (e.g. Task 8's GitTools.commit()), since the
            // fix lives in the repo's own config, not a one-off PersonIdent
            // passed to a single commit call.
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "devflowai");
            config.setString("user", null, "email", "devflowai@localhost");
            config.save();

            git.add().addFilepattern(".").call();
            git.commit().setMessage("baseline").setSign(false).call();
            git.checkout().setCreateBranch(true).setName(branchName()).call();
        } catch (Exception e) {
            throw new IOException("Failed to initialise git in workspace", e);
        }
        guard = new PathGuard(root);
    }

    @Override
    public void cleanup() throws IOException {
        if (root == null || !Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) { }
            });
        }
    }

    private void copyRecursively(Path from, Path to) throws IOException {
        // Skip build-artifact directories (.git, .gradle, build) wherever they
        // occur under the source tree. The fixture is a standalone Gradle
        // project (Task 4's brief itself instructs verifying it builds
        // standalone), so a `.gradle` cache and `build` output directory can
        // legitimately exist on disk next to it even though they're gitignored
        // and never committed -- copyRecursively walks the real filesystem, not
        // git's tracked state, so without this filter those directories (and
        // anything git-related) get copied into every temp workspace. Matched
        // by exact path-component name, not substring, so a real source file
        // like MyBuildHelper.java is never excluded.
        try (Stream<Path> walk = Files.walk(from)) {
            walk.filter(src -> !isUnderSkippedDir(from, src))
                .forEach(src -> {
                    try {
                        Path dest = to.resolve(from.relativize(src).toString());
                        if (Files.isDirectory(src)) Files.createDirectories(dest);
                        else Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
        }
    }

    private boolean isUnderSkippedDir(Path root, Path candidate) {
        for (Path component : root.relativize(candidate)) {
            if (SKIP_DIR_NAMES.contains(component.toString())) return true;
        }
        return false;
    }
}
