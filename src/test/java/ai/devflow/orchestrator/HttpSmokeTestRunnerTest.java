package ai.devflow.orchestrator;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpSmokeTestRunnerTest {

    @Test
    void blankEndpointListDisablesSmokeTests() {
        assertThat(new HttpSmokeTestRunner(" ", Duration.ofSeconds(1)).isConfigured()).isFalse();
    }

    @Test
    void configuredEndpointsMustBeSafeHttpsUrls() {
        assertThatThrownBy(() -> new HttpSmokeTestRunner("http://service.example/smoke", Duration.ofSeconds(1)))
                .hasMessageContaining("https:// URLs");
        assertThatThrownBy(() -> new HttpSmokeTestRunner(
                "https://service.example/smoke?token=secret", Duration.ofSeconds(1)))
                .hasMessageContaining("without credentials");
    }

    @Test
    void timeoutMustBePositive() {
        assertThatThrownBy(() -> new HttpSmokeTestRunner("https://service.example/smoke", Duration.ZERO))
                .hasMessageContaining("timeout must be positive");
    }
}
