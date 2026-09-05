# SDLC MVP core loop — Design

Sub-project 1 of the devflowai → full-SDLC-app pivot (`DevFlowAI_PRD.md`).
Everything else in the PRD (policy engine, evaluation mode, multi-repo
dashboards, the remaining ~13 lifecycle stages) is later sub-projects — see
§8 for the explicit non-goal list and why each is deferred.

## 1. What it is

Three additions to the existing coder → reviewer → gate loop, each closing a
gap the PRD surfaced:

1. **A persisted audit trail.** Today `RunRegistry` evicts a run's
   `RunHandle` — and with it, `RunState` — from memory the instant it
   finishes (`RunRegistry.java:91`, `runs.remove(runId)`). There is no way to
   ask "what did devflowai do yesterday" once a run's SSE stream has closed.
2. **A provider-neutral coding-worker seam.** The PRD's thesis is
   orchestrating *external* coding agents (Codex, Claude Code), not only an
   in-process one. Today `CoderAgent`/`ReviewerAgent` call a Spring AI
   `ChatClient` directly with no seam to swap that out.
3. **A planning stage.** Today the coder goes straight from task text to
   tool calls, with no explicit plan an operator can review before the
   first edit happens, and no artifact the PRD's later evaluation mode
   could compare against.

## 2. Goals

- Every run — approved, rejected, or crashed — is queryable after the fact,
  by task, outcome, and timestamp, without needing the SSE stream open.
- `CoderAgent` and `ReviewerAgent` keep their exact current behavior and
  prompts; the refactor that introduces `CodingWorker` is behavior-preserving
  and provable by the existing test suite passing unmodified.
- A `PlannerAgent` produces a short plan once per run, before the coder's
  first call, visible to the operator and available to the coder's prompt.
- Coder/Reviewer move to a cheaper model tier than today (Sonnet 5 instead
  of Opus 5), reserving Opus 5 for the one high-leverage per-run call
  (Planner). This is a real cost/quality trade to validate, not an assumed
  win — see §4.
- A minimal "past runs" list is reachable from the existing operator page,
  reading the new persisted history.

## 3. Persistence: write-behind recorder

**Approach.** `RunEventPublisher.publish(runId, event)` is already the one
chokepoint every `Orchestrator.emit(...)` call funnels through. It gains an
`ApplicationEventPublisher` dependency and fires a `RunRecorded(runId,
event)` Spring `ApplicationEvent` alongside the existing SSE push. A new
`@Component SdlcRunRecorder` reacts via `@EventListener` and writes to JPA
repositories. `Orchestrator` and `RunEventPublisher`'s public API are
otherwise untouched — the recorder is a second, independent consumer of
events that already exist.

Two additional, narrow event-publishing points, both new:

- **`RunController.approve(...)`** fires an `ApplicationEvent` (`runId`,
  `Gate`, `ApprovalDecision`) right after a decision is accepted — it
  already has `runId`, and can read `handle.gate().pending()` for the
  `Gate` before calling `handle.gate().decide(decision)`. This is the
  natural spot to persist an `Approval` row, and keeps `ApprovalGate`
  itself — a small, currently dependency-free synchronization primitive —
  untouched.
- **`Orchestrator.emit(...)`** is changed to always include the calling
  run's current `RunPhase` in the event's `data` map — a small change, not
  a one-line one: every call site passes an immutable `Map.of(...)`
  literal as `data`, so `emit()` must build a mutable copy with `phase`
  added (`new HashMap<>(data)` then `put("phase", state.phase().name())`)
  before constructing the `RunEvent`, rather than mutating the argument in
  place. `StageExecution` boundaries are then derived mechanically — the
  recorder opens a new `StageExecution` row whenever the phase on an
  incoming event differs from the last one seen for that run, closing the
  previous row's `endedAt`. No message-string parsing.

One further enrichment, closing a gap flagged in Phase 6's final review but
never fixed: the `NEEDS_WORK` emit
(`Orchestrator.java:139`, `Map.of("findings", reviewed.findings().size())`)
carries only a count today. It gains a `findingsDetail` key — the actual
`Finding` list (`origin`/`severity`/`file`/`line`/`message`, all already
plain data sitting in `state`, not a diff) — so the recorder can write real
`ReviewFinding` rows instead of a tally.

**Entities** (Spring Data JPA):

```
SdlcRun         id (=runId), task, repoSlug, status (RUNNING/DONE/ABORTED/FAILED),
                reason, startedAt, finishedAt, inputTokens, outputTokens

StageExecution  id, runId (FK), phase (mirrors RunPhase), startedAt, endedAt

ReviewFinding   id, runId (FK), reviewIteration, origin, severity, file, line, message

Approval        id, runId (FK), gate (mirrors Gate), approved, reason, decidedAt
```

`Artifact` and `ToolExecution` (PRD §13) are deliberately not included —
see §8.

