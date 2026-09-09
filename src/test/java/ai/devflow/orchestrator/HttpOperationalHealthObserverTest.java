package ai.devflow.orchestrator;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpOperationalHealthObserverTest {

    @Test
    void blankEndpointDisablesHealthChecks() {
        var observer = new HttpOperationalHealthObserver(" ", Duration.ofSeconds(1));

        assertThat(observer.isConfigured()).isFalse();
    }

    @Test
    void endpointMustBeHttpsWithoutCredentialsOrAFragment() {
        assertThatThrownBy(() -> new HttpOperationalHealthObserver("http://service.example/health", Duration.ofSeconds(1)))
                .hasMessageContaining("https:// URL");
        assertThatThrownBy(() -> new HttpOperationalHealthObserver("https://user:secret@service.example/health", Duration.ofSeconds(1)))
                .hasMessageContaining("without credentials");
        assertThatThrownBy(() -> new HttpOperationalHealthObserver("https://service.example/health#section", Duration.ofSeconds(1)))
                .hasMessageContaining("without credentials");
        assertThatThrownBy(() -> new HttpOperationalHealthObserver("https://service.example/health?token=secret", Duration.ofSeconds(1)))
                .hasMessageContaining("without credentials");
    }

    @Test
    void timeoutMustBePositive() {
        assertThatThrownBy(() -> new HttpOperationalHealthObserver("https://service.example/health", Duration.ZERO))
                .hasMessageContaining("timeout must be positive");
    }
}
