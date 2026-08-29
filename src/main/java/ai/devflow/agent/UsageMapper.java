package ai.devflow.agent;

import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;

/**
 * Extracts token counts from a model response.
 *
 * <p>Deliberately total: token accounting is bookkeeping, and a run must never
 * fail because usage metadata was absent or malformed. Every unusable input
 * maps to {@link TokenUsage#NONE}.
 */
public final class UsageMapper {

    private UsageMapper() {}

    public static TokenUsage from(ChatResponse response) {
        if (response == null) return TokenUsage.NONE;
        ChatResponseMetadata metadata = response.getMetadata();
        if (metadata == null) return TokenUsage.NONE;
        Usage usage = metadata.getUsage();
        if (usage == null) return TokenUsage.NONE;
        return new TokenUsage(
                zeroIfNull(usage.getPromptTokens()),
                zeroIfNull(usage.getCompletionTokens()));
    }

    private static long zeroIfNull(Integer value) {
        return value == null ? 0L : value.longValue();
    }
}
