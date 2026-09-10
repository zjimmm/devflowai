package ai.devflow.agent;

import ai.devflow.orchestrator.RunState;
import ai.devflow.worker.CodingWorker;
import ai.devflow.worker.WorkerRequest;
import ai.devflow.worker.WorkerResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

public class RequirementAnalystAgent implements RequirementAnalyst {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CodingWorker worker;

    public RequirementAnalystAgent(CodingWorker worker) {
        this.worker = worker;
    }

    @Override public boolean isConfigured() { return true; }

    @Override
    public RequirementAnalysisResult analyze(RunState state) {
        WorkerResult result = worker.run(new WorkerRequest(buildPrompt(state), List.of()));
        return new RequirementAnalysisResult(parse(result.text()), result.tokens());
    }

    private String buildPrompt(RunState state) {
        String clarifications = state.requirementClarifications().isEmpty()
                ? "(none)" : String.join("\n", state.requirementClarifications());
        return """
                Analyze this development request before implementation.

                Original task:
                %s

                Operator clarifications from earlier analysis rounds:
                %s

                Reply with ONLY this JSON shape:
                {
                  "functionalRequirements": ["FR-1 ..."],
                  "assumptions": ["..."],
                  "edgeCases": ["..."],
                  "acceptanceCriteria": ["Given ... when ... then ..."],
                  "missingInformation": ["..."],
                  "clarificationRequired": false,
                  "clarificationQuestion": ""
                }
                Keep each item concise and testable. Do not invent product behavior to hide material ambiguity.
                Set clarificationRequired only when implementation would require a consequential product decision.
                """.formatted(state.task(), clarifications);
    }

    private RequirementAnalysis parse(String raw) {
        try {
            String json = raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1);
            JsonNode root = MAPPER.readTree(json);
            return new RequirementAnalysis(
                    stringList(root, "functionalRequirements", true),
                    stringList(root, "assumptions", false),
                    stringList(root, "edgeCases", false),
                    stringList(root, "acceptanceCriteria", true),
                    stringList(root, "missingInformation", false),
                    root.path("clarificationRequired").asBoolean(false),
                    root.path("clarificationQuestion").asText(""));
        } catch (Exception e) {
            throw new IllegalStateException("Could not parse requirement analysis: " + e.getMessage(), e);
        }
    }

    private List<String> stringList(JsonNode root, String field, boolean required) {
        JsonNode value = root.path(field);
        if (!value.isArray()) {
            if (required) throw new IllegalArgumentException(field + " must be an array");
            return List.of();
        }
        List<String> items = new ArrayList<>();
        value.forEach(item -> {
            if (item.isTextual() && !item.asText().isBlank()) items.add(item.asText().trim());
        });
        if (required && items.isEmpty()) throw new IllegalArgumentException(field + " must not be empty");
        return items;
    }
}
