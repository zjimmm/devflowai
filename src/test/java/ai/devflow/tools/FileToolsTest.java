package ai.devflow.tools;

import ai.devflow.workspace.PathGuard;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

class FileToolsTest {

    FileTools tools;
    Path root;

    @BeforeEach
    void setUp(@TempDir Path dir) throws Exception {
        root = dir;
        Files.writeString(dir.resolve("Hello.java"), "class Hello {}");
        Files.createDirectories(dir.resolve("src"));
        tools = new FileTools(new PathGuard(dir));
    }

    @Test
    void readsAFile() {
        assertThat(tools.readFile("Hello.java")).isEqualTo("class Hello {}");
    }

    @Test
    void writesAFileCreatingParents() {
        tools.writeFile("src/main/java/New.java", "class New {}");
        assertThat(root.resolve("src/main/java/New.java")).exists();
    }

    @Test
    void listsFiles() {
        assertThat(tools.listFiles(".")).contains("Hello.java");
    }

    @Test
    void searchFindsMatchingFiles() {
        assertThat(tools.searchFiles("class Hello")).contains("Hello.java");
    }

    @Test
    void returnsMessageRatherThanThrowingOnMissingFile() {
        assertThat(tools.readFile("Nope.java")).containsIgnoringCase("not found");
    }

    @Test
    void refusesToEscapeTheWorkspace() {
        assertThatThrownBy(() -> tools.readFile("../../../etc/passwd"))
                .isInstanceOf(SecurityException.class);
    }
}
