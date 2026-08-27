package ai.devflow.workspace;

import org.junit.jupiter.api.*;
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
}
