package ai.devflow.workspace;

import java.io.IOException;
import java.nio.file.*;

public final class PathGuard {

    private final Path root;

    public PathGuard(Path root) {
        try {
            this.root = root.toRealPath();
        } catch (IOException e) {
            throw new IllegalArgumentException("Workspace root unreadable: " + root, e);
        }
    }

    public Path root() { return root; }

    public Path resolve(String relative) {
        Path candidate = root.resolve(relative).normalize();
        Path real = realPathOfNearestExistingAncestor(candidate);
        if (!real.startsWith(root)) {
            throw new SecurityException("Path escapes workspace root: " + relative);
        }
        return candidate;
    }

    private Path realPathOfNearestExistingAncestor(Path candidate) {
        Path p = candidate;
        while (p != null && !Files.exists(p, LinkOption.NOFOLLOW_LINKS)) {
            p = p.getParent();
        }
        if (p == null) return candidate;
        try {
            Path real = p.toRealPath();
            Path remainder = p.relativize(candidate);
            return real.resolve(remainder).normalize();
        } catch (IOException e) {
            throw new SecurityException("Cannot verify path: " + candidate, e);
        }
    }
}
