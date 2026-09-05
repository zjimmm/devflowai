package ai.devflow.agent;

import ai.devflow.skill.SkillIndexEntry;
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

class SkillPickerAgentTest {

    private static ChatResponse responseWith(String text) {
        var generation = new Generation(new AssistantMessage(text));
        var metadata = ChatResponseMetadata.builder().usage(new DefaultUsage(10, 5)).build();
        return new ChatResponse(List.of(generation), metadata);
    }

    @Test
    void emptyIndexNeverCallsTheModel() {
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);

        List<String> picked = new SkillPickerAgent(client).pick("add validation", List.of());

        assertThat(picked).isEmpty();
        verifyNoInteractions(client);
    }

    @Test
    void parsesSelectedSkillNames() {
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(client.prompt().user(any(String.class)).call().chatResponse())
                .thenReturn(responseWith("""
                    {"skills":["spring-controller-validation"]}
                    """));

        var index = List.of(new SkillIndexEntry("spring-controller-validation", "d", List.of("validation")));
        List<String> picked = new SkillPickerAgent(client).pick("add validation", index);

        assertThat(picked).containsExactly("spring-controller-validation");
    }

    @Test
    void noneApplyReturnsEmptyList() {
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(client.prompt().user(any(String.class)).call().chatResponse())
                .thenReturn(responseWith("""
                    {"skills":[]}
                    """));

        var index = List.of(new SkillIndexEntry("unrelated-skill", "d", List.of()));
        assertThat(new SkillPickerAgent(client).pick("add validation", index)).isEmpty();
    }

    @Test
    void malformedResponseFailsClosedToNoSkills() {
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(client.prompt().user(any(String.class)).call().chatResponse())
                .thenReturn(responseWith("not json at all"));

        var index = List.of(new SkillIndexEntry("s", "d", List.of()));
        assertThat(new SkillPickerAgent(client).pick("t", index)).isEmpty();
    }

    @Test
    void moreThanThreeNamesAreTruncated() {
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(client.prompt().user(any(String.class)).call().chatResponse())
                .thenReturn(responseWith("""
                    {"skills":["a","b","c","d"]}
                    """));

        var index = List.of(new SkillIndexEntry("a", "", List.of()));
        assertThat(new SkillPickerAgent(client).pick("t", index)).hasSize(3);
    }
}
