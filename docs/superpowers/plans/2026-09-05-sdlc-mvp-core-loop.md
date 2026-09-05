# SDLC MVP Core Loop Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give devflowai a persisted audit trail, a provider-neutral coding-worker seam, and a Planner stage, and move Coder/Reviewer to a cheaper model tier — the first sub-project of the devflowai → full-SDLC-app pivot.

**Architecture:** A write-behind recorder (`SdlcRunRecorder`) reacts to Spring `ApplicationEvent`s fired alongside the existing SSE events, persisting `SdlcRun`/`StageExecution`/`ReviewFinding`/`Approval` rows with zero changes to `Orchestrator`'s or `RunEventPublisher`'s public API. `CoderAgent`/`ReviewerAgent` become thin adapters over a new `CodingWorker` interface, whose only implementation (`SpringAiCodingWorker`) is today's Spring AI `ChatClient` call, moved behind the seam. A new `PlannerAgent` runs once per run, after Gate 1, feeding `state.plan()`.

**Tech Stack:** Spring Boot 4.1.1, Spring AI 2.0.1, Spring Data JPA, H2 (file-mode), Java 21, JUnit 5, Mockito, AssertJ.

**Spec:** `docs/superpowers/specs/2026-09-05-sdlc-mvp-core-loop-design.md`

## Global Constraints

- JDK 21 at `/opt/homebrew/opt/openjdk@21`, not on PATH — `export JAVA_HOME=/opt/homebrew/opt/openjdk@21` before every `./gradlew` invocation.
- Never call `.temperature(...)` on any `AnthropicChatOptions` builder — Opus 5 rejects it with HTTP 400 (CLAUDE.md).
- Pin every model explicitly via `ModelNames` constants — never rely on Spring AI's compiled default.
- `.effort(...)` takes `com.anthropic.models.messages.OutputConfig.Effort`, imported directly (there is no nested `AnthropicChatOptions.OutputConfig`).
- `ChatClient.ChatClientRequestSpec.options(B)` and `ChatClient.Builder.defaultOptions(B)` take the options **builder**, never a built `AnthropicChatOptions` — passing `.build()`'s result fails to compile.
- The orchestrator never holds file contents or diffs — only `AgentResult` records and the plain-data types this plan adds (`Finding`, `RunEvent`, `WorkerRequest`/`WorkerResult`).
- Every path-taking tool goes through `PathGuard`. No exceptions.
- Agents never call each other. They return to the `Orchestrator`, which decides what runs next.
- This repo is public — no API key or secret in any tracked file, ever.
- Agents are singleton beans and must stay stateless. `SdlcRunRecorder` follows the same rule: all state lives in the database, never in an instance field.
- The orchestrator cleans up the workspace on every exit path (approved, rejected, timed out, thrown) — this plan does not touch that `try/finally`.
- A run with an empty changeset is never approved — this plan does not touch that check.

## Design decisions this plan makes

The spec leaves some mechanics unspecified; here is how this plan resolves them, and why.

1. **`RunEventPublisher` keeps its no-arg constructor.** Eight existing test call sites across four files (`RunControllerTest`, `RunRegistryTest`, `RunEventPublisherTest`, `OrchestratorTest`) call `new RunEventPublisher()`. Rather than touch all eight, the no-arg constructor is kept and delegates to a new constructor taking an `ApplicationEventPublisher`, passing a no-op. The parameterized constructor is marked `@Autowired` so Spring uses it in production. This mirrors this codebase's own established pattern (`RunState`'s 3-arg constructor delegating to its 4-arg one from Phase 6).
2. **`RunRecorded` and `ApprovalRecorded` live in `ai.devflow.event`, not `ai.devflow.history`.** `Orchestrator` already depends on `ai.devflow.event` (for `RunEvent`/`RunEventPublisher`). If the new event records lived in `ai.devflow.history` instead, and referenced `Gate`/`ApprovalDecision` types from `ai.devflow.orchestrator`, `ai.devflow.event` would end up depending on `ai.devflow.orchestrator` — which already depends on `ai.devflow.event` — a circular package dependency. `ApprovalRecorded` therefore carries plain `String`/`boolean` fields (`gate.name()`, not `Gate`), converted at the call site in `RunController`, which already depends on `ai.devflow.orchestrator`.
3. **`WorkerRequest` carries a fully-built prompt string and a tool list, not decomposed fields.** The spec described `WorkerRequest` as carrying task/memory/skills/plan/findings as separate fields. This plan keeps prompt construction exactly where it already lives — `CoderAgent.buildPrompt`/`ReviewerAgent`'s prompt literal/`PlannerAgent.buildPrompt` — and gives `SpringAiCodingWorker` only `(String prompt, List<Object> tools)`. This avoids duplicating prompt-building logic inside the worker, keeps the refactor's diff minimal (existing `CoderAgentTest`/`ReviewerAgentTest` assertions on prompt content and tool wiring keep working almost unchanged), and is equally provider-neutral for the spec's stated future case — a CLI-shelling worker would use `prompt` as its instructions and simply ignore `tools`.
4. **`StageExecution` boundaries are derived from a repository query, not recorder-held state.** `SdlcRunRecorder` asks `StageExecutionRepository.findByRunIdAndEndedAtIsNull(runId)` for the currently-open stage rather than caching "last phase seen" in an instance field, keeping the recorder a stateless singleton like every other bean in this codebase.
5. **Two small enrichments close a real gap: a run that fails before `Orchestrator.execute()` ever starts would otherwise never appear in history.** `RunRegistry.start()`'s workspace-prepare failure path publishes an `error` event directly, bypassing `Orchestrator.emit()` entirely and carrying no `task`/`repoSlug`. This plan adds `task`/`repoSlug` to that one event too, so `SdlcRunRecorder`'s single trigger for "create the `SdlcRun` row" — an event whose data map contains both keys — fires uniformly whether the run reached `Orchestrator` at all or not.
6. **A test-only H2 datasource is added once, in `src/test/resources/application.yml`**, rather than editing the `properties` attribute on each of the seven existing `@SpringBootTest` classes individually. Spring Boot layers test-classpath config over main-classpath config automatically, so every current and future `@SpringBootTest` gets an in-memory database without further changes, and none of them accidentally shares or corrupts a real `~/.devflowai/history/devflowai` file.
7. **Planner never returns an explicit `FAILED` `AgentResult`.** The spec said a failed Planner result aborts the run like a failed Coder/Reviewer result. But `CoderAgent` itself never returns `FAILED` either (only `ReviewerAgent` does, because it fail-closes on unparseable structured JSON) — a hard Planner failure (a thrown exception from a dead `ChatClient`) already surfaces through `Orchestrator`'s existing outer `catch (RuntimeException e)` in `run(...)`, exactly like a Coder failure does today. `PlannerAgent` mirrors `CoderAgent`'s style instead of inventing fail-closed JSON parsing it doesn't need — its output is free-form plan text, not a fixed schema.

## File Structure

**New — persistence (`ai.devflow.history`):**
- `SdlcRunStatus.java` — enum `RUNNING, DONE, ABORTED, FAILED`
- `SdlcRun.java`, `StageExecution.java`, `ReviewFinding.java`, `Approval.java` — JPA entities
- `SdlcRunRepository.java`, `StageExecutionRepository.java`, `ReviewFindingRepository.java`, `ApprovalRepository.java` — Spring Data JPA repositories
- `SdlcRunRecorder.java` — `@Component`, reacts to `RunRecorded`/`ApprovalRecorded`, writes all four entities

**New — events (`ai.devflow.event`):**
- `RunRecorded.java` — `record RunRecorded(String runId, RunEvent event)`
- `ApprovalRecorded.java` — `record ApprovalRecorded(String runId, String gate, boolean approved, String reason)`

**New — coding-worker seam (`ai.devflow.worker`):**
- `WorkerRequest.java`, `WorkerResult.java` — plain records
- `CodingWorker.java` — interface, one method
- `SpringAiCodingWorker.java` — the only implementation this sub-project ships

**New — planner (`ai.devflow.agent`):**
- `PlannerAgent.java`

**New — API (`ai.devflow.web`):**
- `RunSummary.java` — response DTO for the history endpoint

**Modified:**
- `RunEventPublisher.java` — fires `RunRecorded`; overloaded constructor (design decision 1)
- `Orchestrator.java` — `emit()` always includes `phase`; the first step event includes `task`/`repoSlug`; `NEEDS_WORK` includes `findingsDetail`/`reviewIteration`; `aborted`/`failed` include token counts; new `planner` field/constructor param, invoked once after Gate 1
- `RunState.java` — new `plan()`/`setPlan(String)`
- `RunRegistry.java` — workspace-prepare failure event includes `task`/`repoSlug`
- `RunController.java` — `approve` fires `ApprovalRecorded`; new `GET /history` endpoint; constructor gains `ApplicationEventPublisher` then `SdlcRunRepository`
- `CoderAgent.java`, `ReviewerAgent.java` — become adapters over `CodingWorker`
- `ChatClientConfig.java` — `opusOptions` → `agentOptions` (effort `HIGH` → `XHIGH`); new `plannerChatClient` bean; coder/reviewer switch to `ModelNames.SONNET`
- `ModelNames.java` — new `SONNET` constant
- `OrchestrationConfig.java` — wires `CodingWorker`/`Agent` beans for coder/reviewer/planner; `Orchestrator` bean gains `plannerAgent`
- `build.gradle.kts` — `spring-boot-starter-data-jpa`, `com.h2database:h2`
- `src/main/resources/application.yml` — datasource + JPA config
- `src/test/resources/application.yml` (new) — in-memory test datasource
- `src/main/resources/static/index.html` — past-runs list section
- Test files: `RunEventPublisherTest`, `RunControllerTest`, `OrchestratorTest`, `CoderAgentTest`, `ReviewerAgentTest`, `RunFlowIntegrationTest`, `StaticPageTest` — see individual tasks

---

# Task 1: Persistence entities, repositories, and JPA/H2 wiring

**Files:**
- Create: `src/main/java/ai/devflow/history/SdlcRunStatus.java`
- Create: `src/main/java/ai/devflow/history/SdlcRun.java`
- Create: `src/main/java/ai/devflow/history/StageExecution.java`
- Create: `src/main/java/ai/devflow/history/ReviewFinding.java`
- Create: `src/main/java/ai/devflow/history/Approval.java`
- Create: `src/main/java/ai/devflow/history/SdlcRunRepository.java`
- Create: `src/main/java/ai/devflow/history/StageExecutionRepository.java`
- Create: `src/main/java/ai/devflow/history/ReviewFindingRepository.java`
- Create: `src/main/java/ai/devflow/history/ApprovalRepository.java`
- Modify: `build.gradle.kts`
- Modify: `src/main/resources/application.yml`
- Create: `src/test/resources/application.yml`
- Test: `src/test/java/ai/devflow/history/HistoryRepositoriesTest.java`

