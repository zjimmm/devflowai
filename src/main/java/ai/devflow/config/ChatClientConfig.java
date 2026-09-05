package ai.devflow.config;

import com.anthropic.models.messages.OutputConfig;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatClientConfig {

    /**
     * Explicit max_tokens for the OPUS-tier agents (coder/reviewer). Left
     * unset, Spring AI falls through to Anthropic's implicit default of 4096
     * (verified via bytecode inspection of AnthropicChatOptions's
     * constructor — see the final-review report), which is tight for a
     * coding agent emitting whole file bodies through writeFile and is
     * shared with adaptive-thinking tokens once thinkingAdaptive() is on.
     * 12000 gives generous headroom for both without being unbounded.
     */
    private static final int OPUS_MAX_TOKENS = 12_000;

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

    /**
     * Same as {@link #options(String)}, plus the spec §7 tuning that only
     * applies to the OPUS-tier agents (coder/reviewer): adaptive thinking at
     * {@code effort: HIGH} and an explicit, generous max_tokens. The
     * HAIKU-tier router does not get this — see CLAUDE.md's "Verified Spring
     * AI 2.0.1 syntax" section for the exact verified shape this mirrors.
     */
    private AnthropicChatOptions.Builder opusOptions(String model) {
        return options(model)
                .thinkingAdaptive()
                .effort(OutputConfig.Effort.HIGH)
                .maxTokens(OPUS_MAX_TOKENS);
    }

    @Bean @Qualifier("coder")
    ChatClient coderChatClient(ChatClient.Builder builder) {
        return builder.defaultOptions(opusOptions(ModelNames.OPUS))
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
        return builder.defaultOptions(opusOptions(ModelNames.OPUS))
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

    @Bean @Qualifier("scribe")
    ChatClient scribeChatClient(ChatClient.Builder builder) {
        return builder.defaultOptions(options(ModelNames.HAIKU))
                .defaultSystem("""
                    You are the Scribe in an automated development crew. You
                    never touch files yourself. Given a task, the findings
                    that caused a correction, and the final diff, decide what
                    is worth remembering so the next run does not repeat the
                    mistake. Answer only in the requested JSON shape. No prose.
                    """)
                .build();
    }
}
