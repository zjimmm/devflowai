package ai.devflow.config;

import ai.devflow.agent.Agent;
import ai.devflow.agent.CoderAgent;
import ai.devflow.agent.ReviewerAgent;
import ai.devflow.event.RunEventPublisher;
import ai.devflow.orchestrator.Orchestrator;
import ai.devflow.orchestrator.RunRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Wires the crew.
 *
 * <p>The agents are singletons and hold no per-run state — everything they
 * need arrives in {@code RunState}, including its {@code GitTools}. That is
 * what makes singleton scope safe here.
 */
@Configuration
public class OrchestrationConfig {

    @Bean
    Agent coderAgent(@Qualifier("coder") ChatClient coderChatClient) {
        return new CoderAgent(coderChatClient);
    }

    @Bean
    Agent reviewerAgent(@Qualifier("reviewer") ChatClient reviewerChatClient) {
        return new ReviewerAgent(reviewerChatClient);
    }

    /** One thread per in-flight run; runs block for minutes at gates. */
    @Bean(destroyMethod = "shutdownNow")
    ExecutorService runExecutor() {
        return Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "devflowai-run");
            t.setDaemon(true);
            return t;
        });
    }

    @Bean
    Orchestrator orchestrator(Agent coderAgent, Agent reviewerAgent, RunEventPublisher events,
                              @Value("${devflowai.review.max-iterations:3}") int maxReviewIterations,
                              @Value("${devflowai.review.max-human-iterations:5}") int maxHumanIterations,
                              @Value("${devflowai.build.timeout-minutes:5}") long buildTimeoutMinutes) {
        return new Orchestrator(coderAgent, reviewerAgent, events,
                maxReviewIterations, maxHumanIterations, Duration.ofMinutes(buildTimeoutMinutes));
    }

    @Bean
    RunRegistry runRegistry(Orchestrator orchestrator, RunEventPublisher events, ExecutorService runExecutor,
                            @Value("${devflowai.fixture.path:src/test/resources/fixture}") String fixturePath,
                            @Value("${devflowai.gate.timeout-minutes:10}") long gateTimeoutMinutes) {
        return new RunRegistry(orchestrator, events, runExecutor,
                Path.of(fixturePath), Duration.ofMinutes(gateTimeoutMinutes));
    }
}
