package ai.devflow.config;

import ai.devflow.orchestrator.Orchestrator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/** Proves the new Phase 5 beans wire together without a real API key. */
@SpringBootTest(properties = "spring.ai.anthropic.api-key=test-key-not-used")
class OrchestrationConfigTest {

    @Autowired Orchestrator orchestrator;

    @Test
    void theFullyWiredOrchestratorExists() {
        assertThat(orchestrator).isNotNull();
    }
}
