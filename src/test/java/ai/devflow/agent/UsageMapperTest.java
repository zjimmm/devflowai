package ai.devflow.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class UsageMapperTest {

    @Test
    void mapsPromptAndCompletionTokens() {
        var metadata = ChatResponseMetadata.builder()
                .usage(new DefaultUsage(120, 45))
                .build();
        ChatResponse response = mock(ChatResponse.class);
        when(response.getMetadata()).thenReturn(metadata);

        assertThat(UsageMapper.from(response)).isEqualTo(new TokenUsage(120, 45));
    }

    @Test
    void returnsNoneForNullResponse() {
        assertThat(UsageMapper.from(null)).isEqualTo(TokenUsage.NONE);
    }

    @Test
    void returnsNoneWhenMetadataMissing() {
        ChatResponse response = mock(ChatResponse.class);
        when(response.getMetadata()).thenReturn(null);
        assertThat(UsageMapper.from(response)).isEqualTo(TokenUsage.NONE);
    }

    @Test
    void treatsNullTokenCountsAsZeroRatherThanThrowing() {
        var metadata = ChatResponseMetadata.builder()
                .usage(new DefaultUsage(null, null))
                .build();
        ChatResponse response = mock(ChatResponse.class);
        when(response.getMetadata()).thenReturn(metadata);

        assertThat(UsageMapper.from(response)).isEqualTo(TokenUsage.NONE);
    }
}
