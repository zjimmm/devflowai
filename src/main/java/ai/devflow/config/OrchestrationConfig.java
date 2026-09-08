package ai.devflow.config;

import ai.devflow.agent.*;
import ai.devflow.event.RunEventPublisher;
import ai.devflow.memory.FileMemoryStore;
import ai.devflow.memory.MemoryStore;
import ai.devflow.orchestrator.DirectExecutor;
import ai.devflow.orchestrator.Orchestrator;
import ai.devflow.orchestrator.RunExecutor;
import ai.devflow.orchestrator.RunRegistry;
import ai.devflow.policy.ConfigurablePolicyEngine;
import ai.devflow.policy.PolicyEngine;
import ai.devflow.skill.FileSkillStore;
import ai.devflow.skill.SkillStore;
import ai.devflow.tools.GitHubApiClient;
import ai.devflow.tools.GitHubClient;
import ai.devflow.worker.CodingWorker;
import ai.devflow.worker.SpringAiCodingWorker;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

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

    @Bean @Qualifier("coder")
    CodingWorker coderWorker(@Qualifier("coder") ChatClient coderChatClient) {
        return new SpringAiCodingWorker(coderChatClient);
    }

    @Bean
    Agent coderAgent(@Qualifier("coder") CodingWorker coderWorker) {
        return new CoderAgent(coderWorker);
    }

    @Bean @Qualifier("reviewer")
    CodingWorker reviewerWorker(@Qualifier("reviewer") ChatClient reviewerChatClient) {
        return new SpringAiCodingWorker(reviewerChatClient);
    }

    @Bean
    Agent reviewerAgent(@Qualifier("reviewer") CodingWorker reviewerWorker) {
        return new ReviewerAgent(reviewerWorker);
    }

    @Bean @Qualifier("planner")
    CodingWorker plannerWorker(@Qualifier("planner") ChatClient plannerChatClient) {
        return new SpringAiCodingWorker(plannerChatClient);
    }

    @Bean
    Agent plannerAgent(@Qualifier("planner") CodingWorker plannerWorker) {
        return new PlannerAgent(plannerWorker);
    }

    @Bean
    SkillPicker skillPickerAgent(@Qualifier("router") ChatClient routerChatClient) {
        return new SkillPickerAgent(routerChatClient);
    }

    @Bean
    Scribe scribeAgent(@Qualifier("scribe") ChatClient scribeChatClient) {
        return new ScribeAgent(scribeChatClient);
    }

    @Bean
    PolicyEngine policyEngine(@Value("${devflowai.policy.require-build-pass:true}") boolean requireBuildPass) {
        return new ConfigurablePolicyEngine(requireBuildPass);
    }

    /** Host-side, keyed by repo (spec §6.4) -- fixed for the bundled fixture; Phase 6's ClonedWorkspace will need a slug derived from the repo URL. */
    @Bean
    SkillStore skillStore() {
        return new FileSkillStore(Path.of(System.getProperty("user.home"), ".devflowai", "skills"));
    }

    @Bean
    MemoryStore memoryStore() {
        return new FileMemoryStore(Path.of(System.getProperty("user.home"), ".devflowai", "memory"));
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

    /** Token read directly from the environment, matching ANTHROPIC_API_KEY's own pattern -- never a tracked file. */
    @Bean
    GitHubClient gitHubClient() {
        String token = System.getenv("DEVFLOWAI_GITHUB_TOKEN");
        return new GitHubApiClient(token, RestClient.builder().baseUrl("https://api.github.com").build());
    }

    @Bean
    Orchestrator orchestrator(Agent coderAgent, Agent reviewerAgent, Agent plannerAgent, SkillPicker skillPicker, Scribe scribe,
                              SkillStore skillStore, MemoryStore memoryStore, RunEventPublisher events,
                              @Value("${devflowai.review.max-iterations:3}") int maxReviewIterations,
                              @Value("${devflowai.review.max-human-iterations:5}") int maxHumanIterations,
                              @Value("${devflowai.build.timeout-minutes:5}") long buildTimeoutMinutes,
                              PolicyEngine policyEngine, GitHubClient gitHubClient) {
        return new Orchestrator(coderAgent, reviewerAgent, plannerAgent, skillPicker, scribe, skillStore, memoryStore,
                events, maxReviewIterations, maxHumanIterations, Duration.ofMinutes(buildTimeoutMinutes), policyEngine,
                gitHubClient);
    }

    @Bean
    RunExecutor directExecutor(Agent coderAgent, RunEventPublisher events,
                              @Value("${devflowai.build.timeout-minutes:5}") long buildTimeoutMinutes,
                              GitHubClient gitHubClient) {
        return new DirectExecutor(coderAgent, events, Duration.ofMinutes(buildTimeoutMinutes), gitHubClient);
    }

    @Bean
    RunRegistry runRegistry(Orchestrator orchestrator, RunExecutor directExecutor, RunEventPublisher events, ExecutorService runExecutor,
                            @Value("${devflowai.fixture.path:src/test/resources/fixture}") String fixturePath,
                            @Value("${devflowai.gate.timeout-minutes:10}") long gateTimeoutMinutes,
                            @Value("${devflowai.clone.timeout-minutes:2}") long cloneTimeoutMinutes) {
        return new RunRegistry(orchestrator, directExecutor, events, runExecutor,
                Path.of(fixturePath), Duration.ofMinutes(gateTimeoutMinutes),
                Duration.ofMinutes(cloneTimeoutMinutes));
    }
}
