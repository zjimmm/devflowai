# devflowai Phase 4 Implementation Plan — SSE, the web page, and approval gates

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn devflowai from a library with a passing test suite into something a person can actually use — a web page where you type a task, watch the agents work live, and approve or steer at three gates.

**Architecture:** The orchestrator moves off the request thread onto a task executor. It emits typed events to an `SseEmitter` as it goes, and parks on a `CompletableFuture<ApprovalDecision>` at each gate. A separate `POST /approve` endpoint completes that future. A rejection carrying a reason becomes a `Finding(HUMAN)` and re-enters the existing bounded loop — the operator is a third reviewer, not a kill switch.

**Tech Stack:** Java 21 · Spring Boot 4.1.1 (spring-webmvc 7.0.9) · Spring AI 2.0.1 · Gradle 9.5.1 · JUnit 5 + AssertJ + Mockito · vanilla JS `EventSource` (no framework)

**Spec:** `docs/superpowers/specs/2026-08-27-devflowai-design.md` (§5.1, §5.2, §5.3 primarily; §7 for cost display; §11 for stated limitations)

## Global Constraints

- **Java 21.** JDK 21 is at `/opt/homebrew/opt/openjdk@21` and is NOT on `PATH`. `export JAVA_HOME=/opt/homebrew/opt/openjdk@21` before every `./gradlew` invocation — the wrapper needs a JVM to bootstrap before Gradle reads any config. No `gradle.properties` setting substitutes for this (that line was deliberately removed in Phase 3 as machine-specific).
- **Spring Boot 4.1.1 + Spring AI 2.0.1.** Matched pair. Do not upgrade one alone.
- **Pin every model explicitly.** Router/scribe `claude-haiku-4-5`; coder/reviewer `claude-opus-5`. Never call `.temperature(...)`.
- **The repo is public.** No API key in any tracked file. `ANTHROPIC_API_KEY` from environment only.
- **Base package:** `ai.devflow`
- **The orchestrator never holds file contents or diffs.** Only `AgentResult` records. This survives Phase 4 — SSE events carry summaries and file *paths*, never file bodies or diffs.
- **Every path-taking tool goes through `PathGuard`.** No exceptions.
- **Review loop cap: 3. Human steering cap: 5. Separate counters.**
- **`GitTools.commit` is NOT a `@Tool`** (Phase 3 C1 fix). It is called only from Java, at Gate 3. Do not re-annotate it.
- **`ReviewerAgent` gets `ReadOnlyFileTools`, never `FileTools`** (Phase 3 I1 fix). Do not widen it.

---

## Spec correction this plan makes

Spec §5's lifecycle puts **Gate 1 at step 6, before the coder runs at step 7** — outside the review loop. But §5.3's mockup captions Gate 1 `ABOUT TO WRITE 3 FILES` with a concrete file list. **Both cannot be true:** before the coder's first LLM call, no file list exists — the model decides what to write during its turn, and the orchestrator cannot know in advance.

The lifecycle ordering is right and the mockup caption is aspirational. This plan resolves it:

- **Gate 1 becomes a pre-flight confirmation** — repo, branch, task, which agents will run. It is the "you are about to start spending Opus 5 tokens on this repo" checkpoint. Note that Gate 1 was never the filesystem safety mechanism: `PathGuard` is (structurally, §9), and the workspace is a disposable temp copy, so writes into it endanger nothing.
- **The file list moves to Gate 2**, where `filesTouched` genuinely exists. Gate 2 becomes the richest gate: *here is what changed, here is what the reviewer said, and I am about to execute the target repo's build.* That is also the gate with the real safety rationale (arbitrary code execution, §9).
- **Gate 3 shows the commit's file list plus the review outcome.**

The mockup's information is preserved; it appears at the gate where it is real.

---

## File Structure

```
src/main/java/ai/devflow/
  orchestrator/
    RunState.java              MODIFIED — thread-safe; adds gitTools(), phase, gate state
    Orchestrator.java          MODIFIED — async-safe, gates woven in, cleanup guaranteed
    RunPhase.java              NEW — enum: PREPARING, CODING, REVIEWING, BUILDING, COMMITTING, DONE, FAILED
    ApprovalDecision.java      NEW — record(boolean approved, String reason)
    Gate.java                  NEW — enum: PRE_FLIGHT, BEFORE_BUILD, BEFORE_COMMIT
    ApprovalGate.java          NEW — parks a CompletableFuture, applies the timeout
    RunRegistry.java           NEW — in-memory Map<String, RunHandle>; starts/looks up/cleans up runs
    RunHandle.java             NEW — RunState + the run's ApprovalGate + its Future<?>
  event/
    RunEvent.java              NEW — record(String type, String message, Map<String,Object> data)
    RunEventPublisher.java     NEW — owns SseEmitter per run; heartbeats; completion/error
  web/
    RunController.java         NEW — POST /api/runs, GET /api/runs/{id}/stream, POST /api/runs/{id}/approve
    StartRunRequest.java       NEW — record(String task, String repo)
    ApproveRequest.java        NEW — record(boolean approved, String reason)
  agent/
    CoderAgent.java            MODIFIED — stateless (fixes I3); real token usage
    ReviewerAgent.java         MODIFIED — real token usage
src/main/resources/
  static/index.html            NEW — the one page
  application.yml              MODIFIED — executor + gate timeout config

src/test/java/ai/devflow/...   mirrors main
```

**Why this split:** `orchestrator/` gains the run-lifecycle types because they are all mutations of run state — they change together. `event/` is separated because SSE plumbing has its own failure modes (dead emitters, timeouts) and should be testable without an orchestrator. `web/` is thin: three endpoints that translate HTTP into registry calls and nothing else.

---

# Task 1: Thread-safe RunState with per-run GitTools

Resolves **I3** (deferred from Phase 3): `CoderAgent` currently binds `GitTools` at construction while reading the workspace from `RunState` at run time. Harmless while every test builds a fresh agent per run — a real bug the moment Phase 4 makes agents singleton beans, and a route back to the Phase 3 C1 disguised-changeset bug.

**Files:**
- Modify: `src/main/java/ai/devflow/orchestrator/RunState.java`
- Modify: `src/main/java/ai/devflow/agent/CoderAgent.java`
- Modify: `src/test/java/ai/devflow/agent/CoderAgentTest.java`
- Modify: `src/test/java/ai/devflow/agent/CoderAgentLiveTest.java`
- Modify: `src/test/java/ai/devflow/EndToEndLiveTest.java`
- Test: `src/test/java/ai/devflow/orchestrator/RunStateTest.java` (new)

**Interfaces:**
- Consumes: existing `Workspace`, `GitTools(Workspace)`, `AgentResult`, `Finding`, `TokenUsage`
- Produces: `RunState.gitTools()` returning `GitTools`; `CoderAgent(ChatClient)` single-arg constructor; `RunState` safe for concurrent read/write

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/ai/devflow/orchestrator/RunStateTest.java`:

```java
package ai.devflow.orchestrator;

import ai.devflow.agent.AgentResult;
import ai.devflow.agent.Finding;
import ai.devflow.agent.TokenUsage;
import ai.devflow.workspace.FixtureWorkspace;
import ai.devflow.workspace.Workspace;
import org.junit.jupiter.api.*;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

class RunStateTest {

    Workspace workspace;

    @BeforeEach
    void setUp() throws Exception {
        workspace = new FixtureWorkspace(Path.of("src/test/resources/fixture"), "runstate-test");
        workspace.prepare();
    }

    @AfterEach
    void tearDown() throws Exception { workspace.cleanup(); }

    @Test
    void exposesGitToolsBoundToItsOwnWorkspace() {
        var state = new RunState("r1", "a task", workspace);
        assertThat(state.gitTools()).isNotNull();
        // Same instance every call — not rebuilt per access.
        assertThat(state.gitTools()).isSameAs(state.gitTools());
        // Bound to THIS run's workspace: a clean fixture reports no changes.
        assertThat(state.gitTools().changedFiles()).isEmpty();
    }

