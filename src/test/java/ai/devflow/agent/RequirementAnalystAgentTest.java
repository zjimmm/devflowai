package ai.devflow.agent;

import ai.devflow.orchestrator.RunState;
import ai.devflow.worker.WorkerRequest;
import ai.devflow.worker.WorkerResult;
import ai.devflow.workspace.Workspace;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class RequirementAnalystAgentTest {

    @Test
    void parsesStructuredRequirementsAndPreservesTokenUsage() {
        var worker = new RequirementAnalystAgent(request -> new WorkerResult("""
                {
                  "functionalRequirements": ["FR-1 Validate email"],
                  "assumptions": ["Existing endpoint remains stable"],
                  "edgeCases": ["Blank email"],
                  "acceptanceCriteria": ["Given a blank email, when submitted, then return 400"],
                  "missingInformation": [],
                  "clarificationRequired": false,
                  "clarificationQuestion": ""
                }
                """, new TokenUsage(23, 11)));

        RequirementAnalysisResult result = worker.analyze(state("Add signup validation"));

        assertThat(result.analysis().functionalRequirements()).containsExactly("FR-1 Validate email");
        assertThat(result.analysis().acceptanceCriteria()).hasSize(1);
        assertThat(result.analysis().status()).isEqualTo("READY");
        assertThat(result.tokens()).isEqualTo(new TokenUsage(23, 11));
    }

    @Test
    void includesEarlierOperatorClarificationWithoutGivingTheAnalystTools() {
        AtomicReference<WorkerRequest> captured = new AtomicReference<>();
        var worker = new RequirementAnalystAgent(request -> {
            captured.set(request);
            return new WorkerResult("""
                    {
                      "functionalRequirements": ["FR-1 Support administrators"],
                      "assumptions": [],
                      "edgeCases": [],
                      "acceptanceCriteria": ["Given an administrator, when saving, then persist the change"],
                      "missingInformation": [],
                      "clarificationRequired": false,
                      "clarificationQuestion": ""
                    }
                    """, TokenUsage.NONE);
        });
        RunState state = state("Add role editing");
        state.addRequirementClarification("Only administrators may edit roles");

        worker.analyze(state);

        assertThat(captured.get().prompt()).contains("Only administrators may edit roles");
        assertThat(captured.get().tools()).isEmpty();
    }

    @Test
    void failsClosedWhenRequiredAcceptanceCriteriaAreMissing() {
        var worker = new RequirementAnalystAgent(request -> new WorkerResult("""
                {
                  "functionalRequirements": ["FR-1 Validate email"],
                  "acceptanceCriteria": [],
                  "clarificationRequired": false
                }
                """, TokenUsage.NONE));

        assertThatThrownBy(() -> worker.analyze(state("Add signup validation")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("acceptanceCriteria must not be empty");
    }

    private RunState state(String task) {
        return new RunState("requirements-test", task, mock(Workspace.class));
    }
}
