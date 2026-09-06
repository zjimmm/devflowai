# Evaluation Mode — Strategy Comparison Design

Sub-project 3 of the devflowai → full-SDLC-app pivot (`DevFlowAI_PRD.md` §22,
"Evaluation and Benchmark Mode" — "a major differentiator"). §22 bundles two
different kinds of work: (a) running the same task through a bare,
non-orchestrated agent (Strategy A) versus the full pipeline (Strategy B) and
comparing metrics devflowai already tracks, and (b) a hidden-test benchmark
harness that would make "task success"/"defects discovered"/"regressions"
independently scoreable. This sub-project is (a) only. (b) is a materially
different, more novel undertaking — essentially a small SWE-bench-style
harness — deferred until this sub-project's comparison has a real second
consumer to design it against, rather than building it speculatively now.

## 1. What it is

A second, much simpler way to run a task: **Strategy A / Direct** —
`RunState → CoderAgent → build (metrics only) → commit`, fully autonomous,
no planner, no reviewer, no gates, no skills, no memory, no learning loop.
It runs alongside the existing **Strategy B / Orchestrated** path unchanged,
using the exact same `Agent coderAgent` bean (same Sonnet 5 + `XHIGH` tier)
so a comparison isn't confounded by a different model. An operator runs the
same task twice, once each way, and reads the difference off the existing
past-runs history — no new endpoint, no new dashboard.

## 2. Goals

