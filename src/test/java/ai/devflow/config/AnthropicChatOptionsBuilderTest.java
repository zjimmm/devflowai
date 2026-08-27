package ai.devflow.config;

import com.anthropic.models.messages.OutputConfig;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatOptions;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure object-construction checks for the Spring AI 2.0.1 / Anthropic Java SDK
 * builder shapes that Task 10 (ChatClientConfig) depends on.
 *
 * <p>No network call happens here -- building an {@link AnthropicChatOptions}
 * instance is plain object construction. This class runs in the default
 * {@code ./gradlew test} suite (no {@code @Tag("live")}, no API key needed)
 * and gives real compiler + runtime evidence, as opposed to the static
 * bytecode inspection recorded in CLAUDE.md.
 */
class AnthropicChatOptionsBuilderTest {

    @Test
    void modelPinnedAndTemperatureLeftUnset() {
        var options = AnthropicChatOptions.builder()
                .model("claude-opus-5")
                .build();

        assertThat(options.getModel()).isEqualTo("claude-opus-5");
        // This is the crux of the temperature spike: when .temperature(...) is
        // never called, the field stays null -- it is not defaulted to 1.0.
        assertThat(options.getTemperature()).isNull();
    }

    @Test
    void thinkingAdaptiveAndEffortCompileAndBuild() {
        var options = AnthropicChatOptions.builder()
                .model("claude-opus-5")
                .thinkingAdaptive()
                .effort(OutputConfig.Effort.HIGH)
                .build();

        assertThat(options.getModel()).isEqualTo("claude-opus-5");
        assertThat(options.getThinking()).isNotNull();
        assertThat(options.getOutputConfig()).isNotNull();
    }
}
