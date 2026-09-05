package ai.devflow.agent;

import ai.devflow.orchestrator.RunState;
import ai.devflow.skill.ScribeDraft;
import ai.devflow.skill.SkillDraft;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Deliberately tool-free (spec §8): it never touches the filesystem itself.
 * It reads the diff via {@code state.gitTools().diff()} -- a plain Java call,
 * not a {@code @Tool} the model can invoke -- exactly the way {@code
 * CoderAgent.buildPrompt()} already pulls other RunState data directly into
 * its own prompt. {@code SkillStore}/{@code MemoryStore} write the result
 * only after Gate 3 resolves (Task 8); this class never writes anything.
 */
public class ScribeAgent implements Scribe {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ChatClient chatClient;

    public ScribeAgent(ChatClient chatClient) { this.chatClient = chatClient; }

    @Override
    public ScribeDraft draft(RunState state, List<Finding> findings, String humanGuidance) {
        String findingsText = findings.isEmpty()
                ? "(none recorded)"
                : findings.stream()
                        .map(f -> "- [%s/%s] %s".formatted(f.origin(), f.severity(), f.message()))
                        .collect(Collectors.joining("\n"));

        String guidance = humanGuidance == null || humanGuidance.isBlank()
                ? ""
                : "\n\nThe operator rejected your previous draft and said: %s\nRevise accordingly.\n".formatted(humanGuidance);

        String prompt = """
            Task: %s

            The coder was corrected during this run. Findings that caused the correction:
            %s

            Final diff:
            %s
            %s
            Decide what is worth remembering from this correction. Reply with ONLY this JSON:
            {"skill": {"name":"kebab-case-slug","description":"...","triggers":["...","..."],"body":"## Steps\\n...\\n## Pitfalls\\n...\\n## Verification\\n..."} or null,
             "memoryFact": "a short durable fact about this repository" or null}
            Use null for either field if this correction taught nothing generalizable. Do not invent a lesson just to fill the field.
            """.formatted(state.task(), findingsText, state.gitTools().diff(), guidance);

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

    private ScribeDraft parse(String raw) {
        try {
            String json = raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1);
            JsonNode node = MAPPER.readTree(json);

            SkillDraft skill = null;
            JsonNode skillNode = node.path("skill");
            if (skillNode.isObject()) {
                List<String> triggers = new ArrayList<>();
                for (JsonNode t : skillNode.path("triggers")) triggers.add(t.asText());
                SkillDraft candidate = new SkillDraft(
                        skillNode.path("name").asText(""),
                        skillNode.path("description").asText(""),
                        triggers,
                        skillNode.path("body").asText(""));
                if (!candidate.name().isBlank()) skill = candidate; // an unnamed skill can't be filed or matched later
            }

            String memoryFact = node.path("memoryFact").isTextual() ? node.path("memoryFact").asText() : null;

            return new ScribeDraft(skill, memoryFact);
        } catch (Exception e) {
            // Fail closed to "nothing learned": a malformed scribe response
            // must never block or corrupt an already-validated commit.
            return ScribeDraft.EMPTY;
        }
    }
}
