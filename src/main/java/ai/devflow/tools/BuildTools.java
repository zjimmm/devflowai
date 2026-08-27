package ai.devflow.tools;

import ai.devflow.workspace.Workspace;
import org.springframework.ai.tool.annotation.Tool;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Runs the *target repository's* own build wrapper (gradlew/mvnw). This is
 * arbitrary code execution by design (spec §9) -- it's the whole point, since
 * it lets the agent run the target repo's real test suite -- so it is gated
 * behind Gate 2 at runtime. What this class is responsible for is making that
 * execution safe to have in an agent loop: a hard wall-clock timeout, and
 * output truncation so a runaway build can't blow the model's context.
 */
public class BuildTools {

    public record BuildResult(boolean success, String output) {}

    private static final int MAX_OUTPUT_CHARS = 4_000;

    private final Workspace workspace;
    private final Duration timeout;

    public BuildTools(Workspace workspace, Duration timeout) {
        this.workspace = workspace;
        this.timeout = timeout;
    }

    @Tool(description = "Run the target repository's test suite. Returns pass/fail and the tail of the build output.")
    public String runTests() {
        BuildResult r = build("test");
        return (r.success() ? "BUILD PASSED\n" : "BUILD FAILED\n") + r.output();
    }

    public BuildResult build(String task) {
        Path root = workspace.root();
        String wrapper = resolveWrapper(root);
        if (wrapper == null) {
            return new BuildResult(false, "No build wrapper found (looked for gradlew and mvnw).");
        }

        Process p;
        try {
            p = new ProcessBuilder(wrapper, task, "--console=plain")
                    .directory(root.toFile())
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException e) {
            return new BuildResult(false, "Build could not run: " + e.getMessage());
        }

        // Drain stdout on a background thread concurrently with waitFor(),
        // rather than reading it to completion first.
        //
        // InputStream.readAllBytes() blocks until EOF, and a child process's
        // stdout only hits EOF when the process exits (or explicitly closes
        // it). A first draft of this method called readAllBytes() *before*
        // waitFor(timeout, unit) -- which means for a hung process that
        // either produces no output, or keeps producing output without
        // exiting, the readAllBytes() call blocks indefinitely and waitFor()
        // is never reached in time to enforce the timeout at all. That
        // silently defeats the entire safety mechanism this class exists to
        // provide. Reading on a separate daemon thread lets waitFor()'s
        // timeout be what actually bounds wall-clock time; destroyForcibly()
        // then unblocks the reader by tearing down the child's stdout pipe.
        OutputDrain drain = new OutputDrain(p.getInputStream());
        Thread reader = new Thread(drain, "buildtools-output-drain");
        reader.setDaemon(true);
        reader.start();

        boolean finished;
        try {
            finished = p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            return new BuildResult(false, "Build was interrupted: " + e.getMessage());
        }

        if (!finished) {
            p.destroyForcibly();
            waitQuietly(p);
        }
        joinQuietly(reader, Duration.ofSeconds(5));
        String output = truncate(drain.output(), MAX_OUTPUT_CHARS);

        if (!finished) {
            return new BuildResult(false, "Build timed out after " + timeout.toMinutes() + " minutes.\n" + output);
        }
        return new BuildResult(p.exitValue() == 0, output);
    }

    private void waitQuietly(Process p) {
        try {
            p.waitFor(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void joinQuietly(Thread t, Duration wait) {
        try {
            t.join(wait.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String resolveWrapper(Path root) {
        for (String name : new String[]{"gradlew", "mvnw"}) {
            Path candidate = root.resolve(name);
            if (Files.isRegularFile(candidate)) {
                candidate.toFile().setExecutable(true);
                return candidate.toAbsolutePath().toString();
            }
        }
        return null;
    }

    /** Keeps the tail — build failures put the useful information at the end. */
    static String truncate(String s, int max) {
        if (s.length() <= max) return s;
        return "[... " + (s.length() - max) + " chars truncated ...]\n" + s.substring(s.length() - max);
    }

    /**
     * Reads a process's combined stdout/stderr to completion on its own
     * thread, tolerating being cut off mid-read when the process is killed
     * out from under it (destroyForcibly() closes the pipe, which surfaces
     * here as EOF or, occasionally, an IOException -- either way we keep
     * whatever was captured so far).
     */
    private static final class OutputDrain implements Runnable {
        private final InputStream in;
        private volatile String result = "";

        OutputDrain(InputStream in) { this.in = in; }

        @Override
        public void run() {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            try {
                in.transferTo(buf);
            } catch (IOException ignored) {
                // Stream was torn down mid-read (e.g. destroyForcibly()); fall
                // through and keep whatever partial output landed in buf.
            } finally {
                result = buf.toString();
            }
        }

        String output() { return result; }
    }
}
