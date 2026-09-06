# Security/Quality Gate + Policy Engine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a real, automated build-must-pass gate to the review loop, evaluated by a small, deliberately extensible `PolicyEngine` — the first real consumer of the "configurable policy" concept the PRD's Phase 2 describes.

**Architecture:** A new `ai.devflow.policy` package (`PolicyContext`/`PolicyResult`/`PolicyEngine`/`ConfigurablePolicyEngine`) is evaluated once per run, after the build completes and before Gate 3. A failure behaves exactly like today's Gate-2 human-rejection-with-reason path: a new `Finding.Origin.POLICY` finding re-enters the existing review loop via a bare `continue;`, costing a `reviewIterations` slot automatically. The bundled fixture gets a real Gradle wrapper so the default `require-build-pass: true` policy has a fixture whose build can actually pass.

**Tech Stack:** Spring Boot 4.1.1, Java 21, JUnit 5, AssertJ, Gradle 9.5.1 (fixture wrapper).

**Spec:** `docs/superpowers/specs/2026-09-06-sdlc-security-quality-gate-design.md`

## Global Constraints

- JDK 21 at `/opt/homebrew/opt/openjdk@21`, not on PATH — `export JAVA_HOME=/opt/homebrew/opt/openjdk@21` before every `./gradlew` invocation.
- The policy check is purely additive — `ReviewerAgent`'s own `needsWork` decision (`status.equals("NEEDS_WORK") || !findings.isEmpty()`) is untouched.
- "Configurable" means exactly one `@Value`-backed boolean (`devflowai.policy.require-build-pass`, default `true`), matching how `maxReviewIterations`/`maxHumanIterations` already work — no new configuration format.
- No real security-scanner or coverage-tool integration in this sub-project (spec §6) — the only rule is build-pass.
- This repo is public — no API key or secret in any tracked file.
- Agents/beans stay stateless; `ConfigurablePolicyEngine` holds only its immutable `requireBuildPass` field, set once at construction.

## File Structure

**New — policy (`ai.devflow.policy`):**
- `PolicyContext.java` — `record PolicyContext(boolean buildPassed)`
- `PolicyResult.java` — `record PolicyResult(boolean passed, String reason)` + `PolicyResult.ok()`
- `PolicyEngine.java` — interface, one method
- `ConfigurablePolicyEngine.java` — the one implementation this sub-project ships

**New — fixture:**
- `src/test/resources/fixture/gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`

**Modified:**
- `Finding.java` — new `Origin.POLICY` value, new `fromPolicy(String)` factory
- `Orchestrator.java` — constructor gains a `PolicyEngine policyEngine` parameter (appended after `buildTimeout`); the policy check is inserted between the build step and Gate 3
- `OrchestrationConfig.java` — new `policyEngine` bean; `orchestrator` bean method threads it through
- `application.yml` — new `devflowai.policy.require-build-pass: true`
- `BuildToolsTest.java` — `reportsMissingWrapperClearly` rewritten to delete the fixture's now-real `gradlew` from its own copied workspace before asserting the no-wrapper path
- `OrchestratorTest.java` — threads a `policyEngine` field (always-pass default) through the existing helper; new test proves a failing policy check bounces to the coder and recovers

---

# Task 1: Policy engine infrastructure

**Files:**
- Create: `src/main/java/ai/devflow/policy/PolicyContext.java`
- Create: `src/main/java/ai/devflow/policy/PolicyResult.java`
- Create: `src/main/java/ai/devflow/policy/PolicyEngine.java`
- Create: `src/main/java/ai/devflow/policy/ConfigurablePolicyEngine.java`
- Modify: `src/main/java/ai/devflow/agent/Finding.java`
- Test: `src/test/java/ai/devflow/policy/ConfigurablePolicyEngineTest.java`

