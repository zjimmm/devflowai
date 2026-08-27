package ai.devflow.agent;

import ai.devflow.orchestrator.RunState;
import ai.devflow.tools.FileTools;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;

import java.util.ArrayList;
import java.util.List;

public class ReviewerAgent implements Agent {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ChatClient chatClient;

    public ReviewerAgent(ChatClient chatClient) { this.chatClient = chatClient; }

    @Override public String name() { return "reviewer"; }

    @Override
    public AgentResult run(RunState state) {
        String changed = state.history().stream()
                .filter(r -> r.agent().equals("coder"))
                .reduce((a, b) -> b)
                .map(r -> String.join("\n", r.filesTouched()))
                .orElse("(none reported)");

        String prompt = """
            Task the coder was given: %s

            Files changed (read them yourself with your tools — they are not included here):
            %s

            Reply with ONLY this JSON:
            {"status":"OK"|"NEEDS_WORK","summary":"...",
             "findings":[{"severity":"LOW"|"MEDIUM"|"HIGH","file":"...","line":0,"message":"..."}]}
            """.formatted(state.task(), changed);

        String raw = chatClient.prompt()
                .user(prompt)
                .tools(new FileTools(state.workspace().guard()))
                .call()
                .content();

        return parse(raw);
    }

    private AgentResult parse(String raw) {
        try {
            String json = raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1);
            JsonNode node = MAPPER.readTree(json);
            String summary = node.path("summary").asText("");

            List<Finding> findings = new ArrayList<>();
            for (JsonNode f : node.path("findings")) {
                findings.add(new Finding(
                        Finding.Origin.REVIEWER,
                        Finding.Severity.valueOf(f.path("severity").asText("MEDIUM")),
                        f.path("file").asText(null),
                        f.has("line") ? f.path("line").asInt() : null,
                        f.path("message").asText("")));
            }

            boolean needsWork = "NEEDS_WORK".equals(node.path("status").asText())
                    || !findings.isEmpty();

            return needsWork
                    ? AgentResult.needsWork(name(), summary, findings, TokenUsage.NONE)
                    : AgentResult.ok(name(), summary, List.of(), TokenUsage.NONE);

        } catch (Exception e) {
            // Fail closed: an unparseable review is never an approval.
            return new AgentResult(name(), AgentResult.Status.FAILED,
                    "Could not parse reviewer output: " + e.getMessage(),
                    List.of(), List.of(), TokenUsage.NONE);
        }
    }
}
