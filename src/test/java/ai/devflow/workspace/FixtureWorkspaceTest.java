package ai.devflow.workspace;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

class FixtureWorkspaceTest {

    Workspace workspace;

    @AfterEach
    void tearDown() throws Exception {
        if (workspace != null) workspace.cleanup();
    }

    @Test
    void prepareCopiesFixtureAndInitialisesGitBranch() throws Exception {
        workspace = new FixtureWorkspace(Path.of("src/test/resources/fixture"), "run-abc123");
        workspace.prepare();

        assertThat(workspace.root()).isDirectory();
        assertThat(workspace.root().resolve("src/main/java/com/example/UserController.java")).exists();
        assertThat(workspace.root().resolve(".git")).exists();
        assertThat(workspace.branchName()).isEqualTo("devflowai/run-abc123");
    }

    @Test
    void cleanupRemovesTheTempDirectory() throws Exception {
        var ws = new FixtureWorkspace(Path.of("src/test/resources/fixture"), "run-xyz");
        ws.prepare();
        Path root = ws.root();
        ws.cleanup();
        assertThat(root).doesNotExist();
    }

    @Test
    void doesNotMutateTheSourceFixture() throws Exception {
        Path source = Path.of("src/test/resources/fixture");
        long before = Files.walk(source).count();
        workspace = new FixtureWorkspace(source, "run-1");
        workspace.prepare();
        Files.writeString(workspace.root().resolve("scratch.txt"), "written by a test");
        assertThat(Files.walk(source).count()).isEqualTo(before);
    }

    @Test
    void excludesGitGradleAndBuildDirectoriesFromTheCopy(@TempDir Path source) throws Exception {
        // Simulates a fixture checkout that has been standalone-built (as
        // Task 4's own brief instructs) and so has real .git/.gradle/build
        // artifact directories sitting on disk alongside the real project
        // files. None of these should end up in the copied workspace.
        Files.writeString(source.resolve("keep.txt"), "real fixture file");

        Path gitDir = source.resolve(".git");
        Files.createDirectories(gitDir);
        Files.writeString(gitDir.resolve("HEAD"), "ref: refs/heads/main");

        Path gradleCache = source.resolve(".gradle/cache");
        Files.createDirectories(gradleCache);
        Files.writeString(gradleCache.resolve("some.bin"), "gradle cache junk");

        Path buildOutput = source.resolve("build/classes");
        Files.createDirectories(buildOutput);
        Files.writeString(buildOutput.resolve("Foo.class"), "compiled junk");

        workspace = new FixtureWorkspace(source, "run-skip");
        workspace.prepare();

        assertThat(workspace.root().resolve("keep.txt")).exists();
        assertThat(workspace.root().resolve(".gradle")).doesNotExist();
        assertThat(workspace.root().resolve("build")).doesNotExist();
        // workspace.root()/.git is expected to exist -- it's created by
        // FixtureWorkspace's own Git.init(), not copied from the source.
        // The assertion that matters is that the *source's* .git contents
        // (e.g. HEAD pointing at "main") never leaked in as copied files.
        assertThat(workspace.root().resolve(".git/HEAD")).content()
                .doesNotContain("refs/heads/main");
    }
}
