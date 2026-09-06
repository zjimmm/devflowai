package ai.devflow.tools;

import ai.devflow.workspace.Workspace;
import org.springframework.ai.tool.annotation.Tool;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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

    public record BuildResult(boolean success, String output, boolean wrapperFound) {}

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
            return new BuildResult(false, "No build wrapper found (looked for gradlew and mvnw).", false);
        }

        Process p;
        try {
            p = new ProcessBuilder(wrapper, task, "--console=plain")
                    .directory(root.toFile())
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException e) {
            return new BuildResult(false, "Build could not run: " + e.getMessage(), true);
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
            return new BuildResult(false, "Build was interrupted: " + e.getMessage(), true);
        }

        if (!finished) {
            p.destroyForcibly();
            waitQuietly(p);
        }
        joinQuietly(reader, Duration.ofSeconds(5));
        String output = truncate(drain.output(), MAX_OUTPUT_CHARS);

        if (!finished) {
            return new BuildResult(false, "Build timed out after " + timeout.toMinutes() + " minutes.\n" + output, true);
        }
        return new BuildResult(p.exitValue() == 0, output, true);
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
     *
     * Buffers only a bounded tail rather than the whole stream. We only ever
     * keep MAX_OUTPUT_CHARS worth of output in the end (truncate() sees to
     * that), so there is no reason to ever hold more than that in memory
     * while draining -- doing otherwise let a runaway or pathological build
     * (arbitrary target-repo code, by design) grow an unbounded buffer for
     * up to the full timeout window (5 minutes in production) before
     * truncate() ever got a chance to run, which could exhaust the host
     * process's heap well before the timeout fires. A fixed-size ring
     * buffer, sized beyond MAX_OUTPUT_CHARS for UTF-8 headroom, bounds
     * memory use regardless of how much output the process actually
     * produces.
     */
    private static final class OutputDrain implements Runnable {
        // Sized beyond MAX_OUTPUT_CHARS to tolerate multi-byte UTF-8
        // sequences straddling the eventual truncation boundary. Bytes vs.
        // chars aren't 1:1 in general, but this is generous enough for the
        // ASCII-heavy build output this class actually handles, and any
        // sequence split by the boundary is simply replaced with U+FFFD by
        // the default String(byte[], UTF_8) decoder below rather than
        // throwing -- acceptable for a best-effort log tail.
        private static final int MAX_BUFFERED_BYTES = MAX_OUTPUT_CHARS * 4;

        private final InputStream in;
        private final byte[] ring = new byte[MAX_BUFFERED_BYTES];
        private long written = 0; // total bytes ever seen; may exceed ring.length
        private volatile String result = "";

        OutputDrain(InputStream in) { this.in = in; }

        @Override
        public void run() {
            byte[] chunk = new byte[8_192];
            int n;
            try {
                while ((n = in.read(chunk)) != -1) {
                    appendToRing(chunk, n);
                }
            } catch (IOException ignored) {
                // Stream was torn down mid-read (e.g. destroyForcibly()); fall
                // through and keep whatever partial output landed in the ring.
            } finally {
                result = decodeTail();
            }
        }

        /** Writes {@code len} bytes of {@code data} into the ring, overwriting the oldest bytes as needed. */
        private void appendToRing(byte[] data, int len) {
            int offset = 0;
            if (len > MAX_BUFFERED_BYTES) {
                // This single chunk alone exceeds the whole buffer; only its
                // own tail could possibly survive anyway, so treat the rest
                // as immediately overwritten without touching the array.
                offset = len - MAX_BUFFERED_BYTES;
                written += offset;
                len = MAX_BUFFERED_BYTES;
            }
            int start = (int) (written % MAX_BUFFERED_BYTES);
            int firstPart = Math.min(len, MAX_BUFFERED_BYTES - start);
            System.arraycopy(data, offset, ring, start, firstPart);
            int remaining = len - firstPart;
            if (remaining > 0) {
                System.arraycopy(data, offset + firstPart, ring, 0, remaining);
            }
            written += len;
        }

        private String decodeTail() {
            int len = (int) Math.min(written, MAX_BUFFERED_BYTES);
            byte[] tail = new byte[len];
            if (written <= MAX_BUFFERED_BYTES) {
                System.arraycopy(ring, 0, tail, 0, len);
            } else {
                int start = (int) (written % MAX_BUFFERED_BYTES);
                System.arraycopy(ring, start, tail, 0, MAX_BUFFERED_BYTES - start);
                System.arraycopy(ring, 0, tail, MAX_BUFFERED_BYTES - start, start);
            }
            return new String(tail, StandardCharsets.UTF_8);
        }

        String output() { return result; }
    }
}
