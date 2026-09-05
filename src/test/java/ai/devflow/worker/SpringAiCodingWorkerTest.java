package ai.devflow.worker;

import ai.devflow.agent.TokenUsage;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SpringAiCodingWorkerTest {

    private static ChatResponse responseWith(String text, int promptTokens, int completionTokens) {
        var generation = new Generation(new AssistantMessage(text));
        var metadata = ChatResponseMetadata.builder()
                .usage(new DefaultUsage(promptTokens, completionTokens))
                .build();
        return new ChatResponse(List.of(generation), metadata);
    }

    @Test
    void sendsThePromptAndToolsAndMapsTheResponse() {
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(client.prompt().user(any(String.class)).tools(any(Object[].class)).call().chatResponse())
                .thenReturn(responseWith("done", 42, 7));

        Object tool = new Object();
        var worker = new SpringAiCodingWorker(client);
        WorkerResult result = worker.run(new WorkerRequest("do the thing", List.of(tool)));

        assertThat(result.text()).isEqualTo("done");
        assertThat(result.tokens()).isEqualTo(new TokenUsage(42, 7));
        verify(client.prompt()).user("do the thing");
    }

    @Test
    void aNullResponseBecomesEmptyTextNotAnException() {
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(client.prompt().user(any(String.class)).tools(any(Object[].class)).call().chatResponse())
                .thenReturn(null);

        WorkerResult result = new SpringAiCodingWorker(client).run(new WorkerRequest("t", List.of()));

        assertThat(result.text()).isEmpty();
        assertThat(result.tokens()).isEqualTo(TokenUsage.NONE);
    }
}
