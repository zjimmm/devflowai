package ai.devflow.orchestrator;

import ai.devflow.agent.AgentResult;
import ai.devflow.agent.Finding;
import ai.devflow.agent.TokenUsage;
import ai.devflow.skill.ScribeDraft;
import ai.devflow.tools.GitTools;
import ai.devflow.workspace.Workspace;

import java.util.ArrayList;
import java.util.List;

/**
 * Mutable context for one run.
 *
 * <p>Thread-safe by design: from Phase 4 the orchestrator mutates this on a
 * worker thread while the SSE publisher and the /approve endpoint read it from
 * request threads. Every accessor is synchronized on the instance, and the
 * list accessors return snapshots (not live views) so a caller iterating a
 * snapshot cannot see a concurrent modification.
 */
public class RunState {

    private final String runId;
    private final String task;
    private final Workspace workspace;
    private final String repoSlug;
    private final GitTools gitTools;

    private final List<AgentResult> history = new ArrayList<>();
    private final List<Finding> openFindings = new ArrayList<>();
    private final List<String> loadedSkills = new ArrayList<>();
    private final List<Finding> allFindings = new ArrayList<>();

    private int reviewIterations = 0;
    private int humanIterations = 0;
    private TokenUsage totalTokens = TokenUsage.NONE;
    private RunPhase phase = RunPhase.PREPARING;
    private String memory = "";
    private ScribeDraft pendingScribeDraft = ScribeDraft.EMPTY;

    public RunState(String runId, String task, Workspace workspace) {
        this(runId, task, workspace, "fixture");
    }

    public RunState(String runId, String task, Workspace workspace, String repoSlug) {
        this.runId = runId;
        this.task = task;
        this.workspace = workspace;
        this.repoSlug = repoSlug;
        // Bound to THIS run's workspace, created once. Agents read tools from
        // here rather than holding their own, so agents stay stateless and are
        // safe to register as singleton beans (Phase 3 finding I3).
        this.gitTools = new GitTools(workspace);
    }

    public String runId() { return runId; }
    public String task() { return task; }
    public Workspace workspace() { return workspace; }
    public String repoSlug() { return repoSlug; }
    public GitTools gitTools() { return gitTools; }

    public synchronized List<AgentResult> history() { return List.copyOf(history); }
    public synchronized List<Finding> openFindings() { return List.copyOf(openFindings); }
    public synchronized List<String> loadedSkills() { return List.copyOf(loadedSkills); }
    public synchronized List<Finding> allFindings() { return List.copyOf(allFindings); }
    public synchronized String memory() { return memory; }
    public synchronized ScribeDraft pendingScribeDraft() { return pendingScribeDraft; }
    public synchronized int reviewIterations() { return reviewIterations; }
    public synchronized int humanIterations() { return humanIterations; }
    public synchronized TokenUsage totalTokens() { return totalTokens; }
    public synchronized RunPhase phase() { return phase; }

    public synchronized void setPhase(RunPhase phase) { this.phase = phase; }
    public synchronized void setMemory(String memory) { this.memory = memory == null ? "" : memory; }
    public synchronized void setPendingScribeDraft(ScribeDraft draft) {
        this.pendingScribeDraft = draft == null ? ScribeDraft.EMPTY : draft;
    }

    public synchronized void record(AgentResult result) {
        history.add(result);
        totalTokens = totalTokens.plus(result.tokens());
    }

    public synchronized void addFindings(List<Finding> findings) {
        openFindings.addAll(findings);
        allFindings.addAll(findings); // never cleared -- the Scribe's input at the end of the run
    }
    public synchronized void clearFindings() { openFindings.clear(); }
    public synchronized void addLoadedSkill(String name) { loadedSkills.add(name); }
    public synchronized void incrementReviewIterations() { reviewIterations++; }
    public synchronized void incrementHumanIterations() { humanIterations++; }

    /**
     * Returns one reviewer iteration. Used when a human rejection sends work
     * back — that round is charged to humanIterations, not the reviewer's cap.
     */
    public synchronized void rollBackReviewIteration() {
        if (reviewIterations > 0) reviewIterations--;
    }
}
