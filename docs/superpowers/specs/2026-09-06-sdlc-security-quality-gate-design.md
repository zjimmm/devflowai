# Security/Quality Gate + Policy Engine — Design

Sub-project 2 of the devflowai → full-SDLC-app pivot (`DevFlowAI_PRD.md`).
The PRD's Phase 2 bundles nine things (requirement analysis, acceptance
criteria extraction, architecture review, risk classification, a
security/quality stage, documentation, PR creation, a configurable policy
engine, CI integration). PR creation and CI integration already moved to
Sub-project 5 (§30). What remains is still multiple independent pieces the
PRD's own text treats as inseparable from policy: architecture review
"should be configurable," documentation "should be conditional," and the
security/quality gate *is* essentially a policy table. This sub-project
takes the smallest slice of that: one real gate, evaluated by one real,
extensible policy mechanism — not four inert stages plus a policy engine
with nothing meaningful to check yet.

## 1. What it is

An automated check that runs once per run, after the reviewer approves and
the build finishes, before Gate 3. It replaces today's purely-informational
"Build passed/failed" step with a real, structural fact: a failed build now
blocks progression and sends the run back to the coder, the same way a
human's reject-with-reason already does. The check is implemented as a
small, deliberately extensible `PolicyEngine` — infrastructure for the
policy-driven stages later sub-projects will add, not a one-off `if`
statement, but with exactly one real rule today.

## 2. Why not more than this

Two things narrowed this scope during design, both worth recording since
they're not obvious from the PRD text alone:

- **The PRD's "Critical/High security findings MUST = 0" line has no
  meaningful data to evaluate against in devflowai's current model.** By the
  time a policy check could run (after the reviewer approves), the
  reviewer's own result structurally carries zero findings —
  `AgentResult.ok(...)` always constructs `findings = List.of()`; only
  `NEEDS_WORK` ever carries them. Any earlier-iteration HIGH-severity
  finding was already fixed by the time the reviewer said OK — checking it
  at gate time would penalize a run for a problem it no longer has. The
  PRD's line almost certainly assumes a dedicated security-scanner's output
  against the final diff, which is real new tool integration (parsing a
  scanner's output against an arbitrary cloned repo, or degrading
  gracefully when none is configured) — explicitly out of scope here, see
  §6.
- **Today, a failed build already doesn't block anything.**
  `Orchestrator.execute()` runs `BuildTools.build("test")`, emits
  "Build passed"/"Build failed", and proceeds to Gate 3 regardless — the
  human sees the result and decides. `Build MUST PASS` is therefore the one
  rule from the PRD's example table that's both meaningful *and* real to
  add without new tooling: build success is already computed, just never
  enforced.

## 3. Architecture — a new `ai.devflow.policy` package

```java
public record PolicyContext(boolean buildPassed) {}

public record PolicyResult(boolean passed, String reason) {
    public static PolicyResult ok() { return new PolicyResult(true, null); }
}

public interface PolicyEngine {
    PolicyResult evaluate(PolicyContext context);
}

public class ConfigurablePolicyEngine implements PolicyEngine {
    // one rule today: requireBuildPass
}
```