**Storage.** `spring-boot-starter-data-jpa` + H2 in file mode
(`jdbc:h2:file:~/.devflowai/history/devflowai`), Hibernate
`ddl-auto=update`. This is the project's first persistence dependency.
No migration tool (Flyway/Liquibase) yet — acceptable for an audit trail,
not yet a system of record. See §8.

**Scope of "persistence."** This is a write-behind audit trail, not
crash-resumability: a run interrupted mid-flight is not resumed from its
last `StageExecution` on restart — `RunRegistry` stays in-memory and
single-node exactly as documented today (`RunRegistry.java:20`). Only
completed/failed/aborted history becomes durable and queryable. The
in-flight recovery this deliberately excludes is the PRD's NFR-2 taken to
its full extent; that remains future work if ever needed.

## 4. CodingWorker: a provider-neutral seam

**Layering**, adapter-in-the-middle so `Agent`'s contract and all existing
call sites (`Orchestrator`, `OrchestrationConfig`) stay exactly as they are:

```
Agent (unchanged)             — RunState -> AgentResult
  └─ CoderAgent, ReviewerAgent — become thin adapters:
       build a WorkerRequest from RunState, delegate, map WorkerResult -> AgentResult
CodingWorker (new interface)  — WorkerRequest -> WorkerResult
  └─ SpringAiCodingWorker      — the only implementation this sub-project builds;
                                  today's ChatClient-calling logic moves here verbatim
```

`WorkerRequest`/`WorkerResult` are plain records — task text, memory,
loaded skills, plan text (§5), prior findings, workspace root path in;
summary, files touched, findings, token usage out. Deliberately no
`RunState` reference, since the entire point of the seam is that a future
worker (one that shells out to the Codex or Claude Code CLI) must not need
devflowai's internal orchestration types.

This sub-project ships exactly one worker implementation. An
external-process worker is out of scope here — see §8 — this task only
builds the seam and proves it behavior-preserving.

## 5. Planner stage

New `PlannerAgent implements Agent`, wired the same way Coder/Reviewer are
(own `@Qualifier` `ChatClient` bean). Runs **once**, immediately after Gate
1 (`PRE_FLIGHT`) and before the review loop's first Coder call — not
before Gate 1, so Gate 1 keeps meaning "spend tokens on this repo, for this
task" for the *first* Opus 5 call, same as it does today. It does not
re-run on a reviewer bounce-back inside the loop; the plan is a once-per-run
artifact, not re-derived each iteration.

No new approval gate. The plan is surfaced as a `step` event (so the
operator sees it in the live log before Gate 2) and stored on `RunState`
via a new `plan()`/`setPlan(String)` accessor, following the same pattern
`memory()`/`loadedSkills()` already use. `SpringAiCodingWorker` includes
`state.plan()` in the Coder's `WorkerRequest` context, alongside memory and
loaded skills.

A `FAILED` planner result aborts the run the same way a failed Coder or
Reviewer result does today (`Orchestrator.failed(state, "Planner failed: "
+ ...)`).

## 6. Model tiers

| Agent | Model | Effort |
|---|---|---|
| Coder | Sonnet 5 | XHIGH |
| Reviewer | Sonnet 5 | XHIGH |
| Planner | Opus 5 | XHIGH |
| SkillPicker | Haiku 4.5 | — (unchanged) |
| Scribe | Haiku 4.5 | — (unchanged) |

`OutputConfig.Effort.XHIGH` is confirmed real and compilable — verified by
direct `javap` bytecode inspection of the resolved
`anthropic-java-core-2.52.0.jar` (`LOW, MEDIUM, HIGH, XHIGH, MAX` all
present as `public static final` fields), not assumed from documentation.

Rationale: Planner is the once-per-run, highest-leverage call everything
downstream depends on — it gets the strongest model at the strongest
available effort. Coder and Reviewer are called repeatedly inside the
bounded review loop (up to `maxReviewIterations` times each); testing
whether Sonnet 5 + XHIGH holds up there, at meaningfully lower cost than
Opus 5, is itself a useful thing to learn — directly in the spirit of the
PRD's own evaluation-mode thesis (§22), even though evaluation mode itself
is a later sub-project.

`ChatClientConfig`'s private `opusOptions(String model)` helper (and its
Javadoc referring to "the OPUS-tier agents (coder/reviewer)") is
generalized to `agentOptions(String model)`, no longer Opus-specific, since
Coder/Reviewer move off Opus. Its `.effort(...)` call becomes `XHIGH`
instead of `HIGH` for all three of Coder/Reviewer/Planner.

**Explicitly out of scope:** the "orchestration" model tier the user raised
alongside this question refers to a future stage-routing/skip decision
(e.g. "documentation-only task → skip the build stage") — not any
component that exists today. Today's `Orchestrator` class makes zero LLM
calls (pure Java control flow), and the existing `@Qualifier("router")`
`ChatClient` bean is SkillPicker's classifier, not a routing engine. No such
component is built in this sub-project; Opus 5 is the working assumption
for whenever it is.

