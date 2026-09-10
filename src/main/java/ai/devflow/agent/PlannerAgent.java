package ai.devflow.agent;

import ai.devflow.orchestrator.RunState;
import ai.devflow.worker.CodingWorker;
import ai.devflow.worker.WorkerRequest;
import ai.devflow.worker.WorkerResult;

import java.util.List;

/**
 * Produces a short plan once per run, before the coder's first call (spec §5).
 * Never returns an explicit FAILED status — see this plan's design decision 7.
 */
public class PlannerAgent implements Agent {

    private final CodingWorker worker;

    public PlannerAgent(CodingWorker worker) { this.worker = worker; }

    @Override public String name() { return "planner"; }

    @Override
    public AgentResult run(RunState state) {
        WorkerResult result = worker.run(new WorkerRequest(buildPrompt(state), List.of()));
        return AgentResult.ok(name(), result.text(), List.of(), result.tokens());
    }

    private String buildPrompt(RunState state) {
        StringBuilder sb = new StringBuilder();
        sb.append("Task: ").append(state.task()).append("\n\n");

        if (state.requirementAnalysis() != null) {
            sb.append("Structured requirement analysis:\n")
              .append(state.requirementAnalysis().summary())
              .append("\n\nAssumptions:\n- ")
              .append(String.join("\n- ", state.requirementAnalysis().assumptions()))
              .append("\n\nEdge cases:\n- ")
              .append(String.join("\n- ", state.requirementAnalysis().edgeCases()))
              .append("\n\n");
        }

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

        sb.append("Write a short, concrete step-by-step plan for how the coder should implement this task and satisfy every acceptance criterion. ")
          .append("Do not write any code yourself. Keep it to a few sentences or a short numbered list.");
        return sb.toString();
    }
}