**Interfaces:**
- Produces: `SdlcRunStatus` enum; `SdlcRun(String id, String task, String repoSlug, Instant startedAt)` + `finish(SdlcRunStatus, String reason, Instant finishedAt, long inputTokens, long outputTokens)`; `StageExecution(String runId, RunPhase phase, Instant startedAt)` + `close(Instant endedAt)`; `ReviewFinding(String runId, int reviewIteration, Finding finding)`; `Approval(String runId, Gate gate, boolean approved, String reason, Instant decidedAt)`; `SdlcRunRepository extends JpaRepository<SdlcRun, String>` with `List<SdlcRun> findTop50ByOrderByStartedAtDesc()`; `StageExecutionRepository extends JpaRepository<StageExecution, Long>` with `Optional<StageExecution> findByRunIdAndEndedAtIsNull(String runId)`; `ReviewFindingRepository extends JpaRepository<ReviewFinding, Long>` with `List<ReviewFinding> findByRunId(String runId)`; `ApprovalRepository extends JpaRepository<Approval, Long>` with `List<Approval> findByRunId(String runId)`.

- [ ] **Step 1: Add JPA/H2 dependencies**

Edit `build.gradle.kts`, in the `dependencies { }` block, add these two lines right after `implementation("org.eclipse.jgit:...")`:

```kotlin
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    runtimeOnly("com.h2database:h2")
```

- [ ] **Step 2: Add datasource/JPA config to the main and test `application.yml`**

Edit `src/main/resources/application.yml`, adding under the existing `spring:` key (as a sibling of `application:` and `ai:`):

```yaml
  datasource:
    url: jdbc:h2:file:~/.devflowai/history/devflowai
    driver-class-name: org.h2.Driver
  jpa:
    hibernate:
      ddl-auto: update
    open-in-view: false
```

Create `src/test/resources/application.yml` (new file, new directory) with only the override every test needs — see design decision 6:

```yaml
spring:
  datasource:
    url: jdbc:h2:mem:devflowai-test;DB_CLOSE_DELAY=-1
    driver-class-name: org.h2.Driver
```

- [ ] **Step 3: Write the failing repository round-trip test**

Create `src/test/java/ai/devflow/history/HistoryRepositoriesTest.java`:

```java
package ai.devflow.history;

import ai.devflow.agent.Finding;
import ai.devflow.orchestrator.Gate;
import ai.devflow.orchestrator.RunPhase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class HistoryRepositoriesTest {

    @Autowired SdlcRunRepository runs;
    @Autowired StageExecutionRepository stages;
    @Autowired ReviewFindingRepository findings;
    @Autowired ApprovalRepository approvals;

    @Test
    void savesAndReadsBackASdlcRun() {
        var run = new SdlcRun("r1", "add validation", "fixture", Instant.now());
        runs.save(run);

        var found = runs.findById("r1").orElseThrow();
        assertThat(found.task()).isEqualTo("add validation");
        assertThat(found.status()).isEqualTo(SdlcRunStatus.RUNNING);

        found.finish(SdlcRunStatus.DONE, "Approved", Instant.now(), 100, 50);
        runs.save(found);
        assertThat(runs.findById("r1").orElseThrow().status()).isEqualTo(SdlcRunStatus.DONE);
    }

    @Test
    void findTop50OrdersByStartedAtDescending() {
        runs.save(new SdlcRun("older", "t", "fixture", Instant.now().minusSeconds(60)));
        runs.save(new SdlcRun("newer", "t", "fixture", Instant.now()));

        assertThat(runs.findTop50ByOrderByStartedAtDesc())
                .extracting(SdlcRun::id).containsExactly("newer", "older");
    }

    @Test
    void stageExecutionTracksTheOpenStageForARun() {
        var stage = new StageExecution("r2", RunPhase.CODING, Instant.now());
        stages.save(stage);

        var open = stages.findByRunIdAndEndedAtIsNull("r2").orElseThrow();
        assertThat(open.phase()).isEqualTo(RunPhase.CODING);

        open.close(Instant.now());
        stages.save(open);
        assertThat(stages.findByRunIdAndEndedAtIsNull("r2")).isEmpty();
    }

    @Test
    void reviewFindingCopiesFieldsFromAFinding() {
        var finding = new Finding(Finding.Origin.REVIEWER, Finding.Severity.HIGH, "A.java", 14, "missing @Valid");
        findings.save(new ReviewFinding("r3", 1, finding));

        var saved = findings.findByRunId("r3");
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).message()).isEqualTo("missing @Valid");
        assertThat(saved.get(0).severity()).isEqualTo(Finding.Severity.HIGH);
    }

    @Test
    void approvalRecordsTheGateAndDecision() {
        approvals.save(new Approval("r4", Gate.BEFORE_BUILD, false, "use a DTO", Instant.now()));

        var saved = approvals.findByRunId("r4");
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).gate()).isEqualTo(Gate.BEFORE_BUILD);
        assertThat(saved.get(0).approved()).isFalse();
    }
}
```

- [ ] **Step 4: Run the test to verify it fails**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests '*HistoryRepositoriesTest*'`
Expected: compilation failure — none of the entity/repository classes exist yet.

- [ ] **Step 5: Create the enum**

`src/main/java/ai/devflow/history/SdlcRunStatus.java`:

```java
package ai.devflow.history;

/** The terminal state a run's persisted row ends in — mirrors the RunEvent type that ended it. */
public enum SdlcRunStatus { RUNNING, DONE, ABORTED, FAILED }
```

- [ ] **Step 6: Create the entities**

`src/main/java/ai/devflow/history/SdlcRun.java`:

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

`src/main/java/ai/devflow/history/StageExecution.java`:

```java
package ai.devflow.history;

import ai.devflow.orchestrator.RunPhase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "stage_execution")
public class StageExecution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String runId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RunPhase phase;

    @Column(nullable = false)
    private Instant startedAt;

    private Instant endedAt;

    protected StageExecution() {} // JPA

    public StageExecution(String runId, RunPhase phase, Instant startedAt) {
        this.runId = runId;
        this.phase = phase;
        this.startedAt = startedAt;
    }

    public Long id() { return id; }
    public String runId() { return runId; }
    public RunPhase phase() { return phase; }
    public Instant startedAt() { return startedAt; }
    public Instant endedAt() { return endedAt; }

    public void close(Instant endedAt) { this.endedAt = endedAt; }
}
```

`src/main/java/ai/devflow/history/ReviewFinding.java`:

```java
package ai.devflow.history;

import ai.devflow.agent.Finding;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "review_finding")
public class ReviewFinding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String runId;

    @Column(nullable = false)
    private int reviewIteration;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Finding.Origin origin;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Finding.Severity severity;

    private String file;
    private Integer line;

    @Column(length = 4_000, nullable = false)
    private String message;

    protected ReviewFinding() {} // JPA

    public ReviewFinding(String runId, int reviewIteration, Finding finding) {
        this.runId = runId;
        this.reviewIteration = reviewIteration;
        this.origin = finding.origin();
        this.severity = finding.severity();
        this.file = finding.file();
        this.line = finding.line();
        this.message = finding.message();
    }

    public Long id() { return id; }
    public String runId() { return runId; }
    public int reviewIteration() { return reviewIteration; }
    public Finding.Origin origin() { return origin; }
    public Finding.Severity severity() { return severity; }
    public String file() { return file; }
    public Integer line() { return line; }
    public String message() { return message; }
}
```

`src/main/java/ai/devflow/history/Approval.java`:

```java
package ai.devflow.history;

import ai.devflow.orchestrator.Gate;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "approval")
public class Approval {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String runId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Gate gate;

    @Column(nullable = false)
    private boolean approved;

    @Column(length = 2_000)
    private String reason;

    @Column(nullable = false)
    private Instant decidedAt;

    protected Approval() {} // JPA

    public Approval(String runId, Gate gate, boolean approved, String reason, Instant decidedAt) {
        this.runId = runId;
        this.gate = gate;
        this.approved = approved;
        this.reason = reason;
        this.decidedAt = decidedAt;
    }

    public Long id() { return id; }
    public String runId() { return runId; }
    public Gate gate() { return gate; }
    public boolean approved() { return approved; }
    public String reason() { return reason; }
    public Instant decidedAt() { return decidedAt; }
}
```

- [ ] **Step 7: Create the repositories**

`src/main/java/ai/devflow/history/SdlcRunRepository.java`:

```java
package ai.devflow.history;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SdlcRunRepository extends JpaRepository<SdlcRun, String> {
    List<SdlcRun> findTop50ByOrderByStartedAtDesc();
}
```

`src/main/java/ai/devflow/history/StageExecutionRepository.java`:

```java
package ai.devflow.history;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface StageExecutionRepository extends JpaRepository<StageExecution, Long> {
    Optional<StageExecution> findByRunIdAndEndedAtIsNull(String runId);
}
```

`src/main/java/ai/devflow/history/ReviewFindingRepository.java`:

```java
package ai.devflow.history;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReviewFindingRepository extends JpaRepository<ReviewFinding, Long> {
    List<ReviewFinding> findByRunId(String runId);
}
```

`src/main/java/ai/devflow/history/ApprovalRepository.java`:

```java
package ai.devflow.history;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ApprovalRepository extends JpaRepository<Approval, Long> {
    List<Approval> findByRunId(String runId);
}
```

- [ ] **Step 8: Run the test to verify it passes**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests '*HistoryRepositoriesTest*'`
Expected: PASS (5 tests). `@DataJpaTest` picks up `src/test/resources/application.yml`'s in-memory datasource automatically.

- [ ] **Step 9: Run the full suite to confirm nothing else broke**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test`
Expected: PASS, all previously-passing tests still pass (every existing `@SpringBootTest` now also boots a JPA `EntityManagerFactory` against the in-memory test datasource; if any of them fail here, the datasource override in `src/test/resources/application.yml` isn't being picked up — verify the file's exact path).

- [ ] **Step 10: Commit**

```bash
git add build.gradle.kts src/main/resources/application.yml src/test/resources/application.yml \
        src/main/java/ai/devflow/history src/test/java/ai/devflow/history
git commit -m "feat: persistence entities and repositories for the SDLC audit trail"
```

---

# Task 2: `SdlcRun` + `StageExecution` recording via a write-behind recorder

**Files:**
- Create: `src/main/java/ai/devflow/event/RunRecorded.java`
- Create: `src/main/java/ai/devflow/history/SdlcRunRecorder.java`
- Modify: `src/main/java/ai/devflow/event/RunEventPublisher.java`
- Modify: `src/main/java/ai/devflow/orchestrator/Orchestrator.java`
- Modify: `src/main/java/ai/devflow/orchestrator/RunRegistry.java`
- Test: `src/test/java/ai/devflow/event/RunEventPublisherTest.java`
- Test: `src/test/java/ai/devflow/history/SdlcRunRecorderTest.java`

**Interfaces:**
- Consumes: `SdlcRunRepository`, `StageExecutionRepository` (Task 1); `RunEvent` (existing).
- Produces: `RunRecorded(String runId, RunEvent event)`; `SdlcRunRecorder` bean reacting via `@EventListener void onRunRecorded(RunRecorded recorded)` (this method grows again in Tasks 3 and 4 — later tasks modify it, they do not replace it).

- [ ] **Step 1: Write the failing recorder test**

Create `src/test/java/ai/devflow/history/SdlcRunRecorderTest.java`. This is a `@DataJpaTest` against the real repositories (backed by the in-memory test datasource from Task 1), not a hand-rolled fake — `JpaRepository` has too large a surface to fake cleanly, and Task 1's `HistoryRepositoriesTest` already proves the repositories themselves work, so this test only needs to prove the recorder's own reaction logic:

```java
package ai.devflow.history;

