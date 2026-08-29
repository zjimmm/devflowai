package ai.devflow.tools;

import ai.devflow.workspace.PathGuard;
import org.springframework.ai.tool.annotation.Tool;

/**
 * Read-only subset of {@link FileTools} for agents that must never write —
 * e.g. the reviewer. Delegates to a {@link FileTools} instance (composition,
 * not inheritance) so the read logic isn't duplicated; deliberately omits
 * {@code writeFile} entirely rather than exposing and rejecting it, so there
 * is no write-shaped @Tool on this class at all for Spring AI to publish.
 */
public class ReadOnlyFileTools {

    private final FileTools delegate;

    public ReadOnlyFileTools(PathGuard guard) {
        this.delegate = new FileTools(guard);
    }

    @Tool(description = "Read a file from the workspace. Path is relative to the repository root.")
    public String readFile(String path) {
        return delegate.readFile(path);
    }

    @Tool(description = "List files under a directory in the workspace, recursively.")
    public String listFiles(String path) {
        return delegate.listFiles(path);
    }

    @Tool(description = "Find files whose contents contain the given text. Returns matching paths.")
    public String searchFiles(String text) {
        return delegate.searchFiles(text);
    }
}
