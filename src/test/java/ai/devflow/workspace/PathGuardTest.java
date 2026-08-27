package ai.devflow.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;

import static org.assertj.core.api.Assertions.*;

class PathGuardTest {

    @Test
    void resolvesPathInsideRoot(@TempDir Path root) throws IOException {
        var guard = new PathGuard(root);
        // startsWith(Path) (not startsWithRaw) calls Path.toRealPath() on both
        // sides internally (verified via javap on assertj-core 3.27.7's
        // Paths.assertStartsWith), which throws NoSuchFileException for a
        // path that doesn't exist yet -- "src/Main.java" is never created
        // here, deliberately, since resolve() must also work for
        // not-yet-created files (see PathGuard's ancestor-walk fallback).
        // startsWithRaw does a pure lexical/component comparison with no
        // filesystem access, so it works for a hypothetical path; comparing
        // against root.toRealPath() (root itself does exist) keeps the
        // comparison consistent with the canonical root PathGuard resolves
        // against internally.
        assertThat(guard.resolve("src/Main.java")).startsWithRaw(root.toRealPath());
    }

    @Test
    void rejectsParentTraversal(@TempDir Path root) {
        var guard = new PathGuard(root);
        assertThatThrownBy(() -> guard.resolve("../../../etc/passwd"))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void rejectsAbsolutePathOutsideRoot(@TempDir Path root) {
        var guard = new PathGuard(root);
        assertThatThrownBy(() -> guard.resolve("/etc/passwd"))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void rejectsSymlinkEscape(@TempDir Path root) throws IOException {
        Path outside = Files.createTempDirectory("outside");
        Files.createSymbolicLink(root.resolve("escape"), outside);
        var guard = new PathGuard(root);
        assertThatThrownBy(() -> guard.resolve("escape/secret.txt"))
                .isInstanceOf(SecurityException.class);
    }
}
