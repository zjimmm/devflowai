# Evaluation Mode Strategy Comparison Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Strategy A ("Direct" — a bare, non-orchestrated coder pass) alongside the existing Strategy B ("Orchestrated") pipeline, so an operator can run the same task both ways and compare them on metrics already persisted since Sub-project 1.

**Architecture:** A new `RunExecutor` interface with `Orchestrator` as one implementation (zero behavior change) and a new, much simpler `DirectExecutor` as the other. `RunRegistry` picks between them per run. `SdlcRun` gains two columns (`strategy`, `buildSucceeded`); everything else the comparison needs (iterations, findings, corrections) is already query-able from existing tables with zero new schema.

**Tech Stack:** Spring Boot 4.1.1, Java 21, JUnit 5, AssertJ, Mockito.

**Spec:** `docs/superpowers/specs/2026-09-06-sdlc-evaluation-mode-design.md`

## Global Constraints

- JDK 21 at `/opt/homebrew/opt/openjdk@21`, not on PATH — `export JAVA_HOME=/opt/homebrew/opt/openjdk@21` before every `./gradlew` invocation.
- Strategy A uses the exact same `coderAgent` bean (Sonnet 5 + `XHIGH`) Strategy B's coder uses — never a different model, or the comparison measures a confounded variable instead of orchestration's effect.
- No dollar-cost conversion, no benchmark/hidden-test harness, no new dashboard beyond two table columns (spec §6) — these are explicit non-goals for this sub-project.
- The empty-changeset-is-never-approved non-negotiable applies to Strategy A too, using the exact same check and message Strategy B uses.
- This repo is public — no API key or secret in any tracked file.

## Design decisions this plan makes

- **`StartRunRequest` gets a second, 2-arg constructor delegating to the new 3-arg one**, exactly mirroring the established `RunState` 3-arg-delegates-to-4-arg pattern from Sub-project 1. This means every existing `new StartRunRequest(task, repo)` call site across `RunControllerTest`/`RunFlowIntegrationTest` needs zero changes — the spec's claim that existing callers are unaffected only actually holds for Java call sites (not just HTTP/JSON ones) with this constructor shape; a bare 3-arg record replacing the 2-arg one would have broken every direct construction.
- **`RunRegistry`'s existing `Orchestrator orchestrator` field/constructor-param keeps its concrete type**, not renamed to `RunExecutor`. Only the new `directExecutor` param is typed `RunExecutor`. This avoids renaming an existing field for a cosmetic-only gain.
- **Spring resolves the new `RunExecutor directExecutor` bean parameter by parameter-name-to-bean-name matching**, since `Orchestrator` (already a `RunExecutor` once Task 1 lands) and the new `DirectExecutor` bean are both candidates of type `RunExecutor` in the context. This is the exact same mechanism already relied on for the `orchestrator` bean method's three same-typed `Agent` parameters (`coderAgent`/`reviewerAgent`/`plannerAgent`) — not a new pattern.

## File Structure

**New:**
- `src/main/java/ai/devflow/orchestrator/RunExecutor.java` — interface
- `src/main/java/ai/devflow/orchestrator/RunStrategy.java` — enum, `ORCHESTRATED` / `DIRECT`
- `src/main/java/ai/devflow/orchestrator/DirectExecutor.java` — Strategy A
- `src/test/java/ai/devflow/orchestrator/DirectExecutorTest.java`

**Modified:**
- `Orchestrator.java` — `implements RunExecutor` (one line; no method changes)
- `SdlcRun.java` — `strategy`/`buildSucceeded` fields, constructor gains `strategy`, new `recordBuildResult(boolean)`
- `SdlcRunRecorder.java` — `maybeCreateRun` reads `strategy`; new `maybeRecordBuildResult`
- `RunSummary.java` — `strategy`/`buildSucceeded` fields
- `StartRunRequest.java` — new 3-arg constructor + delegating 2-arg one
- `RunController.java` — parses `strategy`, passes it to `RunRegistry.start(...)`
- `RunRegistry.java` — `start(...)` gains a `RunStrategy` parameter; constructor gains `RunExecutor directExecutor`; the workspace-prepare-failure event gains `strategy`
- `OrchestrationConfig.java` — new `directExecutor` bean; `runRegistry` bean threads it through
- `src/main/resources/static/index.html` — a strategy dropdown on the start form; two new past-runs table columns
- Test files: `HistoryRepositoriesTest`, `SdlcRunRecorderTest`, `RunControllerTest`, `RunRegistryTest`, `StaticPageTest` — see individual tasks

---

# Task 1: `RunExecutor` interface, `RunStrategy` enum, `DirectExecutor`

**Files:**
- Create: `src/main/java/ai/devflow/orchestrator/RunExecutor.java`
- Create: `src/main/java/ai/devflow/orchestrator/RunStrategy.java`
- Create: `src/main/java/ai/devflow/orchestrator/DirectExecutor.java`
- Modify: `src/main/java/ai/devflow/orchestrator/Orchestrator.java`
- Test: `src/test/java/ai/devflow/orchestrator/DirectExecutorTest.java`

**Interfaces:**
- Produces: `RunExecutor { Orchestrator.RunOutcome run(RunState state, ApprovalGate gate); }`; `RunStrategy { ORCHESTRATED, DIRECT }`; `DirectExecutor(Agent coder, RunEventPublisher events, Duration buildTimeout) implements RunExecutor`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/ai/devflow/orchestrator/DirectExecutorTest.java`:

```java
package ai.devflow.orchestrator;

