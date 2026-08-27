package ai.devflow.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

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

    @Test
    void rejectsNestedSymlinkEscape(@TempDir Path root) throws IOException {
        Path outside = Files.createTempDirectory("outside-nested");
        Files.createDirectories(root.resolve("a/b"));
        Files.createSymbolicLink(root.resolve("a/b/escape"), outside);
        var guard = new PathGuard(root);
        assertThatThrownBy(() -> guard.resolve("a/b/escape/secret.txt"))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void danglingSymlinkWithinRootFailsClosed(@TempDir Path root) throws IOException {
        // "dangling" -> "does-not-exist" is not an escape -- its target, if it
        // ever existed, would sit inside root. But PathGuard cannot prove
        // that: realPathOfNearestExistingAncestor() finds "dangling" itself as
        // the nearest existing ancestor (Files.exists(p, NOFOLLOW_LINKS) is
        // true for the link entry regardless of its target), then calls
        // toRealPath() on it, which *does* follow the link and throws
        // NoSuchFileException because the target is missing -- caught and
        // rethrown as SecurityException. That is the intended fail-closed
        // posture for a security-critical guard, not a bug: a "smarter"
        // permissive resolution would have to walk the raw, unresolved
        // symlink target text without verifying it, which risks approving a
        // target that only appears to sit inside root textually but escapes
        // once something is actually created there later (a race a
        // permissive implementation could not close). Rejecting whenever a
        // path cannot be verified is the conservative, correct choice here,
        // so this test asserts the current (and correct) throwing behavior
        // rather than the more permissive behavior one might instinctively
        // expect.
        Path target = root.resolve("does-not-exist");
        Files.createSymbolicLink(root.resolve("dangling"), target);
        var guard = new PathGuard(root);
        assertThatThrownBy(() -> guard.resolve("dangling"))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void rejectsSymlinkLoopWithoutHanging(@TempDir Path root) throws IOException {
        Path linkA = root.resolve("linkA");
        Path linkB = root.resolve("linkB");
        Files.createSymbolicLink(linkA, linkB);
        Files.createSymbolicLink(linkB, linkA);
        var guard = new PathGuard(root);
        assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                assertThatThrownBy(() -> guard.resolve("linkA/file.txt"))
                        .isInstanceOf(SecurityException.class));
    }
}