import ai.devflow.event.RunEvent;
import ai.devflow.event.RunRecorded;
import ai.devflow.orchestrator.RunPhase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(SdlcRunRecorder.class)
class SdlcRunRecorderTest {

    @Autowired SdlcRunRepository runs;
    @Autowired StageExecutionRepository stages;
    @Autowired SdlcRunRecorder recorder;

    @Test
    void aStartEventCreatesARunningSdlcRun() {
        recorder.onRunRecorded(new RunRecorded("r1", new RunEvent("step", "Workspace ready — branch devflowai/r1",
                Map.of("branch", "devflowai/r1", "task", "add validation", "repoSlug", "fixture", "phase", "PREPARING"))));

        var run = runs.findById("r1").orElseThrow();
        assertThat(run.task()).isEqualTo("add validation");
        assertThat(run.repoSlug()).isEqualTo("fixture");
        assertThat(run.status()).isEqualTo(SdlcRunStatus.RUNNING);
    }

    @Test
    void aStartEventIsIdempotentIfSeenTwice() {
        var start = new RunRecorded("r2", new RunEvent("step", "Workspace ready", Map.of(
                "task", "t", "repoSlug", "fixture", "phase", "PREPARING")));
        recorder.onRunRecorded(start);
        recorder.onRunRecorded(start);

        assertThat(runs.count()).isEqualTo(1);
    }

    @Test
    void aDoneEventFinishesTheRunWithTokens() {
        recorder.onRunRecorded(new RunRecorded("r3", new RunEvent("step", "start",
                Map.of("task", "t", "repoSlug", "fixture", "phase", "PREPARING"))));

        recorder.onRunRecorded(new RunRecorded("r3", new RunEvent("done", "Approved and committed",
                Map.of("phase", "DONE", "inputTokens", 100L, "outputTokens", 40L))));

        var run = runs.findById("r3").orElseThrow();
        assertThat(run.status()).isEqualTo(SdlcRunStatus.DONE);
        assertThat(run.reason()).isEqualTo("Approved and committed");
        assertThat(run.finishedAt()).isNotNull();
        assertThat(run.inputTokens()).isEqualTo(100L);
        assertThat(run.outputTokens()).isEqualTo(40L);
    }

    @Test
    void anAbortedEventMarksTheRunAborted() {
        recorder.onRunRecorded(new RunRecorded("r4", new RunEvent("step", "start",
                Map.of("task", "t", "repoSlug", "fixture", "phase", "PREPARING"))));
        recorder.onRunRecorded(new RunRecorded("r4", new RunEvent("aborted", "Rejected at pre-flight", Map.of())));

        assertThat(runs.findById("r4").orElseThrow().status()).isEqualTo(SdlcRunStatus.ABORTED);
    }

    @Test
    void anErrorEventOnItsOwnStillCreatesAFailedRun() {
        // The RunRegistry workspace-prepare-failure path: task/repoSlug arrive
        // on the SAME error event that ends the run (design decision 5).
        recorder.onRunRecorded(new RunRecorded("r5", new RunEvent("error", "Could not prepare the workspace: boom",
                Map.of("task", "t", "repoSlug", "fixture"))));

        var run = runs.findById("r5").orElseThrow();
        assertThat(run.status()).isEqualTo(SdlcRunStatus.FAILED);
        assertThat(run.reason()).isEqualTo("Could not prepare the workspace: boom");
    }

    @Test
    void aPhaseChangeOpensANewStageAndClosesThePrevious() {
        recorder.onRunRecorded(new RunRecorded("r6", new RunEvent("step", "start",
                Map.of("task", "t", "repoSlug", "fixture", "phase", "PREPARING"))));
        recorder.onRunRecorded(new RunRecorded("r6", new RunEvent("step", "coding",
                Map.of("phase", "CODING"))));

        var open = stages.findByRunIdAndEndedAtIsNull("r6").orElseThrow();
        assertThat(open.phase()).isEqualTo(RunPhase.CODING);
        assertThat(stages.findAll()).filteredOn(s -> s.runId().equals("r6") && s.phase() == RunPhase.PREPARING)
                .allMatch(s -> s.endedAt() != null);
    }