import ai.devflow.agent.Agent;
import ai.devflow.agent.AgentResult;
import ai.devflow.agent.TokenUsage;
import ai.devflow.event.RunEventPublisher;
import ai.devflow.workspace.FixtureWorkspace;
import ai.devflow.workspace.Workspace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DirectExecutorTest {

    Workspace workspace;
    RunEventPublisher events;

    @BeforeEach
    void setUp() throws Exception {
        workspace = new FixtureWorkspace(Path.of("src/test/resources/fixture"), "direct-test");
        workspace.prepare();
        events = new RunEventPublisher();
    }

    @AfterEach
    void tearDown() throws Exception { workspace.cleanup(); }

    static class ScriptedAgent implements Agent {
        private final String name;
        private final AgentResult result;
        int calls = 0;
        ScriptedAgent(String name, AgentResult result) { this.name = name; this.result = result; }
        @Override public String name() { return name; }
        @Override public AgentResult run(RunState s) { calls++; return result; }
    }

    /** Writes a real file so changedFiles() is non-empty, as a real coder would. */
    private ScriptedAgent writingCoder(String summary) {
        return new ScriptedAgent("coder", AgentResult.ok("coder", summary, List.of("scratch.txt"), TokenUsage.NONE)) {
            @Override public AgentResult run(RunState s) {
                try {
                    Files.writeString(s.workspace().root().resolve("scratch.txt"), "written " + calls);
                } catch (Exception e) { throw new RuntimeException(e); }
                return super.run(s);
            }
        };
    }

    private DirectExecutor executor(Agent coder) {
        return new DirectExecutor(coder, events, Duration.ofMinutes(1));
    }

    @Test
    void aSuccessfulCoderRunCommitsAndReachesDone() throws Exception {
        var coder = writingCoder("done");
        var state = new RunState("d1", "add a class", workspace);
        var gate = new ApprovalGate(Duration.ofSeconds(10));

        var outcome = executor(coder).run(state, gate);

        assertThat(outcome.approved()).isTrue();
        assertThat(coder.calls).isEqualTo(1);
        assertThat(state.phase()).isEqualTo(RunPhase.DONE);
    }

    @Test
    void noGateIsEverAwaited() throws Exception {
        var coder = writingCoder("done");
        var state = new RunState("d2", "add a class", workspace);
        var gate = new ApprovalGate(Duration.ofSeconds(10));

        executor(coder).run(state, gate);

        assertThat(gate.pending()).isNull();
    }

    @Test
    void anEmptyChangesetIsNeverApproved() throws Exception {
        var idleCoder = new ScriptedAgent("coder", AgentResult.ok("coder", "did nothing", List.of(), TokenUsage.NONE));
        var state = new RunState("d3", "t", workspace);
        var gate = new ApprovalGate(Duration.ofSeconds(10));

        var outcome = executor(idleCoder).run(state, gate);

        assertThat(outcome.approved()).isFalse();
        assertThat(outcome.reason()).containsIgnoringCase("no changes");
    }

    @Test
    void aFailedCoderResultEndsTheRunWithoutCrashing() throws Exception {
        var failingCoder = new ScriptedAgent("coder",
                new AgentResult("coder", AgentResult.Status.FAILED, "boom", List.of(), List.of(), TokenUsage.NONE));
        var state = new RunState("d4", "t", workspace);
        var gate = new ApprovalGate(Duration.ofSeconds(10));

        var outcome = executor(failingCoder).run(state, gate);

        assertThat(outcome.approved()).isFalse();
        assertThat(outcome.reason()).contains("Coder failed");
    }

    @Test
    void workspaceIsCleanedUpOnEveryExitPath() throws Exception {
        var coder = writingCoder("done");
        var state = new RunState("d5", "t", workspace);
        var gate = new ApprovalGate(Duration.ofSeconds(10));

        executor(coder).run(state, gate);

        assertThat(workspace.root()).doesNotExist();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests '*DirectExecutorTest*'`
Expected: compilation failure — `DirectExecutor` does not exist yet.

- [ ] **Step 3: Create `RunExecutor` and `RunStrategy`**

`src/main/java/ai/devflow/orchestrator/RunExecutor.java`:

```java
package ai.devflow.orchestrator;

/**
 * The shape any top-level run strategy implements. {@link Orchestrator}
 * (Strategy B, the full pipeline) and {@link DirectExecutor} (Strategy A, a
 * bare coder pass) are the two implementations {@link RunRegistry} dispatches
 * between (spec §22).
 */
public interface RunExecutor {
    Orchestrator.RunOutcome run(RunState state, ApprovalGate gate);
}
```

`src/main/java/ai/devflow/orchestrator/RunStrategy.java`:

```java
package ai.devflow.orchestrator;

/** Which RunExecutor a run uses — Strategy B (default) or Strategy A (spec §22). */
public enum RunStrategy {
    ORCHESTRATED,
    DIRECT
}
```

- [ ] **Step 4: `Orchestrator implements RunExecutor`**

Read `src/main/java/ai/devflow/orchestrator/Orchestrator.java` first. Change only the class declaration:

```java
public class Orchestrator {
```
to:
```java
public class Orchestrator implements RunExecutor {
```

No other change to this file. Its existing `public RunOutcome run(RunState state, ApprovalGate gate)` method already satisfies `RunExecutor.run(RunState, ApprovalGate)` exactly — `RunOutcome` unqualified inside `Orchestrator`'s own body already means `Orchestrator.RunOutcome`.

- [ ] **Step 5: Create `DirectExecutor`**

`src/main/java/ai/devflow/orchestrator/DirectExecutor.java`:

```java
package ai.devflow.orchestrator;

import ai.devflow.agent.Agent;
import ai.devflow.agent.AgentResult;
import ai.devflow.event.RunEvent;
import ai.devflow.event.RunEventPublisher;
import ai.devflow.tools.BuildTools;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Strategy A (spec §22): task -> coder -> build (metrics only) -> commit.
 * Fully autonomous — no planner, no reviewer, no gates, no learning loop.
 * Deliberately does not share Orchestrator's private emit/cleanUp helpers:
 * two small helpers do not justify coupling two independent, simple
 * executors before there's a third one to justify the abstraction.
 */
public class DirectExecutor implements RunExecutor {

    private final Agent coder;
    private final RunEventPublisher events;
    private final Duration buildTimeout;

    public DirectExecutor(Agent coder, RunEventPublisher events, Duration buildTimeout) {
        this.coder = coder;
        this.events = events;
        this.buildTimeout = buildTimeout;
    }

    @Override
    public Orchestrator.RunOutcome run(RunState state, ApprovalGate gate) {
        String runId = state.runId();
        try {
            return execute(state);
        } catch (RuntimeException e) {
            state.setPhase(RunPhase.FAILED);
            String reason = "Run failed: " + e;
            events.publish(runId, RunEvent.of("error", reason));
            return new Orchestrator.RunOutcome(false, reason, state);
        } finally {
            cleanUp(state);
            events.complete(runId);
        }
    }

    private Orchestrator.RunOutcome execute(RunState state) {
        emit(state, "step", "Workspace ready — branch " + state.workspace().branchName(),
                Map.of("branch", state.workspace().branchName(),
                       "task", state.task(),
                       "repoSlug", state.repoSlug(),
                       "strategy", RunStrategy.DIRECT.name()));

        state.setPhase(RunPhase.CODING);
        emit(state, "step", "Coder — working…", Map.of());
        AgentResult coded = coder.run(state);
        state.record(coded);
        emit(state, "step", "Coder — " + coded.summary(), Map.of("filesTouched", coded.filesTouched()));

        if (coded.status() == AgentResult.Status.FAILED) {
            return failed(state, "Coder failed: " + coded.summary());
        }

        List<String> changed = state.gitTools().changedFiles();
        if (changed.isEmpty()) {
            return failed(state, "Refusing to approve: the coder made no changes");
        }

        state.setPhase(RunPhase.BUILDING);
        emit(state, "step", "Running the target repository's build…", Map.of());
        var build = new BuildTools(state.workspace(), buildTimeout).build("test");
        emit(state, "step", build.success() ? "Build passed" : "Build failed",
                Map.of("success", build.success()));

        state.setPhase(RunPhase.COMMITTING);
        String committed = state.gitTools().commit("devflowai (direct): " + state.task());
        emit(state, "step", committed, Map.of());

        state.setPhase(RunPhase.DONE);
        emit(state, "done", "Direct run committed on " + state.workspace().branchName(),
                Map.of("branch", state.workspace().branchName(),
                       "inputTokens", state.totalTokens().input(),
                       "outputTokens", state.totalTokens().output()));
        return new Orchestrator.RunOutcome(true, "Direct run committed, no review", state);
    }

    private Orchestrator.RunOutcome failed(RunState state, String reason) {
        state.setPhase(RunPhase.FAILED);
        emit(state, "error", reason, Map.of(
                "inputTokens", state.totalTokens().input(),
                "outputTokens", state.totalTokens().output()));
        return new Orchestrator.RunOutcome(false, reason, state);
    }

    private void emit(RunState state, String type, String message, Map<String, Object> data) {
        Map<String, Object> withPhase = new HashMap<>(data);
        withPhase.put("phase", state.phase().name());
        events.publish(state.runId(), RunEvent.of(type, message, withPhase));
    }

    private void cleanUp(RunState state) {
        try {
            state.workspace().cleanup();
        } catch (Exception e) {
            events.publish(state.runId(),
                    RunEvent.of("warn", "Workspace cleanup failed: " + e.getMessage()));
        }
    }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests '*DirectExecutorTest*'`
Expected: PASS (5 tests).

- [ ] **Step 7: Run the full suite**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test`
Expected: PASS. `Orchestrator implements RunExecutor` is a purely additive class-declaration change — every existing `OrchestratorTest`/`OrchestrationConfigTest` case is unaffected.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/ai/devflow/orchestrator/RunExecutor.java src/main/java/ai/devflow/orchestrator/RunStrategy.java \
        src/main/java/ai/devflow/orchestrator/DirectExecutor.java src/main/java/ai/devflow/orchestrator/Orchestrator.java \
        src/test/java/ai/devflow/orchestrator/DirectExecutorTest.java
git commit -m "feat: add RunExecutor/RunStrategy and DirectExecutor for Strategy A"
```

---

# Task 2: `SdlcRun` gains `strategy`/`buildSucceeded`

**Files:**
- Modify: `src/main/java/ai/devflow/history/SdlcRun.java`
- Modify: `src/main/java/ai/devflow/history/SdlcRunRecorder.java`
- Modify: `src/main/java/ai/devflow/web/RunSummary.java`
- Modify: `src/test/java/ai/devflow/history/HistoryRepositoriesTest.java`
- Modify: `src/test/java/ai/devflow/history/SdlcRunRecorderTest.java`
- Modify: `src/test/java/ai/devflow/web/RunControllerTest.java`

**Interfaces:**
- Consumes: `RunStrategy` (Task 1).
- Produces: `SdlcRun(String id, String task, String repoSlug, RunStrategy strategy, Instant startedAt)`; `SdlcRun.strategy()`/`buildSucceeded()`/`recordBuildResult(boolean)`; `RunSummary` gains `strategy`/`buildSucceeded` fields.

- [ ] **Step 1: Write the failing tests**

Read `src/test/java/ai/devflow/history/HistoryRepositoriesTest.java` first (shown in full below — this is the whole file before this task). Add the import `import ai.devflow.orchestrator.RunStrategy;`. Change every existing `new SdlcRun(...)` call to insert `RunStrategy.ORCHESTRATED` before the `Instant` argument:

```java
    @Test
    void savesAndReadsBackASdlcRun() {
        var run = new SdlcRun("r1", "add validation", "fixture", RunStrategy.ORCHESTRATED, Instant.now());
        runs.save(run);

        var found = runs.findById("r1").orElseThrow();
        assertThat(found.task()).isEqualTo("add validation");
        assertThat(found.status()).isEqualTo(SdlcRunStatus.RUNNING);
        assertThat(found.strategy()).isEqualTo(RunStrategy.ORCHESTRATED);
        assertThat(found.buildSucceeded()).isNull();

        found.recordBuildResult(true);
        found.finish(SdlcRunStatus.DONE, "Approved", Instant.now(), 100, 50);
        runs.save(found);
        var reloaded = runs.findById("r1").orElseThrow();
        assertThat(reloaded.status()).isEqualTo(SdlcRunStatus.DONE);
        assertThat(reloaded.buildSucceeded()).isTrue();
    }

    @Test
    void findTop50OrdersByStartedAtDescending() {
        runs.save(new SdlcRun("older", "t", "fixture", RunStrategy.ORCHESTRATED, Instant.now().minusSeconds(60)));
        runs.save(new SdlcRun("newer", "t", "fixture", RunStrategy.DIRECT, Instant.now()));

        assertThat(runs.findTop50ByOrderByStartedAtDesc())
                .extracting(SdlcRun::id).containsExactly("newer", "older");
    }
```
Leave every other test in the file unchanged.

Read `src/test/java/ai/devflow/history/SdlcRunRecorderTest.java` first. Add two new tests (anywhere after the existing `aStartEventCreatesARunningSdlcRun` test):

```java
    @Test
    void aStartEventWithStrategyCreatesARunWithThatStrategy() {
        recorder.onRunRecorded(new RunRecorded("r11", new RunEvent("step", "Workspace ready",
                Map.of("task", "t", "repoSlug", "fixture", "phase", "PREPARING", "strategy", "DIRECT"))));

        var run = runs.findById("r11").orElseThrow();
        assertThat(run.strategy()).isEqualTo(ai.devflow.orchestrator.RunStrategy.DIRECT);
    }

    @Test
    void aBuildStepEventRecordsBuildSucceededAndTheLatestOneWins() {
        recorder.onRunRecorded(new RunRecorded("r12", new RunEvent("step", "start",
                Map.of("task", "t", "repoSlug", "fixture", "phase", "PREPARING", "strategy", "ORCHESTRATED"))));

        recorder.onRunRecorded(new RunRecorded("r12", new RunEvent("step", "Build failed",
                Map.of("phase", "BUILDING", "success", false))));
        assertThat(runs.findById("r12").orElseThrow().buildSucceeded()).isFalse();

        recorder.onRunRecorded(new RunRecorded("r12", new RunEvent("step", "Build passed",
                Map.of("phase", "BUILDING", "success", true))));
        assertThat(runs.findById("r12").orElseThrow().buildSucceeded()).isTrue();
    }
```

Read `src/test/java/ai/devflow/web/RunControllerTest.java` first. In `historyReturnsRunsMostRecentFirst`, change the two `new ai.devflow.history.SdlcRun(...)` constructions to add the strategy argument, and add assertions on the new JSON fields:

```java
    @Test
    void historyReturnsRunsMostRecentFirst() throws Exception {
        var newer = new ai.devflow.history.SdlcRun("newer", "add a class", "fixture",
                ai.devflow.orchestrator.RunStrategy.ORCHESTRATED, java.time.Instant.now());
        var older = new ai.devflow.history.SdlcRun("older", "fix a bug", "fixture",
                ai.devflow.orchestrator.RunStrategy.DIRECT, java.time.Instant.now().minusSeconds(60));
        when(history.findTop50ByOrderByStartedAtDesc()).thenReturn(java.util.List.of(newer, older));

        mvc.perform(get("/api/runs/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].runId").value("newer"))
                .andExpect(jsonPath("$[0].task").value("add a class"))
                .andExpect(jsonPath("$[0].status").value("RUNNING"))
                .andExpect(jsonPath("$[0].strategy").value("ORCHESTRATED"))
                .andExpect(jsonPath("$[1].runId").value("older"))
                .andExpect(jsonPath("$[1].strategy").value("DIRECT"));
    }
```

- [ ] **Step 2: Run to verify the tests fail**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests '*HistoryRepositoriesTest*' --tests '*SdlcRunRecorderTest*' --tests '*RunControllerTest*'`
Expected: compilation failure — `SdlcRun`'s constructor does not take a `RunStrategy` yet, `strategy()`/`buildSucceeded()`/`recordBuildResult(...)` do not exist.

- [ ] **Step 3: Update `SdlcRun`**

Read `src/main/java/ai/devflow/history/SdlcRun.java` first (shown in full below — the whole file before this task):

```java
package ai.devflow.history;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "sdlc_run")
public class SdlcRun {

    @Id
    private String id;

    @Column(length = 10_000, nullable = false)
    private String task;

    @Column(nullable = false)
    private String repoSlug;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SdlcRunStatus status;

    @Column(length = 2_000)
    private String reason;

    @Column(nullable = false)
    private Instant startedAt;

    private Instant finishedAt;

    private long inputTokens;
    private long outputTokens;

    protected SdlcRun() {} // JPA

    public SdlcRun(String id, String task, String repoSlug, Instant startedAt) {
        this.id = id;
        this.task = task;
        this.repoSlug = repoSlug;
        this.status = SdlcRunStatus.RUNNING;
        this.startedAt = startedAt;
    }

    public String id() { return id; }
    public String task() { return task; }
    public String repoSlug() { return repoSlug; }
    public SdlcRunStatus status() { return status; }
    public String reason() { return reason; }
    public Instant startedAt() { return startedAt; }
    public Instant finishedAt() { return finishedAt; }
    public long inputTokens() { return inputTokens; }
    public long outputTokens() { return outputTokens; }

    public void finish(SdlcRunStatus status, String reason, Instant finishedAt, long inputTokens, long outputTokens) {
        this.status = status;
        this.reason = reason;
        this.finishedAt = finishedAt;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
    }
}
```

Change it to:

```java
package ai.devflow.history;

import ai.devflow.orchestrator.RunStrategy;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "sdlc_run")
public class SdlcRun {

    @Id
    private String id;

    @Column(length = 10_000, nullable = false)
    private String task;

    @Column(nullable = false)
    private String repoSlug;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RunStrategy strategy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SdlcRunStatus status;

    @Column(length = 2_000)
    private String reason;

    @Column(nullable = false)
    private Instant startedAt;

    private Instant finishedAt;

    private long inputTokens;
    private long outputTokens;

    private Boolean buildSucceeded;

    protected SdlcRun() {} // JPA

    public SdlcRun(String id, String task, String repoSlug, RunStrategy strategy, Instant startedAt) {
        this.id = id;
        this.task = task;
        this.repoSlug = repoSlug;
        this.strategy = strategy;
        this.status = SdlcRunStatus.RUNNING;
        this.startedAt = startedAt;
    }

    public String id() { return id; }
    public String task() { return task; }
    public String repoSlug() { return repoSlug; }
    public RunStrategy strategy() { return strategy; }
    public SdlcRunStatus status() { return status; }
    public String reason() { return reason; }
    public Instant startedAt() { return startedAt; }
    public Instant finishedAt() { return finishedAt; }
    public long inputTokens() { return inputTokens; }
    public long outputTokens() { return outputTokens; }
    public Boolean buildSucceeded() { return buildSucceeded; }

    public void finish(SdlcRunStatus status, String reason, Instant finishedAt, long inputTokens, long outputTokens) {
        this.status = status;
        this.reason = reason;
        this.finishedAt = finishedAt;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
    }

    public void recordBuildResult(boolean succeeded) {
        this.buildSucceeded = succeeded;
    }
}
```

- [ ] **Step 4: Update `SdlcRunRecorder`**

Read `src/main/java/ai/devflow/history/SdlcRunRecorder.java` first. Add the import:

```java
import ai.devflow.orchestrator.RunStrategy;
```

Change `maybeCreateRun`:

```java
    private void maybeCreateRun(String runId, Map<String, Object> data) {
        if (data.get("task") instanceof String task
                && data.get("repoSlug") instanceof String repoSlug
                && data.get("strategy") instanceof String strategyName
                && runs.findById(runId).isEmpty()) {
            runs.save(new SdlcRun(runId, task, repoSlug, RunStrategy.valueOf(strategyName), Instant.now()));
        }
    }
```

Add a new method:

```java
    private void maybeRecordBuildResult(String runId, Map<String, Object> data) {
        if (!(data.get("success") instanceof Boolean succeeded)) return;
        runs.findById(runId).ifPresent(run -> {
            run.recordBuildResult(succeeded);
            runs.save(run);
        });
    }
```

Call it from `onRunRecorded`, inside the existing `try` block, alongside the other `maybeXxx` calls:

```java
            maybeCreateRun(runId, data);
            maybeRecordStage(runId, data);
            maybeRecordFindings(runId, data);
            maybeRecordBuildResult(runId, data);
            maybeFinishRun(runId, recorded.event().type(), recorded.event().message(), data);
```

- [ ] **Step 5: Update `RunSummary`**

Read `src/main/java/ai/devflow/web/RunSummary.java` first (shown in full below — the whole file before this task):

```java
package ai.devflow.web;

import ai.devflow.history.SdlcRun;

import java.time.Instant;

public record RunSummary(String runId, String task, String repoSlug, String status, String reason,
                          Instant startedAt, Instant finishedAt, long inputTokens, long outputTokens) {

    public static RunSummary from(SdlcRun run) {
        return new RunSummary(run.id(), run.task(), run.repoSlug(), run.status().name(), run.reason(),
                run.startedAt(), run.finishedAt(), run.inputTokens(), run.outputTokens());
    }
}
```

Change it to:

```java
package ai.devflow.web;

import ai.devflow.history.SdlcRun;

import java.time.Instant;

public record RunSummary(String runId, String task, String repoSlug, String status, String reason,
                          Instant startedAt, Instant finishedAt, long inputTokens, long outputTokens,
                          String strategy, Boolean buildSucceeded) {

    public static RunSummary from(SdlcRun run) {
        return new RunSummary(run.id(), run.task(), run.repoSlug(), run.status().name(), run.reason(),
                run.startedAt(), run.finishedAt(), run.inputTokens(), run.outputTokens(),
                run.strategy() == null ? null : run.strategy().name(), run.buildSucceeded());
    }
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests '*HistoryRepositoriesTest*' --tests '*SdlcRunRecorderTest*' --tests '*RunControllerTest*'`
Expected: PASS (all tests in all three files, old and new).

- [ ] **Step 7: Run the full suite**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test`
Expected: PASS. Hibernate's `ddl-auto: update` adds the two new `sdlc_run` columns automatically; no migration needed.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/ai/devflow/history/SdlcRun.java src/main/java/ai/devflow/history/SdlcRunRecorder.java \
        src/main/java/ai/devflow/web/RunSummary.java \
        src/test/java/ai/devflow/history/HistoryRepositoriesTest.java src/test/java/ai/devflow/history/SdlcRunRecorderTest.java \
        src/test/java/ai/devflow/web/RunControllerTest.java
git commit -m "feat: persist strategy and build result on SdlcRun"
```

---

# Task 3: `RunRegistry`/`StartRunRequest`/`RunController`/`OrchestrationConfig` wiring

**Files:**
- Modify: `src/main/java/ai/devflow/web/StartRunRequest.java`
- Modify: `src/main/java/ai/devflow/web/RunController.java`
- Modify: `src/main/java/ai/devflow/orchestrator/RunRegistry.java`
- Modify: `src/main/java/ai/devflow/config/OrchestrationConfig.java`
- Modify: `src/test/java/ai/devflow/web/RunControllerTest.java`
- Modify: `src/test/java/ai/devflow/orchestrator/RunRegistryTest.java`
- Modify: `src/test/java/ai/devflow/web/RunFlowIntegrationTest.java`

**Interfaces:**
- Consumes: `RunStrategy`, `RunExecutor`, `DirectExecutor` (Task 1).
- Produces: `StartRunRequest(String task, String repo, String strategy)` + a delegating `StartRunRequest(String task, String repo)`; `RunRegistry.start(String task, String repo, RunStrategy strategy)`; `RunRegistry`'s constructor gains a `RunExecutor directExecutor` parameter (5th position, after `RunEventPublisher events`).

- [ ] **Step 1: Update `StartRunRequest`**

Read `src/main/java/ai/devflow/web/StartRunRequest.java` first (currently: `public record StartRunRequest(String task, String repo) {}`). Change it to:

```java
package ai.devflow.web;

/**
 * @param repo "fixture" today, or an https:// git URL (Phase 6).
 * @param strategy "orchestrated" (default) or "direct" (spec §22) — null or
 *                 blank defaults to orchestrated. The 2-arg constructor
 *                 below exists so every pre-existing caller (tests included)
 *                 that constructs this directly with just task/repo keeps
 *                 compiling unmodified.
 */
public record StartRunRequest(String task, String repo, String strategy) {
    public StartRunRequest(String task, String repo) {
        this(task, repo, null);
    }
}
```

- [ ] **Step 2: Write the failing `RunControllerTest` case**

Read `src/test/java/ai/devflow/web/RunControllerTest.java` first (Task 2's version). Add the import `import ai.devflow.orchestrator.RunStrategy;`. Update `startingARunReturnsItsId`'s stubbing/verification to the new 3-arg `registry.start(...)` signature:

```java
    @Test
    void startingARunReturnsItsId() throws Exception {
        when(registry.start(any(), any(), any())).thenReturn(handleFor("abc123"));

        mvc.perform(post("/api/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new StartRunRequest("do a thing", "fixture"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value("abc123"));

        verify(registry).start("do a thing", "fixture", RunStrategy.ORCHESTRATED);
    }
```

Update `startingARunRejectsARepoStringThatIsNotAnHttpsUrl`'s stub the same way (3-arg `any()`):

```java
    @Test
    void startingARunRejectsARepoStringThatIsNotAnHttpsUrl() throws Exception {
        when(registry.start(any(), any(), any()))
                .thenThrow(new IllegalArgumentException("Repo must be an https:// URL, got: ext::sh -c \"true\""));

        mvc.perform(post("/api/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new StartRunRequest("do a thing", "ext::sh -c \"true\""))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Repo must be an https:// URL, got: ext::sh -c \"true\""));
    }
```

Add a new test proving the `"direct"` string routes correctly:

```java
    @Test
    void startingARunWithDirectStrategyPassesItThrough() throws Exception {
        when(registry.start(any(), any(), any())).thenReturn(handleFor("direct1"));

        mvc.perform(post("/api/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new StartRunRequest("do a thing", "fixture", "direct"))))
                .andExpect(status().isOk());

        verify(registry).start("do a thing", "fixture", RunStrategy.DIRECT);
    }
```

- [ ] **Step 3: Run to verify it fails**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests '*RunControllerTest*'`
Expected: compilation failure — `RunRegistry.start(...)` does not take a 3rd argument yet.

- [ ] **Step 4: Update `RunController`**

Read `src/main/java/ai/devflow/web/RunController.java` first. Add the import:

```java
import ai.devflow.orchestrator.RunStrategy;
```

Change `start`:

```java
    @PostMapping
    public ResponseEntity<Map<String, String>> start(@RequestBody StartRunRequest request) {
        if (request.task() == null || request.task().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "task must not be blank"));
        }
        String rawRepo = request.repo() == null ? "" : request.repo().trim();
        String repo = rawRepo.isBlank() ? "fixture" : rawRepo;
        String rawStrategy = request.strategy() == null ? "" : request.strategy().trim();
        RunStrategy strategy = "direct".equalsIgnoreCase(rawStrategy) ? RunStrategy.DIRECT : RunStrategy.ORCHESTRATED;
        RunHandle handle;
        try {
            handle = registry.start(request.task().trim(), repo, strategy);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
        return ResponseEntity.ok(Map.of("runId", handle.runId()));
    }
```

- [ ] **Step 5: Update `RunRegistry`**

Read `src/main/java/ai/devflow/orchestrator/RunRegistry.java` first (shown in full below — the whole file before this task):

```java
package ai.devflow.orchestrator;

import ai.devflow.event.RunEvent;
import ai.devflow.event.RunEventPublisher;
import ai.devflow.util.Slug;
import ai.devflow.workspace.ClonedWorkspace;
import ai.devflow.workspace.FixtureWorkspace;
import ai.devflow.workspace.Workspace;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

public class RunRegistry {

    private final Orchestrator orchestrator;
    private final RunEventPublisher events;
    private final ExecutorService executor;
    private final Path fixtureSource;
    private final Duration gateTimeout;
    private final Duration cloneTimeout;

    private final Map<String, RunHandle> runs = new ConcurrentHashMap<>();

    public RunRegistry(Orchestrator orchestrator, RunEventPublisher events,
                       ExecutorService executor, Path fixtureSource, Duration gateTimeout,
                       Duration cloneTimeout) {
        this.orchestrator = orchestrator;
        this.events = events;
        this.executor = executor;
        this.fixtureSource = fixtureSource;
        this.gateTimeout = gateTimeout;
        this.cloneTimeout = cloneTimeout;
    }

    public RunHandle start(String task, String repo) {
        String runId = UUID.randomUUID().toString().substring(0, 8);
        Workspace workspace;
        String repoSlug;
        if ("fixture".equals(repo)) {
            workspace = new FixtureWorkspace(fixtureSource, runId);
            repoSlug = "fixture";
        } else {
            workspace = new ClonedWorkspace(repo, runId, cloneTimeout);
            repoSlug = Slug.of(normalizeRepoUrl(repo));
        }
        RunState state = new RunState(runId, task, workspace, repoSlug);
        ApprovalGate gate = new ApprovalGate(gateTimeout);

        var future = executor.submit(() -> {
            try {
                workspace.prepare();
                orchestrator.run(state, gate);
            } catch (Exception e) {
                state.setPhase(RunPhase.FAILED);
                events.publish(runId, RunEvent.of("error",
                        "Could not prepare the workspace: " + e.getMessage(),
                        Map.of("task", task, "repoSlug", repoSlug)));
                try {
                    workspace.cleanup();
                } catch (Exception cleanupFailure) {
                    events.publish(runId, RunEvent.of("warn",
                            "Workspace cleanup also failed: " + cleanupFailure.getMessage(), Map.of()));
                }
                events.complete(runId);
            } finally {
                runs.remove(runId);
            }
        });

        RunHandle handle = new RunHandle(runId, state, gate, future);
        runs.put(runId, handle);
        return handle;
    }

    public RunHandle find(String runId) {
        return runs.get(runId);
    }

    static String normalizeRepoUrl(String url) {
        String s = url.substring("https://".length());
        if (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        if (s.endsWith(".git")) s = s.substring(0, s.length() - 4);
        return s;
    }
}
```

Change it to:

```java
package ai.devflow.orchestrator;

import ai.devflow.event.RunEvent;
import ai.devflow.event.RunEventPublisher;
import ai.devflow.util.Slug;
import ai.devflow.workspace.ClonedWorkspace;
import ai.devflow.workspace.FixtureWorkspace;
import ai.devflow.workspace.Workspace;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

public class RunRegistry {

    private final Orchestrator orchestrator;
    private final RunExecutor directExecutor;
    private final RunEventPublisher events;
    private final ExecutorService executor;
    private final Path fixtureSource;
    private final Duration gateTimeout;
    private final Duration cloneTimeout;

    private final Map<String, RunHandle> runs = new ConcurrentHashMap<>();

    public RunRegistry(Orchestrator orchestrator, RunExecutor directExecutor, RunEventPublisher events,
                       ExecutorService executor, Path fixtureSource, Duration gateTimeout,
                       Duration cloneTimeout) {
        this.orchestrator = orchestrator;
        this.directExecutor = directExecutor;
        this.events = events;
        this.executor = executor;
        this.fixtureSource = fixtureSource;
        this.gateTimeout = gateTimeout;
        this.cloneTimeout = cloneTimeout;
    }

    public RunHandle start(String task, String repo, RunStrategy strategy) {
        String runId = UUID.randomUUID().toString().substring(0, 8);
        Workspace workspace;
        String repoSlug;
        if ("fixture".equals(repo)) {
            workspace = new FixtureWorkspace(fixtureSource, runId);
            repoSlug = "fixture";
        } else {
            workspace = new ClonedWorkspace(repo, runId, cloneTimeout);
            repoSlug = Slug.of(normalizeRepoUrl(repo));
        }
        RunState state = new RunState(runId, task, workspace, repoSlug);
        ApprovalGate gate = new ApprovalGate(gateTimeout);
        RunExecutor selectedExecutor = strategy == RunStrategy.DIRECT ? directExecutor : orchestrator;

        var future = executor.submit(() -> {
            try {
                workspace.prepare();
                selectedExecutor.run(state, gate);
            } catch (Exception e) {
                state.setPhase(RunPhase.FAILED);
                events.publish(runId, RunEvent.of("error",
                        "Could not prepare the workspace: " + e.getMessage(),
                        Map.of("task", task, "repoSlug", repoSlug, "strategy", strategy.name())));
                try {
                    workspace.cleanup();
                } catch (Exception cleanupFailure) {
                    events.publish(runId, RunEvent.of("warn",
                            "Workspace cleanup also failed: " + cleanupFailure.getMessage(), Map.of()));
                }
                events.complete(runId);
            } finally {
                runs.remove(runId);
            }
        });

        RunHandle handle = new RunHandle(runId, state, gate, future);
        runs.put(runId, handle);
        return handle;
    }

    public RunHandle find(String runId) {
        return runs.get(runId);
    }

    static String normalizeRepoUrl(String url) {
        String s = url.substring("https://".length());
        if (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        if (s.endsWith(".git")) s = s.substring(0, s.length() - 4);
        return s;
    }
}
```

(The class-level Javadoc and the `start(...)` method's own Javadoc from the original file are omitted above for brevity — keep them as they were, they still apply unchanged.)

- [ ] **Step 6: Update `OrchestrationConfig`**

Read `src/main/java/ai/devflow/config/OrchestrationConfig.java` first. Add the import:

```java
import ai.devflow.orchestrator.DirectExecutor;
import ai.devflow.orchestrator.RunExecutor;
```

Add a new bean (anywhere among the existing `@Bean` methods — after `orchestrator(...)` is a reasonable spot):

```java
    @Bean
    RunExecutor directExecutor(Agent coderAgent, RunEventPublisher events,
                              @Value("${devflowai.build.timeout-minutes:5}") long buildTimeoutMinutes) {
        return new DirectExecutor(coderAgent, events, Duration.ofMinutes(buildTimeoutMinutes));
    }
```

Change the `runRegistry` bean method to take and pass it:

```java
    @Bean
    RunRegistry runRegistry(Orchestrator orchestrator, RunExecutor directExecutor, RunEventPublisher events, ExecutorService runExecutor,
                            @Value("${devflowai.fixture.path:src/test/resources/fixture}") String fixturePath,
                            @Value("${devflowai.gate.timeout-minutes:10}") long gateTimeoutMinutes,
                            @Value("${devflowai.clone.timeout-minutes:2}") long cloneTimeoutMinutes) {
        return new RunRegistry(orchestrator, directExecutor, events, runExecutor,
                Path.of(fixturePath), Duration.ofMinutes(gateTimeoutMinutes),
                Duration.ofMinutes(cloneTimeoutMinutes));
    }
```

Spring resolves the `RunExecutor directExecutor` parameter by matching the parameter name against the `directExecutor` bean's name — `Orchestrator` (which already satisfies `RunExecutor` after Task 1) and the new `DirectExecutor` bean are both type-matching candidates, so name-based resolution is required here, exactly the same mechanism this file already relies on for the `orchestrator` bean method's three same-typed `Agent` parameters (`coderAgent`/`reviewerAgent`/`plannerAgent`).

- [ ] **Step 7: Run `RunControllerTest` to verify it passes**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests '*RunControllerTest*'`
Expected: PASS (all tests in the file, old and new).

- [ ] **Step 8: Fix `RunRegistryTest`**

Read `src/test/java/ai/devflow/orchestrator/RunRegistryTest.java` first (shown in full below — the whole file before this task):

```java
package ai.devflow.orchestrator;

import ai.devflow.agent.*;
import ai.devflow.event.RunEventPublisher;
import ai.devflow.memory.MemoryStore;
import ai.devflow.policy.PolicyResult;
import ai.devflow.skill.ScribeDraft;
import ai.devflow.skill.SkillDraft;
import ai.devflow.skill.SkillIndexEntry;
import ai.devflow.skill.SkillStore;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    /** No skills/memory on disk for this test -- nothing to load, nothing to persist. */
    static class NoOpSkillStore implements SkillStore {
        @Override public List<SkillIndexEntry> index(String repoSlug) { return List.of(); }
        @Override public String readFull(String repoSlug, String name) { return ""; }
        @Override public String write(String repoSlug, String runId, SkillDraft draft) { return ""; }
    }

    static class NoOpMemoryStore implements MemoryStore {
        @Override public String read(String repoSlug) { return ""; }
        @Override public String append(String repoSlug, String fact) { return ""; }
    }

    @BeforeEach
    void setUp() {
        var events = new RunEventPublisher();
        var orchestrator = new Orchestrator(new StubAgent("coder"), new StubAgent("reviewer"), new StubAgent("planner"),
                (task, index) -> List.of(), (state, findings, reason) -> ScribeDraft.EMPTY,
                new NoOpSkillStore(), new NoOpMemoryStore(),
                events, 3, 5, Duration.ofMinutes(1), context -> PolicyResult.ok());
        registry = new RunRegistry(orchestrator, events, Executors.newCachedThreadPool(),
                java.nio.file.Path.of("src/test/resources/fixture"), Duration.ofSeconds(2),
                Duration.ofSeconds(2));
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

    @Test
    void aNonHttpsRepoIsRejectedSynchronouslyWithoutStartingAnyWork() {
        assertThatThrownBy(() -> registry.start("task", "ext::sh -c \"true\""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void normalizeRepoUrlStripsTrailingGitAndSlashSoBothFormsMatch() {
        assertThat(RunRegistry.normalizeRepoUrl("https://github.com/o/r.git"))
                .isEqualTo(RunRegistry.normalizeRepoUrl("https://github.com/o/r"));
        assertThat(RunRegistry.normalizeRepoUrl("https://github.com/o/r.git"))
                .isEqualTo("github.com/o/r");
        assertThat(RunRegistry.normalizeRepoUrl("https://github.com/o/r/"))
                .isEqualTo("github.com/o/r");
    }
}
```

There is exactly one `new RunRegistry(...)` call site (in `setUp()`) and four `.start(...)` call sites. Change `setUp()` to pass a trivial stub `RunExecutor` as the 2nd constructor argument (this file's existing scope is entirely about `Orchestrator`-strategy/workspace behavior — a stub is enough here since Direct-strategy dispatch itself is proven by `DirectExecutorTest` and `RunFlowIntegrationTest`), and change every `.start(...)` call to pass `RunStrategy.ORCHESTRATED` as the 3rd argument:

```java
    @BeforeEach
    void setUp() {
        var events = new RunEventPublisher();
        var orchestrator = new Orchestrator(new StubAgent("coder"), new StubAgent("reviewer"), new StubAgent("planner"),
                (task, index) -> List.of(), (state, findings, reason) -> ScribeDraft.EMPTY,
                new NoOpSkillStore(), new NoOpMemoryStore(),
                events, 3, 5, Duration.ofMinutes(1), context -> PolicyResult.ok());
        RunExecutor stubDirectExecutor = (state, gate) -> new Orchestrator.RunOutcome(true, "stub", state);
        registry = new RunRegistry(orchestrator, stubDirectExecutor, events, Executors.newCachedThreadPool(),
                java.nio.file.Path.of("src/test/resources/fixture"), Duration.ofSeconds(2),
                Duration.ofSeconds(2));
    }

    @Test
    void startingARunGivesItAUniqueIdAndRegistersIt() {
        RunHandle a = registry.start("task one", "fixture", RunStrategy.ORCHESTRATED);
        RunHandle b = registry.start("task two", "fixture", RunStrategy.ORCHESTRATED);

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
        RunHandle handle = registry.start("a task", "fixture", RunStrategy.ORCHESTRATED);
        assertThat(handle.state().task()).isEqualTo("a task");
        assertThat(handle.state().runId()).isEqualTo(handle.runId());
        assertThat(handle.gate()).isNotNull();
    }

    @Test
    void aNonHttpsRepoIsRejectedSynchronouslyWithoutStartingAnyWork() {
        assertThatThrownBy(() -> registry.start("task", "ext::sh -c \"true\"", RunStrategy.ORCHESTRATED))
                .isInstanceOf(IllegalArgumentException.class);
    }
```

Leave `normalizeRepoUrlStripsTrailingGitAndSlashSoBothFormsMatch` unchanged — it doesn't call `.start(...)` or the constructor.

Add one new test proving Direct-strategy dispatch actually reaches the `directExecutor` instead of the `orchestrator`:

```java
    @Test
    void directStrategyDispatchesToTheDirectExecutorNotTheOrchestrator() {
        java.util.concurrent.atomic.AtomicBoolean directCalled = new java.util.concurrent.atomic.AtomicBoolean(false);
        var events = new RunEventPublisher();
        var orchestrator = new Orchestrator(new StubAgent("coder"), new StubAgent("reviewer"), new StubAgent("planner"),
                (task, index) -> List.of(), (state, findings, reason) -> ScribeDraft.EMPTY,
                new NoOpSkillStore(), new NoOpMemoryStore(),
                events, 3, 5, Duration.ofMinutes(1), context -> PolicyResult.ok());
        RunExecutor recordingDirectExecutor = (state, gate) -> {
            directCalled.set(true);
            return new Orchestrator.RunOutcome(true, "stub", state);
        };
        var directRegistry = new RunRegistry(orchestrator, recordingDirectExecutor, events, Executors.newCachedThreadPool(),
                java.nio.file.Path.of("src/test/resources/fixture"), Duration.ofSeconds(2),
                Duration.ofSeconds(2));

        RunHandle handle = directRegistry.start("a task", "fixture", RunStrategy.DIRECT);
        while (!handle.task().isDone()) {
            try { Thread.sleep(5); } catch (InterruptedException e) { throw new RuntimeException(e); }
        }

        assertThat(directCalled.get()).isTrue();
    }
```

- [ ] **Step 9: Fix `RunFlowIntegrationTest`**

Read `src/test/java/ai/devflow/web/RunFlowIntegrationTest.java` first (shown in full in the plan's exploration — it already has `@TestBean` overrides for `coderAgent`/`reviewerAgent`/`plannerAgent`/`scribeAgent`/`skillStore`/`memoryStore`). This test's existing 4 test methods all use the real Spring-wired `directExecutor` bean, which in turn uses the real (already-overridden) `coderAgentOverride` stub — so no new `@TestBean` override is needed here for those existing tests to keep passing; the new `directExecutor` bean resolves through the same stubbed `coderAgent` bean automatically.

Add one new test proving a Direct-strategy run works end to end over HTTP:

```java
    @Test
    void aDirectStrategyRunSkipsAllGatesAndCommits() throws Exception {
        String body = mvc.perform(post("/api/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new StartRunRequest("add a class", "fixture", "direct"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String runId = json.readTree(body).get("runId").asText();
        RunHandle handle = registry.find(runId);
        assertThat(handle).isNotNull();

        long deadline = System.currentTimeMillis() + 30_000;
        while (!handle.task().isDone()) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("run never finished");
            Thread.sleep(10);
        }

        assertThat(handle.state().phase()).isEqualTo(RunPhase.DONE);
        assertThat(handle.gate().pending()).isNull();
    }
```

- [ ] **Step 10: Run the full suite**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test`
Expected: PASS. `OrchestrationConfigTest` (which boots the full Spring context) proves the new `directExecutor` bean and the widened `RunRegistry` constructor wire together correctly.

- [ ] **Step 11: Commit**

```bash
git add src/main/java/ai/devflow/web/StartRunRequest.java src/main/java/ai/devflow/web/RunController.java \
        src/main/java/ai/devflow/orchestrator/RunRegistry.java src/main/java/ai/devflow/config/OrchestrationConfig.java \
        src/test/java/ai/devflow/web/RunControllerTest.java src/test/java/ai/devflow/orchestrator/RunRegistryTest.java \
        src/test/java/ai/devflow/web/RunFlowIntegrationTest.java
git commit -m "feat: wire RunStrategy end to end from the HTTP API to RunRegistry"
```

---

# Task 4: UI — strategy selector + past-runs table columns

**Files:**
- Modify: `src/main/resources/static/index.html`
- Modify: `src/test/java/ai/devflow/web/StaticPageTest.java`

- [ ] **Step 1: Write the failing test**

Read `src/test/java/ai/devflow/web/StaticPageTest.java` first. Add two more assertions to the existing test:

```java
    @Test
    void servesTheOperatorPageAtRoot() throws Exception {
        mvc.perform(get("/")).andExpect(status().isOk());

        mvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("devflowai")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"task\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"run\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("EventSource")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"history\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/api/runs/history")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"strategy\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("value=\"direct\"")));
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests '*StaticPageTest*'`
Expected: FAIL — no `id="strategy"` element exists yet.

- [ ] **Step 3: Add the strategy dropdown to the start form**

Read `src/main/resources/static/index.html` first. In the `<section id="start">` block, add a new row right after the "Attach a PRD or task doc" row and before the `<button id="run">` line:

```html
    <div class="row">
      <label for="strategy">Strategy</label>
      <select id="strategy">
        <option value="orchestrated">Orchestrated (planner, reviewer, gates)</option>
        <option value="direct">Direct (single coder pass, no review)</option>
      </select>
    </div>
```

- [ ] **Step 4: Send the strategy when starting a run**

In the same file's `<script>` block, find the `$('run').onclick` handler's fetch call:

```javascript
    const res = await fetch('/api/runs', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ task, repo })
    });
```

Change it to:

```javascript
    const res = await fetch('/api/runs', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ task, repo, strategy: $('strategy').value })
    });
