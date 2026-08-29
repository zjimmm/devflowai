package ai.devflow.tools;

import ai.devflow.workspace.PathGuard;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Mirrors FileToolsTest's patterns for the read operations, plus the
 * write-capability assertion the I1 fix exists to guarantee: the reviewer's
 * tool object must have no writeFile method at all, not merely one that
 * declines to write.
 */
class ReadOnlyFileToolsTest {

    ReadOnlyFileTools tools;
    Path root;

    @BeforeEach
    void setUp(@TempDir Path dir) throws Exception {
        root = dir;
        Files.writeString(dir.resolve("Hello.java"), "class Hello {}");
        Files.createDirectories(dir.resolve("src"));
        tools = new ReadOnlyFileTools(new PathGuard(dir));
    }

    @Test
    void hasNoWriteFileMethod() {
        assertThat(ReadOnlyFileTools.class.getMethods())
                .extracting(Method::getName)
                .doesNotContain("writeFile");
    }

    @Test
    void readsAFile() {
        assertThat(tools.readFile("Hello.java")).isEqualTo("class Hello {}");
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