## 7. API and UI: a minimal past-runs list

**API.** One new read-only endpoint:

```
GET /api/runs/history
  -> [{ runId, task, repoSlug, status, reason, startedAt, finishedAt,
        inputTokens, outputTokens }, ...]
```

Reads `SdlcRun` rows, most recent `startedAt` first, capped at 50 rows. No
pagination, no filtering, no per-run drill-down endpoint — see §8.

**UI.** `index.html` gains a "Past runs" section below the existing
single-run operator view: a plain table populated by one fetch on page
load, in the same vanilla HTML/CSS/JS style the page already uses (no new
dependency). A manual "Refresh" button reloads the list; there is no live
update while new runs complete and no click-through detail view. This is
deliberately the smallest useful proof that the persisted history works,
not the mission-control dashboard the PRD eventually wants — that belongs
to a later sub-project once there's more than one concurrent run worth
surveying (multi-repo/multi-worker), per the sub-project sequencing already
agreed.

## 8. Non-goals (explicit)

Deferred to a later sub-project, each for a specific reason:

- **`Artifact`, `ToolExecution` entities (PRD §13)** — no current run
  produces artifacts beyond a git commit, and per-tool-call logging has no
  consumer yet. Add when a stage that needs them (e.g. deployment) is built.
- **True crash-resumability** — see §3's scope note. `RunRegistry` staying
  in-memory single-node is unchanged.
- **Policy engine (PRD §18)** — Sub-project 2.
- **Evaluation/benchmark mode (PRD §22)** — Sub-project 3; this sub-project
  only sets up the model-tier split that mode would eventually measure.
- **External CLI-shelling `CodingWorker`** (Codex, Claude Code processes) —
  this sub-project builds only the seam and `SpringAiCodingWorker`.
- **Routing/orchestration LLM** (stage-skip decisions) — not built; see §6.
- **Full mission-control dashboard, multi-repo, multi-worker, RBAC** —
  Sub-project 4. §7's list is its cheap, minimal precursor only.
- **DB migration tooling (Flyway/Liquibase)** — Hibernate `ddl-auto=update`
  is enough for a single-operator audit trail; revisit once the schema is a
  system of record other things depend on.
- **Pagination/filtering on the past-runs list, per-run history
  drill-down UI** — the list is a flat, capped table; replaying a finished
  run's full stage timeline is dashboard-sub-project work.

## 9. Testing

All of the following stay in the default `./gradlew test` suite — no
`liveTest` additions needed; the temperature/model-compatibility spike
already covers live-call verification separately.

- **`SdlcRunRecorder`**: unit-tested directly — fire a fake `RunRecorded`
  event (and the new approval-decided event from `RunController`), assert
  the right JPA entity was written, against an in-memory or temp-file H2
  instance (this is the project's first persistence test; establishes the
  pattern for later sub-projects too).
- **`SpringAiCodingWorker`**: same stubbed-`ChatClient` pattern
  `CoderAgentTest`/`ReviewerAgentTest` already use — asserts the adapter
  maps `WorkerRequest` → prompt and response → `WorkerResult` correctly, not
  a re-test of Coder/Reviewer's existing prompt-behavior tests (those stay
  as-is, now exercising the adapter path).
- **`PlannerAgent`**: same stubbed-`ChatClient` pattern as existing agents,
  plus one `OrchestratorTest` case (in the style of Phase 6's
  `repoSlugFromRunStateThreadsThroughToTheStores`) proving the plan actually
  reaches `state.plan()` and that Planner runs exactly once, between Gate 1
  and the first Coder call — not once per review iteration.
- **`GET /api/runs/history`**: a `RunControllerTest`/`MockMvc` case seeding
  a couple of `SdlcRun` rows and asserting the response shape and ordering.

## 10. Ship order

For the implementation plan:

1. Persistence entities + repositories + `SdlcRunRecorder` +
   `ApplicationEvent` wiring (`RunEventPublisher`, `RunController.approve`,
   `Orchestrator`'s phase/finding-detail enrichment).
2. `CodingWorker` seam (`WorkerRequest`/`WorkerResult`,
   `SpringAiCodingWorker`, `CoderAgent`/`ReviewerAgent` become adapters) —
   pure refactor; existing tests must pass unmodified.
3. `PlannerAgent` + `ChatClientConfig`/`OrchestrationConfig` wiring +
   `Orchestrator` integration (`state.plan()`, gate ordering).
4. Model-tier reassignment (`ChatClientConfig` generalization) — deliberately
   last, so it lands as a small, isolated config change once the refactor
   it depends on (step 2) is already proven safe.
5. `GET /api/runs/history` endpoint.
6. UI: past-runs list section.
