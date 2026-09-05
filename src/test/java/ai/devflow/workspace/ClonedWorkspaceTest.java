package ai.devflow.workspace;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClonedWorkspaceTest {

    @Test
    void rejectsTheExtProtocol() {
        assertThatThrownBy(() -> new ClonedWorkspace("ext::sh -c \"true\"", "t", Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("https://");
    }

    @Test
    void rejectsTheFileProtocol() {
        assertThatThrownBy(() -> new ClonedWorkspace("file:///etc", "t", Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsABareLocalPath() {
        assertThatThrownBy(() -> new ClonedWorkspace("/etc/passwd", "t", Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsPlainHttp() {
        assertThatThrownBy(() -> new ClonedWorkspace("http://example.com/repo.git", "t", Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsSsh() {
        assertThatThrownBy(() -> new ClonedWorkspace("ssh://git@example.com/repo.git", "t", Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> new ClonedWorkspace(null, "t", Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsAnHttpsUrlAtConstructionTimeWithoutTouchingTheNetwork() {
        // Construction alone must never make a network call -- only prepare() does.
        var workspace = new ClonedWorkspace("https://github.com/zjimmm/devflowai.git", "t", Duration.ofSeconds(5));
        assertThat(workspace.branchName()).isEqualTo("devflowai/t");
    }
}
