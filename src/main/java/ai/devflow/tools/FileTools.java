package ai.devflow.tools;

import ai.devflow.workspace.PathGuard;
import org.springframework.ai.tool.annotation.Tool;

import java.io.IOException;
import java.nio.file.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class FileTools {

    private final PathGuard guard;

    public FileTools(PathGuard guard) { this.guard = guard; }

    @Tool(description = "Read a file from the workspace. Path is relative to the repository root.")
    public String readFile(String path) {
        Path target = guard.resolve(path);
        try {
            return Files.readString(target);
        } catch (NoSuchFileException e) {
            return "File not found: " + path;
        } catch (IOException e) {
            return "Could not read " + path + ": " + e.getMessage();
        }
    }

    @Tool(description = "Write a file in the workspace, creating parent directories. Overwrites if it exists.")
    public String writeFile(String path, String content) {
        Path target = guard.resolve(path);
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, content);
            return "Wrote " + path + " (" + content.length() + " chars)";
        } catch (IOException e) {
            return "Could not write " + path + ": " + e.getMessage();
        }
    }

    @Tool(description = "List files under a directory in the workspace, recursively.")
    public String listFiles(String path) {
        Path target = guard.resolve(path);
        try (Stream<Path> walk = Files.walk(target)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> !isUnderGitDir(guard.root(), p))
                    .map(p -> guard.root().relativize(p).toString())
                    .collect(Collectors.joining("\n"));
        } catch (IOException e) {
            return "Could not list " + path + ": " + e.getMessage();
        }
    }

    @Tool(description = "Find files whose contents contain the given text. Returns matching paths.")
    public String searchFiles(String text) {
        try (Stream<Path> walk = Files.walk(guard.root())) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> !isUnderGitDir(guard.root(), p))
                    .filter(p -> {
                        try { return Files.readString(p).contains(text); }
                        catch (IOException e) { return false; }
                    })
                    .map(p -> guard.root().relativize(p).toString())
                    .collect(Collectors.joining("\n"));
        } catch (IOException e) {
            return "Search failed: " + e.getMessage();
        }
    }

    private boolean isUnderGitDir(Path root, Path candidate) {
        for (Path component : root.relativize(candidate)) {
            if (component.toString().equals(".git")) return true;
        }
        return false;
    }
}
