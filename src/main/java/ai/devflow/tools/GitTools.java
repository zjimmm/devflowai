package ai.devflow.tools;

import ai.devflow.workspace.Workspace;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.springframework.ai.tool.annotation.Tool;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

public class GitTools {

    private final Workspace workspace;

    public GitTools(Workspace workspace) { this.workspace = workspace; }

    @Tool(description = "Show which files in the workspace have been added, modified or deleted.")
    public String status() {
        try (Git git = Git.open(workspace.root().toFile())) {
            Status s = git.status().call();
            if (s.isClean()) return "Working tree clean.";
            List<String> lines = new ArrayList<>();
            s.getAdded().forEach(f -> lines.add("added:    " + f));
            s.getChanged().forEach(f -> lines.add("changed:  " + f));
            s.getModified().forEach(f -> lines.add("modified: " + f));
            s.getRemoved().forEach(f -> lines.add("removed:  " + f));
            s.getUntracked().forEach(f -> lines.add("new:      " + f));
            return String.join("\n", lines);
        } catch (Exception e) {
            return "git status failed: " + e.getMessage();
        }
    }

    @Tool(description = "Show the unified diff of all uncommitted changes in the workspace.")
    public String diff() {
        try (Git git = Git.open(workspace.root().toFile())) {
            var out = new ByteArrayOutputStream();
            git.diff().setOutputStream(out).call();
            String d = out.toString();
            return d.isBlank() ? "No changes." : d;
        } catch (Exception e) {
            return "git diff failed: " + e.getMessage();
        }
    }

    @Tool(description = "Stage all changes and commit them with the given message.")
    public String commit(String message) {
        try (Git git = Git.open(workspace.root().toFile())) {
            git.add().addFilepattern(".").call();
            git.add().addFilepattern(".").setUpdate(true).call();
            var rev = git.commit().setMessage(message).setSign(false).call();
            return "Committed " + rev.getName().substring(0, 7) + ": " + message;
        } catch (Exception e) {
            return "git commit failed: " + e.getMessage();
        }
    }

    /** Not a @Tool — the orchestrator uses this to populate AgentResult.filesTouched. */
    public List<String> changedFiles() {
        try (Git git = Git.open(workspace.root().toFile())) {
            Status s = git.status().call();
            var all = new TreeSet<String>();
            all.addAll(s.getAdded());
            all.addAll(s.getChanged());
            all.addAll(s.getModified());
            all.addAll(s.getRemoved());
            all.addAll(s.getUntracked());
            return new ArrayList<>(all);
        } catch (Exception e) {
            return List.of();
        }
    }
}