    @Test
    void repeatingTheSamePhaseDoesNotOpenARedundantStage() {
        recorder.onRunRecorded(new RunRecorded("r7", new RunEvent("step", "start",
                Map.of("task", "t", "repoSlug", "fixture", "phase", "PREPARING"))));
        recorder.onRunRecorded(new RunRecorded("r7", new RunEvent("gate", "about to run", Map.of("phase", "PREPARING"))));

        assertThat(stages.findAll()).filteredOn(s -> s.runId().equals("r7")).hasSize(1);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests '*SdlcRunRecorderTest*'`
Expected: compilation failure — `RunRecorded` and `SdlcRunRecorder` do not exist yet.

- [ ] **Step 3: Create `RunRecorded`**

`src/main/java/ai/devflow/event/RunRecorded.java`:

```java
package ai.devflow.event;

/** Fired by {@link RunEventPublisher#publish} alongside its SSE push, for SdlcRunRecorder to persist. */
public record RunRecorded(String runId, RunEvent event) {}
```

- [ ] **Step 4: Create `SdlcRunRecorder`**

`src/main/java/ai/devflow/history/SdlcRunRecorder.java`:

```java
package ai.devflow.history;

import ai.devflow.event.RunRecorded;
import ai.devflow.orchestrator.RunPhase;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Write-behind persistence for the run history (spec §3). Stateless: every
 * decision reads the database first, never an instance field, so this stays
 * a safe singleton bean.
 */
@Component
public class SdlcRunRecorder {

    private final SdlcRunRepository runs;
    private final StageExecutionRepository stages;

    public SdlcRunRecorder(SdlcRunRepository runs, StageExecutionRepository stages) {
        this.runs = runs;
        this.stages = stages;
    }

    @EventListener
    public void onRunRecorded(RunRecorded recorded) {
        String runId = recorded.runId();
        Map<String, Object> data = recorded.event().data();

        maybeCreateRun(runId, data);
        maybeRecordStage(runId, data);
        maybeFinishRun(runId, recorded.event().type(), recorded.event().message(), data);
    }

    private void maybeCreateRun(String runId, Map<String, Object> data) {
        if (data.get("task") instanceof String task
                && data.get("repoSlug") instanceof String repoSlug
                && runs.findById(runId).isEmpty()) {
            runs.save(new SdlcRun(runId, task, repoSlug, Instant.now()));
        }
    }

    private void maybeRecordStage(String runId, Map<String, Object> data) {
        if (!(data.get("phase") instanceof String phaseName)) return;
        RunPhase phase = RunPhase.valueOf(phaseName);

        Optional<StageExecution> open = stages.findByRunIdAndEndedAtIsNull(runId);
        if (open.isPresent() && open.get().phase() == phase) return;

        Instant now = Instant.now();
        open.ifPresent(s -> { s.close(now); stages.save(s); });
        stages.save(new StageExecution(runId, phase, now));
    }

    private void maybeFinishRun(String runId, String type, String message, Map<String, Object> data) {
        SdlcRunStatus status = switch (type) {
            case "done" -> SdlcRunStatus.DONE;
            case "error" -> SdlcRunStatus.FAILED;
            case "aborted" -> SdlcRunStatus.ABORTED;
            default -> null;
        };
        if (status == null) return;

        runs.findById(runId).ifPresent(run -> {
            run.finish(status, message, Instant.now(), longOrZero(data, "inputTokens"), longOrZero(data, "outputTokens"));
            runs.save(run);
        });
        stages.findByRunIdAndEndedAtIsNull(runId).ifPresent(s -> { s.close(Instant.now()); stages.save(s); });
    }

    private long longOrZero(Map<String, Object> data, String key) {
        return data.get(key) instanceof Long l ? l : 0L;
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests '*SdlcRunRecorderTest*'`
Expected: PASS (7 tests).

- [ ] **Step 6: Wire `RunEventPublisher` to fire `RunRecorded`**

Read `src/main/java/ai/devflow/event/RunEventPublisher.java` first (Task 1 did not touch it). Modify it:

Add these imports:
```java
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
```

Add a field and two constructors right after the existing field declarations (`emitters`/`history`):

```java
    private final ApplicationEventPublisher applicationEvents;

    /** Used by every existing test that constructs this directly — fires nothing. */
    public RunEventPublisher() {
        this(event -> {});
    }

    @Autowired
    public RunEventPublisher(ApplicationEventPublisher applicationEvents) {
        this.applicationEvents = applicationEvents;
    }
```

Modify `publish` to fire the event alongside the existing SSE push:

```java
    public void publish(String runId, RunEvent event) {
        recordHistory(runId, event);
        applicationEvents.publishEvent(new RunRecorded(runId, event));
        SseEmitter emitter = emitters.get(runId);
        if (emitter == null) return;
        sendQuietly(runId, emitter, event);
    }
```

- [ ] **Step 7: Write the failing test proving `publish` fires the event**

Read `src/test/java/ai/devflow/event/RunEventPublisherTest.java` first. Add this test to the existing class (it already has several `new RunEventPublisher()` tests — leave those exactly as they are):

```java
    @Test
    void publishFiresARunRecordedApplicationEvent() {
        var captured = new java.util.ArrayList<Object>();
        var publisher = new RunEventPublisher(captured::add);

        var event = RunEvent.of("step", "hello");
        publisher.publish("run-x", event);

        assertThat(captured).containsExactly(new RunRecorded("run-x", event));
    }
```

Add the import: `import ai.devflow.event.RunRecorded;` — actually this test class is already in package `ai.devflow.event`, so no import is needed for `RunRecorded`; add `import static org.assertj.core.api.Assertions.assertThat;` only if not already present (check the file first).

- [ ] **Step 8: Run to verify it fails, then passes**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests '*RunEventPublisherTest*'`
Expected: first FAIL (no such constructor), then after Step 6's edit, PASS (all tests in the file, old and new).

- [ ] **Step 9: Enrich `Orchestrator.emit` with `phase`, the start event with `task`/`repoSlug`, and terminal events with token counts**

Read `src/main/java/ai/devflow/orchestrator/Orchestrator.java` first. Make these three changes:

Change the first emit in `execute(...)`:
```java
        emit(state, "step", "Workspace ready — branch " + state.workspace().branchName(),
                Map.of("branch", state.workspace().branchName(),
                       "task", state.task(),
                       "repoSlug", state.repoSlug()));
```

Change `emit(...)` itself, near the bottom of the class:
```java
    private void emit(RunState state, String type, String message, Map<String, Object> data) {
        Map<String, Object> withPhase = new HashMap<>(data);
        withPhase.put("phase", state.phase().name());
        events.publish(state.runId(), RunEvent.of(type, message, withPhase));
    }
```
(`HashMap` is already imported at the top of this file.)

Change `aborted`/`failed`:
```java
    private RunOutcome aborted(RunState state, String reason) {
        state.setPhase(RunPhase.FAILED);
        emit(state, "aborted", reason, tokenData(state));
        return new RunOutcome(false, reason, state);
    }

    private RunOutcome failed(RunState state, String reason) {
        state.setPhase(RunPhase.FAILED);
        emit(state, "error", reason, tokenData(state));
        return new RunOutcome(false, reason, state);
    }

    private Map<String, Object> tokenData(RunState state) {
        return Map.of("inputTokens", state.totalTokens().input(), "outputTokens", state.totalTokens().output());
    }
```

- [ ] **Step 10: Enrich `RunRegistry`'s workspace-prepare-failure event**

Read `src/main/java/ai/devflow/orchestrator/RunRegistry.java` first. In `start(...)`'s executor lambda, change:
```java
                events.publish(runId, RunEvent.of("error",
                        "Could not prepare the workspace: " + e.getMessage(), Map.of()));
```
to:
```java
                events.publish(runId, RunEvent.of("error",
                        "Could not prepare the workspace: " + e.getMessage(),
                        Map.of("task", task, "repoSlug", repoSlug)));
```
(`task` and `repoSlug` are the method's own local variables, already in scope and effectively final.)

- [ ] **Step 11: Run the full suite**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test`
Expected: PASS. `OrchestratorTest` and `RunFlowIntegrationTest` construct `RunEventPublisher` via `new RunEventPublisher()` or Spring autowiring respectively — both still work (design decision 1). No existing assertion inspects the `data` map's exact key set, so the new `phase`/`task`/`repoSlug` keys don't break anything.

- [ ] **Step 12: Commit**

```bash
git add src/main/java/ai/devflow/event/RunRecorded.java src/main/java/ai/devflow/history/SdlcRunRecorder.java \
        src/main/java/ai/devflow/event/RunEventPublisher.java src/main/java/ai/devflow/orchestrator/Orchestrator.java \
        src/main/java/ai/devflow/orchestrator/RunRegistry.java \
        src/test/java/ai/devflow/event/RunEventPublisherTest.java src/test/java/ai/devflow/history/SdlcRunRecorderTest.java
git commit -m "feat: persist SdlcRun and StageExecution via a write-behind recorder"
```

---

# Task 3: `ReviewFinding` recording

**Files:**
- Modify: `src/main/java/ai/devflow/orchestrator/Orchestrator.java`
- Modify: `src/main/java/ai/devflow/history/SdlcRunRecorder.java`
- Test: `src/test/java/ai/devflow/history/SdlcRunRecorderTest.java`

**Interfaces:**
- Consumes: `ReviewFindingRepository` (Task 1), `Finding` (existing).
- Produces: `SdlcRunRecorder.onRunRecorded` now also writes `ReviewFinding` rows when a `NEEDS_WORK` event's data carries `findingsDetail`.

- [ ] **Step 1: Write the failing test**

Add to `src/test/java/ai/devflow/history/SdlcRunRecorderTest.java` (add the `ReviewFindingRepository` autowired field alongside the existing three):

```java
    @Autowired ReviewFindingRepository findings;
```

```java
    @Test
    void needsWorkEventWithFindingsDetailWritesReviewFindingRows() {
        var f = new ai.devflow.agent.Finding(ai.devflow.agent.Finding.Origin.REVIEWER,
                ai.devflow.agent.Finding.Severity.HIGH, "A.java", 14, "missing @Valid");

        recorder.onRunRecorded(new RunRecorded("r8", new RunEvent("step", "Reviewer — needs work: nope",
                Map.of("phase", "REVIEWING", "findings", 1, "findingsDetail", java.util.List.of(f), "reviewIteration", 1))));

        var saved = findings.findByRunId("r8");
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).message()).isEqualTo("missing @Valid");
        assertThat(saved.get(0).reviewIteration()).isEqualTo(1);
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests '*SdlcRunRecorderTest*'`
Expected: FAIL — `findings.findByRunId("r8")` is empty (nothing writes `ReviewFinding` rows yet).

- [ ] **Step 3: Enrich the `NEEDS_WORK` emit in `Orchestrator`**

Read `src/main/java/ai/devflow/orchestrator/Orchestrator.java` first. In the `reviewLoop`'s `switch (reviewed.status())`, change the `NEEDS_WORK` case:

```java
                case NEEDS_WORK -> {
                    state.addFindings(reviewed.findings());
                    emit(state, "step", "Reviewer — needs work: " + reviewed.summary(),
                            Map.of("findings", reviewed.findings().size(),
                                   "findingsDetail", reviewed.findings(),
                                   "reviewIteration", state.reviewIterations()));
                    continue;
                }
```

- [ ] **Step 4: Add the `ReviewFindingRepository` dependency and finding-writing logic to `SdlcRunRecorder`**

Read `src/main/java/ai/devflow/history/SdlcRunRecorder.java` first (Task 2's version). Add imports:
```java
import ai.devflow.agent.Finding;

import java.util.List;
```

Change the constructor and field:
```java
    private final SdlcRunRepository runs;
    private final StageExecutionRepository stages;
    private final ReviewFindingRepository findings;

    public SdlcRunRecorder(SdlcRunRepository runs, StageExecutionRepository stages, ReviewFindingRepository findings) {
        this.runs = runs;
        this.stages = stages;
        this.findings = findings;
    }
```

Add a call and a new private method inside `onRunRecorded`:
```java
    @EventListener
    public void onRunRecorded(RunRecorded recorded) {
        String runId = recorded.runId();
        Map<String, Object> data = recorded.event().data();

        maybeCreateRun(runId, data);
        maybeRecordStage(runId, data);
        maybeRecordFindings(runId, data);
        maybeFinishRun(runId, recorded.event().type(), recorded.event().message(), data);
    }
```

```java
    private void maybeRecordFindings(String runId, Map<String, Object> data) {
        if (!(data.get("findingsDetail") instanceof List<?> list)) return;
        int iteration = data.get("reviewIteration") instanceof Integer i ? i : 0;
        for (Object o : list) {
            if (o instanceof Finding f) {
                findings.save(new ReviewFinding(runId, iteration, f));
            }
        }
    }
```

- [ ] **Step 5: Run test to verify it passes**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests '*SdlcRunRecorderTest*'`
Expected: PASS (8 tests).

- [ ] **Step 6: Run the full suite**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/ai/devflow/orchestrator/Orchestrator.java src/main/java/ai/devflow/history/SdlcRunRecorder.java \
        src/test/java/ai/devflow/history/SdlcRunRecorderTest.java
git commit -m "feat: persist ReviewFinding rows from reviewer bounce-backs"
```

---

# Task 4: `Approval` recording

**Files:**
- Create: `src/main/java/ai/devflow/event/ApprovalRecorded.java`
- Modify: `src/main/java/ai/devflow/web/RunController.java`
- Modify: `src/main/java/ai/devflow/history/SdlcRunRecorder.java`
- Test: `src/test/java/ai/devflow/web/RunControllerTest.java`
- Test: `src/test/java/ai/devflow/history/SdlcRunRecorderTest.java`

**Interfaces:**
- Consumes: `ApprovalRepository` (Task 1).
- Produces: `ApprovalRecorded(String runId, String gate, boolean approved, String reason)`; `RunController`'s constructor gains an `ApplicationEventPublisher` (3rd param, after `RunRegistry` and `RunEventPublisher`).

- [ ] **Step 1: Write the failing `SdlcRunRecorder` test**

Add to `src/test/java/ai/devflow/history/SdlcRunRecorderTest.java`:

```java
    @Autowired ApprovalRepository approvals;
```

```java
    @Test
    void approvalRecordedEventWritesAnApprovalRow() {
        recorder.onApprovalRecorded(new ai.devflow.event.ApprovalRecorded("r9", "BEFORE_BUILD", false, "use a DTO"));

        var saved = approvals.findByRunId("r9");
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).gate()).isEqualTo(ai.devflow.orchestrator.Gate.BEFORE_BUILD);
        assertThat(saved.get(0).approved()).isFalse();
        assertThat(saved.get(0).reason()).isEqualTo("use a DTO");
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests '*SdlcRunRecorderTest*'`
Expected: compilation failure — `ApprovalRecorded` and `onApprovalRecorded` do not exist yet.

- [ ] **Step 3: Create `ApprovalRecorded`**

`src/main/java/ai/devflow/event/ApprovalRecorded.java`:

```java
package ai.devflow.event;

/**
 * Fired by {@code RunController.approve} when a gate decision is accepted.
 * Plain fields, not {@code Gate}/{@code ApprovalDecision} — see the plan's
 * design decision 2 on why {@code ai.devflow.event} must not depend on
 * {@code ai.devflow.orchestrator}.
 */
public record ApprovalRecorded(String runId, String gate, boolean approved, String reason) {}
```

- [ ] **Step 4: Add the listener to `SdlcRunRecorder`**

Read `src/main/java/ai/devflow/history/SdlcRunRecorder.java` first (Task 3's version). Add imports:
```java
import ai.devflow.event.ApprovalRecorded;
import ai.devflow.orchestrator.Gate;
```

Add field/constructor param and the new listener method:
```java
    private final ApprovalRepository approvals;

    public SdlcRunRecorder(SdlcRunRepository runs, StageExecutionRepository stages,
                            ReviewFindingRepository findings, ApprovalRepository approvals) {
        this.runs = runs;
        this.stages = stages;
        this.findings = findings;
        this.approvals = approvals;
    }
```

```java
    @EventListener
    public void onApprovalRecorded(ApprovalRecorded recorded) {
        approvals.save(new Approval(recorded.runId(), Gate.valueOf(recorded.gate()),
                recorded.approved(), recorded.reason(), Instant.now()));
    }
```

- [ ] **Step 5: Run the `SdlcRunRecorder` test to verify it passes**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests '*SdlcRunRecorderTest*'`
Expected: PASS (9 tests).

- [ ] **Step 6: Wire `RunController.approve` to fire the event**

Read `src/main/java/ai/devflow/web/RunController.java` first. Its existing `ai.devflow.orchestrator` imports are individual, not a wildcard (`ApprovalDecision`, `RunHandle`, `RunRegistry` — no `Gate`). Add imports:
```java
import ai.devflow.event.ApprovalRecorded;
import ai.devflow.orchestrator.Gate;
import org.springframework.context.ApplicationEventPublisher;
```

Add a field and constructor param:
```java
    private final ApplicationEventPublisher applicationEvents;

    public RunController(RunRegistry registry, RunEventPublisher events, ApplicationEventPublisher applicationEvents) {
        this.registry = registry;
        this.events = events;
        this.applicationEvents = applicationEvents;
    }
```

Change `approve`:
```java
    @PostMapping("/{runId}/approve")
    public ResponseEntity<Map<String, String>> approve(@PathVariable String runId,
                                                       @RequestBody ApproveRequest request) {
        RunHandle handle = registry.find(runId);
        if (handle == null) {
            return ResponseEntity.notFound().build();
        }
        ApprovalDecision decision = request.approved()
                ? ApprovalDecision.approve()
                : ApprovalDecision.rejectWith(request.reason());

        Gate pendingGate = handle.gate().pending();
        if (!handle.gate().decide(decision)) {
            return ResponseEntity.status(409)
                    .body(Map.of("error", "no gate is currently awaiting a decision"));
        }
        if (pendingGate != null) {
            applicationEvents.publishEvent(new ApprovalRecorded(
                    runId, pendingGate.name(), decision.approved(), decision.reason()));
        }
        return ResponseEntity.ok(Map.of("status", "accepted"));
    }
```

- [ ] **Step 7: Update `RunControllerTest`'s construction and add the firing test**

Read `src/test/java/ai/devflow/web/RunControllerTest.java` first. Add a field and change `setUp()`:
```java
    java.util.List<Object> publishedEvents = new java.util.ArrayList<>();
```
```java
    @BeforeEach
    void setUp() {
        registry = mock(RunRegistry.class);
        events = new RunEventPublisher();
        mvc = MockMvcBuilders.standaloneSetup(
                new RunController(registry, events, publishedEvents::add)).build();
    }
```

Add a test:
```java
    @Test
    void approvingAPendingGateFiresAnApprovalRecordedEvent() throws Exception {
        RunHandle handle = handleFor("run-3");
        when(registry.find("run-3")).thenReturn(handle);

        var pool = Executors.newSingleThreadExecutor();
        pool.submit(() -> handle.gate().await(Gate.PRE_FLIGHT));
        while (handle.gate().pending() == null) Thread.sleep(5);

        mvc.perform(post("/api/runs/run-3/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new ApproveRequest(true, null))))
                .andExpect(status().isOk());

        assertThat(publishedEvents).containsExactly(
                new ai.devflow.event.ApprovalRecorded("run-3", "PRE_FLIGHT", true, null));

        pool.shutdownNow();
        handle.state().workspace().cleanup();
    }
```
Add `import static org.assertj.core.api.Assertions.assertThat;` if not already present in the file (check first — it currently is not imported there).

- [ ] **Step 8: Run the test to verify it passes**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests '*RunControllerTest*'`
Expected: PASS (all tests in the file, old and new).

- [ ] **Step 9: Run the full suite**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test`
Expected: PASS. `RunFlowIntegrationTest`/`StaticPageTest` construct `RunController` via Spring autowiring, so the new constructor param resolves automatically (`ApplicationEventPublisher` is always available in any `ApplicationContext`) — no changes needed there.

- [ ] **Step 10: Commit**

```bash
git add src/main/java/ai/devflow/event/ApprovalRecorded.java src/main/java/ai/devflow/web/RunController.java \
        src/main/java/ai/devflow/history/SdlcRunRecorder.java \
        src/test/java/ai/devflow/web/RunControllerTest.java src/test/java/ai/devflow/history/SdlcRunRecorderTest.java
git commit -m "feat: persist Approval rows from gate decisions"
```

---

# Task 5: `CodingWorker` seam + `CoderAgent` refactor

**Files:**
- Create: `src/main/java/ai/devflow/worker/WorkerRequest.java`
- Create: `src/main/java/ai/devflow/worker/WorkerResult.java`
- Create: `src/main/java/ai/devflow/worker/CodingWorker.java`
- Create: `src/main/java/ai/devflow/worker/SpringAiCodingWorker.java`
- Modify: `src/main/java/ai/devflow/agent/CoderAgent.java`
- Modify: `src/main/java/ai/devflow/config/OrchestrationConfig.java`
- Test: `src/test/java/ai/devflow/worker/SpringAiCodingWorkerTest.java`
- Test: `src/test/java/ai/devflow/agent/CoderAgentTest.java`

**Interfaces:**
- Produces: `WorkerRequest(String prompt, List<Object> tools)`; `WorkerResult(String text, TokenUsage tokens)`; `CodingWorker { WorkerResult run(WorkerRequest request); }`; `SpringAiCodingWorker(ChatClient chatClient)`.
- Consumes: `TokenUsage`, `UsageMapper` (existing, from `ai.devflow.agent`).

- [ ] **Step 1: Write the failing `SpringAiCodingWorker` test**

Create `src/test/java/ai/devflow/worker/SpringAiCodingWorkerTest.java`:

```java
package ai.devflow.worker;

import ai.devflow.agent.TokenUsage;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SpringAiCodingWorkerTest {

    private static ChatResponse responseWith(String text, int promptTokens, int completionTokens) {
        var generation = new Generation(new AssistantMessage(text));
        var metadata = ChatResponseMetadata.builder()
                .usage(new DefaultUsage(promptTokens, completionTokens))
                .build();
        return new ChatResponse(List.of(generation), metadata);
    }

    @Test
    void sendsThePromptAndToolsAndMapsTheResponse() {
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(client.prompt().user(any(String.class)).tools(any(Object[].class)).call().chatResponse())
                .thenReturn(responseWith("done", 42, 7));

        Object tool = new Object();
        var worker = new SpringAiCodingWorker(client);
        WorkerResult result = worker.run(new WorkerRequest("do the thing", List.of(tool)));

        assertThat(result.text()).isEqualTo("done");
        assertThat(result.tokens()).isEqualTo(new TokenUsage(42, 7));
        verify(client.prompt()).user("do the thing");
    }

    @Test
    void aNullResponseBecomesEmptyTextNotAnException() {
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(client.prompt().user(any(String.class)).tools(any(Object[].class)).call().chatResponse())
                .thenReturn(null);

        WorkerResult result = new SpringAiCodingWorker(client).run(new WorkerRequest("t", List.of()));

        assertThat(result.text()).isEmpty();
        assertThat(result.tokens()).isEqualTo(TokenUsage.NONE);
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests '*SpringAiCodingWorkerTest*'`
Expected: compilation failure — none of `WorkerRequest`/`WorkerResult`/`CodingWorker`/`SpringAiCodingWorker` exist yet.

- [ ] **Step 3: Create the seam types**

`src/main/java/ai/devflow/worker/WorkerRequest.java`:

```java
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
```

`src/main/java/ai/devflow/worker/WorkerResult.java`:

```java
package ai.devflow.worker;

import ai.devflow.agent.TokenUsage;

public record WorkerResult(String text, TokenUsage tokens) {}
```

`src/main/java/ai/devflow/worker/CodingWorker.java`:

```java
package ai.devflow.worker;

/** Provider-neutral seam over "produce or review code" — today Spring AI, later possibly an external CLI agent. */
public interface CodingWorker {
    WorkerResult run(WorkerRequest request);
}
```

`src/main/java/ai/devflow/worker/SpringAiCodingWorker.java`:

```java
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
```

- [ ] **Step 4: Run the worker test to verify it passes**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests '*SpringAiCodingWorkerTest*'`
Expected: PASS (2 tests).

- [ ] **Step 5: Refactor `CoderAgent` onto `CodingWorker`**

Read `src/main/java/ai/devflow/agent/CoderAgent.java` first (shown in full in the summary above). Replace its entire content:

```java
package ai.devflow.agent;

import ai.devflow.orchestrator.RunState;
import ai.devflow.tools.FileTools;
import ai.devflow.worker.CodingWorker;
import ai.devflow.worker.WorkerRequest;
import ai.devflow.worker.WorkerResult;

import java.util.List;
import java.util.stream.Collectors;

public class CoderAgent implements Agent {

    private final CodingWorker worker;

    public CoderAgent(CodingWorker worker) {
        this.worker = worker;
    }

    @Override public String name() { return "coder"; }

    @Override
    public AgentResult run(RunState state) {
        String prompt = buildPrompt(state);
        List<Object> tools = List.of(new FileTools(state.workspace().guard()), state.gitTools());

        WorkerResult result = worker.run(new WorkerRequest(prompt, tools));

        // Derived from git, never from what the model claims.
        List<String> touched = state.gitTools().changedFiles();

        return AgentResult.ok(name(), result.text(), touched, result.tokens());
    }

    private String buildPrompt(RunState state) {
        StringBuilder sb = new StringBuilder();
        sb.append("Task: ").append(state.task()).append("\n\n");

        if (!state.memory().isBlank()) {
            sb.append("Known facts about this repository from previous runs:\n")
              .append(state.memory())
              .append("\n\n");
        }

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
```

Task 7 adds a `state.plan()` section to this same method once `RunState.plan()` exists — not yet, in this task.

- [ ] **Step 6: Update `CoderAgentTest`'s construction**

Read `src/test/java/ai/devflow/agent/CoderAgentTest.java` first. Add the import:
```java
import ai.devflow.worker.SpringAiCodingWorker;
```
Change every `new CoderAgent(client)` to `new CoderAgent(new SpringAiCodingWorker(client))` — there are 4 occurrences (`filesTouchedComesFromGitNotFromTheModel`, `openFindingsAreIncludedInThePrompt`, `memoryIsIncludedInThePromptAsItsOwnSection`, `reportsRealTokenUsageFromTheResponse`). No other line in the file changes.

- [ ] **Step 7: Update `OrchestrationConfig`'s coder wiring**

Read `src/main/java/ai/devflow/config/OrchestrationConfig.java` first. Add the import:
```java
import ai.devflow.worker.CodingWorker;
import ai.devflow.worker.SpringAiCodingWorker;
```
Change:
```java
    @Bean
    Agent coderAgent(@Qualifier("coder") ChatClient coderChatClient) {
        return new CoderAgent(coderChatClient);
    }
```
to:
```java
    @Bean @Qualifier("coder")
    CodingWorker coderWorker(@Qualifier("coder") ChatClient coderChatClient) {
        return new SpringAiCodingWorker(coderChatClient);
    }

    @Bean
    Agent coderAgent(@Qualifier("coder") CodingWorker coderWorker) {
        return new CoderAgent(coderWorker);
    }
```

- [ ] **Step 8: Run the full suite**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test`
Expected: PASS. `CoderAgentTest`'s assertions on prompt content, tool wiring (`any(Object[].class)`), and token usage all still hold — only the construction line changed.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/ai/devflow/worker src/main/java/ai/devflow/agent/CoderAgent.java \
        src/main/java/ai/devflow/config/OrchestrationConfig.java \
        src/test/java/ai/devflow/worker/SpringAiCodingWorkerTest.java src/test/java/ai/devflow/agent/CoderAgentTest.java
git commit -m "refactor: CoderAgent delegates to a provider-neutral CodingWorker"
```

---

# Task 6: `ReviewerAgent` refactor onto `CodingWorker`

**Files:**
- Modify: `src/main/java/ai/devflow/agent/ReviewerAgent.java`
- Modify: `src/main/java/ai/devflow/config/OrchestrationConfig.java`
- Test: `src/test/java/ai/devflow/agent/ReviewerAgentTest.java`

**Interfaces:**
- Consumes: `CodingWorker`, `WorkerRequest`, `WorkerResult` (Task 5).

- [ ] **Step 1: Refactor `ReviewerAgent`**

Read `src/main/java/ai/devflow/agent/ReviewerAgent.java` first (shown in full in the summary above). Replace its content, keeping `parse(...)` byte-for-byte identical to today:

```java
package ai.devflow.agent;

import ai.devflow.orchestrator.RunState;
import ai.devflow.tools.ReadOnlyFileTools;
import ai.devflow.worker.CodingWorker;
import ai.devflow.worker.WorkerRequest;
import ai.devflow.worker.WorkerResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

public class ReviewerAgent implements Agent {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CodingWorker worker;

    public ReviewerAgent(CodingWorker worker) { this.worker = worker; }

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

        List<Object> tools = List.of(new ReadOnlyFileTools(state.workspace().guard()));
        WorkerResult result = worker.run(new WorkerRequest(prompt, tools));

        return parse(result.text(), result.tokens());
    }

    private AgentResult parse(String raw, TokenUsage usage) {
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

            String status = node.path("status").asText("");
            if (!status.equals("OK") && !status.equals("NEEDS_WORK")) {
                throw new IllegalArgumentException("Reviewer returned an unrecognized status: " + status);
            }

            boolean needsWork = status.equals("NEEDS_WORK") || !findings.isEmpty();

            return needsWork
                    ? AgentResult.needsWork(name(), summary, findings, usage)
                    : AgentResult.ok(name(), summary, List.of(), usage);

        } catch (Exception e) {
            return new AgentResult(name(), AgentResult.Status.FAILED,
                    "Could not parse reviewer output: " + e.getMessage(),
                    List.of(), List.of(), usage);
        }
    }
}
```

- [ ] **Step 2: Update `ReviewerAgentTest`'s construction**

Read `src/test/java/ai/devflow/agent/ReviewerAgentTest.java` first. Add the import:
```java
import ai.devflow.worker.SpringAiCodingWorker;
```
Change every `new ReviewerAgent(client)` to `new ReviewerAgent(new SpringAiCodingWorker(client))` — 7 occurrences. No other line changes.

- [ ] **Step 3: Update `OrchestrationConfig`'s reviewer wiring**

Read `src/main/java/ai/devflow/config/OrchestrationConfig.java` first (Task 5's version). Change:
```java
    @Bean
    Agent reviewerAgent(@Qualifier("reviewer") ChatClient reviewerChatClient) {
        return new ReviewerAgent(reviewerChatClient);
    }
```
to:
```java
    @Bean @Qualifier("reviewer")
    CodingWorker reviewerWorker(@Qualifier("reviewer") ChatClient reviewerChatClient) {
        return new SpringAiCodingWorker(reviewerChatClient);
    }

    @Bean
    Agent reviewerAgent(@Qualifier("reviewer") CodingWorker reviewerWorker) {
        return new ReviewerAgent(reviewerWorker);
    }
```

- [ ] **Step 4: Run the full suite**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test`
Expected: PASS. Every `ReviewerAgentTest` assertion (JSON parsing, markdown-fence tolerance, fail-closed behavior, token usage) is unchanged in substance.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/ai/devflow/agent/ReviewerAgent.java src/main/java/ai/devflow/config/OrchestrationConfig.java \
        src/test/java/ai/devflow/agent/ReviewerAgentTest.java
git commit -m "refactor: ReviewerAgent delegates to a provider-neutral CodingWorker"
```

---

# Task 7: `PlannerAgent` + `Orchestrator` integration

**Files:**
- Create: `src/main/java/ai/devflow/agent/PlannerAgent.java`
- Modify: `src/main/java/ai/devflow/orchestrator/RunState.java`
- Modify: `src/main/java/ai/devflow/orchestrator/Orchestrator.java`
- Modify: `src/main/java/ai/devflow/agent/CoderAgent.java`
- Modify: `src/main/java/ai/devflow/config/ChatClientConfig.java`
- Modify: `src/main/java/ai/devflow/config/OrchestrationConfig.java`
- Modify: `src/test/java/ai/devflow/web/RunFlowIntegrationTest.java`
- Test: `src/test/java/ai/devflow/agent/PlannerAgentTest.java`
- Test: `src/test/java/ai/devflow/orchestrator/OrchestratorTest.java`

**Interfaces:**
- Produces: `PlannerAgent(CodingWorker worker) implements Agent`, `name() == "planner"`; `RunState.plan()`/`setPlan(String)`; `Orchestrator`'s constructor gains an `Agent planner` parameter (3rd position, after `reviewer`).

- [ ] **Step 1: Add `RunState.plan()`**

Read `src/main/java/ai/devflow/orchestrator/RunState.java` first. Add a field next to `memory`:
```java
    private String plan = "";
```
Add accessors next to `memory()`/`setMemory(...)`:
```java
    public synchronized String plan() { return plan; }
    public synchronized void setPlan(String plan) { this.plan = plan == null ? "" : plan; }
```

- [ ] **Step 2: Add the plan section to `CoderAgent.buildPrompt`**

Read `src/main/java/ai/devflow/agent/CoderAgent.java` first (Task 5's version). In `buildPrompt`, right after the `Task:` line and before the `memory` block, add:
```java
        if (!state.plan().isBlank()) {
            sb.append("Plan for this task:\n").append(state.plan()).append("\n\n");
        }
```

- [ ] **Step 3: Write the failing `PlannerAgentTest`**

Create `src/test/java/ai/devflow/agent/PlannerAgentTest.java`:

```java
package ai.devflow.agent;

import ai.devflow.orchestrator.RunState;
import ai.devflow.worker.SpringAiCodingWorker;
import ai.devflow.workspace.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PlannerAgentTest {

    Workspace workspace;

    @BeforeEach
    void setUp() throws Exception {
        workspace = new FixtureWorkspace(Path.of("src/test/resources/fixture"), "planner-test");
        workspace.prepare();
    }

    @AfterEach
    void tearDown() throws Exception { workspace.cleanup(); }

    private static ChatResponse responseWith(String text, int promptTokens, int completionTokens) {
        var generation = new Generation(new AssistantMessage(text));
        var metadata = ChatResponseMetadata.builder()
                .usage(new DefaultUsage(promptTokens, completionTokens))
                .build();
        return new ChatResponse(List.of(generation), metadata);
    }

    @Test
    void planTextBecomesTheSummary() {
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(client.prompt().user(any(String.class)).tools(any(Object[].class)).call().chatResponse())
                .thenReturn(responseWith("1. Add @Valid\n2. Add a test", 10, 5));

        var agent = new PlannerAgent(new SpringAiCodingWorker(client));
        var state = new RunState("planner-test", "add validation", workspace);

        AgentResult result = agent.run(state);

        assertThat(result.status()).isEqualTo(AgentResult.Status.OK);
        assertThat(result.summary()).isEqualTo("1. Add @Valid\n2. Add a test");
        assertThat(result.filesTouched()).isEmpty();
    }

    @Test
    void memoryAndLoadedSkillsAreIncludedInThePrompt() {
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(client.prompt().user(any(String.class)).tools(any(Object[].class)).call().chatResponse())
                .thenReturn(responseWith("a plan", 10, 5));

        var agent = new PlannerAgent(new SpringAiCodingWorker(client));
        var state = new RunState("planner-test", "add validation", workspace);
        state.setMemory("tests use JUnit 5 + AssertJ");
        state.addLoadedSkill("## Steps\n1. Add @Valid\n");

        agent.run(state);

        var promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(client.prompt(), atLeastOnce()).user(promptCaptor.capture());
        assertThat(promptCaptor.getValue()).contains("tests use JUnit 5 + AssertJ");
        assertThat(promptCaptor.getValue()).contains("Add @Valid");
    }

    @Test
    void reportsRealTokenUsageFromTheResponse() {
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(client.prompt().user(any(String.class)).tools(any(Object[].class)).call().chatResponse())
                .thenReturn(responseWith("a plan", 321, 123));

        var result = new PlannerAgent(new SpringAiCodingWorker(client)).run(new RunState("u", "t", workspace));

        assertThat(result.tokens()).isEqualTo(new TokenUsage(321, 123));
    }
}
```

- [ ] **Step 4: Run to verify it fails**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests '*PlannerAgentTest*'`
Expected: compilation failure — `PlannerAgent` does not exist yet.

- [ ] **Step 5: Create `PlannerAgent`**

`src/main/java/ai/devflow/agent/PlannerAgent.java`:

```java
package ai.devflow.agent;

import ai.devflow.orchestrator.RunState;
import ai.devflow.worker.CodingWorker;
import ai.devflow.worker.WorkerRequest;
import ai.devflow.worker.WorkerResult;

import java.util.List;

/**
 * Produces a short plan once per run, before the coder's first call (spec §5).
 * Never returns an explicit FAILED status — see this plan's design decision 7.
 */
public class PlannerAgent implements Agent {

    private final CodingWorker worker;

    public PlannerAgent(CodingWorker worker) { this.worker = worker; }

    @Override public String name() { return "planner"; }

    @Override
    public AgentResult run(RunState state) {
        WorkerResult result = worker.run(new WorkerRequest(buildPrompt(state), List.of()));
        return AgentResult.ok(name(), result.text(), List.of(), result.tokens());
    }

    private String buildPrompt(RunState state) {
        StringBuilder sb = new StringBuilder();
        sb.append("Task: ").append(state.task()).append("\n\n");

        if (!state.memory().isBlank()) {
            sb.append("Known facts about this repository from previous runs:\n")
              .append(state.memory())
              .append("\n\n");
        }

        if (!state.loadedSkills().isEmpty()) {
            sb.append("Relevant knowledge from previous runs on this repository:\n")
              .append(String.join("\n\n", state.loadedSkills()))
              .append("\n\n");
        }

        sb.append("Write a short, concrete step-by-step plan for how the coder should implement this task. ")
          .append("Do not write any code yourself. Keep it to a few sentences or a short numbered list.");
        return sb.toString();
    }
}
```

- [ ] **Step 6: Run `PlannerAgentTest` to verify it passes**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests '*PlannerAgentTest*'`
Expected: PASS (3 tests).

- [ ] **Step 7: Wire `Orchestrator` to run the planner once, after Gate 1**

Read `src/main/java/ai/devflow/orchestrator/Orchestrator.java` first (Task 3's version). Add a field and constructor param:
```java
    private final Agent coder;
    private final Agent reviewer;
    private final Agent planner;
    private final SkillPicker skillPicker;
```
```java
    public Orchestrator(Agent coder, Agent reviewer, Agent planner, SkillPicker skillPicker, Scribe scribe,
                        SkillStore skillStore, MemoryStore memoryStore,
                        RunEventPublisher events, int maxReviewIterations, int maxHumanIterations,
                        Duration buildTimeout) {
        this.coder = coder;
        this.reviewer = reviewer;
        this.planner = planner;
        this.skillPicker = skillPicker;
        this.scribe = scribe;
        this.skillStore = skillStore;
        this.memoryStore = memoryStore;
        this.events = events;
        this.maxReviewIterations = maxReviewIterations;
        this.maxHumanIterations = maxHumanIterations;
        this.buildTimeout = buildTimeout;
    }
```

In `execute(...)`, right after the Gate 1 `if (!preFlight.approved()) { ... }` block and before `AgentResult lastReview = null;`, add:
```java
        // ---- Planner: once per run, after Gate 1 (spec §5) ----------------
        emit(state, "step", "Planner — thinking…", Map.of());
        AgentResult planned = planner.run(state);
        state.record(planned);
        state.setPlan(planned.summary());
        emit(state, "step", "Planner — plan ready", Map.of("plan", planned.summary()));
```

- [ ] **Step 8: Update `OrchestratorTest`'s helpers and add the planner tests**

Read `src/test/java/ai/devflow/orchestrator/OrchestratorTest.java` first (shown in full in the summary above). Add an instance field near the top of the class (alongside `workspace`/`events`/`pool`):
```java
    Agent planner = defaultPlanner();
```
Add a static helper near `ScriptedAgent`/`ExplodingAgent`:
```java
    static Agent defaultPlanner() {
        return new Agent() {
            @Override public String name() { return "planner"; }
            @Override public AgentResult run(RunState s) {
                return AgentResult.ok("planner", "plan", List.of(), TokenUsage.NONE);
            }
        };
    }
```
Change both `orchestrator(...)` overloads to thread the field through:
```java
    private Orchestrator orchestrator(Agent coder, Agent reviewer) {
        return orchestrator(coder, reviewer, (task, index) -> List.of(),
                (state, findings, reason) -> ScribeDraft.EMPTY, new FakeSkillStore(), new FakeMemoryStore());
    }

    private Orchestrator orchestrator(Agent coder, Agent reviewer, SkillPicker picker, Scribe scribe,
                                      SkillStore skillStore, MemoryStore memoryStore) {
        return new Orchestrator(coder, reviewer, planner, picker, scribe, skillStore, memoryStore,
                events, 3, 5, Duration.ofMinutes(1));
    }
```
(No other existing test method needs to change — every call site keeps its current argument list; `planner` is read from the instance field, defaulting to `defaultPlanner()` for every test that does not override it.)

Add two new tests at the end of the class, before the closing brace:
```java
    @Test
    void plannerRunsExactlyOnceEvenAcrossMultipleReviewIterations() throws Exception {
        var coder = writingCoder("attempt");
        var bounce = AgentResult.needsWork("reviewer", "nope",
                List.of(new Finding(Finding.Origin.REVIEWER, Finding.Severity.HIGH, "A.java", 1, "still wrong")), TokenUsage.NONE);
        var ok = AgentResult.ok("reviewer", "looks good now", List.of(), TokenUsage.NONE);
        var reviewer = new ScriptedAgent("reviewer", List.of(bounce, ok));
        var plannerCalls = new AtomicInteger(0);
        planner = new Agent() {
            @Override public String name() { return "planner"; }
            @Override public AgentResult run(RunState s) {
                plannerCalls.incrementAndGet();
                return AgentResult.ok("planner", "1. Add validation\n2. Add a test", List.of(), TokenUsage.NONE);
            }
        };
        var state = new RunState("g22", "t", workspace);
        var gate = new ApprovalGate(Duration.ofSeconds(10));

        var outcome = runApprovingAll(orchestrator(coder, reviewer), state, gate);

        assertThat(outcome.approved()).isTrue();
        assertThat(state.reviewIterations()).isEqualTo(2);
        assertThat(plannerCalls.get())
                .as("planner is a once-per-run artifact, not re-derived each review iteration")
                .isEqualTo(1);
        assertThat(state.plan()).isEqualTo("1. Add validation\n2. Add a test");
    }

    @Test
    void plannerNeverRunsIfPreFlightIsRejected() throws Exception {
        var coder = writingCoder("should never run");
        var reviewer = new ScriptedAgent("reviewer",
                List.of(AgentResult.ok("reviewer", "ok", List.of(), TokenUsage.NONE)));
        var plannerCalls = new AtomicInteger(0);
        planner = new Agent() {
            @Override public String name() { return "planner"; }
            @Override public AgentResult run(RunState s) {
                plannerCalls.incrementAndGet();
                return AgentResult.ok("planner", "p", List.of(), TokenUsage.NONE);
            }
        };
        var state = new RunState("g23", "t", workspace);
        var gate = new ApprovalGate(Duration.ofSeconds(10));

        Future<Orchestrator.RunOutcome> f = pool.submit(() -> orchestrator(coder, reviewer).run(state, gate));
        while (gate.pending() == null) Thread.sleep(5);
        gate.decide(ApprovalDecision.reject());

        assertThat(f.get(10, TimeUnit.SECONDS).approved()).isFalse();
        assertThat(plannerCalls.get()).isZero();
    }
```

- [ ] **Step 9: Run `OrchestratorTest` to verify it passes**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests '*OrchestratorTest*'`
Expected: PASS (all existing tests plus the 2 new ones).

- [ ] **Step 10: Wire the planner's `ChatClient`/`CodingWorker`/`Agent` beans**

Read `src/main/java/ai/devflow/config/ChatClientConfig.java` first (Task 5/6 did not touch it). Add a new bean, using the existing `opusOptions` helper as-is (Task 8 renames it):
```java
    @Bean @Qualifier("planner")
    ChatClient plannerChatClient(ChatClient.Builder builder) {
        return builder.defaultOptions(opusOptions(ModelNames.OPUS))
                .defaultSystem("""
                    You are the Planner in an automated development crew.
                    Read the task and write a short, concrete plan for the coder to follow.
                    Do not write any code yourself. Answer in plain text, not JSON.
                    """)
                .build();
    }
```

Read `src/main/java/ai/devflow/config/OrchestrationConfig.java` first (Task 6's version). Add:
```java
    @Bean @Qualifier("planner")
    CodingWorker plannerWorker(@Qualifier("planner") ChatClient plannerChatClient) {
        return new SpringAiCodingWorker(plannerChatClient);
    }

    @Bean
    Agent plannerAgent(@Qualifier("planner") CodingWorker plannerWorker) {
        return new PlannerAgent(plannerWorker);
    }
```
Change the `orchestrator` bean method to take and pass the new agent:
```java
    @Bean
    Orchestrator orchestrator(Agent coderAgent, Agent reviewerAgent, Agent plannerAgent, SkillPicker skillPicker, Scribe scribe,
                              SkillStore skillStore, MemoryStore memoryStore, RunEventPublisher events,
                              @Value("${devflowai.review.max-iterations:3}") int maxReviewIterations,
                              @Value("${devflowai.review.max-human-iterations:5}") int maxHumanIterations,
                              @Value("${devflowai.build.timeout-minutes:5}") long buildTimeoutMinutes) {
        return new Orchestrator(coderAgent, reviewerAgent, plannerAgent, skillPicker, scribe, skillStore, memoryStore,
                events, maxReviewIterations, maxHumanIterations, Duration.ofMinutes(buildTimeoutMinutes));
    }
```
Add the import `import ai.devflow.agent.PlannerAgent;` alongside the existing `import ai.devflow.agent.*;` (already a wildcard import, so `PlannerAgent` needs no new import line — confirm the file uses `import ai.devflow.agent.*;` before skipping this).

- [ ] **Step 11: Add a `plannerAgent` stub override to `RunFlowIntegrationTest`**

Read `src/test/java/ai/devflow/web/RunFlowIntegrationTest.java` first (shown in full in the summary above). Without this step, its `@SpringBootTest` context now wires a REAL `plannerAgent` bean calling the real Opus 5 API with a fake API key, breaking every non-`@Tag("live")` test in the file. Add:
```java
    @TestBean(name = "plannerAgent", methodName = "stubPlannerAgent")
    Agent plannerAgentOverride;
```
```java
    static Agent stubPlannerAgent() {
        return new Agent() {
            @Override public String name() { return "planner"; }
            @Override public AgentResult run(RunState s) {
                return AgentResult.ok("planner", "1. Make the change\n2. Verify it", List.of(), TokenUsage.NONE);
            }
        };
    }
```
(Place both alongside the existing `coderAgentOverride`/`stubCoderAgent` pair, following the same `@TestBean` pattern already used for `coderAgent`/`reviewerAgent`/`scribeAgent`/`skillStore`/`memoryStore`.)

- [ ] **Step 12: Run the full suite**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test`
Expected: PASS.

- [ ] **Step 13: Commit**

```bash
git add src/main/java/ai/devflow/agent/PlannerAgent.java src/main/java/ai/devflow/orchestrator/RunState.java \
        src/main/java/ai/devflow/orchestrator/Orchestrator.java src/main/java/ai/devflow/agent/CoderAgent.java \
        src/main/java/ai/devflow/config/ChatClientConfig.java src/main/java/ai/devflow/config/OrchestrationConfig.java \
        src/test/java/ai/devflow/web/RunFlowIntegrationTest.java \
        src/test/java/ai/devflow/agent/PlannerAgentTest.java src/test/java/ai/devflow/orchestrator/OrchestratorTest.java
git commit -m "feat: add a PlannerAgent that runs once per run, after Gate 1"
```

---

# Task 8: Model-tier reassignment

**Files:**
- Modify: `src/main/java/ai/devflow/config/ModelNames.java`
- Modify: `src/main/java/ai/devflow/config/ChatClientConfig.java`
- Test: `src/test/java/ai/devflow/config/ChatClientConfigTest.java`

**Interfaces:**
- Produces: `ModelNames.SONNET = "claude-sonnet-5"`.

- [ ] **Step 1: Write the failing test**

Read `src/test/java/ai/devflow/config/ChatClientConfigTest.java` first. Add:
```java
    @Test
    void sonnetIsPinnedForTheRepeatedPerLoopAgents() {
        assertThat(ModelNames.SONNET).isEqualTo("claude-sonnet-5");
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests '*ChatClientConfigTest*'`
Expected: compilation failure — `ModelNames.SONNET` does not exist yet.

- [ ] **Step 3: Add the constant**

Read `src/main/java/ai/devflow/config/ModelNames.java` first. Change:
```java
package ai.devflow.config;

public final class ModelNames {
    /** Cheap: routing, skill selection, scribe. */
    public static final String HAIKU = "claude-haiku-4-5";
    /** Strong: code generation and review. */
    public static final String OPUS  = "claude-opus-5";
    private ModelNames() {}
}
```
to:
```java
package ai.devflow.config;

public final class ModelNames {
    /** Cheap: routing, skill selection, scribe. */
    public static final String HAIKU  = "claude-haiku-4-5";
    /** Strong: code generation and review, called repeatedly inside the review loop. */
    public static final String SONNET = "claude-sonnet-5";
    /** Strongest: the once-per-run Planner call. */
    public static final String OPUS   = "claude-opus-5";
    private ModelNames() {}
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests '*ChatClientConfigTest*'`
Expected: PASS.

- [ ] **Step 5: Rename `opusOptions` to `agentOptions`, bump effort to XHIGH, switch coder/reviewer to Sonnet**

Read `src/main/java/ai/devflow/config/ChatClientConfig.java` first (Task 7's version, with `plannerChatClient` added). Change:
```java
    /**
     * Same as {@link #options(String)}, plus the spec §7 tuning that only
     * applies to the OPUS-tier agents (coder/reviewer): adaptive thinking at
     * {@code effort: HIGH} and an explicit, generous max_tokens. The
     * HAIKU-tier router does not get this — see CLAUDE.md's "Verified Spring
     * AI 2.0.1 syntax" section for the exact verified shape this mirrors.
     */
    private AnthropicChatOptions.Builder opusOptions(String model) {
        return options(model)
                .thinkingAdaptive()
                .effort(OutputConfig.Effort.HIGH)
                .maxTokens(OPUS_MAX_TOKENS);
    }
```
to:
```java
    /**
     * Same as {@link #options(String)}, plus the spec §7 tuning shared by the
     * three heavier agents (coder, reviewer, planner): adaptive thinking at
     * {@code effort: XHIGH} and an explicit, generous max_tokens. The
     * HAIKU-tier router/scribe do not get this — see CLAUDE.md's "Verified
     * Spring AI 2.0.1 syntax" section for the exact verified shape this
     * mirrors. Named generically (not "opusOptions") since coder/reviewer
     * moved off Opus 5 to Sonnet 5 — see the Sub-project 1 spec §6.
     */
    private AnthropicChatOptions.Builder agentOptions(String model) {
        return options(model)
                .thinkingAdaptive()
                .effort(OutputConfig.Effort.XHIGH)
                .maxTokens(OPUS_MAX_TOKENS);
    }
```
Rename its every other call site — `coderChatClient`, `reviewerChatClient`, `plannerChatClient` — from `opusOptions(...)` to `agentOptions(...)`, and switch the model passed by `coderChatClient`/`reviewerChatClient` from `ModelNames.OPUS` to `ModelNames.SONNET` (`plannerChatClient` keeps `ModelNames.OPUS`):
```java
    @Bean @Qualifier("coder")
    ChatClient coderChatClient(ChatClient.Builder builder) {
        return builder.defaultOptions(agentOptions(ModelNames.SONNET))
                .defaultSystem("""
                    You are the Coder in an automated development crew.
                    Implement the requested change in the workspace using your tools.
                    Make the smallest change that fully satisfies the task.
                    Follow the conventions already present in the code you are editing.
                    When you are done, stop. Do not explain at length.
                    """)
                .build();
    }

    @Bean @Qualifier("reviewer")
    ChatClient reviewerChatClient(ChatClient.Builder builder) {
        return builder.defaultOptions(agentOptions(ModelNames.SONNET))
                .defaultSystem("""
                    You are the Reviewer in an automated development crew.
                    Read the changed files with your tools and judge correctness and security.
                    Report only real defects. Do not report style preferences.
                    If the change is correct, say so plainly.
                    """)
                .build();
    }

    @Bean @Qualifier("planner")
    ChatClient plannerChatClient(ChatClient.Builder builder) {
        return builder.defaultOptions(agentOptions(ModelNames.OPUS))
                .defaultSystem("""
                    You are the Planner in an automated development crew.
                    Read the task and write a short, concrete plan for the coder to follow.
                    Do not write any code yourself. Answer in plain text, not JSON.
                    """)
                .build();
    }
```

Also update the class Javadoc for `OPUS_MAX_TOKENS` (currently reads "the OPUS-tier agents (coder/reviewer)"), since max_tokens now applies to all three agents regardless of model:
```java
    /**
     * Explicit max_tokens for the heavier agents (coder/reviewer/planner).
     * Left unset, Spring AI falls through to Anthropic's implicit default of
     * 4096 (verified via bytecode inspection of AnthropicChatOptions's
     * constructor — see the final-review report), which is tight for a
     * coding agent emitting whole file bodies through writeFile and is
     * shared with adaptive-thinking tokens once thinkingAdaptive() is on.
     * 12000 gives generous headroom for both without being unbounded.
     */
```

- [ ] **Step 6: Run the full suite**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test`
Expected: PASS. No test asserts on `.effort(...)` or the specific model string beyond `ChatClientConfigTest`'s own constant checks, so nothing else is sensitive to this change.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/ai/devflow/config/ModelNames.java src/main/java/ai/devflow/config/ChatClientConfig.java \
        src/test/java/ai/devflow/config/ChatClientConfigTest.java
git commit -m "feat: move Coder/Reviewer to Sonnet 5 + XHIGH, reserve Opus 5 + XHIGH for the Planner"
```

---

# Task 9: `GET /api/runs/history` endpoint

**Files:**
- Create: `src/main/java/ai/devflow/web/RunSummary.java`
- Modify: `src/main/java/ai/devflow/web/RunController.java`
- Test: `src/test/java/ai/devflow/web/RunControllerTest.java`

**Interfaces:**
- Consumes: `SdlcRunRepository` (Task 1).
- Produces: `RunSummary(String runId, String task, String repoSlug, String status, String reason, Instant startedAt, Instant finishedAt, long inputTokens, long outputTokens)`; `RunController`'s constructor gains a 4th param, `SdlcRunRepository`.

- [ ] **Step 1: Create `RunSummary`**

`src/main/java/ai/devflow/web/RunSummary.java`:

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

- [ ] **Step 2: Write the failing `RunControllerTest` case**

Read `src/test/java/ai/devflow/web/RunControllerTest.java` first (Task 4's version). Add an autowired-style mock field and wire it into `setUp()`:
```java
    ai.devflow.history.SdlcRunRepository history;
```
```java
    @BeforeEach
    void setUp() {
        registry = mock(RunRegistry.class);
        events = new RunEventPublisher();
        history = mock(ai.devflow.history.SdlcRunRepository.class);
        mvc = MockMvcBuilders.standaloneSetup(
                new RunController(registry, events, publishedEvents::add, history)).build();
    }
```
Add a test:
```java
    @Test
    void historyReturnsRunsMostRecentFirst() throws Exception {
        var newer = new ai.devflow.history.SdlcRun("newer", "add a class", "fixture", java.time.Instant.now());
        var older = new ai.devflow.history.SdlcRun("older", "fix a bug", "fixture",
                java.time.Instant.now().minusSeconds(60));
        when(history.findTop50ByOrderByStartedAtDesc()).thenReturn(java.util.List.of(newer, older));

        mvc.perform(get("/api/runs/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].runId").value("newer"))
                .andExpect(jsonPath("$[0].task").value("add a class"))
                .andExpect(jsonPath("$[0].status").value("RUNNING"))
                .andExpect(jsonPath("$[1].runId").value("older"));
    }
```

- [ ] **Step 3: Run to verify it fails**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests '*RunControllerTest*'`
Expected: compilation failure — `RunController`'s constructor does not yet take a 4th argument, and `GET /api/runs/history` does not exist.

- [ ] **Step 4: Add the endpoint**

Read `src/main/java/ai/devflow/web/RunController.java` first (Task 4's version). Add imports — `java.util.List` is not already imported in this file (only `java.util.Map` is):
```java
import ai.devflow.history.SdlcRunRepository;
import java.util.List;
```
Add a field, extend the constructor, and add the endpoint:
```java
    private final SdlcRunRepository history;

    public RunController(RunRegistry registry, RunEventPublisher events,
                         ApplicationEventPublisher applicationEvents, SdlcRunRepository history) {
        this.registry = registry;
        this.events = events;
        this.applicationEvents = applicationEvents;
        this.history = history;
    }
```
```java
    @GetMapping("/history")
    public List<RunSummary> history() {
        return history.findTop50ByOrderByStartedAtDesc().stream().map(RunSummary::from).toList();
    }
```
- [ ] **Step 5: Run to verify it passes**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests '*RunControllerTest*'`
Expected: PASS (all tests in the file, old and new).

- [ ] **Step 6: Run the full suite**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test`
Expected: PASS. `RunFlowIntegrationTest`/`StaticPageTest` construct `RunController` via Spring autowiring, so the new `SdlcRunRepository` param resolves automatically from Task 1's JPA auto-configuration.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/ai/devflow/web/RunSummary.java src/main/java/ai/devflow/web/RunController.java \
        src/test/java/ai/devflow/web/RunControllerTest.java
git commit -m "feat: add GET /api/runs/history"
```

---

# Task 10: UI — past-runs list section

**Files:**
- Modify: `src/main/resources/static/index.html`
- Modify: `src/test/java/ai/devflow/web/StaticPageTest.java`

- [ ] **Step 1: Write the failing test**

Read `src/test/java/ai/devflow/web/StaticPageTest.java` first. Add an assertion to the existing test:
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
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/api/runs/history")));
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests '*StaticPageTest*'`
Expected: FAIL — no `id="history"` element or `/api/runs/history` fetch exists yet.

- [ ] **Step 3: Add the past-runs list section**

Read `src/main/resources/static/index.html` first (shown in full in the summary above). Add a new `<section>` right after the existing `<section id="gate" ...>` block, before `</main>`:
```html
  <hr>

  <section id="history">
    <h2 style="font-size:0.95rem; margin:0 0 0.6rem;">Past runs</h2>
    <button id="history-refresh" type="button">Refresh</button>
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
  </section>
```
Add the fetch logic at the end of the existing `<script>` block, right before the closing `</script>` tag:
```javascript
  function renderHistory(rows) {
    const body = $('history-body');
    body.innerHTML = '';
    rows.forEach(r => {
      const tr = document.createElement('tr');
      tr.style.borderTop = '1px solid var(--line)';
      const task = r.task.length > 60 ? r.task.slice(0, 60) + '…' : r.task;
      tr.innerHTML = `<td style="padding:0.3rem 0;">${task}</td><td>${r.status}</td><td>${new Date(r.startedAt).toLocaleString()}</td>`;
      body.appendChild(tr);
    });
  }

  async function loadHistory() {
    const res = await fetch('/api/runs/history');
    if (!res.ok) return;
    renderHistory(await res.json());
  }

  $('history-refresh').onclick = loadHistory;
  loadHistory();
```

- [ ] **Step 4: Run to verify it passes**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests '*StaticPageTest*'`
Expected: PASS.

- [ ] **Step 5: Manually verify in a browser**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && export ANTHROPIC_API_KEY=<key> && ./gradlew bootRun`

Open `http://localhost:8080`. Confirm the "Past runs" table appears below the gate section (empty on first run, since `~/.devflowai/history/devflowai` starts fresh). Run one task on the bundled fixture through to completion, click "Refresh", and confirm the completed run appears with its task, status, and timestamp.

- [ ] **Step 6: Run the full suite one last time**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/static/index.html src/test/java/ai/devflow/web/StaticPageTest.java
git commit -m "feat: add a past-runs list to the operator page"
```

---

## Spec coverage check

- §3 Persistence: write-behind recorder → Tasks 1, 2, 3, 4.
- §4 CodingWorker → Tasks 5, 6.
- §5 Planner stage → Task 7.
- §6 Model tiers → Task 8 (Planner's tier wired in Task 7, at the final Opus+XHIGH value from the start — see Task 7 Step 10).
- §7 API and UI: past-runs list → Tasks 9, 10.
- §8 Non-goals — deliberately not built: no task creates `Artifact`/`ToolExecution`, a policy engine, evaluation mode, an external CLI-shelling worker, a routing/orchestration LLM, a full dashboard, or DB migration tooling. No task adds pagination/filtering/drill-down to the history list.
- §9 Testing — every new class has a matching test task (`HistoryRepositoriesTest`, `SdlcRunRecorderTest`, `SpringAiCodingWorkerTest`, `PlannerAgentTest`, the `OrchestratorTest`/`RunControllerTest`/`StaticPageTest` additions); all run in the default `./gradlew test` suite, no `liveTest` additions.
- §10 Ship order — followed exactly, task-for-task.
