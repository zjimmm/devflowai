package ai.devflow.agent;

import ai.devflow.orchestrator.RunState;
import ai.devflow.tools.FileTools;
import ai.devflow.worker.CodingWorker;
import ai.devflow.worker.WorkerRequest;
import ai.devflow.worker.WorkerResult;

import java.util.List;
import java.util.stream.Collectors;

public class CoderAgent implements Agent {

    private final CodingWorker worker;

    public CoderAgent(CodingWorker worker) {
        this.worker = worker;
    }

    @Override public String name() { return "coder"; }

    @Override
    public AgentResult run(RunState state) {
        String prompt = buildPrompt(state);
        List<Object> tools = List.of(new FileTools(state.workspace().guard()), state.gitTools());

        WorkerResult result = worker.run(new WorkerRequest(prompt, tools));

        // Derived from git, never from what the model claims.
        List<String> touched = state.gitTools().changedFiles();

        return AgentResult.ok(name(), result.text(), touched, result.tokens());
    }

    private String buildPrompt(RunState state) {
        StringBuilder sb = new StringBuilder();
        sb.append("Task: ").append(state.task()).append("\n\n");

        if (!state.memory().isBlank()) {
            sb.append("Known facts about this repository from previous runs:\n")
              .append(state.memory())
              .append("\n\n");
        }

        if (!state.loadedSkills().isEmpty()) {
            sb.append("Relevant knowledge from previous runs on this repository:\n")
              .append(String.join("\n\n", state.loadedSkills()))
              .append("\n\n");
        }

        if (!state.openFindings().isEmpty()) {
            sb.append("Your previous attempt was rejected. Fix these findings:\n")
              .append(state.openFindings().stream()
                      .map(f -> "- [" + f.origin() + "/" + f.severity() + "] "
                              + (f.file() != null ? f.file() + ": " : "") + f.message())
                      .collect(Collectors.joining("\n")))
              .append("\n\n");
        }

        sb.append("Use your tools to read and modify files. Then stop.");
        return sb.toString();
    }
}
