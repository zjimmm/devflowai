package ai.devflow.workspace;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("live")
class ClonedWorkspaceLiveTest {

    @Test
    void clonesARealPublicRepoAndCreatesTheRunBranch() throws Exception {
        var workspace = new ClonedWorkspace(
                "https://github.com/zjimmm/devflowai.git", "live-clone-test", Duration.ofMinutes(2));
        try {
            workspace.prepare();

            assertThat(Files.exists(workspace.root().resolve("build.gradle.kts"))).isTrue();
            try (Git git = Git.open(workspace.root().toFile())) {
                assertThat(git.getRepository().getBranch()).isEqualTo("devflowai/live-clone-test");
            }
        } finally {
            workspace.cleanup();
        }
    }
}
