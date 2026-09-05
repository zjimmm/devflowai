package ai.devflow.workspace;

import org.eclipse.jgit.api.Git;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.Set;
import java.util.stream.Stream;

public class FixtureWorkspace extends AbstractGitWorkspace {

    private static final Set<String> SKIP_DIR_NAMES = Set.of(".git", ".gradle", "build");

    private final Path source;

    public FixtureWorkspace(Path source, String runId) {
        super(runId);
        this.source = source;
    }

    @Override
    public void prepare() throws IOException {
        root = Files.createTempDirectory("devflowai-" + runId + "-");
        copyRecursively(source, root);
        try (Git git = Git.init().setDirectory(root.toFile()).call()) {
            configureIdentity(git);
            git.add().addFilepattern(".").call();
            git.commit().setMessage("baseline").setSign(false).call();
            git.checkout().setCreateBranch(true).setName(branchName()).call();
        } catch (Exception e) {
            throw new IOException("Failed to initialise git in workspace", e);
        }
        guard = new PathGuard(root);
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