**Interfaces:**
- Produces: `PolicyContext(boolean buildPassed)`; `PolicyResult(boolean passed, String reason)` + static `PolicyResult.ok()`; `PolicyEngine { PolicyResult evaluate(PolicyContext context); }`; `ConfigurablePolicyEngine(boolean requireBuildPass)`; `Finding.Origin.POLICY`; `Finding.fromPolicy(String message)` → `Finding` with `Origin.POLICY`, `Severity.HIGH`, `file`/`line` null.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/ai/devflow/policy/ConfigurablePolicyEngineTest.java`:

```java
package ai.devflow.policy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigurablePolicyEngineTest {

    @Test
    void passesWhenTheBuildPassed() {
        var engine = new ConfigurablePolicyEngine(true);

        var result = engine.evaluate(new PolicyContext(true));

        assertThat(result.passed()).isTrue();
        assertThat(result.reason()).isNull();
    }

    @Test
    void failsWhenTheBuildDidNotPassAndBuildPassIsRequired() {
        var engine = new ConfigurablePolicyEngine(true);

        var result = engine.evaluate(new PolicyContext(false));

        assertThat(result.passed()).isFalse();
        assertThat(result.reason()).contains("build");
    }

    @Test
    void passesWhenTheBuildDidNotPassButBuildPassIsNotRequired() {
        var engine = new ConfigurablePolicyEngine(false);

        var result = engine.evaluate(new PolicyContext(false));

        assertThat(result.passed()).isTrue();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests '*ConfigurablePolicyEngineTest*'`
Expected: compilation failure — none of `PolicyContext`/`PolicyResult`/`PolicyEngine`/`ConfigurablePolicyEngine` exist yet.

- [ ] **Step 3: Create the policy types**

`src/main/java/ai/devflow/policy/PolicyContext.java`:

```java
package ai.devflow.policy;

/**
 * What a {@link PolicyEngine} evaluates. A record specifically so later
 * sub-projects can add fields (a coverage percentage, a scanner's finding
 * counts) without another signature change across every caller — the same
 * reasoning that shaped {@code WorkerRequest} in Sub-project 1.
 */
public record PolicyContext(boolean buildPassed) {}
```

`src/main/java/ai/devflow/policy/PolicyResult.java`:

```java
package ai.devflow.policy;

public record PolicyResult(boolean passed, String reason) {
    public static PolicyResult ok() {
        return new PolicyResult(true, null);
    }
}
```

`src/main/java/ai/devflow/policy/PolicyEngine.java`:

```java
package ai.devflow.policy;

/**
 * Decides whether a run may proceed past its automated checks. An interface
 * (not just the concrete {@code ConfigurablePolicyEngine}) so tests can
 * supply a trivial lambda double, matching this codebase's existing
 * {@code SkillPicker}/{@code Scribe} pattern.
 */
public interface PolicyEngine {
    PolicyResult evaluate(PolicyContext context);
}
```

`src/main/java/ai/devflow/policy/ConfigurablePolicyEngine.java`:

```java
package ai.devflow.policy;

/** The one policy rule this sub-project ships: the build must pass. */
public class ConfigurablePolicyEngine implements PolicyEngine {

    private final boolean requireBuildPass;

    public ConfigurablePolicyEngine(boolean requireBuildPass) {
        this.requireBuildPass = requireBuildPass;
    }

    @Override
    public PolicyResult evaluate(PolicyContext context) {
        if (requireBuildPass && !context.buildPassed()) {
            return new PolicyResult(false, "the build did not pass");
        }
        return PolicyResult.ok();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests '*ConfigurablePolicyEngineTest*'`
Expected: PASS (3 tests).

- [ ] **Step 5: Add `Finding.Origin.POLICY` and `Finding.fromPolicy`**

Read `src/main/java/ai/devflow/agent/Finding.java` first (it is small — shown here in full for reference):

```java
package ai.devflow.agent;

public record Finding(
        Origin origin,
        Severity severity,
        String file,      // null for HUMAN findings
        Integer line,     // null for HUMAN findings
        String message) {

    public enum Origin { REVIEWER, HUMAN }
    public enum Severity { LOW, MEDIUM, HIGH }

    public static Finding fromHuman(String message) {
        return new Finding(Origin.HUMAN, Severity.HIGH, null, null, message);
    }
}
```

Change it to:

```java
package ai.devflow.agent;

public record Finding(
        Origin origin,
        Severity severity,
        String file,      // null for HUMAN and POLICY findings
        Integer line,     // null for HUMAN and POLICY findings
        String message) {

    public enum Origin { REVIEWER, HUMAN, POLICY }
    public enum Severity { LOW, MEDIUM, HIGH }

    public static Finding fromHuman(String message) {
        return new Finding(Origin.HUMAN, Severity.HIGH, null, null, message);
    }

    public static Finding fromPolicy(String message) {
        return new Finding(Origin.POLICY, Severity.HIGH, null, null, message);
    }
}
```

- [ ] **Step 6: Write a small test for the new factory**

`Finding` has no dedicated test file yet. Create `src/test/java/ai/devflow/agent/FindingTest.java`:

```java
package ai.devflow.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FindingTest {

    @Test
    void fromPolicyProducesAPolicyOriginHighSeverityFinding() {
        Finding finding = Finding.fromPolicy("the build did not pass");

        assertThat(finding.origin()).isEqualTo(Finding.Origin.POLICY);
        assertThat(finding.severity()).isEqualTo(Finding.Severity.HIGH);
        assertThat(finding.file()).isNull();
        assertThat(finding.line()).isNull();
        assertThat(finding.message()).isEqualTo("the build did not pass");
    }
}
```

- [ ] **Step 7: Run to verify it passes**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests '*FindingTest*'`
Expected: PASS (1 test).

- [ ] **Step 8: Run the full suite**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test`
Expected: PASS. Nothing else references `Finding.Origin`'s enum values by exhaustive `switch` (Java would otherwise force every `switch` over the enum to handle the new case) — confirm this by searching for `switch` statements over `Finding.Origin`; there are none in the current codebase, only string formatting (`"[" + f.origin() + "/" + f.severity() + "]"` in `CoderAgent.buildPrompt`), which handles any enum value generically.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/ai/devflow/policy src/main/java/ai/devflow/agent/Finding.java \
        src/test/java/ai/devflow/policy/ConfigurablePolicyEngineTest.java \
        src/test/java/ai/devflow/agent/FindingTest.java
git commit -m "feat: add PolicyEngine infrastructure and a POLICY finding origin"
```

---

# Task 2: Vendor a real Gradle wrapper for the bundled fixture

**Files:**
- Create: `src/test/resources/fixture/gradlew`
- Create: `src/test/resources/fixture/gradlew.bat`
- Create: `src/test/resources/fixture/gradle/wrapper/gradle-wrapper.jar`
- Create: `src/test/resources/fixture/gradle/wrapper/gradle-wrapper.properties`
- Modify: `src/test/java/ai/devflow/tools/BuildToolsTest.java`

**Interfaces:**
- No new production types. This task changes the *content* of the bundled fixture and the one test that specifically depended on it lacking a build wrapper.

This task is independent of Task 1 — it can be done in either order — but Task 3 depends on both.

- [ ] **Step 1: Vendor the wrapper files**

devflowai's own project root already has a working Gradle 9.5.1 wrapper. Copy it into the fixture directly — this is a plain file copy, not a build step:

```bash
cp /Users/jim/Projects/devflowai/gradlew /Users/jim/Projects/devflowai/src/test/resources/fixture/gradlew
cp /Users/jim/Projects/devflowai/gradlew.bat /Users/jim/Projects/devflowai/src/test/resources/fixture/gradlew.bat
mkdir -p /Users/jim/Projects/devflowai/src/test/resources/fixture/gradle/wrapper
cp /Users/jim/Projects/devflowai/gradle/wrapper/gradle-wrapper.jar /Users/jim/Projects/devflowai/src/test/resources/fixture/gradle/wrapper/gradle-wrapper.jar
cp /Users/jim/Projects/devflowai/gradle/wrapper/gradle-wrapper.properties /Users/jim/Projects/devflowai/src/test/resources/fixture/gradle/wrapper/gradle-wrapper.properties
chmod +x /Users/jim/Projects/devflowai/src/test/resources/fixture/gradlew
```

Using the exact same wrapper devflowai's own project already uses (Gradle 9.5.1) means the fixture's build shares the same cached Gradle distribution (`~/.gradle/wrapper/dists`) — no network download the first time it runs.

- [ ] **Step 2: Verify the fixture now builds standalone**

Run:
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
cd /Users/jim/Projects/devflowai/src/test/resources/fixture
./gradlew test --console=plain
cd /Users/jim/Projects/devflowai
```
Expected: `BUILD SUCCESSFUL`. If it fails, do not proceed — the fixture project itself (`build.gradle.kts`, `settings.gradle.kts`, `src/`) is unchanged by this task, so a failure here means the wrapper vendoring itself went wrong (wrong Gradle version, missing jar, etc.), not a fixture code problem.

- [ ] **Step 3: Fix `BuildToolsTest.reportsMissingWrapperClearly`**

Read `src/test/java/ai/devflow/tools/BuildToolsTest.java` first (shown in full below for reference — this is the whole file as it exists before this task):

```java
package ai.devflow.tools;

import ai.devflow.workspace.*;
import org.junit.jupiter.api.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;

class BuildToolsTest {

    Workspace workspace;
    BuildTools build;

    @BeforeEach
    void setUp() throws Exception {
        workspace = new FixtureWorkspace(Path.of("src/test/resources/fixture"), "build-test");
        workspace.prepare();
        build = new BuildTools(workspace, Duration.ofMinutes(5));
    }

    @AfterEach
    void tearDown() throws Exception { workspace.cleanup(); }

    @Test
    void reportsMissingWrapperClearly() {
        // The fixture has no gradlew wrapper committed; BuildTools must say so
        // rather than hanging or throwing.
        var result = build.build("test");
        assertThat(result.output()).isNotBlank();
    }

    @Test
    void truncatesVeryLongOutput() {
        // ... unchanged, and every test below this point is also unchanged ...
```

Change only the `reportsMissingWrapperClearly` test — every other test in this file (`truncatesVeryLongOutput`, `capturesOutputAndSucceedsWhenTheWrapperExitsZero`, `reportsFailureAndCapturesOutputWhenTheWrapperExitsNonZero`, `runTestsPrefixesResultWithPassOrFail`, `boundsOutputRegardlessOfHowMuchTheProcessActuallyProduces`, `timeoutActuallyBoundsWallClockTimeAndKillsTheHungProcess`) already overwrites `gradlew` with its own fake script via the existing `writeFakeGradlew(...)` helper and is unaffected by the fixture now having a real one:

```java
    @Test
    void reportsMissingWrapperClearly() throws Exception {
        // The fixture now ships a real gradlew (Sub-project 2's build-pass
        // policy needs a fixture whose build can genuinely pass), so this
        // test deletes it from its own copied workspace to still exercise
        // the no-wrapper path rather than relying on the fixture itself
        // lacking one.
        Files.delete(workspace.root().resolve("gradlew"));

        var result = build.build("test");

        assertThat(result.success()).isFalse();
        assertThat(result.output()).contains("No build wrapper found");
    }
```

(`Files` is already imported at the top of this file.)

- [ ] **Step 4: Run the test to verify it passes**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests '*BuildToolsTest*'`
Expected: PASS (all tests in the file, old and new).

- [ ] **Step 5: Run the full suite**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test`
Expected: PASS. This does not yet change any *behavior* outside `BuildToolsTest` — nothing reads `build.success()` to gate anything until Task 3 — but confirm no other test asserts on the exact file count or listing of the fixture directory (none do: `FixtureWorkspaceTest.doesNotMutateTheSourceFixture` compares the source directory's file count before and after a copy, a self-relative comparison unaffected by the fixture having more files than before; `excludesGitGradleAndBuildDirectoriesFromTheCopy` uses its own synthetic `@TempDir`, not this fixture).

- [ ] **Step 6: Commit**

```bash
git add src/test/resources/fixture/gradlew src/test/resources/fixture/gradlew.bat \
        src/test/resources/fixture/gradle src/test/java/ai/devflow/tools/BuildToolsTest.java
git commit -m "feat: give the bundled fixture a real, working Gradle wrapper"
```

---

# Task 3: Orchestrator integration + config wiring

**Files:**
- Modify: `src/main/java/ai/devflow/orchestrator/Orchestrator.java`
- Modify: `src/main/java/ai/devflow/config/OrchestrationConfig.java`
- Modify: `src/main/resources/application.yml`
- Modify: `src/test/java/ai/devflow/orchestrator/OrchestratorTest.java`

**Interfaces:**
- Consumes: `PolicyEngine`, `PolicyContext`, `PolicyResult` (Task 1); `Finding.fromPolicy` (Task 1); the fixture's real build (Task 2).
- Produces: `Orchestrator`'s 12th constructor parameter, `PolicyEngine policyEngine` (appended after `Duration buildTimeout`).

- [ ] **Step 1: Write the failing `OrchestratorTest` case**

Read `src/test/java/ai/devflow/orchestrator/OrchestratorTest.java` first (shown in full in the plan's exploration — it currently has a `planner` field and `defaultPlanner()` helper following exactly the pattern this step mirrors). Add the import:

```java
import ai.devflow.policy.*;
```

Add a new instance field next to the existing `Agent planner = defaultPlanner();`:

```java
    PolicyEngine policyEngine = defaultPolicyEngine();
```

Add a static helper next to `defaultPlanner()`:

```java
    static PolicyEngine defaultPolicyEngine() {
        return context -> PolicyResult.ok();
    }
```

Change the 6-arg `orchestrator(...)` helper to thread it through (this is the ONLY place either overload needs to change — the 2-arg overload already delegates to this one):

```java
    private Orchestrator orchestrator(Agent coder, Agent reviewer, SkillPicker picker, Scribe scribe,
                                      SkillStore skillStore, MemoryStore memoryStore) {
        return new Orchestrator(coder, reviewer, planner, picker, scribe, skillStore, memoryStore,
                events, 3, 5, Duration.ofMinutes(1), policyEngine);
    }
```

Add a new test at the end of the class, before the closing brace:

```java
    @Test
    void aFailingPolicyCheckSendsItBackToTheCoderThenRecovers() throws Exception {
        var coder = writingCoder("attempt");
        var reviewer = new ScriptedAgent("reviewer",
                List.of(AgentResult.ok("reviewer", "looks good", List.of(), TokenUsage.NONE)));
        var policyCalls = new AtomicInteger(0);
        policyEngine = context -> {
            int n = policyCalls.incrementAndGet();
            return n == 1 ? new PolicyResult(false, "the build did not pass") : PolicyResult.ok();
        };
        var state = new RunState("g24", "t", workspace);
        var gate = new ApprovalGate(Duration.ofSeconds(10));

        var outcome = runApprovingAll(orchestrator(coder, reviewer), state, gate);

        assertThat(outcome.approved()).isTrue();
        assertThat(coder.calls)
                .as("a failed policy check must send the run back to the coder")
                .isEqualTo(2);
        assertThat(state.reviewIterations()).isEqualTo(2);
        assertThat(policyCalls.get()).isEqualTo(2);
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests '*OrchestratorTest*'`
Expected: compilation failure — `Orchestrator`'s constructor does not yet take a `PolicyEngine` parameter.

- [ ] **Step 3: Add the `PolicyEngine` field and constructor parameter**

Read `src/main/java/ai/devflow/orchestrator/Orchestrator.java` first. Add the import:

```java
import ai.devflow.policy.PolicyContext;
import ai.devflow.policy.PolicyEngine;
import ai.devflow.policy.PolicyResult;
```

Add a field next to `buildTimeout`:

```java
    private final Duration buildTimeout;
    private final PolicyEngine policyEngine;
```

Change the constructor:

```java
    public Orchestrator(Agent coder, Agent reviewer, Agent planner, SkillPicker skillPicker, Scribe scribe,
                        SkillStore skillStore, MemoryStore memoryStore,
                        RunEventPublisher events, int maxReviewIterations, int maxHumanIterations,
                        Duration buildTimeout, PolicyEngine policyEngine) {
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
        this.policyEngine = policyEngine;
    }
```

- [ ] **Step 4: Insert the policy check between the build and Gate 3**

In the same file, find this exact block inside `execute(...)`:

```java
            var build = new BuildTools(state.workspace(), buildTimeout).build("test");
            emit(state, "step", build.success() ? "Build passed" : "Build failed",
                    Map.of("success", build.success()));

            // ---- Gate 3: before the commit --------------------------------
            // C1-b: a run that changed nothing must never be reported approved.
            if (changed.isEmpty()) {
                return failed(state, "Refusing to approve: the coder made no changes");
            }
```

Change it to:

```java
            var build = new BuildTools(state.workspace(), buildTimeout).build("test");
            emit(state, "step", build.success() ? "Build passed" : "Build failed",
                    Map.of("success", build.success()));

            PolicyResult policyResult = policyEngine.evaluate(new PolicyContext(build.success()));
            if (!policyResult.passed()) {
                state.addFindings(List.of(Finding.fromPolicy(policyResult.reason())));
                emit(state, "step", "Policy check failed: " + policyResult.reason(), Map.of());
                continue;
            }

            // ---- Gate 3: before the commit --------------------------------
            // C1-b: a run that changed nothing must never be reported approved.
            if (changed.isEmpty()) {
                return failed(state, "Refusing to approve: the coder made no changes");
            }
```

The bare `continue;` re-enters the `reviewLoop:` `while` loop — its first statement is `state.incrementReviewIterations()`, so this costs a review-iteration slot automatically, identically to how the existing Gate-2 human-rejection-with-reason path a few lines earlier in the same method already works (`if (beforeBuild.hasReason() ...) { ...; continue; }`). No new counter is needed.

- [ ] **Step 5: Run the `OrchestratorTest` case to verify it passes**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests '*OrchestratorTest*'`
Expected: PASS (all existing tests plus the new one). This is also the first time this test class's ~15 build-reaching tests exercise a fixture whose build genuinely passes (Task 2) — expect the suite to take noticeably longer than before (measured during design: roughly 0.85s added per test that reaches the build step, since `BuildTools` is constructed directly inside `Orchestrator.execute()` and is not mocked).

- [ ] **Step 6: Wire the `policyEngine` bean and thread it through `OrchestrationConfig`**

Read `src/main/java/ai/devflow/config/OrchestrationConfig.java` first. Add the import:

```java
import ai.devflow.policy.ConfigurablePolicyEngine;
import ai.devflow.policy.PolicyEngine;
```

Add a new bean method (anywhere among the other `@Bean` methods — after `scribeAgent` is a reasonable spot):

```java
    @Bean
    PolicyEngine policyEngine(@Value("${devflowai.policy.require-build-pass:true}") boolean requireBuildPass) {
        return new ConfigurablePolicyEngine(requireBuildPass);
    }
```

Change the `orchestrator` bean method to take and pass it:

```java
    @Bean
    Orchestrator orchestrator(Agent coderAgent, Agent reviewerAgent, Agent plannerAgent, SkillPicker skillPicker, Scribe scribe,
                              SkillStore skillStore, MemoryStore memoryStore, RunEventPublisher events,
                              @Value("${devflowai.review.max-iterations:3}") int maxReviewIterations,
                              @Value("${devflowai.review.max-human-iterations:5}") int maxHumanIterations,
                              @Value("${devflowai.build.timeout-minutes:5}") long buildTimeoutMinutes,
                              PolicyEngine policyEngine) {
        return new Orchestrator(coderAgent, reviewerAgent, plannerAgent, skillPicker, scribe, skillStore, memoryStore,
                events, maxReviewIterations, maxHumanIterations, Duration.ofMinutes(buildTimeoutMinutes), policyEngine);
    }
```

- [ ] **Step 7: Add the config property**

Read `src/main/resources/application.yml` first. Add a new top-level section under the existing `devflowai:` key (as a sibling of `review:`, `gate:`, `build:`, `fixture:`, `clone:`):

```yaml
  policy:
    require-build-pass: true
```

- [ ] **Step 8: Run the full suite**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test`
Expected: PASS. `OrchestrationConfigTest` (which boots the full Spring context) proves the new `policyEngine` bean and the 12-arg `Orchestrator` wire together correctly. `RunFlowIntegrationTest` and `StaticPageTest` construct `RunController`/`Orchestrator` via Spring autowiring, so they need no changes — the new `PolicyEngine` bean resolves automatically, and `ConfigurablePolicyEngine` makes no LLM call, so no new `@TestBean` override is needed there either. With the fixture's build now genuinely passing (Task 2) and `require-build-pass` defaulting to `true`, `RunFlowIntegrationTest`'s happy-path tests continue to reach `RunPhase.DONE` as before — expect the whole suite to take noticeably longer than the pre-Task-2 baseline (every real build-reaching test now spawns one real ~0.85s Gradle process).

- [ ] **Step 9: Manually verify in a browser**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && export ANTHROPIC_API_KEY=<key> && ./gradlew bootRun`

Open `http://localhost:8080`, run a task against the bundled fixture through to Gate 2, approve it, and confirm the live log shows "Build passed" followed immediately by Gate 3 (no policy-failure step) — the fixture's build now genuinely passes, so the policy check should be invisible in the normal happy path. There is no manual way to trigger a policy failure against the bundled fixture without deliberately breaking its build; `aFailingPolicyCheckSendsItBackToTheCoderThenRecovers` is this behavior's coverage.

- [ ] **Step 10: Commit**

```bash
git add src/main/java/ai/devflow/orchestrator/Orchestrator.java src/main/java/ai/devflow/config/OrchestrationConfig.java \
        src/main/resources/application.yml src/test/java/ai/devflow/orchestrator/OrchestratorTest.java
git commit -m "feat: enforce build-must-pass via the new PolicyEngine, before Gate 3"
```

---

## Spec coverage check

- §2 (why not more than build-pass) — no task attempts a severity-threshold or coverage check; §6's non-goals are respected throughout.
- §3 (policy package architecture) — Task 1.
- §4 (Orchestrator integration, exact insertion point and `continue;` semantics) — Task 3.
- §5 (the fixture fix, the accepted timing cost, the `BuildToolsTest` fix) — Task 2, and Task 3 Step 5/8 call out the expected slowdown explicitly rather than let it surprise whoever runs the suite next.
- §6 (non-goals) — no task builds a config DSL, touches `ReviewerAgent`'s own bounce logic, or adds scanner/coverage integration.
- §7 (testing) — `ConfigurablePolicyEngineTest` (Task 1), the `BuildToolsTest` fix (Task 2), the new `OrchestratorTest` case and the note that `RunFlowIntegrationTest` needs no new `@TestBean` (Task 3) are all present.
- §8 (ship order) — followed, with items 1+2 and 4+5 each combined into one task per this plan's own task-right-sizing judgment (see the plan's introduction) rather than five near-empty tasks.