- Answer 3 of the PRD's 4 example questions without any new tooling: *does
  orchestration improve reliability enough to justify its extra cost*,
  *which model performs best as coder* (already partly set up by
  Sub-project 1's tier split), and *when does direct usage outperform the
  full pipeline*. The 4th ("which task types benefit from independent
  review") needs the benchmark harness this sub-project deliberately isn't
  building.
- Every metric the PRD's §22 list asks for that's derivable from data
  devflowai *already persists* (task success, build success, iterations,
  review findings, human corrections, tokens, execution time) is captured
  for both strategies with the smallest possible schema change.
- Strategy A and Strategy B are equally observable: same SSE stream, same
  `SdlcRun`/`StageExecution` persistence, same past-runs history endpoint —
  a Direct run is a first-class run, not a special case bolted on.

## 3. Architecture — a `RunExecutor` interface, `Orchestrator` becomes one implementation

```java
public interface RunExecutor {
    Orchestrator.RunOutcome run(RunState state, ApprovalGate gate);
}
```

`Orchestrator implements RunExecutor` — a one-line class-declaration change;
its existing `run(RunState, ApprovalGate)` method already matches this
signature exactly, so nothing else about `Orchestrator` changes. `RunOutcome`
stays a nested record on `Orchestrator` rather than being extracted to a
top-level type: extracting it would require updating every existing
`OrchestratorTest` call site that references `Orchestrator.RunOutcome`
(there are many) for a purely cosmetic improvement — `DirectExecutor`
referencing `Orchestrator.RunOutcome` is an accepted, deliberate wart, not
an oversight.

**`DirectExecutor implements RunExecutor`** is the whole of Strategy A — a
new, small class (roughly a fifth of `Orchestrator`'s size), deliberately
*not* sharing `Orchestrator`'s private `emit`/`cleanUp` helpers even though
the bodies are similar: two 5-line helpers do not justify a shared base
class or extracted utility coupling two otherwise-independent, simple
executors before there's a third one to justify the abstraction (YAGNI).

`DirectExecutor`'s constructor takes only `Agent coder`, `RunEventPublisher
events`, and `Duration buildTimeout` — no `SkillStore`, `MemoryStore`,
`Scribe`, or `SkillPicker`. This is deliberate, not an oversight: Strategy A
represents what a user gets from calling a coding agent directly, with none
of devflowai's learning loop — it never calls `loadKnowledge(...)`-equivalent
logic, so `RunState.memory()`/`loadedSkills()` simply stay at their default
empty values for a Direct run. Wiring the SAME `coderAgent` bean
`Orchestrator` already uses (not a new `ChatClient`/`CodingWorker`) needs no
new Spring config beyond `OrchestrationConfig` gaining one `directExecutor`
bean and `RunRegistry` gaining one new constructor dependency.

`DirectExecutor.run(state, gate)` keeps the `ApprovalGate` parameter for
uniform dispatch but never calls `gate.await(...)`. If an operator mistakenly
`POST`s to `/approve` for a Direct run, they get the exact 409 "no gate is
currently awaiting a decision" `RunController.approve(...)` already returns
today for a stale/duplicate click — no new error handling needed.

**`DirectExecutor`'s flow**, mirroring `Orchestrator.execute()`'s shape
where it overlaps:

```java
emit(state, "step", "Workspace ready — branch " + state.workspace().branchName(),
        Map.of("branch", state.workspace().branchName(), "task", state.task(),
               "repoSlug", state.repoSlug(), "strategy", RunStrategy.DIRECT.name()));

state.setPhase(RunPhase.CODING);
AgentResult coded = coder.run(state);
state.record(coded);
if (coded.status() == AgentResult.Status.FAILED) return failed(state, "Coder failed: " + coded.summary());

List<String> changed = state.gitTools().changedFiles();
if (changed.isEmpty()) return failed(state, "Refusing to approve: the coder made no changes");

state.setPhase(RunPhase.BUILDING);
var build = new BuildTools(state.workspace(), buildTimeout).build("test"); // metrics only — nothing gates on this
emit(state, "step", build.success() ? "Build passed" : "Build failed", Map.of("success", build.success()));

state.setPhase(RunPhase.COMMITTING);
String committed = state.gitTools().commit("devflowai (direct): " + state.task());

state.setPhase(RunPhase.DONE);
emit(state, "done", "Direct run committed on " + state.workspace().branchName(),
        Map.of("branch", ..., "inputTokens", ..., "outputTokens", ...));
return new Orchestrator.RunOutcome(true, "Direct run committed, no review", state);
```

The "no changes → refuse" check reuses `Orchestrator`'s exact wording (spec
non-negotiable: a run with an empty changeset is never approved, in *either*
strategy). The build runs and its result is recorded purely as data — there
is no loop to feed a failure back into, matching the PRD's own Strategy A
diagram (`Developer → Coding Agent → Result`, no fix loop shown).

**`RunRegistry`** gains a new `RunStrategy` enum parameter on `start(...)`
(`ai.devflow.orchestrator.RunStrategy { ORCHESTRATED, DIRECT }`) and a new
constructor dependency, `RunExecutor directExecutor` — the existing
`Orchestrator orchestrator` field is untouched (it already satisfies
`RunExecutor` once Task 1 adds the `implements` clause). At the call site:
`RunExecutor selected = strategy == RunStrategy.DIRECT ? directExecutor :
orchestrator;`, then `selected.run(state, gate)` inside the same
`executor.submit(...)` block that exists today. `StartRunRequest` gains a
nullable `String strategy` field; `RunController.start(...)` parses it the
same way it already trims/defaults `repo` (blank or absent → `"orchestrated"`
→ `RunStrategy.ORCHESTRATED`), so every existing caller keeps working with
zero changes.

**A regression this design must not reintroduce:** `RunRegistry.start(...)`'s
workspace-prepare-failure catch block (added in Sub-project 1 specifically
so a run that never reaches an executor still appears in history) currently
publishes `Map.of("task", task, "repoSlug", repoSlug)` with no `strategy`
key. Once `SdlcRunRecorder.maybeCreateRun` requires a `strategy` key to
create a row (§4), this event needs `strategy` added too, using the
`RunStrategy` parameter `start(...)` now has in scope — otherwise a
workspace-prep failure silently stops appearing in history again, the exact
gap Sub-project 1 closed.

## 4. What gets persisted

Two additions to `SdlcRun`, both nullable-safe for existing rows (Hibernate
`ddl-auto: update` adds them as new columns; old rows read back as `null`):

- **`strategy`** (`RunStrategy`, `@Enumerated(STRING)`) — set from a new
  `"strategy"` key on the first `RunRecorded` event, read by
  `SdlcRunRecorder.maybeCreateRun` the same way `task`/`repoSlug` already
  are. `SdlcRun`'s constructor gains this as a 4th parameter (before
  `startedAt`) — every existing direct `new SdlcRun(...)` call site in
  `HistoryRepositoriesTest`/wherever else needs updating; find them with
  `grep -rn "new SdlcRun("`.
- **`buildSucceeded`** (`Boolean`, nullable — `null` until a build actually
  runs) — set from the existing `"success"` key already present on every
  build-step event from *both* `Orchestrator` and `DirectExecutor` (no
  per-strategy special-casing in the recorder). `SdlcRunRecorder` gains one
  new handler, `maybeRecordBuildResult`, called alongside the existing
  `maybeXxx` methods in `onRunRecorded`. Last-write-wins: for a Strategy B
  run whose loop builds more than once, this ends up reflecting the *most
  recently committed* build's outcome, matching what a human at Gate 3
  actually saw.

Everything else the PRD's metrics list asks for is already derivable with
**zero new schema**, computed at query time by the comparison view (§5), not
persisted redundantly:

| Metric | Query |
|---|---|
| Iterations | `COUNT(*) FROM stage_execution WHERE run_id = ? AND phase = 'CODING'` (always 1 for a Direct run — no loop) |
| Review findings | `COUNT(*) FROM review_finding WHERE run_id = ?` (always 0 for Direct — no reviewer) |
| Human corrections | `COUNT(*) FROM approval WHERE run_id = ? AND approved = false` (always 0 for Direct — no gates) |
| Tokens, execution time, task success | Already on `SdlcRun`: `inputTokens`/`outputTokens`, `finishedAt - startedAt`, `status` |

## 5. The comparison surface

`RunSummary` (the `GET /api/runs/history` DTO) gains `strategy` and
`buildSucceeded` fields, mapped straight from the new `SdlcRun` columns. The
existing minimal past-runs table (Sub-project 1) gains two more columns.
That is the entire UI change: an operator runs a task once each way and
reads the two rows. No new endpoint, no side-by-side comparison view, no
computed-metrics columns (iterations/findings/corrections) surfaced in the
UI in this sub-project — those stay query-only, reachable by hand against
the database, until a real dashboard (Sub-project 4) is worth building
against a second real consumer of this shape.

## 6. Non-goals (explicit)

- **Dollar-cost conversion.** Raw `inputTokens`/`outputTokens` only. A
  per-model pricing table is the PRD's own separate §21 concern, not
  something this sub-project invents a stopgap version of.
- **The benchmark/hidden-test harness** — "task success" beyond
  build-pass/no-error, "defects discovered," "regressions." All three need
  a benchmark-task format (a task + starting repo state + a hidden scoring
  suite) that doesn't exist and isn't designed here.
- **Any dashboard beyond two extra table columns.** Sub-project 4's
  territory once multi-repo/multi-worker exists to justify it.
- **Iteration/finding/correction counts as persisted `SdlcRun` columns.**
  Deliberately query-time only (§4) — no denormalization for data that's
  already one join away.
- **Letting an operator choose a different model for Strategy A.** It
  always uses the same `coderAgent` bean Strategy B uses, so a comparison
  measures orchestration's effect, not a confounded model difference.
  Comparing models is Sub-project 1's tier split's job, already shipped.

## 7. Testing

- **`DirectExecutorTest`** (new) — mirrors `OrchestratorTest`'s existing
  patterns (`ScriptedAgent`/`writingCoder` doubles, a real `FixtureWorkspace`,
  a real `RunEventPublisher`): a successful coder run reaches `DONE` and
  commits; a coder that makes no changes is refused with the same message
  `Orchestrator` uses; a `FAILED` coder result ends the run without
  crashing; the emitted "done"/first-step events carry `strategy=DIRECT`.
  No `ApprovalGate` interaction needs testing — there is none.
- **`SdlcRunRecorderTest`** additions — a `RunRecorded` event carrying
  `strategy` creates an `SdlcRun` row with that strategy set; a build-step
  event's `success` value is reflected in `buildSucceeded`, and a second
  build-step event overwrites it (last-write-wins).
- **`RunRegistryTest`** — `start(...)`'s new `RunStrategy` parameter routes
  to the correct `RunExecutor`; the workspace-prepare-failure path's event
  carries `strategy` too (the regression flagged in §3).
- **`RunControllerTest`/`RunFlowIntegrationTest`** — `StartRunRequest`
  without a `strategy` field still defaults to orchestrated (existing tests
  keep passing unmodified); a request with `"strategy":"direct"` reaches
  `DirectExecutor` instead. `RunFlowIntegrationTest` needs a `directExecutor`
  `@TestBean` override analogous to its existing `coderAgent` override, so
  a Direct-strategy integration test doesn't make a real API call.

## 8. Ship order

1. `RunExecutor` interface; `Orchestrator implements RunExecutor` (no
   behavior change); `RunStrategy` enum.
2. `DirectExecutor` + `DirectExecutorTest`.
3. `SdlcRun` gains `strategy`/`buildSucceeded`; `SdlcRunRecorder` gains
   `maybeRecordBuildResult` and reads `strategy` in `maybeCreateRun`;
   `RunSummary` surfaces both fields.
4. `RunRegistry`/`StartRunRequest`/`RunController` wiring, including the
   workspace-prepare-failure event's `strategy` fix; `OrchestrationConfig`'s
   new `directExecutor` bean; the past-runs table's two new columns.
