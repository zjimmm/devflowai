package ai.devflow.tools;

import ai.devflow.workspace.*;
import org.junit.jupiter.api.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;

class BuildToolsTest {

    Workspace workspace;
    BuildTools build;

    @BeforeEach
    void setUp() throws Exception {
        workspace = new FixtureWorkspace(Path.of("src/test/resources/fixture"), "build-test");
        workspace.prepare();
        build = new BuildTools(workspace, Duration.ofMinutes(5));
    }

    @AfterEach
    void tearDown() throws Exception { workspace.cleanup(); }

    @Test
    void reportsMissingWrapperClearly() throws Exception {
        // The fixture now ships a real gradlew (Sub-project 2's build-pass
        // policy needs a fixture whose build can genuinely pass), so this
        // test deletes it from its own copied workspace to still exercise
        // the no-wrapper path rather than relying on the fixture itself
        // lacking one.
        Files.delete(workspace.root().resolve("gradlew"));

        var result = build.build("test");

        assertThat(result.success()).isFalse();
        assertThat(result.output()).contains("No build wrapper found");
        assertThat(result.wrapperFound()).isFalse();
    }

    @Test
    void truncatesVeryLongOutput() {
        String huge = "x".repeat(50_000);
        assertThat(BuildTools.truncate(huge, 4_000)).hasSizeLessThan(4_200);
        assertThat(BuildTools.truncate(huge, 4_000)).contains("truncated");
    }

    // --- Additional coverage beyond the brief -------------------------------
    //
    // The two tests above never actually spawn a build process: the missing-
    // wrapper test short-circuits before any Process is created, and the
    // truncate test is pure string logic. Neither exercises the part of this
    // class that matters most for safety -- a real child process, its output
    // capture, and the hard timeout / destroyForcibly path. The tests below
    // fill that gap using a fake "gradlew" script dropped into the workspace,
    // so we can drive real process behaviour deterministically and quickly
    // instead of waiting out a real 5-minute hang.

    @Test
    void capturesOutputAndSucceedsWhenTheWrapperExitsZero() throws Exception {
        writeFakeGradlew("#!/bin/sh\necho hello-from-fake-gradlew\nexit 0\n");

        var result = build.build("test");

        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("hello-from-fake-gradlew");
        assertThat(result.wrapperFound()).isTrue();
    }

    @Test
    void reportsFailureAndCapturesOutputWhenTheWrapperExitsNonZero() throws Exception {
        writeFakeGradlew("#!/bin/sh\necho boom\nexit 1\n");

        var result = build.build("test");

        assertThat(result.success()).isFalse();
        assertThat(result.output()).contains("boom");
    }

    @Test
    void runTestsPrefixesResultWithPassOrFail() throws Exception {
        writeFakeGradlew("#!/bin/sh\necho ok\nexit 0\n");

        assertThat(build.runTests()).startsWith("BUILD PASSED");
    }

    @Test
    void boundsOutputRegardlessOfHowMuchTheProcessActuallyProduces() throws Exception {
        // A build that produces output far larger than MAX_OUTPUT_CHARS must
        // never require BuildTools to hold all of it in memory at once --
        // only the bounded tail that truncate() would keep in the end
        // anyway. A prior implementation buffered the entire stream into an
        // unbounded ByteArrayOutputStream before truncate() ever ran, so a
        // pathological build (arbitrary target-repo code, by design) could
        // exhaust heap well before the 5-minute production timeout fires --
        // defeating the whole purpose truncation exists for. `yes` piped
        // through `head -c` produces 5,000,000 bytes near-instantly, well
        // beyond MAX_OUTPUT_CHARS (4,000), so this both runs fast and proves
        // the result length stays bounded rather than growing with the
        // amount of output actually produced.
        writeFakeGradlew("#!/bin/sh\nyes \"0123456789abcdef\" | head -c 5000000\nexit 0\n");

        var result = build.build("test");

        assertThat(result.success()).isTrue();
        assertThat(result.output()).hasSizeLessThan(4_200);
        assertThat(result.output()).contains("truncated");
    }

    @Test
    void timeoutActuallyBoundsWallClockTimeAndKillsTheHungProcess() throws Exception {
        // A process that produces continuous output and never exits on its own
        // for 60 seconds. A naive implementation that reads the process's
        // stdout to EOF *before* calling Process.waitFor(timeout, unit) would
        // block on that read until the child closes its stream -- i.e. until
        // it exits naturally -- so the configured timeout would never actually
        // get a chance to fire. This test proves it does: build() must return
        // close to the configured 2-second timeout, not after the script's
        // full 60-second run, and the child must actually be killed rather
        // than left running in the background.
        Path marker = workspace.root().resolve("still-running.marker");
        writeFakeGradlew("""
                #!/bin/sh
                for i in $(seq 1 60); do
                  echo "tick $i"
                  touch "%s"
                  sleep 1
                done
                """.formatted(marker));

        BuildTools shortTimeoutBuild = new BuildTools(workspace, Duration.ofSeconds(2));
        long start = System.nanoTime();
        var result = shortTimeoutBuild.build("test");
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(result.success()).isFalse();
        assertThat(result.output()).containsIgnoringCase("timed out");
        assertThat(elapsed).isLessThan(Duration.ofSeconds(15));

        // Confirm the child was actually killed (destroyForcibly), not merely
        // abandoned: if it were still running it would keep touching the
        // marker file every second.
        Files.deleteIfExists(marker);
        Thread.sleep(1_500);
        assertThat(Files.exists(marker))
                .as("the hung child process should have been destroyed, not left running")
                .isFalse();
    }

    private void writeFakeGradlew(String contents) throws Exception {
        Path gradlew = workspace.root().resolve("gradlew");
        Files.writeString(gradlew, contents);
        assertThat(gradlew.toFile().setExecutable(true)).isTrue();
    }
}
