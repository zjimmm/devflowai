package ai.devflow.tools;

import ai.devflow.workspace.*;
import org.junit.jupiter.api.*;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

class GitToolsTest {

    Workspace workspace;
    GitTools git;

    @BeforeEach
    void setUp() throws Exception {
        workspace = new FixtureWorkspace(Path.of("src/test/resources/fixture"), "git-test");
        workspace.prepare();
        git = new GitTools(workspace);
    }

    @AfterEach
    void tearDown() throws Exception { workspace.cleanup(); }

    @Test
    void statusIsCleanOnAFreshWorkspace() {
        assertThat(git.status()).containsIgnoringCase("clean");
    }

    @Test
    void diffShowsAnEditedFile() throws Exception {
        Files.writeString(workspace.root().resolve("src/main/java/com/example/User.java"),
                "package com.example;\npublic class User { }\n");
        assertThat(git.diff()).contains("User.java");
    }

    @Test
    void changedFilesListsModifiedPaths() throws Exception {
        Files.writeString(workspace.root().resolve("newfile.txt"), "hello");
        assertThat(git.changedFiles()).contains("newfile.txt");
    }

    @Test
    void commitRecordsTheChange() throws Exception {
        Files.writeString(workspace.root().resolve("newfile.txt"), "hello");
        assertThat(git.commit("add newfile")).containsIgnoringCase("committed");
        assertThat(git.status()).containsIgnoringCase("clean");
    }

    // Empirically verifies JGit's add/status honor a repo's own .gitignore
    // for build.commit()'s unconditional `git add .` -- the fixture itself
    // has no .gitignore (it's not a real target repo), so this is the only
    // place that behavior gets exercised. See STATUS.md's corrected note:
    // a well-behaved real repo's own .gitignore already keeps build output
    // out of the changed-files list without any special-casing here.
    @Test
    void gitignoredBuildOutputNeverReachesStatusChangedFilesOrCommit() throws Exception {
        Files.writeString(workspace.root().resolve(".gitignore"), "build/\n");
        git.commit("add gitignore");

        Path buildDir = workspace.root().resolve("build");
        Files.createDirectories(buildDir);
        Files.writeString(buildDir.resolve("output.class"), "fake bytecode");

        assertThat(git.status()).containsIgnoringCase("clean");
        assertThat(git.changedFiles()).isEmpty();
        assertThat(git.commit("after build")).containsIgnoringCase("committed");
    }

    @Test
    void statusAndChangedFilesIncludeUnstagedDeletions() throws Exception {
        // Delete a tracked file without staging the deletion
        Files.delete(workspace.root().resolve("src/main/java/com/example/User.java"));
        // status() should report it as missing
        assertThat(git.status()).containsIgnoringCase("missing");
        // changedFiles() should include it
        assertThat(git.changedFiles()).contains("src/main/java/com/example/User.java");
    }
}
