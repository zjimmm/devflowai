package ai.devflow.policy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigurablePolicyEngineTest {

    @Test
    void passesWhenTheBuildPassed() {
        var engine = new ConfigurablePolicyEngine(true);

        var result = engine.evaluate(new PolicyContext(true));

        assertThat(result.passed()).isTrue();
        assertThat(result.reason()).isNull();
    }

    @Test
    void failsWhenTheBuildDidNotPassAndBuildPassIsRequired() {
        var engine = new ConfigurablePolicyEngine(true);

        var result = engine.evaluate(new PolicyContext(false));

        assertThat(result.passed()).isFalse();
        assertThat(result.reason()).contains("build");
    }

    @Test
    void passesWhenTheBuildDidNotPassButBuildPassIsNotRequired() {
        var engine = new ConfigurablePolicyEngine(false);

        var result = engine.evaluate(new PolicyContext(false));

        assertThat(result.passed()).isTrue();
    }
}
