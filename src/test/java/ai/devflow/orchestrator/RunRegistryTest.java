package ai.devflow.orchestrator;

import ai.devflow.agent.*;
import ai.devflow.event.RunEventPublisher;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class RunRegistryTest {

    RunRegistry registry;

    static class StubAgent implements Agent {
        private final String name;
        StubAgent(String name) { this.name = name; }
        @Override public String name() { return name; }
        @Override public AgentResult run(RunState s) {
            return AgentResult.ok(name, "stub", List.of(), TokenUsage.NONE);
        }
    }

    @BeforeEach
    void setUp() {
        var events = new RunEventPublisher();
        var orchestrator = new Orchestrator(new StubAgent("coder"), new StubAgent("reviewer"),
                events, 3, 5, Duration.ofMinutes(1));
        registry = new RunRegistry(orchestrator, events, Executors.newCachedThreadPool(),
                java.nio.file.Path.of("src/test/resources/fixture"), Duration.ofSeconds(2));
    }

    @Test
    void startingARunGivesItAUniqueIdAndRegistersIt() {
        RunHandle a = registry.start("task one", "fixture");
        RunHandle b = registry.start("task two", "fixture");

        assertThat(a.runId()).isNotBlank();
        assertThat(b.runId()).isNotEqualTo(a.runId());
        assertThat(registry.find(a.runId())).isSameAs(a);
        assertThat(registry.find(b.runId())).isSameAs(b);
    }

    @Test
    void findingAnUnknownRunReturnsNull() {
        assertThat(registry.find("no-such-run")).isNull();
    }

    @Test
    void theHandleExposesTheRunsStateAndGate() {
        RunHandle handle = registry.start("a task", "fixture");
        assertThat(handle.state().task()).isEqualTo("a task");
        assertThat(handle.state().runId()).isEqualTo(handle.runId());
        assertThat(handle.gate()).isNotNull();
    }
}
