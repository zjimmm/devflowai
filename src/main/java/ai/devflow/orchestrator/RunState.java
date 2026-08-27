package ai.devflow.orchestrator;

import ai.devflow.agent.AgentResult;
import ai.devflow.agent.Finding;
import ai.devflow.agent.TokenUsage;
import ai.devflow.workspace.Workspace;

import java.util.ArrayList;
import java.util.List;

public class RunState {

    private final String runId;
    private final String task;
    private final Workspace workspace;
    private final List<AgentResult> history = new ArrayList<>();
    private final List<Finding> openFindings = new ArrayList<>();
    private final List<String> loadedSkills = new ArrayList<>();

    private int reviewIterations = 0;
    private int humanIterations = 0;
    private TokenUsage totalTokens = TokenUsage.NONE;

    public RunState(String runId, String task, Workspace workspace) {
        this.runId = runId;
        this.task = task;
        this.workspace = workspace;
    }

    public String runId() { return runId; }
    public String task() { return task; }
    public Workspace workspace() { return workspace; }
    public List<AgentResult> history() { return List.copyOf(history); }
    public List<Finding> openFindings() { return List.copyOf(openFindings); }
    public List<String> loadedSkills() { return List.copyOf(loadedSkills); }
    public int reviewIterations() { return reviewIterations; }
    public int humanIterations() { return humanIterations; }
    public TokenUsage totalTokens() { return totalTokens; }

    public void record(AgentResult result) {
        history.add(result);
        totalTokens = totalTokens.plus(result.tokens());
    }

    public void addFindings(List<Finding> findings) { openFindings.addAll(findings); }
    public void clearFindings() { openFindings.clear(); }
    public void addLoadedSkill(String name) { loadedSkills.add(name); }
    public void incrementReviewIterations() { reviewIterations++; }
    public void incrementHumanIterations() { humanIterations++; }
}
