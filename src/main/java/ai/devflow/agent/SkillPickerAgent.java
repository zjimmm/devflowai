package ai.devflow.agent;

import ai.devflow.skill.SkillIndexEntry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;

import java.util.ArrayList;
import java.util.List;

public class SkillPickerAgent implements SkillPicker {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_SKILLS = 3;

    private final ChatClient chatClient;

    public SkillPickerAgent(ChatClient chatClient) { this.chatClient = chatClient; }

    @Override
    public List<String> pick(String task, List<SkillIndexEntry> index) {
        if (index.isEmpty()) return List.of();

        String catalogue = index.stream()
                .map(e -> "- %s: %s (triggers: %s)".formatted(e.name(), e.description(), String.join(", ", e.triggers())))
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");

        String prompt = """
            Task: %s

            Available skills learned from previous runs on this repository:
            %s

            Pick at most %d skills relevant to this task. Reply with ONLY this JSON:
            {"skills":["name1","name2"]}
            If none apply, reply {"skills":[]}.
            """.formatted(task, catalogue, MAX_SKILLS);

        ChatResponse response = chatClient.prompt().user(prompt).call().chatResponse();
        return parse(textOf(response));
    }

    private static String textOf(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return "";
        }
        String text = response.getResult().getOutput().getText();
        return text == null ? "" : text;
    }

    private List<String> parse(String raw) {
        try {
            String json = raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1);
            JsonNode node = MAPPER.readTree(json);
            List<String> names = new ArrayList<>();
            for (JsonNode n : node.path("skills")) {
                String name = n.asText(null);
                if (name != null && !name.isBlank()) names.add(name);
            }
            return names.size() > MAX_SKILLS ? names.subList(0, MAX_SKILLS) : names;
        } catch (Exception e) {
            // Fail closed to "no skills": a malformed picker response must
            // never crash the run over a cost optimization.
            return List.of();
        }
    }
}
