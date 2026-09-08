package ai.devflow.workspace;

import java.io.IOException;
import java.nio.file.Path;

public interface Workspace {
    Path root();
    String branchName();
    PathGuard guard();
    /** The origin repo's https:// URL, or null for a workspace with no real remote (the fixture). */
    String repoUrl();
    void prepare() throws IOException;
    void cleanup() throws IOException;
}