```

- [ ] **Step 5: Add the two new past-runs table columns**

In the same file, find the history table's header:

```html
    <table style="width:100%; border-collapse:collapse; margin-top:0.6rem; font-size:0.85rem;">
      <thead>
        <tr style="text-align:left; color:var(--muted);">
          <th style="padding:0.3rem 0;">Task</th>
          <th>Status</th>
          <th>Started</th>
        </tr>
      </thead>
      <tbody id="history-body"></tbody>
    </table>
```

Change it to:

```html
    <table style="width:100%; border-collapse:collapse; margin-top:0.6rem; font-size:0.85rem;">
      <thead>
        <tr style="text-align:left; color:var(--muted);">
          <th style="padding:0.3rem 0;">Task</th>
          <th>Strategy</th>
          <th>Status</th>
          <th>Build</th>
          <th>Started</th>
        </tr>
      </thead>
      <tbody id="history-body"></tbody>
    </table>
```

Find the `renderHistory(rows)` function:

```javascript
  function renderHistory(rows) {
    const body = $('history-body');
    body.innerHTML = '';
    rows.forEach(r => {
      const tr = document.createElement('tr');
      tr.style.borderTop = '1px solid var(--line)';

      const task = r.task.length > 60 ? r.task.slice(0, 60) + '…' : r.task;
      const taskCell = document.createElement('td');
      taskCell.style.padding = '0.3rem 0';
      taskCell.textContent = task;

      const statusCell = document.createElement('td');
      statusCell.textContent = r.status;

      const startedCell = document.createElement('td');
      startedCell.textContent = new Date(r.startedAt).toLocaleString();

      tr.appendChild(taskCell);
      tr.appendChild(statusCell);
      tr.appendChild(startedCell);
      body.appendChild(tr);
    });
  }
