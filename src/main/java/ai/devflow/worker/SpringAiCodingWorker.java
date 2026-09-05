package ai.devflow.worker;

import ai.devflow.agent.UsageMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;

/** The only {@link CodingWorker} this sub-project ships: today's Spring AI ChatClient call, moved behind the seam. */
public class SpringAiCodingWorker implements CodingWorker {

    private final ChatClient chatClient;

    public SpringAiCodingWorker(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    public WorkerResult run(WorkerRequest request) {
        ChatResponse response = chatClient.prompt()
                .user(request.prompt())
                .tools(request.tools().toArray())
                .call()
                .chatResponse();
        return new WorkerResult(textOf(response), UsageMapper.from(response));
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
}
