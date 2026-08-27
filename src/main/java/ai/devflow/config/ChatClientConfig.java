package ai.devflow.config;

import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatClientConfig {

    /**
     * NOTE: temperature is deliberately never set. Spring AI defaults it to 1.0 and
     * Opus 5 rejects sampling parameters with HTTP 400. See CLAUDE.md.
     *
     * <p>Returns the builder, not a built {@link AnthropicChatOptions}: empirically
     * verified (javac) that {@code ChatClient.Builder.defaultOptions(...)} in Spring
     * AI 2.0.1 only has an overload accepting {@code ChatOptions.Builder}, mirroring
     * the per-request {@code options(B)} shape Task 3 found on
     * {@code ChatClientRequestSpec}. Passing {@code .build()}'s result fails to
     * compile ("incompatible types: AnthropicChatOptions cannot be converted to
     * Builder"). See CLAUDE.md.
     */
    private AnthropicChatOptions.Builder options(String model) {
        return AnthropicChatOptions.builder()
                .model(model);
    }

    @Bean @Qualifier("coder")
    ChatClient coderChatClient(ChatClient.Builder builder) {
        return builder.defaultOptions(options(ModelNames.OPUS))
                .defaultSystem("""
                    You are the Coder in an automated development crew.
                    Implement the requested change in the workspace using your tools.
                    Make the smallest change that fully satisfies the task.
                    Follow the conventions already present in the code you are editing.
                    When you are done, stop. Do not explain at length.
                    """)
                .build();
    }

    @Bean @Qualifier("reviewer")
    ChatClient reviewerChatClient(ChatClient.Builder builder) {
        return builder.defaultOptions(options(ModelNames.OPUS))
                .defaultSystem("""
                    You are the Reviewer in an automated development crew.
                    Read the changed files with your tools and judge correctness and security.
                    Report only real defects. Do not report style preferences.
                    If the change is correct, say so plainly.
                    """)
                .build();
    }

    @Bean @Qualifier("router")
    ChatClient routerChatClient(ChatClient.Builder builder) {
        return builder.defaultOptions(options(ModelNames.HAIKU))
                .defaultSystem("""
                    You classify development tasks and select which agents should run.
                    Answer only in the requested JSON shape. No prose.
                    """)
                .build();
    }
}
