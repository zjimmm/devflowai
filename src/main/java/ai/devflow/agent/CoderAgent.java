package ai.devflow.agent;

import ai.devflow.orchestrator.RunState;
import ai.devflow.tools.FileTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;

import java.util.List;
import java.util.stream.Collectors;

public class CoderAgent implements Agent {

    private final ChatClient chatClient;

    public CoderAgent(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override public String name() { return "coder"; }

    @Override
    public AgentResult run(RunState state) {
        String prompt = buildPrompt(state);

        ChatResponse response = chatClient.prompt()
                .user(prompt)
                .tools(new FileTools(state.workspace().guard()), state.gitTools())
                .call()
                .chatResponse();

        String summary = textOf(response);

        // Derived from git, never from what the model claims.
        List<String> touched = state.gitTools().changedFiles();

        return AgentResult.ok(name(), summary, touched, UsageMapper.from(response));
    }

    /** Response text, tolerating a null or empty response rather than throwing. */
    private static String textOf(ChatResponse response) {
        if (response == null || response.getResult() == null
                || response.getResult().getOutput() == null) {
            return "";
        }
        String text = response.getResult().getOutput().getText();
        return text == null ? "" : text;
    }

    private String buildPrompt(RunState state) {
        StringBuilder sb = new StringBuilder();
        sb.append("Task: ").append(state.task()).append("\n\n");

        if (!state.loadedSkills().isEmpty()) {
            sb.append("Relevant knowledge from previous runs on this repository:\n")
              .append(String.join("\n\n", state.loadedSkills()))
              .append("\n\n");
        }

        if (!state.openFindings().isEmpty()) {
            sb.append("Your previous attempt was rejected. Fix these findings:\n")
              .append(state.openFindings().stream()
                      .map(f -> "- [" + f.origin() + "/" + f.severity() + "] "
                              + (f.file() != null ? f.file() + ": " : "") + f.message())
                      .collect(Collectors.joining("\n")))
              .append("\n\n");
        }

        sb.append("Use your tools to read and modify files. Then stop.");
        return sb.toString();
    }
}
