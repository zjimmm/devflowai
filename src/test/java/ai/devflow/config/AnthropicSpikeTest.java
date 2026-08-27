package ai.devflow.config;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Tag("live")
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
class AnthropicSpikeTest {

    @Autowired
    ChatClient.Builder builder;

    @Test
    void opus5RespondsWithoutTemperature() {
        // NOTE: ChatClientRequestSpec.options(B) in Spring AI 2.0.1 takes a
        // ChatOptions.Builder<?>, not a built ChatOptions instance -- passing
        // the result of .build() fails to compile ("inference variable B has
        // incompatible bounds"). Pass the builder itself.
        var optionsBuilder = AnthropicChatOptions.builder()
                .model("claude-opus-5");

        String reply = builder.build()
                .prompt("Reply with exactly the word: OK")
                .options(optionsBuilder)
                .call()
                .content();

        assertThat(reply).contains("OK");
    }
}
