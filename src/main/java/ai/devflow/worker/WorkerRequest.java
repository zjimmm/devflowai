package ai.devflow.worker;

import java.util.List;

/**
 * What a {@link CodingWorker} needs: a fully-built prompt and the tool
 * objects Spring AI's {@code ChatClient.tools(...)} should expose. A future
 * CLI-shelling worker would use {@code prompt} as its instructions and
 * simply ignore {@code tools} — see this plan's design decision 3.
 */
public record WorkerRequest(String prompt, List<Object> tools) {
    public WorkerRequest {
        tools = List.copyOf(tools);
    }
}
