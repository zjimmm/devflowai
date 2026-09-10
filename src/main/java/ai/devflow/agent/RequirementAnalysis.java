package ai.devflow.agent;

import java.util.List;

public record RequirementAnalysis(List<String> functionalRequirements, List<String> assumptions,
                                  List<String> edgeCases, List<String> acceptanceCriteria,
                                  List<String> missingInformation, boolean clarificationRequired,
                                  String clarificationQuestion) {
    public RequirementAnalysis {
        functionalRequirements = List.copyOf(functionalRequirements);
        assumptions = List.copyOf(assumptions);
        edgeCases = List.copyOf(edgeCases);
        acceptanceCriteria = List.copyOf(acceptanceCriteria);
        missingInformation = List.copyOf(missingInformation);
        clarificationQuestion = clarificationQuestion == null ? "" : clarificationQuestion.trim();
        if (functionalRequirements.isEmpty()) {
            throw new IllegalArgumentException("functionalRequirements must not be empty");
        }
        if (acceptanceCriteria.isEmpty()) {
            throw new IllegalArgumentException("acceptanceCriteria must not be empty");
        }
        if (clarificationRequired && clarificationQuestion.isBlank()) {
            throw new IllegalArgumentException("clarificationQuestion is required when clarificationRequired is true");
        }
    }

    public String status() {
        return clarificationRequired ? "NEEDS_CLARIFICATION" : "READY";
    }

    public String summary() {
        return "Functional requirements:\n- " + String.join("\n- ", functionalRequirements)
                + "\n\nAcceptance criteria:\n- " + String.join("\n- ", acceptanceCriteria);
    }
}