```

Change it to:

```javascript
  function renderHistory(rows) {
    const body = $('history-body');
    body.innerHTML = '';
    rows.forEach(r => {
      const tr = document.createElement('tr');
      tr.style.borderTop = '1px solid var(--line)';

      const task = r.task.length > 60 ? r.task.slice(0, 60) + '…' : r.task;
      const taskCell = document.createElement('td');
      taskCell.style.padding = '0.3rem 0';
      taskCell.textContent = task;

      const strategyCell = document.createElement('td');
      strategyCell.textContent = r.strategy || '';

      const statusCell = document.createElement('td');
      statusCell.textContent = r.status;

      const buildCell = document.createElement('td');
      buildCell.textContent = r.buildSucceeded === true ? 'passed' : r.buildSucceeded === false ? 'failed' : '';

      const startedCell = document.createElement('td');
      startedCell.textContent = new Date(r.startedAt).toLocaleString();

      tr.appendChild(taskCell);
      tr.appendChild(strategyCell);
      tr.appendChild(statusCell);
      tr.appendChild(buildCell);
      tr.appendChild(startedCell);
      body.appendChild(tr);
    });
  }
```

Note this continues using `textContent` for every cell (never `innerHTML`), matching the fix Sub-project 2's final review required — task text (and now strategy/build-status strings, both server-controlled enum values, but keeping the same safe construction pattern throughout is simpler than special-casing which columns need it).

- [ ] **Step 6: Run to verify it passes**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests '*StaticPageTest*'`
Expected: PASS.