`PolicyContext` is a record specifically so later sub-projects can add
fields (a coverage percentage, a scanner's finding counts) without another
method-signature change across every caller — the same reasoning that
shaped `WorkerRequest` in Sub-project 1. `PolicyEngine` is an interface (not
just the concrete class) following this codebase's existing pattern for
non-`Agent` collaborators (`SkillPicker`, `Scribe`): it lets `OrchestratorTest`
supply a trivial lambda double instead of constructing a real
`ConfigurablePolicyEngine` in most tests.

**`Finding.Origin` gains a `POLICY` value.** A new factory,
`Finding.fromPolicy(String message)`, mirrors `Finding.fromHuman(...)`
exactly (severity `HIGH`, `file`/`line` null) — an automated policy failure
is exactly as blocking as a human's reject-with-reason, so it gets the same
severity. `CoderAgent.buildPrompt`'s findings-formatting loop
(`"- [" + f.origin() + "/" + f.severity() + "] " + ...`) needs no change —
it already formats any `Origin` value generically.

## 4. Orchestrator integration

Read `src/main/java/ai/devflow/orchestrator/Orchestrator.java`'s current
`execute(...)` before implementing this — the exact insertion point matters.

Today, right after the build runs:

```java
var build = new BuildTools(state.workspace(), buildTimeout).build("test");
emit(state, "step", build.success() ? "Build passed" : "Build failed",
        Map.of("success", build.success()));

// ---- Gate 3: before the commit --------------------------------
if (changed.isEmpty()) {
    return failed(state, "Refusing to approve: the coder made no changes");
}
```

This sub-project inserts the policy check between those two blocks:

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
if (changed.isEmpty()) {
    return failed(state, "Refusing to approve: the coder made no changes");
}
```

The bare `continue;` re-enters the `reviewLoop:` `while` loop, whose first
statement is `state.incrementReviewIterations()` — a policy-triggered
bounce costs a review-iteration slot automatically, identically to how a
Gate-2 human rejection-with-reason already works a few lines earlier in the
same method (`if (beforeBuild.hasReason() ...) { ...; continue; }`). No new
counter, no special-casing: this is structurally the same shape, just
automated instead of waiting on a human decision.

`Orchestrator`'s current constructor is `(Agent coder, Agent reviewer, Agent
planner, SkillPicker skillPicker, Scribe scribe, SkillStore skillStore,
MemoryStore memoryStore, RunEventPublisher events, int maxReviewIterations,
int maxHumanIterations, Duration buildTimeout)` — 11 parameters. It gains a
12th, `PolicyEngine policyEngine`, appended after `buildTimeout` (simplest:
extends the parameter list rather than renumbering any existing position).
`OrchestrationConfig` gains:

```java
@Bean
PolicyEngine policyEngine(@Value("${devflowai.policy.require-build-pass:true}") boolean requireBuildPass) {
    return new ConfigurablePolicyEngine(requireBuildPass);
}
```

and the `orchestrator` bean method threads it through. `application.yml`
gains `devflowai.policy.require-build-pass: true`.

## 5. The fixture fix

`src/test/resources/fixture/` has no `gradlew`/`mvnw` — confirmed via
`BuildTools.resolveWrapper` returning `null` and `build(...)` returning
`BuildResult(false, "No build wrapper found...")`. With
`require-build-pass` defaulting to `true`, every run against the bundled
fixture would bounce on policy forever until the iteration cap — breaking
the default demo experience and the existing happy-path integration tests.

**Fix:** vendor a real, minimal Gradle wrapper into the fixture —
`gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`,
`gradle/wrapper/gradle-wrapper.properties` — pinned to Gradle 9.5.1, the
same version devflowai's own project already uses and has cached locally
(`~/.gradle/wrapper/dists`). Confirmed empirically before choosing this:
copying that wrapper into a project matching the fixture's shape and
running `./gradlew test` from a **fresh** temp directory each time
(mirroring `FixtureWorkspace.prepare()`'s `Files.createTempDirectory`
per run) took ~0.85 seconds consistently across three separate fresh
directories — no network call, the shared Gradle distribution and a warm
daemon are reused across different project paths.

**Known, accepted cost:** `Orchestrator.execute()` constructs `BuildTools`
directly and always calls the real `build("test")` — it is not mocked or
injected anywhere, so every existing `OrchestratorTest` method that already
drives a run through Gate 2 (roughly 15 of them) already invokes a real
build against the fixture today; the result is just currently discarded.
Giving the fixture a real wrapper means each of those ~15 tests will now
spawn one real ~0.85s Gradle process. That's roughly 13 seconds added to a
suite that currently runs in ~11 seconds for 182 tests — call it doubling
to ~24 seconds. Measured, not estimated; accepted as the cost of a fixture
whose build genuinely passes, which the policy engine needs to be
meaningful by default.

**Test fix required alongside this:** `BuildToolsTest.reportsMissingWrapperClearly`
currently relies on the fixture genuinely lacking a wrapper
(`workspace = new FixtureWorkspace(Path.of("src/test/resources/fixture"), "build-test")`,
no wrapper written before calling `build.build("test")`). Once the fixture
has a real one, this test needs to delete the copied `gradlew` from its
workspace before asserting the no-wrapper path, or it silently stops
testing what its own name and comment claim. No other `BuildToolsTest` case
is affected — every other one already overwrites `gradlew` with its own
fake script via `writeFakeGradlew(...)` inside the copied workspace,
regardless of what the source fixture contains.

## 6. Non-goals (explicit)

- **Real security-scanner integration** (SAST, secret detection, dependency
  vulnerabilities) — the PRD's "Critical/High security findings" line stays
  unimplemented until there's a real scanner producing that data; see §2.
- **Coverage-threshold enforcement** — same reasoning; no coverage tool is
  integrated in this sub-project.
- **Requirement analysis, architecture review, documentation stages** — the
  other three PRD Phase 2 stages. Each is its own sub-project once the
  policy mechanism this one establishes has a second real consumer to
  generalize against, rather than being designed speculatively now.
- **A configuration DSL or rules language.** "Configurable" here means one
  `@Value`-backed boolean, matching how `maxReviewIterations` and
  `maxHumanIterations` already work — not a new configuration format.
- **Replacing the reviewer's own bounce logic.** `ReviewerAgent`'s
  `needsWork` decision (`status.equals("NEEDS_WORK") || !findings.isEmpty()`)
  is untouched. The policy check is fully additive, sitting after the
  reviewer has already said OK.

## 7. Testing

- **`ConfigurablePolicyEngineTest`** — pure unit test, no Spring context:
  `evaluate(new PolicyContext(true))` → `PolicyResult.ok()`;
  `evaluate(new PolicyContext(false))` with `requireBuildPass=true` → fails
  with a reason mentioning the build; `evaluate(new PolicyContext(false))`
  with `requireBuildPass=false` → passes.
- **`OrchestratorTest`** — threads a `PolicyEngine policyEngine =
  defaultPolicyEngine();` instance field (always `PolicyResult.ok()`)
  through the existing `orchestrator(...)` helper, exactly the pattern
  Sub-project 1 used for `planner`. New test: a policy engine stubbed to
  fail once, then pass, proves the run bounces back to the coder and
  recovers — mirroring the existing reviewer-bounce tests' structure.
- **`RunFlowIntegrationTest`** needs no new `@TestBean` override —
  `ConfigurablePolicyEngine` is plain Java with no LLM call, so the real
  bean runs as-is. With the fixture's build now genuinely passing, this
  test's existing happy-path assertions (reaching `RunPhase.DONE`) continue
  to hold — the fixed cost is the extra real build time noted in §5, not a
  behavior change to this test's expectations.
- **`BuildToolsTest`** — one test rewritten (§5); five others unaffected.

## 8. Ship order

1. `ai.devflow.policy` package (`PolicyContext`, `PolicyResult`,
   `PolicyEngine`, `ConfigurablePolicyEngine`) + `ConfigurablePolicyEngineTest`.
2. `Finding.Origin.POLICY` + `Finding.fromPolicy(...)`.
3. Vendor the fixture's Gradle wrapper; fix `BuildToolsTest.reportsMissingWrapperClearly`.
4. `Orchestrator` integration (constructor param, the policy check between
   build and Gate 3) + `OrchestratorTest` additions.
5. `OrchestrationConfig`/`application.yml` wiring.
