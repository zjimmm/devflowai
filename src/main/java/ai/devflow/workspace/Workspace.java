package ai.devflow.workspace;

import java.io.IOException;
import java.nio.file.Path;

public interface Workspace {
    Path root();
    String branchName();
    PathGuard guard();
    void prepare() throws IOException;
    void cleanup() throws IOException;
}