- [ ] **Step 7: Manually verify in a browser**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && export ANTHROPIC_API_KEY=<key> && ./gradlew bootRun`

Open `http://localhost:8080`. Confirm the Strategy dropdown appears below the doc-attach row. Run a task once with "Orchestrated" selected (confirm gates still appear as before) and once with "Direct" selected (confirm no gates appear at all and the run completes on its own). Click "Refresh" on the past-runs table and confirm both rows show their respective Strategy and Build columns.

- [ ] **Step 8: Run the full suite one last time**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add src/main/resources/static/index.html src/test/java/ai/devflow/web/StaticPageTest.java
git commit -m "feat: add a strategy selector and past-runs comparison columns to the operator page"
```

---

## Spec coverage check

- §2 (goals: answer 3 of the PRD's 4 example questions without new tooling) — Tasks 1-4 collectively deliver this; no benchmark harness attempted.
- §3 (RunExecutor/DirectExecutor architecture, the workspace-prepare-failure `strategy` fix) — Task 1 (RunExecutor/DirectExecutor), Task 3 Step 5 (the regression fix, explicitly called out).
- §4 (SdlcRun schema additions, derivable-metric queries) — Task 2. The query-time metrics (iterations/findings/corrections) are documented as available but deliberately not surfaced in any UI in this plan, matching §5/§6.
- §5 (comparison surface: two new RunSummary fields, two new table columns, no new endpoint) — Task 2 Step 5 (RunSummary), Task 4 (table).
- §6 (non-goals: no cost conversion, no benchmark harness, no dashboard beyond two columns, no query-time metrics persisted, no alternate model for Strategy A) — respected throughout; `DirectExecutor` hard-codes the same `coderAgent` bean, never a parameter.
- §7 (testing) — `DirectExecutorTest` (Task 1), `SdlcRunRecorderTest`/`HistoryRepositoriesTest` additions (Task 2), `RunRegistryTest`/`RunControllerTest`/`RunFlowIntegrationTest` additions (Task 3) are all present.
- §8 (ship order) — followed exactly, task-for-task.