    @Test
    void concurrentRecordsAreAllRetained() throws Exception {
        var state = new RunState("r2", "a task", workspace);
        int threads = 8, perThread = 50;
        var pool = Executors.newFixedThreadPool(threads);
        var start = new CountDownLatch(1);

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                start.await();
                for (int i = 0; i < perThread; i++) {
                    state.record(AgentResult.ok("coder", "s", List.of(), new TokenUsage(1, 1)));
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        assertThat(state.history()).hasSize(threads * perThread);
        assertThat(state.totalTokens()).isEqualTo(new TokenUsage(threads * perThread, threads * perThread));
    }

    @Test
    void concurrentIterationIncrementsAreNotLost() throws Exception {
        var state = new RunState("r3", "a task", workspace);
        int threads = 8, perThread = 50;
        var pool = Executors.newFixedThreadPool(threads);
        var start = new CountDownLatch(1);

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                start.await();
                for (int i = 0; i < perThread; i++) state.incrementHumanIterations();
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        assertThat(state.humanIterations()).isEqualTo(threads * perThread);
    }

    @Test
    void historySnapshotIsNotAffectedByLaterWrites() {
        var state = new RunState("r4", "a task", workspace);
        state.record(AgentResult.ok("coder", "first", List.of(), TokenUsage.NONE));
        List<AgentResult> snapshot = state.history();
        state.record(AgentResult.ok("coder", "second", List.of(), TokenUsage.NONE));
        assertThat(snapshot).hasSize(1);
    }

    @Test
    void tracksPhase() {
        var state = new RunState("r5", "a task", workspace);
        assertThat(state.phase()).isEqualTo(RunPhase.PREPARING);
        state.setPhase(RunPhase.CODING);
        assertThat(state.phase()).isEqualTo(RunPhase.CODING);
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests '*RunStateTest*'`
Expected: FAIL — `gitTools()`, `phase()`, `setPhase(...)`, and `RunPhase` do not exist.

- [ ] **Step 3: Create the RunPhase enum**

Create `src/main/java/ai/devflow/orchestrator/RunPhase.java`:

```java
package ai.devflow.orchestrator;

/** Coarse lifecycle position of a run, surfaced to the UI via SSE. */
public enum RunPhase {
    PREPARING,
    CODING,
    REVIEWING,
    BUILDING,
    COMMITTING,
    DONE,
    FAILED
}
```

- [ ] **Step 4: Make RunState thread-safe and add gitTools()/phase**

Replace `src/main/java/ai/devflow/orchestrator/RunState.java` entirely:

```java
package ai.devflow.orchestrator;

import ai.devflow.agent.AgentResult;
import ai.devflow.agent.Finding;
import ai.devflow.agent.TokenUsage;
import ai.devflow.tools.GitTools;
import ai.devflow.workspace.Workspace;

import java.util.ArrayList;
import java.util.List;

/**
 * Mutable context for one run.
 *
 * <p>Thread-safe by design: from Phase 4 the orchestrator mutates this on a
 * worker thread while the SSE publisher and the /approve endpoint read it from
 * request threads. Every accessor is synchronized on the instance, and the
 * list accessors return snapshots (not live views) so a caller iterating a
 * snapshot cannot see a concurrent modification.
 */
public class RunState {

    private final String runId;
    private final String task;
    private final Workspace workspace;
    private final GitTools gitTools;

    private final List<AgentResult> history = new ArrayList<>();
    private final List<Finding> openFindings = new ArrayList<>();
    private final List<String> loadedSkills = new ArrayList<>();

    private int reviewIterations = 0;
    private int humanIterations = 0;
    private TokenUsage totalTokens = TokenUsage.NONE;
    private RunPhase phase = RunPhase.PREPARING;

    public RunState(String runId, String task, Workspace workspace) {
        this.runId = runId;
        this.task = task;
        this.workspace = workspace;
        // Bound to THIS run's workspace, created once. Agents read tools from
        // here rather than holding their own, so agents stay stateless and are
        // safe to register as singleton beans (Phase 3 finding I3).
        this.gitTools = new GitTools(workspace);
    }

    public String runId() { return runId; }
    public String task() { return task; }
    public Workspace workspace() { return workspace; }
    public GitTools gitTools() { return gitTools; }

    public synchronized List<AgentResult> history() { return List.copyOf(history); }
    public synchronized List<Finding> openFindings() { return List.copyOf(openFindings); }
    public synchronized List<String> loadedSkills() { return List.copyOf(loadedSkills); }
    public synchronized int reviewIterations() { return reviewIterations; }
    public synchronized int humanIterations() { return humanIterations; }
    public synchronized TokenUsage totalTokens() { return totalTokens; }
    public synchronized RunPhase phase() { return phase; }

    public synchronized void setPhase(RunPhase phase) { this.phase = phase; }

    public synchronized void record(AgentResult result) {
        history.add(result);
        totalTokens = totalTokens.plus(result.tokens());
    }

    public synchronized void addFindings(List<Finding> findings) { openFindings.addAll(findings); }
    public synchronized void clearFindings() { openFindings.clear(); }
    public synchronized void addLoadedSkill(String name) { loadedSkills.add(name); }
    public synchronized void incrementReviewIterations() { reviewIterations++; }
    public synchronized void incrementHumanIterations() { humanIterations++; }
}
```

- [ ] **Step 5: Make CoderAgent stateless**

In `src/main/java/ai/devflow/agent/CoderAgent.java`, remove the `GitTools` field and constructor parameter, and read it from the state instead. The changed parts:

```java
    private final ChatClient chatClient;
    private String lastPrompt = "";

    public CoderAgent(ChatClient chatClient) {
        this.chatClient = chatClient;
    }
```

and inside `run(RunState state)`, replace both uses of the old field:

```java
        String summary = chatClient.prompt()
                .user(lastPrompt)
                .tools(new FileTools(state.workspace().guard()), state.gitTools())
                .call()
                .content();

        // Derived from git, never from what the model claims.
        List<String> touched = state.gitTools().changedFiles();
```

Delete the now-unused `import ai.devflow.tools.GitTools;` only if nothing else references it (the `.tools(...)` line no longer names the type, so it should go).

- [ ] **Step 6: Update the three call sites that construct CoderAgent**

In `src/test/java/ai/devflow/agent/CoderAgentTest.java`, both occurrences:

```java
        var agent = new CoderAgent(client);
```

(the `new GitTools(workspace)` argument is dropped; the state supplies it).

In `src/test/java/ai/devflow/agent/CoderAgentLiveTest.java`:

```java
            var agent = new CoderAgent(coderClient);
```

In `src/test/java/ai/devflow/EndToEndLiveTest.java`:

```java
            var orchestrator = new Orchestrator(
                    new CoderAgent(coderClient),
                    new ReviewerAgent(reviewerClient),
                    3, 5);
```

Remove any `import ai.devflow.tools.GitTools;` left unused in those test files.

- [ ] **Step 7: Run the tests**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test`
Expected: PASS, including the 5 new `RunStateTest` cases. The concurrency tests are the point — if `history()` returned the live list or the counters were unsynchronized, they would fail.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/ai/devflow/orchestrator/RunState.java \
        src/main/java/ai/devflow/orchestrator/RunPhase.java \
        src/main/java/ai/devflow/agent/CoderAgent.java \
        src/test/java/ai/devflow/orchestrator/RunStateTest.java \
        src/test/java/ai/devflow/agent/CoderAgentTest.java \
        src/test/java/ai/devflow/agent/CoderAgentLiveTest.java \
        src/test/java/ai/devflow/EndToEndLiveTest.java
git commit -m "feat: thread-safe RunState with per-run GitTools, stateless CoderAgent"
```

---

# Task 2: Real token usage from the model response

The Phase 0–3 plan's own self-review recorded this as deferred: `TokenUsage.NONE` is passed everywhere. Phase 4 shows per-run cost in the UI, so the numbers have to be real.

**Verified API shape** (checked against the resolved jars, do not re-derive):
`chatClient.prompt()...call().chatResponse()` returns `org.springframework.ai.chat.model.ChatResponse`. From it, `.getMetadata().getUsage()` returns `org.springframework.ai.chat.metadata.Usage`, which has `Integer getPromptTokens()` and `Integer getCompletionTokens()`. Response text comes from `.getResult().getOutput().getText()`.

**Files:**
- Create: `src/main/java/ai/devflow/agent/UsageMapper.java`
- Modify: `src/main/java/ai/devflow/agent/CoderAgent.java`
- Modify: `src/main/java/ai/devflow/agent/ReviewerAgent.java`
- Modify: `src/test/java/ai/devflow/agent/CoderAgentTest.java`
- Modify: `src/test/java/ai/devflow/agent/ReviewerAgentTest.java`
- Test: `src/test/java/ai/devflow/agent/UsageMapperTest.java`

**Interfaces:**
- Consumes: `TokenUsage(long input, long output)`, `TokenUsage.NONE`
- Produces: `UsageMapper.from(ChatResponse)` returning `TokenUsage` (never null, never throws)

- [ ] **Step 1: Write the failing test**

Create `src/test/java/ai/devflow/agent/UsageMapperTest.java`:

```java
package ai.devflow.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class UsageMapperTest {

    @Test
    void mapsPromptAndCompletionTokens() {
        var metadata = ChatResponseMetadata.builder()
                .usage(new DefaultUsage(120, 45))
                .build();
        ChatResponse response = mock(ChatResponse.class);
        when(response.getMetadata()).thenReturn(metadata);

        assertThat(UsageMapper.from(response)).isEqualTo(new TokenUsage(120, 45));
    }

    @Test
    void returnsNoneForNullResponse() {
        assertThat(UsageMapper.from(null)).isEqualTo(TokenUsage.NONE);
    }

    @Test
    void returnsNoneWhenMetadataMissing() {
        ChatResponse response = mock(ChatResponse.class);
        when(response.getMetadata()).thenReturn(null);
        assertThat(UsageMapper.from(response)).isEqualTo(TokenUsage.NONE);
    }

    @Test
    void treatsNullTokenCountsAsZeroRatherThanThrowing() {
        var metadata = ChatResponseMetadata.builder()
                .usage(new DefaultUsage(null, null))
                .build();
        ChatResponse response = mock(ChatResponse.class);
        when(response.getMetadata()).thenReturn(metadata);

        assertThat(UsageMapper.from(response)).isEqualTo(TokenUsage.NONE);
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests '*UsageMapperTest*'`
Expected: FAIL — `UsageMapper` does not exist. If `DefaultUsage`'s constructor signature differs from `(Integer, Integer)`, the compiler will say so — use what it reports rather than guessing.

- [ ] **Step 3: Implement UsageMapper**

Create `src/main/java/ai/devflow/agent/UsageMapper.java`:

```java
package ai.devflow.agent;

import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;

/**
 * Extracts token counts from a model response.
 *
 * <p>Deliberately total: token accounting is bookkeeping, and a run must never
 * fail because usage metadata was absent or malformed. Every unusable input
 * maps to {@link TokenUsage#NONE}.
 */
public final class UsageMapper {

    private UsageMapper() {}

    public static TokenUsage from(ChatResponse response) {
        if (response == null) return TokenUsage.NONE;
        ChatResponseMetadata metadata = response.getMetadata();
        if (metadata == null) return TokenUsage.NONE;
        Usage usage = metadata.getUsage();
        if (usage == null) return TokenUsage.NONE;
        return new TokenUsage(
                zeroIfNull(usage.getPromptTokens()),
                zeroIfNull(usage.getCompletionTokens()));
    }

    private static long zeroIfNull(Integer value) {
        return value == null ? 0L : value.longValue();
    }
}
```

- [ ] **Step 4: Run the mapper tests**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests '*UsageMapperTest*'`
Expected: 4/4 PASS.

- [ ] **Step 5: Switch CoderAgent to chatResponse()**

In `src/main/java/ai/devflow/agent/CoderAgent.java`, replace the model call and result construction inside `run(RunState state)`:

```java
        ChatResponse response = chatClient.prompt()
                .user(lastPrompt)
                .tools(new FileTools(state.workspace().guard()), state.gitTools())
                .call()
                .chatResponse();

        String summary = textOf(response);

        // Derived from git, never from what the model claims.
        List<String> touched = state.gitTools().changedFiles();

        return AgentResult.ok(name(), summary, touched, UsageMapper.from(response));
```

Add the import `org.springframework.ai.chat.model.ChatResponse;` and this helper to the class:

```java
    /** Response text, tolerating a null or empty response rather than throwing. */
    private static String textOf(ChatResponse response) {
        if (response == null || response.getResult() == null
                || response.getResult().getOutput() == null) {
            return "";
        }
        String text = response.getResult().getOutput().getText();
        return text == null ? "" : text;
    }
```

- [ ] **Step 6: Switch ReviewerAgent to chatResponse()**

In `src/main/java/ai/devflow/agent/ReviewerAgent.java`, change `run(RunState state)` so it captures the response and threads usage into the parse:

```java
        ChatResponse response = chatClient.prompt()
                .user(prompt)
                .tools(new ReadOnlyFileTools(state.workspace().guard()))
                .call()
                .chatResponse();

        return parse(textOf(response), UsageMapper.from(response));
```

Change `parse`'s signature to `private AgentResult parse(String raw, TokenUsage usage)` and replace every `TokenUsage.NONE` inside it with `usage` (there are three: the `needsWork` branch, the `ok` branch, and the `FAILED` catch block). Add the same `textOf(...)` helper shown in Step 5 and the `ChatResponse` import.

**Do not weaken the fail-closed validation** added in Phase 3 — the `status` allow-list check stays exactly as it is.

- [ ] **Step 7: Update the agent tests' mocks**

Both `CoderAgentTest` and `ReviewerAgentTest` currently stub `.call().content()`. They must now stub `.call().chatResponse()` and return a real `ChatResponse`. Add this helper to each test class:

```java
    /** A ChatResponse carrying the given text and token counts. */
    private static ChatResponse responseWith(String text, int promptTokens, int completionTokens) {
        var generation = new Generation(new AssistantMessage(text));
        var metadata = ChatResponseMetadata.builder()
                .usage(new DefaultUsage(promptTokens, completionTokens))
                .build();
        return new ChatResponse(List.of(generation), metadata);
    }
```

with imports:

```java
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
```

Then change every stub from:

```java
when(client.prompt().user(any(String.class)).tools(any(Object[].class)).call().content())
        .thenReturn("...");
```

to:

```java
when(client.prompt().user(any(String.class)).tools(any(Object[].class)).call().chatResponse())
        .thenReturn(responseWith("...", 10, 5));
```

If a constructor signature above is wrong, the compiler names the right one — fix from its message rather than guessing.

- [ ] **Step 8: Add a usage assertion to each agent test**

Add to `CoderAgentTest`:

```java
    @Test
    void reportsRealTokenUsageFromTheResponse() throws Exception {
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(client.prompt().user(any(String.class)).tools(any(Object[].class)).call().chatResponse())
                .thenReturn(responseWith("done", 321, 123));

        var agent = new CoderAgent(client);
        var result = agent.run(new RunState("usage-test", "t", workspace));

        assertThat(result.tokens()).isEqualTo(new TokenUsage(321, 123));
    }
```

Add the equivalent to `ReviewerAgentTest`, asserting usage survives a `NEEDS_WORK` parse:

```java
    @Test
    void reportsRealTokenUsageFromTheResponse() {
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(client.prompt().user(any(String.class)).tools(any(Object[].class)).call().chatResponse())
                .thenReturn(responseWith("""
                    {"status":"NEEDS_WORK","summary":"nope",
                     "findings":[{"severity":"HIGH","file":"A.java","line":1,"message":"x"}]}
                    """, 500, 60));

        var result = new ReviewerAgent(client).run(new RunState("u", "t", workspace));

        assertThat(result.tokens()).isEqualTo(new TokenUsage(500, 60));
    }
```

- [ ] **Step 9: Run the full suite**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew clean test`
Expected: all green, including the pre-existing fail-closed tests (`emptyJsonObjectFailsClosed`, `unrecognizedStatusValueFailsClosed`) — those must still pass unchanged.

- [ ] **Step 10: Commit**

```bash
git add src/main/java/ai/devflow/agent/ src/test/java/ai/devflow/agent/
git commit -m "feat: wire real token usage from model responses into AgentResult"
```

---

# Task 3: RunEvent and RunEventPublisher (SSE plumbing)

Built and tested with no orchestrator involved, so its failure modes (dead client, timeout, completion) are isolated.

**Files:**
- Create: `src/main/java/ai/devflow/event/RunEvent.java`
- Create: `src/main/java/ai/devflow/event/RunEventPublisher.java`
- Test: `src/test/java/ai/devflow/event/RunEventPublisherTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks
- Produces:
  - `RunEvent.of(String type, String message)` and `RunEvent.of(String type, String message, Map<String,Object> data)`
  - `RunEventPublisher` (a `@Component`) with `SseEmitter subscribe(String runId)`, `void publish(String runId, RunEvent event)`, `void complete(String runId)`, `void fail(String runId, String message)`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/ai/devflow/event/RunEventPublisherTest.java`:

```java
package ai.devflow.event;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class RunEventPublisherTest {

    @Test
    void subscribeReturnsAnEmitterForTheRun() {
        var publisher = new RunEventPublisher();
        SseEmitter emitter = publisher.subscribe("run-1");
        assertThat(emitter).isNotNull();
        assertThat(publisher.isSubscribed("run-1")).isTrue();
    }

    @Test
    void publishingToAnUnknownRunIsSilentlyIgnored() {
        var publisher = new RunEventPublisher();
        // No subscriber yet — must not throw. The orchestrator runs whether or
        // not anyone is watching.
        assertThatCode(() -> publisher.publish("nobody-home", RunEvent.of("step", "hi")))
                .doesNotThrowAnyException();
    }

    @Test
    void completeRemovesTheSubscription() {
        var publisher = new RunEventPublisher();
        publisher.subscribe("run-2");
        publisher.complete("run-2");
        assertThat(publisher.isSubscribed("run-2")).isFalse();
    }

    @Test
    void aFailingEmitterIsDroppedRatherThanBreakingTheRun() {
        var publisher = new RunEventPublisher();
        var attempts = new AtomicInteger();
        // An emitter whose send() always throws simulates a client that
        // disconnected mid-run.
        SseEmitter broken = new SseEmitter() {
            @Override
            public void send(SseEventBuilder builder) throws java.io.IOException {
                attempts.incrementAndGet();
                throw new java.io.IOException("client gone");
            }
        };
        publisher.register("run-3", broken);

        assertThatCode(() -> publisher.publish("run-3", RunEvent.of("step", "one")))
                .doesNotThrowAnyException();
        assertThat(attempts.get()).isEqualTo(1);
        // Dropped after the first failure — no repeated attempts on a dead client.
        assertThat(publisher.isSubscribed("run-3")).isFalse();

        publisher.publish("run-3", RunEvent.of("step", "two"));
        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    void eventCarriesTypeMessageAndData() {
        var event = RunEvent.of("gate", "about to build", Map.of("gate", "BEFORE_BUILD"));
        assertThat(event.type()).isEqualTo("gate");
        assertThat(event.message()).isEqualTo("about to build");
        assertThat(event.data()).containsEntry("gate", "BEFORE_BUILD");
    }

    @Test
    void eventWithoutDataHasAnEmptyMap() {
        assertThat(RunEvent.of("step", "hi").data()).isEmpty();
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests '*RunEventPublisherTest*'`
Expected: FAIL — neither class exists.

- [ ] **Step 3: Create RunEvent**

Create `src/main/java/ai/devflow/event/RunEvent.java`:

```java
package ai.devflow.event;

import java.util.Map;

/**
 * One line in the run's live log.
 *
 * <p>Carries a summary and structured data only — never file contents or a
 * diff. That is the same context-isolation rule the orchestrator follows
 * (spec §3.2): the browser gets paths and summaries, and fetches nothing else.
 */
public record RunEvent(String type, String message, Map<String, Object> data) {

    public static RunEvent of(String type, String message) {
        return new RunEvent(type, message, Map.of());
    }

    public static RunEvent of(String type, String message, Map<String, Object> data) {
        return new RunEvent(type, message, Map.copyOf(data));
    }
}
```

- [ ] **Step 4: Create RunEventPublisher**

Create `src/main/java/ai/devflow/event/RunEventPublisher.java`:

```java
package ai.devflow.event;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns one {@link SseEmitter} per run and pushes events to it.
 *
 * <p>Publishing is best-effort by design. A run is a real process doing real
 * work; nobody watching, or a browser that closed its tab, must never be able
 * to break it. Every send failure drops that subscription and returns quietly.
 */
@Component
public class RunEventPublisher {

    /** Long, but not infinite: a browser left open overnight eventually releases the connection. */
    private static final long EMITTER_TIMEOUT_MS = Duration.ofHours(2).toMillis();

    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();

    public SseEmitter subscribe(String runId) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        register(runId, emitter);
        return emitter;
    }

    /** Visible for tests, which supply their own (possibly failing) emitter. */
    public void register(String runId, SseEmitter emitter) {
        emitter.onCompletion(() -> emitters.remove(runId, emitter));
        emitter.onTimeout(() -> emitters.remove(runId, emitter));
        emitter.onError(e -> emitters.remove(runId, emitter));
        emitters.put(runId, emitter);
    }

    public boolean isSubscribed(String runId) {
        return emitters.containsKey(runId);
    }

    public void publish(String runId, RunEvent event) {
        SseEmitter emitter = emitters.get(runId);
        if (emitter == null) return;
        try {
            emitter.send(SseEmitter.event().name(event.type()).data(event));
        } catch (IOException | IllegalStateException e) {
            // Client is gone or the response is already committed. Drop it and
            // let the run continue.
            emitters.remove(runId, emitter);
        }
    }

    public void complete(String runId) {
        SseEmitter emitter = emitters.remove(runId);
        if (emitter == null) return;
        try {
            emitter.complete();
        } catch (RuntimeException ignored) {
            // Already closed by the container; nothing to do.
        }
    }

    public void fail(String runId, String message) {
        publish(runId, RunEvent.of("error", message));
        complete(runId);
    }
}
```

- [ ] **Step 5: Run the tests**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests '*RunEventPublisherTest*'`
Expected: 6/6 PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/ai/devflow/event/ src/test/java/ai/devflow/event/
git commit -m "feat: RunEvent and RunEventPublisher for live SSE streaming"
```

---

# Task 4: ApprovalGate — parking, timeout, and decision

Implements spec §5.1's mechanism and §5.2's `ApprovalDecision`.

**Files:**
- Create: `src/main/java/ai/devflow/orchestrator/Gate.java`
- Create: `src/main/java/ai/devflow/orchestrator/ApprovalDecision.java`
- Create: `src/main/java/ai/devflow/orchestrator/ApprovalGate.java`
- Test: `src/test/java/ai/devflow/orchestrator/ApprovalGateTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks
- Produces:
  - `enum Gate { PRE_FLIGHT, BEFORE_BUILD, BEFORE_COMMIT }`
  - `record ApprovalDecision(boolean approved, String reason)` with `approve()`, `reject()`, `rejectWith(String)`, and `hasReason()`
  - `ApprovalGate(Duration timeout)` with `ApprovalDecision await(Gate gate)`, `boolean decide(ApprovalDecision decision)`, `Gate pending()` (null when not waiting)

- [ ] **Step 1: Write the failing test**

Create `src/test/java/ai/devflow/orchestrator/ApprovalGateTest.java`:

```java
package ai.devflow.orchestrator;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalGateTest {

    @Test
    void awaitReturnsTheDecisionSuppliedByAnotherThread() throws Exception {
        var gate = new ApprovalGate(Duration.ofSeconds(5));
        var pool = Executors.newSingleThreadExecutor();
        try {
            Future<ApprovalDecision> waiting = pool.submit(() -> gate.await(Gate.PRE_FLIGHT));

            // Wait for the gate to actually be parked before deciding.
            await(() -> gate.pending() == Gate.PRE_FLIGHT);
            assertThat(gate.decide(ApprovalDecision.approve())).isTrue();

            assertThat(waiting.get(5, TimeUnit.SECONDS).approved()).isTrue();
            assertThat(gate.pending()).isNull();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void rejectionWithAReasonCarriesTheReason() throws Exception {
        var gate = new ApprovalGate(Duration.ofSeconds(5));
        var pool = Executors.newSingleThreadExecutor();
        try {
            Future<ApprovalDecision> waiting = pool.submit(() -> gate.await(Gate.BEFORE_BUILD));
            await(() -> gate.pending() == Gate.BEFORE_BUILD);
            gate.decide(ApprovalDecision.rejectWith("use a DTO, don't annotate the entity"));

            ApprovalDecision decision = waiting.get(5, TimeUnit.SECONDS);
            assertThat(decision.approved()).isFalse();
            assertThat(decision.hasReason()).isTrue();
            assertThat(decision.reason()).contains("use a DTO");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void timingOutCountsAsRejectionWithoutAReason() {
        var gate = new ApprovalGate(Duration.ofMillis(150));
        ApprovalDecision decision = gate.await(Gate.BEFORE_COMMIT);
        assertThat(decision.approved()).isFalse();
        assertThat(decision.hasReason()).isFalse();
    }

    @Test
    void decidingWhenNothingIsPendingReturnsFalse() {
        var gate = new ApprovalGate(Duration.ofSeconds(5));
        assertThat(gate.pending()).isNull();
        assertThat(gate.decide(ApprovalDecision.approve())).isFalse();
    }

    @Test
    void bareRejectionHasNoReason() {
        assertThat(ApprovalDecision.reject().hasReason()).isFalse();
        assertThat(ApprovalDecision.rejectWith("   ").hasReason())
                .as("blank reason is not a reason")
                .isFalse();
    }

    private static void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("condition never became true");
            Thread.sleep(5);
        }
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests '*ApprovalGateTest*'`
Expected: FAIL — the three classes do not exist.

- [ ] **Step 3: Create Gate and ApprovalDecision**

Create `src/main/java/ai/devflow/orchestrator/Gate.java`:

```java
package ai.devflow.orchestrator;

/**
 * The three points where a run pauses for a human (spec §5.2).
 *
 * <p>{@code PRE_FLIGHT} is a start confirmation, not a filesystem guard —
 * {@link ai.devflow.workspace.PathGuard} is the filesystem guard, and the
 * workspace is a disposable temp copy. What PRE_FLIGHT actually confirms is
 * "spend Opus 5 tokens on this repo, for this task".
 */
public enum Gate {
    PRE_FLIGHT,
    BEFORE_BUILD,
    BEFORE_COMMIT
}
```

Create `src/main/java/ai/devflow/orchestrator/ApprovalDecision.java`:

```java
package ai.devflow.orchestrator;

/**
 * A human's answer at a gate. A rejection may carry a reason, which becomes a
 * {@code Finding} with {@code origin = HUMAN} and re-enters the bounded loop —
 * making the operator a third reviewer rather than a kill switch (spec §5.2).
 */
public record ApprovalDecision(boolean approved, String reason) {

    public static ApprovalDecision approve() {
        return new ApprovalDecision(true, null);
    }

    public static ApprovalDecision reject() {
        return new ApprovalDecision(false, null);
    }

    public static ApprovalDecision rejectWith(String reason) {
        return new ApprovalDecision(false, reason);
    }

    /** A blank reason is not a reason — it must not be fed to the coder as guidance. */
    public boolean hasReason() {
        return reason != null && !reason.isBlank();
    }
}
```

- [ ] **Step 4: Create ApprovalGate**

Create `src/main/java/ai/devflow/orchestrator/ApprovalGate.java`:

```java
package ai.devflow.orchestrator;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Parks the orchestrator thread until a human decides, or the timeout expires.
 *
 * <p>SSE is server-to-client only and cannot carry the answer back, so the
 * decision arrives on a different thread via {@code POST /approve} calling
 * {@link #decide}. This class is the meeting point (spec §5.1).
 *
 * <p>A timeout is treated as a bare rejection: the run aborts and the workspace
 * is cleaned up. Silence is never consent.
 */
public class ApprovalGate {

    private final Duration timeout;
    private final AtomicReference<Pending> pending = new AtomicReference<>();

    private record Pending(Gate gate, CompletableFuture<ApprovalDecision> future) {}

    public ApprovalGate(Duration timeout) {
        this.timeout = timeout;
    }

    /** The gate currently awaiting a decision, or null if the run is not paused. */
    public Gate pending() {
        Pending current = pending.get();
        return current == null ? null : current.gate();
    }

    /**
     * Blocks the calling (orchestrator) thread until {@link #decide} is called
     * or the timeout expires.
     */
    public ApprovalDecision await(Gate gate) {
        var future = new CompletableFuture<ApprovalDecision>();
        pending.set(new Pending(gate, future));
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return ApprovalDecision.reject();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ApprovalDecision.reject();
        } catch (java.util.concurrent.ExecutionException e) {
            return ApprovalDecision.reject();
        } finally {
            pending.set(null);
        }
    }

    /**
     * Supplies the decision the orchestrator is waiting for.
     *
     * @return false if nothing was pending — a stale or duplicate approval,
     *         which the controller reports rather than silently swallowing.
     */
    public boolean decide(ApprovalDecision decision) {
        Pending current = pending.getAndSet(null);
        if (current == null) return false;
        return current.future().complete(decision);
    }
}
```

- [ ] **Step 5: Run the tests**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests '*ApprovalGateTest*'`
Expected: 5/5 PASS. The timeout test takes ~150ms; if it hangs, `await`'s timeout units are wrong.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/ai/devflow/orchestrator/Gate.java \
        src/main/java/ai/devflow/orchestrator/ApprovalDecision.java \
        src/main/java/ai/devflow/orchestrator/ApprovalGate.java \
        src/test/java/ai/devflow/orchestrator/ApprovalGateTest.java
git commit -m "feat: ApprovalGate parks the run until a human decides or the timeout expires"
```

---

# Task 5: Orchestrator rework — gates, events, error handling, cleanup

The biggest task in this phase. It resolves **I2** (uncaught model/transport errors escaping `run()` and skipping cleanup) and **C1-b** (a run with an empty changeset could reach `approved = true`).

**Files:**
- Modify: `src/main/java/ai/devflow/orchestrator/Orchestrator.java`
- Modify: `src/test/java/ai/devflow/orchestrator/OrchestratorTest.java`

**Interfaces:**
- Consumes: `Agent`, `AgentResult`, `RunState` (+ `gitTools()`, `setPhase`), `RunPhase`, `Gate`, `ApprovalDecision`, `ApprovalGate`, `RunEventPublisher`, `RunEvent`, `Finding.fromHuman(String)`, `BuildTools(Workspace, Duration)`
- Produces: `Orchestrator(Agent coder, Agent reviewer, RunEventPublisher events, int maxReviewIterations, int maxHumanIterations, Duration buildTimeout)` and `RunOutcome run(RunState state, ApprovalGate gate)`

**The four-arg constructor and one-arg `run(RunState)` from Phase 3 are replaced.** `EndToEndLiveTest` is updated in Task 8.

- [ ] **Step 1: Write the failing tests**

Replace `src/test/java/ai/devflow/orchestrator/OrchestratorTest.java` entirely:

```java
package ai.devflow.orchestrator;

import ai.devflow.agent.*;
import ai.devflow.event.RunEventPublisher;
import ai.devflow.workspace.*;
import org.junit.jupiter.api.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

class OrchestratorTest {

    Workspace workspace;
    RunEventPublisher events;
    ExecutorService pool;

    @BeforeEach
    void setUp() throws Exception {
        workspace = new FixtureWorkspace(Path.of("src/test/resources/fixture"), "orch-test");
        workspace.prepare();
        events = new RunEventPublisher();
        pool = Executors.newCachedThreadPool();
    }

    @AfterEach
    void tearDown() throws Exception {
        pool.shutdownNow();
        workspace.cleanup();
    }

    /** Returns a scripted sequence of results; the last entry repeats. */
    static class ScriptedAgent implements Agent {
        private final String name;
        private final List<AgentResult> script;
        private int i = 0;
        int calls = 0;
        ScriptedAgent(String name, List<AgentResult> script) { this.name = name; this.script = script; }
        @Override public String name() { return name; }
        @Override public AgentResult run(RunState s) {
            calls++;
            return script.get(Math.min(i++, script.size() - 1));
        }
    }

    /** Throws on every call — stands in for a 429 or a dropped connection. */
    static class ExplodingAgent implements Agent {
        private final String name;
        ExplodingAgent(String name) { this.name = name; }
        @Override public String name() { return name; }
        @Override public AgentResult run(RunState s) {
            throw new RuntimeException("429 rate limited");
        }
    }

    private Orchestrator orchestrator(Agent coder, Agent reviewer) {
        return new Orchestrator(coder, reviewer, events, 3, 5, Duration.ofMinutes(1));
    }

    /** Writes a real file so changedFiles() is non-empty, as a real coder would. */
    private ScriptedAgent writingCoder(String summary) {
        return new ScriptedAgent("coder", List.of(
                AgentResult.ok("coder", summary, List.of("scratch.txt"), TokenUsage.NONE))) {
            @Override public AgentResult run(RunState s) {
                try {
                    Files.writeString(s.workspace().root().resolve("scratch.txt"), "written " + calls);
                } catch (Exception e) { throw new RuntimeException(e); }
                return super.run(s);
            }
        };
    }

    /** Runs the orchestrator on a pool, approving every gate as it appears. */
    private Orchestrator.RunOutcome runApprovingAll(Orchestrator orchestrator, RunState state,
                                                    ApprovalGate gate) throws Exception {
        Future<Orchestrator.RunOutcome> outcome = pool.submit(() -> orchestrator.run(state, gate));
        approveGatesUntilDone(gate, outcome);
        return outcome.get(20, TimeUnit.SECONDS);
    }

    private void approveGatesUntilDone(ApprovalGate gate, Future<?> outcome) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while (!outcome.isDone()) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("run never finished");
            if (gate.pending() != null) gate.decide(ApprovalDecision.approve());
            Thread.sleep(5);
        }
    }

    @Test
    void approvedRunPassesThroughAllThreeGates() throws Exception {
        var coder = writingCoder("done");
        var reviewer = new ScriptedAgent("reviewer",
                List.of(AgentResult.ok("reviewer", "looks good", List.of(), TokenUsage.NONE)));
        var state = new RunState("g1", "do a thing", workspace);
        var gate = new ApprovalGate(Duration.ofSeconds(10));

        var outcome = runApprovingAll(orchestrator(coder, reviewer), state, gate);

        assertThat(outcome.approved()).isTrue();
        assertThat(coder.calls).isEqualTo(1);
        assertThat(reviewer.calls).isEqualTo(1);
        assertThat(state.phase()).isEqualTo(RunPhase.DONE);
    }

    @Test
    void rejectingPreFlightAbortsWithoutRunningTheCoder() throws Exception {
        var coder = writingCoder("should never run");
        var reviewer = new ScriptedAgent("reviewer",
                List.of(AgentResult.ok("reviewer", "ok", List.of(), TokenUsage.NONE)));
        var state = new RunState("g2", "t", workspace);
        var gate = new ApprovalGate(Duration.ofSeconds(10));

        Future<Orchestrator.RunOutcome> f = pool.submit(() -> orchestrator(coder, reviewer).run(state, gate));
        while (gate.pending() == null) Thread.sleep(5);
        gate.decide(ApprovalDecision.reject());

        assertThat(f.get(10, TimeUnit.SECONDS).approved()).isFalse();
        assertThat(coder.calls).isZero();
    }

    @Test
    void rejectingWithAReasonSendsAHumanFindingBackToTheCoder() throws Exception {
        var coder = writingCoder("attempt");
        var reviewer = new ScriptedAgent("reviewer",
                List.of(AgentResult.ok("reviewer", "ok", List.of(), TokenUsage.NONE)));
        var state = new RunState("g3", "t", workspace);
        var gate = new ApprovalGate(Duration.ofSeconds(10));

        Future<Orchestrator.RunOutcome> f = pool.submit(() -> orchestrator(coder, reviewer).run(state, gate));

        // Approve pre-flight, then reject the build gate with a reason once.
        while (gate.pending() != Gate.PRE_FLIGHT) Thread.sleep(5);
        gate.decide(ApprovalDecision.approve());
        while (gate.pending() != Gate.BEFORE_BUILD) Thread.sleep(5);
        gate.decide(ApprovalDecision.rejectWith("use a DTO"));

        // Then approve everything else so the run can finish.
        approveGatesUntilDone(gate, f);
        f.get(20, TimeUnit.SECONDS);

        assertThat(coder.calls)
                .as("human rejection sent work back to the coder")
                .isGreaterThanOrEqualTo(2);
        assertThat(state.humanIterations()).isEqualTo(1);
        assertThat(state.reviewIterations())
                .as("human steering must not consume the reviewer's cap")
                .isLessThanOrEqualTo(2);
    }

    @Test
    void loopsBackToTheCoderThenStopsAtTheReviewCap() throws Exception {
        var bounce = AgentResult.needsWork("reviewer", "nope",
                List.of(new Finding(Finding.Origin.REVIEWER, Finding.Severity.HIGH,
                        "A.java", 1, "still wrong")), TokenUsage.NONE);
        var coder = writingCoder("attempt");
        var reviewer = new ScriptedAgent("reviewer", List.of(bounce));
        var state = new RunState("g4", "t", workspace);
        var gate = new ApprovalGate(Duration.ofSeconds(10));

        var outcome = runApprovingAll(orchestrator(coder, reviewer), state, gate);

        assertThat(outcome.approved()).isFalse();
        assertThat(coder.calls).isEqualTo(3);
        assertThat(state.reviewIterations()).isEqualTo(3);
    }

    @Test
    void aFailedReviewIsNotTreatedAsApproval() throws Exception {
        var coder = writingCoder("attempt");
        var reviewer = new ScriptedAgent("reviewer",
                List.of(new AgentResult("reviewer", AgentResult.Status.FAILED,
                        "unparseable", List.of(), List.of(), TokenUsage.NONE)));
        var state = new RunState("g5", "t", workspace);
        var gate = new ApprovalGate(Duration.ofSeconds(10));

        assertThat(runApprovingAll(orchestrator(coder, reviewer), state, gate).approved()).isFalse();
    }

    @Test
    void anExceptionFromAnAgentBecomesAFailedOutcomeNotAnEscapedThrowable() throws Exception {
        var state = new RunState("g6", "t", workspace);
        var gate = new ApprovalGate(Duration.ofSeconds(10));
        var orchestrator = orchestrator(new ExplodingAgent("coder"),
                new ScriptedAgent("reviewer", List.of(
                        AgentResult.ok("reviewer", "ok", List.of(), TokenUsage.NONE))));

        var outcome = runApprovingAll(orchestrator, state, gate);

        assertThat(outcome.approved()).isFalse();
        assertThat(outcome.reason()).contains("429");
        assertThat(state.phase()).isEqualTo(RunPhase.FAILED);
    }

    @Test
    void anEmptyChangesetIsNeverApproved() throws Exception {
        // A coder that writes nothing: changedFiles() stays empty. Even if the
        // reviewer says OK (nothing to complain about), approving would claim a
        // change was made and reviewed when neither happened.
        var idleCoder = new ScriptedAgent("coder",
                List.of(AgentResult.ok("coder", "I did nothing", List.of(), TokenUsage.NONE)));
        var reviewer = new ScriptedAgent("reviewer",
                List.of(AgentResult.ok("reviewer", "nothing to review", List.of(), TokenUsage.NONE)));
        var state = new RunState("g7", "t", workspace);
        var gate = new ApprovalGate(Duration.ofSeconds(10));

        var outcome = runApprovingAll(orchestrator(idleCoder, reviewer), state, gate);

        assertThat(outcome.approved()).isFalse();
        assertThat(outcome.reason()).containsIgnoringCase("no changes");
    }

    @Test
    void orchestratorNeverHoldsFileContents() throws Exception {
        var coder = writingCoder("done");
        var reviewer = new ScriptedAgent("reviewer",
                List.of(AgentResult.ok("reviewer", "ok", List.of(), TokenUsage.NONE)));
        var state = new RunState("g8", "t", workspace);
        var gate = new ApprovalGate(Duration.ofSeconds(10));

        runApprovingAll(orchestrator(coder, reviewer), state, gate);

        assertThat(state.history()).allSatisfy(r ->
                assertThat(r.summary().length()).isLessThan(2_000));
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests '*OrchestratorTest*'`
Expected: FAIL — the new constructor and `run(RunState, ApprovalGate)` do not exist.

- [ ] **Step 3: Rewrite Orchestrator**

Replace `src/main/java/ai/devflow/orchestrator/Orchestrator.java` entirely:

```java
package ai.devflow.orchestrator;

import ai.devflow.agent.Agent;
import ai.devflow.agent.AgentResult;
import ai.devflow.agent.Finding;
import ai.devflow.event.RunEvent;
import ai.devflow.event.RunEventPublisher;
import ai.devflow.tools.BuildTools;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Sequences the crew and owns the only control flow in the system.
 *
 * <p>Central orchestration: the agents are pure {@code RunState -> AgentResult}
 * functions that never call each other. This class decides what runs next, and
 * holds only {@code AgentResult} records — never file contents or diffs.
 */
public class Orchestrator {

    public record RunOutcome(boolean approved, String reason, RunState state) {}

    private final Agent coder;
    private final Agent reviewer;
    private final RunEventPublisher events;
    private final int maxReviewIterations;
    private final int maxHumanIterations;
    private final Duration buildTimeout;

    public Orchestrator(Agent coder, Agent reviewer, RunEventPublisher events,
                        int maxReviewIterations, int maxHumanIterations, Duration buildTimeout) {
        this.coder = coder;
        this.reviewer = reviewer;
        this.events = events;
        this.maxReviewIterations = maxReviewIterations;
        this.maxHumanIterations = maxHumanIterations;
        this.buildTimeout = buildTimeout;
    }

    public RunOutcome run(RunState state, ApprovalGate gate) {
        String runId = state.runId();
        try {
            return execute(state, gate);
        } catch (RuntimeException e) {
            // I2: a model or transport failure (a 429, a dropped connection)
            // must become a FAILED outcome, never an escaped throwable that
            // skips the cleanup in the finally block below.
            state.setPhase(RunPhase.FAILED);
            String reason = "Run failed: " + e;
            events.publish(runId, RunEvent.of("error", reason));
            return new RunOutcome(false, reason, state);
        } finally {
            cleanUp(state);
            events.complete(runId);
        }
    }

    private RunOutcome execute(RunState state, ApprovalGate gate) {
        String runId = state.runId();

        emit(state, "step", "Workspace ready — branch " + state.workspace().branchName(),
                Map.of("branch", state.workspace().branchName()));

        // ---- Gate 1: pre-flight -------------------------------------------
        // Not a filesystem guard (PathGuard is, and the workspace is a
        // disposable temp copy) — this confirms "spend tokens on this repo,
        // for this task" before the first Opus 5 call.
        emit(state, "gate", "About to run CODER then REVIEWER on this task", Map.of(
                "gate", Gate.PRE_FLIGHT.name(),
                "branch", state.workspace().branchName(),
                "task", state.task()));
        ApprovalDecision preFlight = gate.await(Gate.PRE_FLIGHT);
        if (!preFlight.approved()) {
            if (preFlight.hasReason()) {
                // A reason at pre-flight refines the task rather than aborting.
                state.addFindings(List.of(Finding.fromHuman(preFlight.reason())));
                state.incrementHumanIterations();
                emit(state, "step", "Operator refined the task before starting", Map.of());
            } else {
                return aborted(state, "Rejected at pre-flight");
            }
        }

        AgentResult lastReview = null;

        while (state.reviewIterations() < maxReviewIterations) {
            state.incrementReviewIterations();

            // ---- Coder ----------------------------------------------------
            state.setPhase(RunPhase.CODING);
            emit(state, "step", "Coder — working…", Map.of("iteration", state.reviewIterations()));
            AgentResult coded = coder.run(state);
            state.record(coded);
            state.clearFindings();
            emit(state, "step", "Coder — " + coded.summary(),
                    Map.of("filesTouched", coded.filesTouched()));

            if (coded.status() == AgentResult.Status.FAILED) {
                return failed(state, "Coder failed: " + coded.summary());
            }

            // ---- Reviewer -------------------------------------------------
            state.setPhase(RunPhase.REVIEWING);
            emit(state, "step", "Reviewer — reading changed files…", Map.of());
            AgentResult reviewed = reviewer.run(state);
            state.record(reviewed);
            lastReview = reviewed;

            switch (reviewed.status()) {
                case FAILED -> {
                    // Fail closed — an unparseable review is not an approval.
                    return failed(state, "Review failed: " + reviewed.summary());
                }
                case NEEDS_WORK -> {
                    state.addFindings(reviewed.findings());
                    emit(state, "step", "Reviewer — needs work: " + reviewed.summary(),
                            Map.of("findings", reviewed.findings().size()));
                    continue;
                }
                case OK -> {
                    emit(state, "step", "Reviewer — approved: " + reviewed.summary(), Map.of());
                }
            }

            // ---- Gate 2: before the build ---------------------------------
            // Arbitrary code execution: this runs the TARGET repo's wrapper.
            // Also the first gate where a real file list exists.
            List<String> changed = state.gitTools().changedFiles();
            emit(state, "gate", "About to run the build on " + changed.size() + " changed file(s)",
                    Map.of("gate", Gate.BEFORE_BUILD.name(), "filesTouched", changed));
            ApprovalDecision beforeBuild = gate.await(Gate.BEFORE_BUILD);
            if (!beforeBuild.approved()) {
                if (beforeBuild.hasReason() && state.humanIterations() < maxHumanIterations) {
                    state.addFindings(List.of(Finding.fromHuman(beforeBuild.reason())));
                    state.incrementHumanIterations();
                    // A human correction does not consume the reviewer's cap.
                    decrementReviewIteration(state);
                    emit(state, "step", "Operator sent it back: " + beforeBuild.reason(), Map.of());
                    continue;
                }
                return aborted(state, "Rejected before the build");
            }

            // ---- Build ----------------------------------------------------
            state.setPhase(RunPhase.BUILDING);
            emit(state, "step", "Running the target repository's build…", Map.of());
            var build = new BuildTools(state.workspace(), buildTimeout).build("test");
            emit(state, "step", build.success() ? "Build passed" : "Build failed", 
                    Map.of("success", build.success()));

            // ---- Gate 3: before the commit --------------------------------
            // C1-b: a run that changed nothing must never be reported approved.
            // Nothing was disguised here (filesTouched is visibly empty), but
            // approved=true is an operator-facing claim, so refuse it.
            if (changed.isEmpty()) {
                return failed(state, "Refusing to approve: the coder made no changes");
            }

            emit(state, "gate", "About to commit " + changed.size() + " file(s)", Map.of(
                    "gate", Gate.BEFORE_COMMIT.name(),
                    "filesTouched", changed,
                    "buildPassed", build.success()));
            ApprovalDecision beforeCommit = gate.await(Gate.BEFORE_COMMIT);
            if (!beforeCommit.approved()) {
                if (beforeCommit.hasReason() && state.humanIterations() < maxHumanIterations) {
                    state.addFindings(List.of(Finding.fromHuman(beforeCommit.reason())));
                    state.incrementHumanIterations();
                    decrementReviewIteration(state);
                    emit(state, "step", "Operator sent it back: " + beforeCommit.reason(), Map.of());
                    continue;
                }
                return aborted(state, "Rejected before the commit");
            }

            // ---- Commit ---------------------------------------------------
            state.setPhase(RunPhase.COMMITTING);
            String committed = state.gitTools().commit("devflowai: " + state.task());
            emit(state, "step", committed, Map.of());

            state.setPhase(RunPhase.DONE);
            emit(state, "done", "Approved and committed on " + state.workspace().branchName(),
                    Map.of("branch", state.workspace().branchName(),
                           "inputTokens", state.totalTokens().input(),
                           "outputTokens", state.totalTokens().output()));
            return new RunOutcome(true, "Approved by reviewer and operator", state);
        }

        String reason = lastReview == null
                ? "Review loop ended without a review"
                : "Review loop hit the cap of " + maxReviewIterations + " iterations";
        return failed(state, reason);
    }

    /**
     * Gives back one reviewer iteration after a human correction. The reviewer
     * cap exists to stop two models ping-ponging at the operator's expense; a
     * human deliberately steering is charged to {@code humanIterations}
     * instead (spec §5.2).
     */
    private void decrementReviewIteration(RunState state) {
        if (state.reviewIterations() > 0) state.rollBackReviewIteration();
    }

    private RunOutcome aborted(RunState state, String reason) {
        state.setPhase(RunPhase.FAILED);
        emit(state, "aborted", reason, Map.of());
        return new RunOutcome(false, reason, state);
    }

    private RunOutcome failed(RunState state, String reason) {
        state.setPhase(RunPhase.FAILED);
        emit(state, "error", reason, Map.of());
        return new RunOutcome(false, reason, state);
    }

    private void emit(RunState state, String type, String message, Map<String, Object> data) {
        events.publish(state.runId(), RunEvent.of(type, message, data));
    }

    /** Runs on every exit path — normal, rejected, or thrown. */
    private void cleanUp(RunState state) {
        try {
            state.workspace().cleanup();
        } catch (Exception e) {
            events.publish(state.runId(),
                    RunEvent.of("warn", "Workspace cleanup failed: " + e.getMessage()));
        }
    }

    public int maxHumanIterations() { return maxHumanIterations; }
}
```

- [ ] **Step 4: Add rollBackReviewIteration to RunState**

The orchestrator needs to return an iteration to the pool after a human correction. Add to `src/main/java/ai/devflow/orchestrator/RunState.java`:

```java
    /**
     * Returns one reviewer iteration. Used when a human rejection sends work
     * back — that round is charged to humanIterations, not the reviewer's cap.
     */
    public synchronized void rollBackReviewIteration() {
        if (reviewIterations > 0) reviewIterations--;
    }
```

- [ ] **Step 5: Run the orchestrator tests**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests '*OrchestratorTest*'`
Expected: 8/8 PASS.

Note `anEmptyChangesetIsNeverApproved` and `anExceptionFromAnAgentBecomesAFailedOutcomeNotAnEscapedThrowable` are the two carrying deferred findings C1-b and I2 — if either fails, that finding is not actually fixed.

- [ ] **Step 6: Run the full suite**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew clean test`
Expected: `EndToEndLiveTest` will not compile against the new constructor yet — that is fixed in Task 8. If the build fails only there, continue; otherwise fix what broke.

To keep the tree green between tasks, temporarily update `EndToEndLiveTest`'s orchestrator construction now:

```java
            var orchestrator = new Orchestrator(
                    new CoderAgent(coderClient),
                    new ReviewerAgent(reviewerClient),
                    new ai.devflow.event.RunEventPublisher(),
                    3, 5, java.time.Duration.ofMinutes(5));

            var state = new RunState("e2e", "Add input validation to UserController so a null or "
                    + "blank email is rejected with HTTP 400. Cover it with a test.", ws);

            var gate = new ApprovalGate(java.time.Duration.ofSeconds(30));
            var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
            java.util.concurrent.Future<Orchestrator.RunOutcome> f =
                    pool.submit(() -> orchestrator.run(state, gate));
            while (!f.isDone()) {
                if (gate.pending() != null) gate.decide(ApprovalDecision.approve());
                Thread.sleep(10);
            }
            var outcome = f.get();
            pool.shutdownNow();
```

Note the orchestrator now cleans up the workspace itself, so `EndToEndLiveTest`'s own `finally { ws.cleanup(); }` becomes a harmless second call (`cleanup()` is a no-op when the root is already gone). Leave it — belt and braces.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/ai/devflow/orchestrator/ src/test/java/ai/devflow/orchestrator/ \
        src/test/java/ai/devflow/EndToEndLiveTest.java
git commit -m "feat: orchestrator gates, event emission, error handling and guaranteed cleanup"
```

---

# Task 6: RunRegistry and RunHandle

**Files:**
- Create: `src/main/java/ai/devflow/orchestrator/RunHandle.java`
- Create: `src/main/java/ai/devflow/orchestrator/RunRegistry.java`
- Modify: `src/main/resources/application.yml`
- Test: `src/test/java/ai/devflow/orchestrator/RunRegistryTest.java`

**Interfaces:**
- Consumes: `RunState`, `ApprovalGate`, `Orchestrator`, `Workspace`, `FixtureWorkspace`
- Produces: `RunRegistry.start(String task, String repo)` returning `RunHandle`; `RunRegistry.find(String runId)` returning `RunHandle` or null; `RunHandle.runId()`, `.state()`, `.gate()`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/ai/devflow/orchestrator/RunRegistryTest.java`:

```java
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
```

- [ ] **Step 2: Run to verify failure**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests '*RunRegistryTest*'`
Expected: FAIL — neither class exists.

- [ ] **Step 3: Create RunHandle**

Create `src/main/java/ai/devflow/orchestrator/RunHandle.java`:

```java
package ai.devflow.orchestrator;

import java.util.concurrent.Future;

/** Everything the web layer needs to observe or steer one in-flight run. */
public record RunHandle(String runId, RunState state, ApprovalGate gate, Future<?> task) {}
```

- [ ] **Step 4: Create RunRegistry**

Create `src/main/java/ai/devflow/orchestrator/RunRegistry.java`:

```java
package ai.devflow.orchestrator;

import ai.devflow.event.RunEvent;
import ai.devflow.event.RunEventPublisher;
import ai.devflow.workspace.FixtureWorkspace;
import ai.devflow.workspace.Workspace;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/**
 * Starts runs and keeps the in-flight ones addressable by id.
 *
 * <p>In-memory and single-node by design (spec §11): an interrupted run is
 * lost, and that is an accepted limitation for a single-user local tool.
 */
public class RunRegistry {

    private final Orchestrator orchestrator;
    private final RunEventPublisher events;
    private final ExecutorService executor;
    private final Path fixtureSource;
    private final Duration gateTimeout;

    private final Map<String, RunHandle> runs = new ConcurrentHashMap<>();

    public RunRegistry(Orchestrator orchestrator, RunEventPublisher events,
                       ExecutorService executor, Path fixtureSource, Duration gateTimeout) {
        this.orchestrator = orchestrator;
        this.events = events;
        this.executor = executor;
        this.fixtureSource = fixtureSource;
        this.gateTimeout = gateTimeout;
    }

    /**
     * Prepares a workspace and starts the orchestrator on the executor.
     *
     * @param repo currently only "fixture" — Phase 6 adds git-URL cloning
     *             behind the same {@link Workspace} interface.
     */
    public RunHandle start(String task, String repo) {
        String runId = UUID.randomUUID().toString().substring(0, 8);
        Workspace workspace = new FixtureWorkspace(fixtureSource, runId);
        RunState state = new RunState(runId, task, workspace);
        ApprovalGate gate = new ApprovalGate(gateTimeout);

        var future = executor.submit(() -> {
            try {
                workspace.prepare();
                orchestrator.run(state, gate);
            } catch (Exception e) {
                state.setPhase(RunPhase.FAILED);
                events.publish(runId, RunEvent.of("error",
                        "Could not prepare the workspace: " + e.getMessage(), Map.of()));
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
}
```

Note `runs.remove(runId)` in the `finally` block means a finished run is no longer findable — the controller must therefore report an unknown run id rather than assuming it is still live. Task 7's `approve` endpoint handles that.

- [ ] **Step 5: Add configuration to application.yml**

Append to `src/main/resources/application.yml` under the existing `devflowai:` key (the `review` and `gate` blocks are already there from Phase 0):

```yaml
devflowai:
  review:
    max-iterations: 3
    max-human-iterations: 5
  gate:
    timeout-minutes: 10
  build:
    timeout-minutes: 5
  fixture:
    path: src/test/resources/fixture
```

- [ ] **Step 6: Run the tests**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests '*RunRegistryTest*'`
Expected: 3/3 PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/ai/devflow/orchestrator/RunHandle.java \
        src/main/java/ai/devflow/orchestrator/RunRegistry.java \
        src/main/resources/application.yml \
        src/test/java/ai/devflow/orchestrator/RunRegistryTest.java
git commit -m "feat: RunRegistry starts runs on an executor and keeps them addressable"
```

---

# Task 7: RunController and Spring wiring

**Files:**
- Create: `src/main/java/ai/devflow/web/StartRunRequest.java`
- Create: `src/main/java/ai/devflow/web/ApproveRequest.java`
- Create: `src/main/java/ai/devflow/web/RunController.java`
- Create: `src/main/java/ai/devflow/config/OrchestrationConfig.java`
- Test: `src/test/java/ai/devflow/web/RunControllerTest.java`

**Interfaces:**
- Consumes: `RunRegistry`, `RunHandle`, `RunEventPublisher`, `ApprovalDecision`, `Orchestrator`, `ChatClient` beans
- Produces: `POST /api/runs` → `{"runId": "..."}`; `GET /api/runs/{id}/stream` → `SseEmitter`; `POST /api/runs/{id}/approve` → 200 or 404/409

- [ ] **Step 1: Write the failing test**

Create `src/test/java/ai/devflow/web/RunControllerTest.java`:

```java
package ai.devflow.web;

import ai.devflow.orchestrator.*;
import ai.devflow.event.RunEventPublisher;
import ai.devflow.workspace.FixtureWorkspace;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Executors;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RunControllerTest {

    MockMvc mvc;
    RunRegistry registry;
    RunEventPublisher events;
    ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        registry = mock(RunRegistry.class);
        events = new RunEventPublisher();
        mvc = MockMvcBuilders.standaloneSetup(new RunController(registry, events)).build();
    }

    private RunHandle handleFor(String runId) throws Exception {
        var workspace = new FixtureWorkspace(Path.of("src/test/resources/fixture"), runId);
        workspace.prepare();
        var state = new RunState(runId, "a task", workspace);
        return new RunHandle(runId, state, new ApprovalGate(Duration.ofSeconds(5)), null);
    }

    @Test
    void startingARunReturnsItsId() throws Exception {
        when(registry.start(any(), any())).thenReturn(handleFor("abc123"));

        mvc.perform(post("/api/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new StartRunRequest("do a thing", "fixture"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value("abc123"));

        verify(registry).start("do a thing", "fixture");
    }

    @Test
    void startingARunRejectsABlankTask() throws Exception {
        mvc.perform(post("/api/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new StartRunRequest("   ", "fixture"))))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(registry);
    }

    @Test
    void streamingAnUnknownRunIs404() throws Exception {
        when(registry.find("nope")).thenReturn(null);
        mvc.perform(get("/api/runs/nope/stream")).andExpect(status().isNotFound());
    }

    @Test
    void approvingAnUnknownRunIs404() throws Exception {
        when(registry.find("nope")).thenReturn(null);
        mvc.perform(post("/api/runs/nope/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new ApproveRequest(true, null))))
                .andExpect(status().isNotFound());
    }

    @Test
    void approvingWhenNoGateIsPendingIs409() throws Exception {
        RunHandle handle = handleFor("run-1");
        when(registry.find("run-1")).thenReturn(handle);

        // Nothing is parked, so the decision has nowhere to go.
        mvc.perform(post("/api/runs/run-1/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new ApproveRequest(true, null))))
                .andExpect(status().isConflict());

        handle.state().workspace().cleanup();
    }

    @Test
    void approvingAPendingGateSucceeds() throws Exception {
        RunHandle handle = handleFor("run-2");
        when(registry.find("run-2")).thenReturn(handle);

        var pool = Executors.newSingleThreadExecutor();
        pool.submit(() -> handle.gate().await(Gate.PRE_FLIGHT));
        while (handle.gate().pending() == null) Thread.sleep(5);

        mvc.perform(post("/api/runs/run-2/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new ApproveRequest(true, null))))
                .andExpect(status().isOk());

        pool.shutdownNow();
        handle.state().workspace().cleanup();
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests '*RunControllerTest*'`
Expected: FAIL — the controller and request records do not exist.

- [ ] **Step 3: Create the request records**

Create `src/main/java/ai/devflow/web/StartRunRequest.java`:

```java
package ai.devflow.web;

/** @param repo "fixture" today; Phase 6 accepts a git URL here. */
public record StartRunRequest(String task, String repo) {}
```

Create `src/main/java/ai/devflow/web/ApproveRequest.java`:

```java
package ai.devflow.web;

/** @param reason optional; a rejection carrying one steers instead of aborting. */
public record ApproveRequest(boolean approved, String reason) {}
```

- [ ] **Step 4: Create RunController**

Create `src/main/java/ai/devflow/web/RunController.java`:

```java
package ai.devflow.web;

import ai.devflow.event.RunEventPublisher;
import ai.devflow.orchestrator.ApprovalDecision;
import ai.devflow.orchestrator.RunHandle;
import ai.devflow.orchestrator.RunRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/**
 * The entire HTTP surface: start a run, watch it, answer a gate.
 *
 * <p>Three endpoints and nothing else — the page reaches no other route
 * (spec §5.3).
 */
@RestController
@RequestMapping("/api/runs")
public class RunController {

    private final RunRegistry registry;
    private final RunEventPublisher events;

    public RunController(RunRegistry registry, RunEventPublisher events) {
        this.registry = registry;
        this.events = events;
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> start(@RequestBody StartRunRequest request) {
        if (request.task() == null || request.task().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "task must not be blank"));
        }
        String repo = (request.repo() == null || request.repo().isBlank()) ? "fixture" : request.repo();
        RunHandle handle = registry.start(request.task().trim(), repo);
        return ResponseEntity.ok(Map.of("runId", handle.runId()));
    }

    @GetMapping("/{runId}/stream")
    public ResponseEntity<SseEmitter> stream(@PathVariable String runId) {
        if (registry.find(runId) == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(events.subscribe(runId));
    }

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

        // False means nothing was parked: a stale click, a double submit, or a
        // gate that already timed out. Report it rather than pretending.
        if (!handle.gate().decide(decision)) {
            return ResponseEntity.status(409)
                    .body(Map.of("error", "no gate is currently awaiting a decision"));
        }
        return ResponseEntity.ok(Map.of("status", "accepted"));
    }
}
```

- [ ] **Step 5: Wire the beans**

Create `src/main/java/ai/devflow/config/OrchestrationConfig.java`:

```java
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
```

- [ ] **Step 6: Run the tests**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew clean test`
Expected: 6/6 `RunControllerTest` pass, and `contextLoads` still passes — proving the new beans wire into a real Spring context alongside the existing `ChatClientConfig` beans.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/ai/devflow/web/ src/main/java/ai/devflow/config/OrchestrationConfig.java \
        src/test/java/ai/devflow/web/
git commit -m "feat: RunController with start, stream and approve endpoints"
```

---

# Task 8: The web page

One page, three states, vanilla JS. Spec §5.3 is explicit that a framework would be more machinery than this surface justifies.

**Files:**
- Create: `src/main/resources/static/index.html`
- Modify: `src/test/java/ai/devflow/EndToEndLiveTest.java`
- Test: `src/test/java/ai/devflow/web/StaticPageTest.java`

**Interfaces:**
- Consumes: the three endpoints from Task 7
- Produces: a served page at `/`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/ai/devflow/web/StaticPageTest.java`:

```java
package ai.devflow.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.ai.anthropic.api-key=test-key-not-used")
@AutoConfigureMockMvc
class StaticPageTest {

    @Autowired MockMvc mvc;

    @Test
    void servesTheOperatorPageAtRoot() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("devflowai")))
                // The three things the operator actually interacts with.
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"task\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"run\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("EventSource")));
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests '*StaticPageTest*'`
Expected: FAIL — 404, no page exists.

- [ ] **Step 3: Create the page**

Create `src/main/resources/static/index.html`:

```html
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>devflowai</title>
  <style>
    :root { color-scheme: light dark; --fg:#1a1a1a; --bg:#fbfbfa; --muted:#6b6b6b;
            --line:#e0e0dc; --accent:#2d6a4f; --warn:#9a3412; }
    @media (prefers-color-scheme: dark) {
      :root { --fg:#e8e8e6; --bg:#1c1c1b; --muted:#9a9a97; --line:#333331;
              --accent:#74c69d; --warn:#fb923c; }
    }
    * { box-sizing: border-box; }
    body { margin:0; padding:2rem 1rem; background:var(--bg); color:var(--fg);
           font:15px/1.55 ui-sans-serif, system-ui, -apple-system, sans-serif; }
    main { max-width: 46rem; margin: 0 auto; }
    h1 { font-size:1.1rem; letter-spacing:-0.01em; margin:0 0 1.5rem; }
    label { display:block; font-size:0.8rem; color:var(--muted); margin-bottom:0.3rem; }
    .row { margin-bottom:0.9rem; }
    input, select, textarea { width:100%; padding:0.55rem 0.7rem; font:inherit;
      color:var(--fg); background:var(--bg); border:1px solid var(--line); border-radius:6px; }
    button { font:inherit; padding:0.5rem 1.1rem; border-radius:6px; cursor:pointer;
      border:1px solid var(--line); background:var(--bg); color:var(--fg); }
    button.primary { background:var(--accent); border-color:var(--accent); color:var(--bg); }
    button:disabled { opacity:0.45; cursor:not-allowed; }
    hr { border:0; border-top:1px solid var(--line); margin:1.5rem 0; }
    #log { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size:13px; }
    .ev { padding:0.3rem 0; border-bottom:1px solid var(--line); }
    .ev .t { color:var(--muted); margin-right:0.5rem; }
    .ev.error .m, .ev.aborted .m { color:var(--warn); }
    .ev.done .m { color:var(--accent); }
    .files { color:var(--muted); padding-left:1.4rem; }
    #gate { border:1px solid var(--accent); border-radius:8px; padding:1rem; margin-top:1rem; }
    #gate h2 { font-size:0.95rem; margin:0 0 0.6rem; }
    .hidden { display:none; }
    .actions { display:flex; gap:0.6rem; margin-top:0.8rem; }
  </style>
</head>
<body>
<main>
  <h1>devflowai</h1>

  <section id="start">
    <div class="row">
      <label for="repo">Repo</label>
      <select id="repo"><option value="fixture">bundled fixture</option></select>
    </div>
    <div class="row">
      <label for="task">Task</label>
      <input id="task" placeholder="Add input validation to UserController and cover it with tests."
             value="Add input validation to UserController so a null or blank email is rejected with HTTP 400. Cover it with a test.">
    </div>
    <button id="run" class="primary">Run</button>
  </section>

  <hr>

  <section id="log"></section>

  <section id="gate" class="hidden">
    <h2 id="gate-title"></h2>
    <div id="gate-detail" class="files"></div>
    <div class="row" style="margin-top:0.8rem">
      <label for="reason">reason (optional — a rejection with a reason sends it back to the coder)</label>
      <input id="reason" placeholder="use a DTO, don't annotate the entity">
    </div>
    <div class="actions">
      <button id="approve" class="primary">Approve</button>
      <button id="reject">Reject</button>
    </div>
  </section>
</main>

<script>
  const $ = (id) => document.getElementById(id);
  let runId = null;

  function log(type, message, data) {
    const el = document.createElement('div');
    el.className = 'ev ' + type;
    const time = new Date().toLocaleTimeString();
    el.innerHTML = `<span class="t">${time}</span><span class="m"></span>`;
    el.querySelector('.m').textContent = message;
    if (data && Array.isArray(data.filesTouched) && data.filesTouched.length) {
      const files = document.createElement('div');
      files.className = 'files';
      files.textContent = data.filesTouched.join('  ·  ');
      el.appendChild(files);
    }
    $('log').appendChild(el);
    el.scrollIntoView({ block: 'nearest' });
  }

  function showGate(message, data) {
    $('gate-title').textContent = message;
    const files = (data && data.filesTouched) || [];
    $('gate-detail').textContent = files.length ? files.join('\n') : '';
    $('reason').value = '';
    $('gate').classList.remove('hidden');
  }

  function hideGate() { $('gate').classList.add('hidden'); }

  async function decide(approved) {
    const reason = $('reason').value.trim();
    hideGate();
    const res = await fetch(`/api/runs/${runId}/approve`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ approved, reason: reason || null })
    });
    if (!res.ok) log('error', `Could not submit that decision (HTTP ${res.status})`);
  }

  $('approve').onclick = () => decide(true);
  $('reject').onclick = () => decide(false);

  $('run').onclick = async () => {
    const task = $('task').value.trim();
    if (!task) return;
    $('run').disabled = true;
    $('log').innerHTML = '';
    hideGate();

    const res = await fetch('/api/runs', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ task, repo: $('repo').value })
    });
    if (!res.ok) {
      log('error', `Could not start the run (HTTP ${res.status})`);
      $('run').disabled = false;
      return;
    }
    runId = (await res.json()).runId;
    log('step', `Run ${runId} started`);

    const source = new EventSource(`/api/runs/${runId}/stream`);
    const onEvent = (e) => {
      const payload = JSON.parse(e.data);
      if (payload.type === 'gate') showGate(payload.message, payload.data);
      else log(payload.type, payload.message, payload.data);
      if (payload.type === 'done' || payload.type === 'error' || payload.type === 'aborted') {
        source.close();
        $('run').disabled = false;
      }
    };
    ['step', 'gate', 'done', 'error', 'aborted', 'warn'].forEach(n => source.addEventListener(n, onEvent));
    source.onerror = () => { source.close(); $('run').disabled = false; };
  };
</script>
</body>
</html>
```

- [ ] **Step 4: Run the page test**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests '*StaticPageTest*'`
Expected: PASS.

- [ ] **Step 5: Start the app and click through it by hand**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export ANTHROPIC_API_KEY=<your key>
./gradlew bootRun
```

Open <http://localhost:8080>. Type a task, click Run. You should see the pre-flight gate appear, then — after approving — coder and reviewer steps streaming in. Approve through the build and commit gates. This is the first time devflowai is a usable product rather than a library.

Record what you observed in the report. If no key is available, note that the page loads and the pre-flight gate appears (workspace preparation needs no model call), and that everything past the first Opus 5 call is unverified.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/static/index.html src/test/java/ai/devflow/web/StaticPageTest.java
git commit -m "feat: single-page operator UI with live SSE log and gate controls"
```

---

# Task 9: Full-stack integration test

Proves the whole path — HTTP start, SSE events, a gate decision over HTTP, completion — with stubbed `ChatClient`s, so it costs nothing and runs in CI.

**Files:**
- Test: `src/test/java/ai/devflow/web/RunFlowIntegrationTest.java`

**Interfaces:**
- Consumes: everything

- [ ] **Step 1: Write the test**

Create `src/test/java/ai/devflow/web/RunFlowIntegrationTest.java`:

```java
package ai.devflow.web;

import ai.devflow.orchestrator.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

import ai.devflow.agent.*;

import java.nio.file.Files;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.ai.anthropic.api-key=test-key-not-used",
        "devflowai.gate.timeout-minutes=1"
})
@AutoConfigureMockMvc
@Import(RunFlowIntegrationTest.StubAgents.class)
class RunFlowIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired RunRegistry registry;
    ObjectMapper json = new ObjectMapper();

    @TestConfiguration
    static class StubAgents {
        /** Writes a real file so changedFiles() is non-empty, like a real coder. */
        @Bean @Primary
        Agent coderAgent() {
            return new Agent() {
                @Override public String name() { return "coder"; }
                @Override public AgentResult run(RunState s) {
                    try {
                        Files.writeString(s.workspace().root().resolve("Added.java"), "class Added {}");
                    } catch (Exception e) { throw new RuntimeException(e); }
                    return AgentResult.ok("coder", "added a class",
                            s.gitTools().changedFiles(), TokenUsage.NONE);
                }
            };
        }

        @Bean @Primary
        Agent reviewerAgent() {
            return new Agent() {
                @Override public String name() { return "reviewer"; }
                @Override public AgentResult run(RunState s) {
                    return AgentResult.ok("reviewer", "looks correct", List.of(), TokenUsage.NONE);
                }
            };
        }
    }

    @Test
    void aRunStartsPausesAtEachGateAndCompletesWhenApproved() throws Exception {
        String body = mvc.perform(post("/api/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new StartRunRequest("add a class", "fixture"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String runId = json.readTree(body).get("runId").asText();

        RunHandle handle = registry.find(runId);
        assertThat(handle).isNotNull();

        // Approve each gate as it appears, over HTTP — the real path.
        long deadline = System.currentTimeMillis() + 60_000;
        while (!handle.task().isDone()) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("run never finished");
            if (handle.gate().pending() != null) {
                mvc.perform(post("/api/runs/" + runId + "/approve")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(new ApproveRequest(true, null))))
                        .andExpect(status().isOk());
            }
            Thread.sleep(10);
        }

        assertThat(handle.state().phase()).isEqualTo(RunPhase.DONE);
        assertThat(handle.state().history()).isNotEmpty();
    }

    @Test
    void rejectingPreFlightEndsTheRun() throws Exception {
        String body = mvc.perform(post("/api/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new StartRunRequest("add a class", "fixture"))))
                .andReturn().getResponse().getContentAsString();
        String runId = json.readTree(body).get("runId").asText();
        RunHandle handle = registry.find(runId);

        while (handle.gate().pending() == null) Thread.sleep(5);
        mvc.perform(post("/api/runs/" + runId + "/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new ApproveRequest(false, null))))
                .andExpect(status().isOk());

        long deadline = System.currentTimeMillis() + 30_000;
        while (!handle.task().isDone()) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("run never ended");
            Thread.sleep(10);
        }
        assertThat(handle.state().phase()).isEqualTo(RunPhase.FAILED);
    }
}
```

- [ ] **Step 2: Run it**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests '*RunFlowIntegrationTest*'`
Expected: 2/2 PASS, with no API key needed.

If `@Primary` stub agents do not override the real ones, check `OrchestrationConfig`'s bean names — a `@Bean` method named `coderAgent` in the test config must match to take precedence.

- [ ] **Step 3: Run the full suite**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew clean test`
Expected: everything green.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/ai/devflow/web/RunFlowIntegrationTest.java
git commit -m "test: full-stack run flow through HTTP with stubbed agents"
```

---

# Task 10: Update CLAUDE.md and the design spec

Phase 4 changes two things a future session would otherwise get wrong: how you run the app, and Gate 1's meaning.

**Files:**
- Modify: `CLAUDE.md`
- Modify: `docs/superpowers/specs/2026-08-27-devflowai-design.md`

- [ ] **Step 1: Add a "Running it" section to CLAUDE.md**

Insert after the existing "Build and test" section:

```markdown
## Running it

    export JAVA_HOME=/opt/homebrew/opt/openjdk@21
    export ANTHROPIC_API_KEY=<key>
    ./gradlew bootRun

Then open http://localhost:8080. Type a task, click Run, approve at each of the
three gates. A rejection with a reason sends the work back to the coder instead
of ending the run.

Endpoints (the page uses these three and nothing else):

    POST /api/runs                  {"task": "...", "repo": "fixture"} -> {"runId": "..."}
    GET  /api/runs/{id}/stream      Server-Sent Events
    POST /api/runs/{id}/approve     {"approved": true|false, "reason": "..."|null}
```

- [ ] **Step 2: Add the Phase 4 non-negotiables to CLAUDE.md**

Append to the "Non-negotiables" list:

```markdown
- **Agents are singleton beans and must stay stateless.** Everything per-run
  arrives via `RunState` — including its `GitTools`. An agent that caches a
  workspace or tool in a field will silently operate on the wrong run.
- **The orchestrator cleans up the workspace on every exit path** — approved,
  rejected, timed out, or thrown. If you add an early return to
  `Orchestrator.run`, it must stay inside the try/finally.
- **A run with an empty changeset is never approved.** `approved = true` is a
  claim shown to an operator; it must mean real, reviewed changes exist.
```

- [ ] **Step 3: Correct spec §5.3's Gate 1 caption**

In `docs/superpowers/specs/2026-08-27-devflowai-design.md`, replace the mockup's gate block:

```
│  ⏸  ABOUT TO WRITE 3 FILES                         │
│       UserRequest.java        (new)                │
│       UserController.java     (modified)           │
│       UserControllerTest.java (new)                │
```

with:

```
│  ⏸  ABOUT TO RUN THE BUILD — 3 files changed       │
│       UserRequest.java        (new)                │
│       UserController.java     (modified)           │
│       UserControllerTest.java (new)                │
```

and add this note directly beneath the mockup:

```markdown
**Gate 1 is a pre-flight confirmation, not a file-write guard.** Before the
coder's first call no file list exists — the model decides what to write during
its turn. Gate 1 shows the repo, branch and task, and confirms "spend Opus 5
tokens on this"; the file list appears at Gate 2, where it is real. Filesystem
safety is `PathGuard` (§9), not a gate.
```

- [ ] **Step 4: Commit**

```bash
git add CLAUDE.md docs/superpowers/specs/2026-08-27-devflowai-design.md
git commit -m "docs: record how to run the app and correct Gate 1's meaning in the spec"
```

---

## Self-Review

**Spec coverage:**

| Spec § | Covered by |
|---|---|
| §5 lifecycle steps 1–14 | Task 5 (`Orchestrator.execute`) |
| §5.1 gate mechanism (parked future, timeout, cleanup) | Task 4 (`ApprovalGate`), Task 5 (`cleanUp`) |
| §5.2 `ApprovalDecision`, reject-with-reason → `Finding(HUMAN)` | Task 4 (record), Task 5 (gate handling), Task 7 (`/approve`) |
| §5.2 separate human/review counters | Task 5 (`rollBackReviewIteration` + `humanIterations`) |
| §5.2 per-gate semantics table | Task 5 — pre-flight and build/commit gates; **Gate 3's Scribe column is Phase 5** (no Scribe exists yet), noted below |
| §5.3 four interaction points, three states | Task 8 (`index.html`) |
| §5.3 server-rendered HTML + vanilla `EventSource`, no framework | Task 8 |
| §7 per-run token logging | Task 2 (`UsageMapper`), Task 5 (`done` event carries totals) |
| §11 in-memory, non-durable run state | Task 6 (`RunRegistry`, documented as such) |
| Deferred I2 (error → FAILED, cleanup) | Task 5 (`anExceptionFromAnAgentBecomesAFailedOutcome...`) |
| Deferred I3 (agent workspace binding) | Task 1 (`RunState.gitTools()`, stateless `CoderAgent`) |
| Deferred C1-b (empty changeset approval) | Task 5 (`anEmptyChangesetIsNeverApproved`) |

**Known scope boundary, stated deliberately:** spec §5.2's Gate 3 row says a
rejection with a reason should "re-run Scribe with the reason as guidance" and a
bare rejection should "commit code, discard the skill draft." No Scribe and no
skill drafts exist until Phase 5. This plan implements Gate 3's *code* half —
approve commits, reject-with-reason steers back to the coder, bare reject
aborts — and Phase 5 adds the skill-draft half when there is a draft to accept
or discard. Flagged so it reads as a decision, not an omission.

**Placeholder scan:** no TBD/TODO/"add error handling"/"similar to Task N". Every
code step carries complete code.

**Type consistency:** `RunState.gitTools()`, `RunState.setPhase/phase`,
`RunState.rollBackReviewIteration()`, `CoderAgent(ChatClient)`,
`UsageMapper.from(ChatResponse)`, `RunEvent.of(...)`,
`RunEventPublisher.publish/subscribe/complete/register/isSubscribed`,
`ApprovalGate.await(Gate)/decide(ApprovalDecision)/pending()`,
`ApprovalDecision.approve()/reject()/rejectWith(String)/hasReason()`,
`Orchestrator(Agent, Agent, RunEventPublisher, int, int, Duration)`,
`Orchestrator.run(RunState, ApprovalGate)`, `RunHandle(runId, state, gate, task)`,
`RunRegistry.start(String, String)/find(String)` are each defined once and used
with identical signatures everywhere they appear.

**One deliberate carry-over:** `BuildTools` is constructed inside
`Orchestrator.execute` rather than injected. It needs the per-run workspace, so
it cannot be a singleton bean; constructing it at the point of use keeps it
per-run without adding a factory. Revisit if a second call site appears.
