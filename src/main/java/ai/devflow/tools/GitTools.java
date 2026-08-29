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
            s.getMissing().forEach(f -> lines.add("missing:  " + f));
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

    /**
     * Not a @Tool — committing sits behind Gate 3 (a human gate, Phase 4) per
     * spec §5 step 13. An agent-callable commit tool would let the coder
     * commit mid-task, which clears git status and makes changedFiles()
     * return empty before the reviewer ever runs — see the C1 finding in
     * the Phases 0-3 final review. Only Java code (the future orchestrator)
     * may call this.
     */
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
            all.addAll(s.getMissing());
            return new ArrayList<>(all);
        } catch (Exception e) {
            return List.of();
        }
    }
}
