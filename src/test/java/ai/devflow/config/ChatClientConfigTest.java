package ai.devflow.config;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ChatClientConfigTest {

    @Test
    void modelsArePinnedAndNotTheSpringAiDefault() {
        assertThat(ModelNames.OPUS).isEqualTo("claude-opus-5");
        assertThat(ModelNames.HAIKU).isEqualTo("claude-haiku-4-5");
        // Guards against silently inheriting Spring AI's stale default.
        assertThat(ModelNames.OPUS).doesNotContain("sonnet-4-2025");
    }
}
