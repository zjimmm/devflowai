# devflowai — Status

**Last updated:** 2026-09-06
**Branch:** `main` — 104 commits, all merged, `./gradlew clean test`: 203 tests, 0 failures
**Spec:** `docs/superpowers/specs/2026-08-27-devflowai-design.md` (Sub-project 1 additionally argues from `docs/superpowers/specs/2026-09-05-sdlc-mvp-core-loop-design.md`; Sub-project 2 from `docs/superpowers/specs/2026-09-06-sdlc-security-quality-gate-design.md`; Sub-project 3 from `docs/superpowers/specs/2026-09-06-sdlc-evaluation-mode-design.md`)

---

## One-line summary

The core agent system is built, tested, and reviewed against every plan and spec
requirement — **but no LLM call has ever been made against this code.** Every
finding below comes from static analysis, stubbed tests, and bytecode
inspection. That's the one gap no amount of review closes; see [What's actually
unverified](#whats-actually-unverified).

---

## Plans executed

| Plan | Tasks | Fix waves | Status |
|---|---|---|---|
| [Phases 0–3](docs/superpowers/plans/2026-08-27-devflowai-phases-0-3.md) | 15 | 1 (4 commits) | ✅ Merged |
| [Phase 4](docs/superpowers/plans/2026-08-29-devflowai-phase-4.md) | 10 | 1 (3 commits) | ✅ Merged |
| [Phase 5](docs/superpowers/plans/2026-09-05-devflowai-phase-5.md) | 10 | 1 (1 commit) | ✅ Merged |
| [Phase 6](docs/superpowers/plans/2026-09-05-devflowai-phase-6.md) | 6 | 2 (1 commit each) | ✅ Merged |
| [Sub-project 1 — SDLC MVP core loop](docs/superpowers/plans/2026-09-05-sdlc-mvp-core-loop.md) | 10 | 4 (1 commit each) | ✅ Merged |
| [Sub-project 2 — Security/Quality Gate](docs/superpowers/plans/2026-09-06-sdlc-security-quality-gate.md) | 3 | 1 (1 commit) | ✅ Merged |
| [Sub-project 3 — Evaluation Mode](docs/superpowers/plans/2026-09-06-sdlc-evaluation-mode.md) | 4 | 2 (1 commit each) | ✅ Merged |

All seven plans were executed subagent-driven: a fresh implementer per task, an
independent reviewer per task (never the implementer grading its own work), a
fix loop for anything the reviewer flagged, and one broad whole-branch review
at the end of each plan. The ledgers are gone (deleted per process once each
plan's final review went clean) — the git history below is now the record.

Sub-project 1 begins a naming shift, not just a new plan: it's the first
increment of steering devflowai from a single-repo coding-crew library toward
the full AI-native SDLC control plane described in `DevFlowAI_PRD.md` — see
that sub-project's own section below for what changed and why "Sub-project"
replaces "Phase" going forward.

---

## Phases 0–3 — the core agent system

**Goal:** orchestrator → coder → reviewer → bounded review loop, on a
deterministic fixture, provable without a live API key.

| Task | What it built |
|---|---|
| 1–3 | Spring Boot 4.1.1 + Spring AI 2.0.1 skeleton, Claude Code project scaffolding, the spike that resolved two Spring AI integration landmines (stale default model, `temperature` rejected by Opus 5) |
| 4–9 | The fixture (a deliberately flawed `UserController`), `PathGuard`, `Workspace`/`FixtureWorkspace`, `FileTools`, `GitTools`, `BuildTools` — all deterministic, no LLM involved |
| 10–12 | Domain types (`AgentResult`, `Finding`, `TokenUsage`), pinned-model `ChatClient` beans, `CoderAgent` |
| 13–15 | `ReviewerAgent`, `Orchestrator` with the bounded loop, the live end-to-end test |

**Real bugs found and fixed along the way** (not hypothetical — each one was
demonstrated, then closed, then re-verified by an independent reviewer):

- A `.git`/`.gradle`/`build` artifact-filtering gap that would have baked build
  cruft into every workspace's baseline commit
- `GitTools`'s file-change detection missed **unstaged deletions**
  (`getMissing()`), meaning a deleted file could vanish from what the
  orchestrator believes changed
- `BuildTools`'s timeout **never actually fired** — a hung build would hang
  forever, because the code read output before waiting with a timeout
- `BuildTools`'s output buffer was **unbounded**, so a runaway build could OOM
  the host before the (now-working) timeout even triggered
- **The most serious one:** `ReviewerAgent`'s JSON parsing was fail-*open* — a
  degenerate response like literal `{}` parsed as a valid, empty "approval,"
  silently treating garbage output as if the reviewer had said OK. Fixed with
  an explicit allow-list on the `status` field.

**Final whole-branch review** caught one more, invisible to any single task's
review: the **coder could call `git commit` mid-task**, and since
`filesTouched` is computed *after* the model call by diffing against git, a
self-committed run would report an empty changeset — which the reviewer could
then wave through as "nothing to complain about." Fixed by making `commit`
plain Java, never LLM-callable (mirroring how `changedFiles()` already worked).
Also fixed in that pass: the reviewer had full write access to the workspace
instead of read-only, and `ChatClientConfig` never actually wired the adaptive
thinking / effort settings the Task 3 spike had verified.

---

## Phase 4 — the actual product

**Goal:** turn the library into something you can open in a browser — SSE
streaming, three human approval gates, reject-with-reason steering.

| Task | What it built |
|---|---|
| 1–2 | Thread-safe `RunState` (agents can now be singleton beans), real token usage from `ChatResponse` |
| 3–4 | `RunEvent`/`RunEventPublisher` (SSE), `ApprovalGate` (the cross-thread parking mechanism) |
| 5 | `Orchestrator` rework — gates woven into the loop, guaranteed cleanup, error handling |
| 6–7 | `RunRegistry`, `RunController` — the three HTTP endpoints |
| 8 | **The web page** — one HTML file, vanilla JS, no framework |
| 9 | Full-stack integration test — real HTTP → real orchestrator → real gates, stubbed agents |
| 10 | Docs |

**A spec self-contradiction found during planning, not during review:** the
design doc's Gate 1 mockup showed a file list ("ABOUT TO WRITE 3 FILES"), but
the lifecycle puts Gate 1 *before* the coder's first call — no file list can
exist yet. Resolved: Gate 1 is a pre-flight confirmation ("spend tokens on
this task"); the file list moved to Gate 2, where it's real. The spec itself
was corrected in Task 10.

**Real bugs found and fixed:**

- A genuine **concurrency race**: `CoderAgent` is now a singleton bean, but it
  still had a mutable field (`lastPrompt`) written and read across the model
  call — two simultaneous runs on the same JVM could send one run's prompt
  under another run's identity. This is the same bug class Task 1 in this
  phase had *just* fixed for a different field, on the same class, missed
  because the field was added later for test introspection.
- A **silent failure mode**: if a run failed before the browser's SSE
  connection finished setting up, the failure event was dropped and the run
  vanished from the registry — the operator would see the Run button
  re-enable with zero explanation. Fixed in a later commit (N2) by giving
  `RunEventPublisher` a bounded per-run event history that replays to a
  newly-subscribing emitter — this was originally logged as only partially
  fixed in this file, which was itself stale; corrected 2026-09-05.
- Per-run **token cost was computed but never shown** — Task 2 built it
  specifically so Phase 4 could display it, but the piece connecting the two
  fell through the gap between two different tasks' briefs. Now rendered.
- My own mid-execution correction: a Spring bean-wiring fix I specified for
  the integration test was **itself wrong** (two `@Primary` beans of the same
  type compete with *each other*, not just with the real ones) — caught and
  fixed with Spring's `@TestBean` mechanism instead, verified by an
  independent reviewer who reproduced both the original bug and my incorrect
  fix byte-for-byte to confirm the diagnosis.

---

## Phase 5 — skills and memory

**Goal:** the differentiating idea from the original concept — the system
learns from reviewer bounces and human corrections, writing what it learned
as markdown committed alongside the code, and reads it back in on later runs
against the same repo.

| Task | What it built |
|---|---|
| 1–3 | Skill data model (`SkillDraft`/`ScribeDraft`/`SkillIndexEntry`) and the hand-rolled frontmatter file format; `SkillStore`/`FileSkillStore` and `MemoryStore`/`FileMemoryStore` — host-side persistence, outside any workspace, keyed by repo |
| 4–6 | `RunState` gains `memory()` and a running findings history separate from what's cleared each coder turn; `SkillPickerAgent` (reuses the Haiku `router` bean that had sat unused since Phase 0–3); tool-free `ScribeAgent` |
| 7–8 | `Orchestrator` wiring: memory/skills loaded before Gate 1; the write-trigger condition; Gate 3 rebuilt with its own approve/reject-with-reason/reject-without-reason semantics (spec §5.2) |
| 9–10 | Operator page shows the loaded skills and the Gate 3 draft; the full-stack "Learning test" (spec §12) proving a corrected run writes a skill into the host-side store |

**A spec/implementation gap found during planning, not during review:** the
spec's write trigger is literally `reviewIterations >= 2`. Tracing the actual
Phase 4 code showed this can't be right on its own — `decrementReviewIteration`
(added in Phase 4 specifically so a human correction doesn't consume the
reviewer's separate cap) rolls that counter back on every human correction, so
a run corrected *only* by a human, with the reviewer approving immediately
each time, can finish with `reviewIterations` stuck at 1 forever. Fixed by
using `reviewIterations >= 2 || humanIterations >= 1`, with a regression test
for exactly this case.

**Real bugs found and fixed:**

- **The most serious one, caught only by the final whole-branch review:**
  `FileSkillStore.write()` saves a skill by a sanitized slug
  (`add-validation.md`), but `readFull()` was resolving the file by the *raw*
  name it was given — which, coming back out of a file's frontmatter, is
  whatever human-readable name the Scribe originally chose (`"Add
  Validation"`). Every per-task review missed this because every task's own
  tests happened to use names that were already kebab-case. Net effect: a
  skill would be written, appear in the index, get picked by the
  `SkillPicker` — and then silently fail to ever reach the coder's prompt, no
  error anywhere. Fixed by having `readFull` resolve through the same slug
  rule `write` uses. The same unslugged lookup was also an unvalidated path
  read; the fix closes both at once, and a test now writes a
  non-kebab-case-named skill and reads it back by its raw name.
- Alongside that fix: the `SkillPicker`'s output is now filtered against the
  actual skill index before any file lookup happens (defense in depth — an
  earlier review had judged the missing filter non-load-bearing on the
  assumption that an unknown name always resolves to nothing; the bug above
  showed that assumption was only half true), and both host-side stores
  (`FileSkillStore`, `FileMemoryStore`) are now `synchronized` — a
  confirmed-reachable race once concurrent runs share a singleton store keyed
  by the same `repoSlug`.
- A **misleading operator-facing message**: committing after a bare
  (reasonless) Gate‑3 rejection — which spec §5.2 says should commit the
  validated code and discard only the draft — reported the same
  `"Approved by reviewer and operator"` text as a normal approval, with no
  indication the operator had actually declined something.

**Design calls made explicitly, not left implicit:** no general multi-agent
router was built (the spec's "Router" box was never implemented in Phases
0–4 either — only coder→reviewer exists, so there is nothing yet for a router
to route between); the `SkillPicker` reuses the router's Haiku bean scoped
narrowly to skill selection. `repoSlug` was hardcoded to `"fixture"` in this
phase — Phase 6 gave it a real derivation from a repo URL. "Merges into an
existing skill by name" (spec §6.3) is implemented as whole-file overwrite by
slug, not content-level merging.

**Also shipped alongside this phase, outside its plan:** the operator page can
now attach a `.md`/`.txt` file instead of typing the task by hand — read
client-side, no backend change, so the documented three-endpoint contract is
untouched.

---

## Phase 6 — `ClonedWorkspace`

**Goal:** point devflowai at a real public git repo URL instead of only the
bundled fixture — "an interviewer can hand over a URL" (spec §12).

| Task | What it built |
|---|---|
| 1 | `Slug` utility extracted from Phase 5's skill-naming code, reused for repo-URL-to-repoSlug derivation |
| 2 | `AbstractGitWorkspace` (shared base for `FixtureWorkspace`/`ClonedWorkspace`); `ClonedWorkspace` itself — shallow clone, `https://`-only scheme allowlist |
| 3 | `repoSlug` becomes genuinely per-run (carried on `RunState`) instead of a static config value |
| 4 | `RunRegistry` routes `"fixture"` vs. a real URL; a rejected URL becomes a clean HTTP 400 instead of a raw 500 |
| 5 | Operator page gets a URL field that overrides the repo dropdown |
| 6 | Full-stack live test — a real clone flows through HTTP end to end, without ever letting a real (recursively nested) build run |

**A security finding that changed the design, not just the implementation:**
before writing any code, a live spike against the actual resolved JGit 7.1.0
jar found that `Git.cloneRepository().setURI(...)` doesn't just fetch over
HTTPS — it dispatches on the URI's scheme to whichever transport JGit has
registered. Confirmed live: an `ext::<command>` URI spawns `<command>` as a
real subprocess (the resulting `TransportException`'s shape — a live process
whose pipe closed, not "unsupported protocol" — proves the subprocess
actually ran), and both `file://<path>` and a bare schemeless path silently
clone from the *server's own filesystem* with no exception at all. This is
recorded in the spec itself (§4.1, added during brainstorming, before any
task was written) rather than discovered mid-implementation: the operator-
supplied `repo` string is validated against a strict `https://`-prefix
allowlist in `ClonedWorkspace`'s constructor, before any network call.

**A second, independent confirmation from the final whole-branch review:**
that reviewer went further and disassembled JGit's `TransportHttp` to check
whether the allowlist could be bypassed via an HTTP redirect from a
legitimate `https://` URL to a dangerous scheme — it can't; JGit's own
`isValidRedirect` refuses to follow a redirect whose scheme isn't the same
protocol or literally `https`. The allowlist holds through the full
transport lifetime, not just at the door.

**Real bugs found and fixed:**

- **Caught only by the final whole-branch review, not any per-task review:**
  `repoSlug` was derived by slugging the raw repo URL directly, so
  `"https://github.com/o/r.git"` and `"https://github.com/o/r"` — both
  plausible pastes for the same repo (GitHub's own "Code → HTTPS" button
  appends `.git`; the browser address bar doesn't) — produced two different
  slugs, silently splitting the learning loop's identity for that repo
  depending purely on which URL spelling was pasted. Fixed by normalizing
  the URL (stripping the scheme, a trailing `/`, and a trailing `.git`)
  before slugging. Caught before any real skill data existed under the old
  scheme, so nothing needed migrating.
- The same review found no test proved a non-`"fixture"` `repoSlug` actually
  reached `SkillStore`/`MemoryStore` through `Orchestrator` — a real
  coverage gap on the phase's central claim, carried forward from Task 3
  through Task 4 without closing. Closed with a dedicated test.
- Two free, low-risk fixes taken opportunistically: `RunController` now
  trims the `repo` string before validating it (a stray leading space
  previously produced a confusing, apparently self-contradictory rejection
  message), and `Slug.of` now uses `Locale.ROOT` rather than the JVM's
  default locale (which could otherwise mangle a slug differently on a
  different deployment's locale settings).

**Known, deliberately unresolved after this phase:** whether a real cloned
repo's own `.gitignore` correctly protects (or, in the other direction,
doesn't accidentally exclude) `.devflowai/` — the live test clones this
project's own repo but rejects before any commit happens, so it never
actually exercises this. See the `git add .` row below.

**Design calls made explicitly:** duplicated temp-dir creation between
`FixtureWorkspace`/`ClonedWorkspace.prepare()` was left as-is rather than
factored into `AbstractGitWorkspace` (cosmetic, not correctness-affecting).
The scheme check is deliberately case-sensitive (`HTTPS://` is rejected) —
fails safe, matches the client-side check, no divergence to trip over.
Destination validation (a well-formed `https://` URL pointing at a private
or link-local address) is explicitly out of scope under spec §11's
single-user framing, recorded as such in §4.1 rather than left implicit.

---

## Sub-project 1 — SDLC MVP core loop

**Goal:** the first increment of steering devflowai toward the full-SDLC
vision in `DevFlowAI_PRD.md` — a persisted audit trail, a provider-neutral
seam for the coding agents, and a Planner stage, without rebuilding the
proven coder→reviewer→gate loop. This project's own earlier "Phase 7" sketch
(below, now superseded) had lumped "the rest of the crew" together with a
routing evaluation harness; brainstorming the actual PRD pivot split that
into five sub-projects and scoped this first one down to just the pieces a
real MVP needs — see the spec's own non-goals (§8) for what was deliberately
deferred to Sub-projects 2–5.

| Task | What it built |
|---|---|
| 1 | JPA entities/repositories (`SdlcRun`, `StageExecution`, `ReviewFinding`, `Approval`) + H2, this project's first persistence dependency |
| 2 | `SdlcRunRecorder` — a write-behind `@EventListener` reacting to a new `RunRecorded` Spring event fired alongside the existing SSE push, with zero changes to `Orchestrator`'s or `RunEventPublisher`'s public API |
| 3–4 | `ReviewFinding` and `Approval` recording, extending the same recorder |
| 5–6 | The `CodingWorker` seam — `CoderAgent`/`ReviewerAgent` refactored from calling Spring AI's `ChatClient` directly to delegating through a provider-neutral interface (`SpringAiCodingWorker` the only implementation), a pure behavior-preserving refactor |
| 7 | `PlannerAgent` — runs once per run, after Gate 1, feeding `RunState.plan()` into the coder's prompt |
| 8 | Model-tier split: Coder/Reviewer move from Opus 5 to Sonnet 5 (both at `XHIGH` effort); Planner gets Opus 5 at `XHIGH` — a real cost/quality trade to test, not an assumed win |
| 9–10 | `GET /api/runs/history` and a minimal past-runs list on the operator page |

**`OutputConfig.Effort.XHIGH` was verified real before use, not assumed:**
`javap` against the actual resolved `anthropic-java-core-2.52.0.jar` confirmed
`LOW, MEDIUM, HIGH, XHIGH, MAX` all exist as compiled constants — CLAUDE.md's
"Verified Spring AI 2.0.1 syntax" section had previously only tested `HIGH`.

**Real bugs found and fixed:**

- **Caught only by a task reviewer who independently ran the test, not by
  reading the code:** `HistoryRepositoriesTest` (Task 1) used `@SpringBootTest`
  in place of `@DataJpaTest` (confirmed genuinely removed from Boot 4.1.1,
  not just missing a starter dependency — a `spring-boot-starter-data-jpa-test`
  spike still couldn't resolve the annotation), but without `@Transactional`
  — meaning test methods shared the same JVM-lifetime H2 database with no
  rollback between them. The reviewer reproduced the exact order-dependent
  pass/fail live before flagging it. Fixed with `@Transactional` on the class.
- **A structural risk in the new recorder, caught by task review before it
  could compound:** `SdlcRunRecorder.onRunRecorded` ran with no exception
  isolation — since Spring's default event multicaster invokes
  `@EventListener` methods synchronously in the publisher's thread, a
  persistence hiccup (a `DataAccessException`, an invalid phase string) would
  have propagated out through `Orchestrator.emit()` and aborted a live
  coding run over an audit-trail write failure. Fixed by wrapping the
  listener in the same catch-and-drop philosophy `RunEventPublisher.
  sendQuietly` already uses for SSE delivery — ruled load-bearing and fixed
  immediately rather than deferred, since Tasks 3 and 4 both extended the
  same method next.
- **Caught only by the final whole-branch review:** the new past-runs table
  built each row via a template-literal `innerHTML` assignment, interpolating
  the task string — untrusted, since Phase 5 lets an operator attach a task
  document instead of typing it — directly as raw HTML. Fixed by building
  each cell with `createElement`/`textContent`, matching the page's existing
  convention everywhere else it renders dynamic text.
- **Also caught only by the final review:** a `SdlcRunRecorderTest` assertion
  (`runs.count()`) counted the *entire* shared H2 table rather than scoping
  to the `runId` under test — passing only by accident of Gradle's
  alphabetical test-class ordering, and only became a live risk once Task 7
  wired `RunFlowIntegrationTest` to exercise the same recorder through a real
  run. Neither task's own reviewer could see that coupling. Fixed by scoping
  the assertion to its `runId`.
- The final review also found nothing proved `state.plan()` actually reached
  the text sent to the model — only that it got set. `CoderAgent`'s two-line
  plan-inclusion block could have been deleted and every test would still
  have passed. Closed with a dedicated prompt-capture test.

**Design calls made explicitly:** `WorkerRequest` carries a fully-built
prompt string and a tool list rather than decomposed task/memory/plan
fields — keeps prompt construction where it already correctly lived, and is
equally provider-neutral for a future CLI-shelling worker. `PlannerAgent`
never returns an explicit `FAILED` status, mirroring `CoderAgent`'s own
style rather than inventing fail-closed JSON parsing it doesn't need. No new
`RunPhase` value was added for the planning step — its `StageExecution` row
is recorded under whatever phase preceded it (`PREPARING`).

**Known, deliberately unresolved after this sub-project:** `src/test/
resources/application.yml` fully replaces (not merges with) `src/main/
resources/application.yml` on the test classpath, so `ddl-auto: update` and
`open-in-view: false` are never actually exercised by any test — harmless
today only because Spring Boot's own embedded-database auto-detection
defaults to `create-drop`, which happens to make schema creation work
anyway. A run that crashes mid-flight leaves its `SdlcRun` row stuck at
`RUNNING` forever, with no startup reconciliation — accepted per spec §3 as
an audit trail, not true crash-resumability. `claude-sonnet-5`'s wire id has
never been called against the live API (see below).

---

## Sub-project 2 — Security/Quality Gate + Policy Engine

**Goal:** the PRD's Phase 2 bundles nine things (requirement analysis,
acceptance criteria, architecture review, risk classification, a
security/quality stage, documentation, PR creation, a policy engine, CI
integration) — PR/CI already moved to Sub-project 5. What remained was
still multiple things the PRD treats as inseparable from policy: "the
security/quality gate *is* essentially a policy table." Rather than build
four inert stages plus a policy engine with nothing real to check, this
sub-project shipped the smallest real slice: one enforced rule
(`Build MUST PASS`), evaluated by a small, deliberately extensible
`PolicyEngine` — the first real consumer of "configurable policy," not a
speculative framework.

| Task | What it built |
|---|---|
| 1 | `ai.devflow.policy` package (`PolicyContext`, `PolicyResult`, `PolicyEngine`, `ConfigurablePolicyEngine`); `Finding.Origin.POLICY` + `Finding.fromPolicy(...)` |
| 2 | A real, working Gradle wrapper vendored into the bundled test fixture — previously it had none, so its build always failed |
| 3 | `Orchestrator` wiring: the policy check runs once per loop iteration, right after the build and before Gate 3; a failure re-enters the loop exactly like today's Gate-2 human-rejection-with-reason path |

**A scope narrowing found during design, not implementation:** the PRD's
"Critical/High security findings MUST = 0" line has no meaningful data to
check against in this codebase — by the time a policy check could run, the
reviewer has already said OK, and `AgentResult.ok(...)` structurally
carries zero findings. That line assumes a dedicated security scanner's
output, which is real new tool integration, explicitly out of scope here.
`Build MUST PASS` was the one rule from the PRD's example table that was
both meaningful and free: build success was already computed, just never
enforced (Gate 3 showed "Build failed" to the human and proceeded anyway).

**The most serious finding, caught only by the final whole-branch review:**
treating a *missing* build wrapper the same as a *failed* build meant any
`ClonedWorkspace` run (Phase 6) against a repo without a committed
Gradle/Maven wrapper — any Python/Node/Go/Rust repo, or a Java repo that
doesn't commit its wrapper — would now fail policy every iteration, exhaust
the retry cap, and plausibly lead the coder to fabricate a build wrapper
into someone's real cloned repo just to satisfy the check. This was a
genuine unreviewed side effect on functionality outside this sub-project's
stated scope: the spec only ever considered the bundled fixture. Fixed by
giving `BuildTools.BuildResult` a `wrapperFound` flag and `PolicyContext` a
`buildRan` flag — the policy only enforces build-pass when a build actually
ran; a missing wrapper is "nothing to enforce," not a failure.

**A second finding from the same review, where the reviewer's own suggested
fix was overridden:** the coder received zero diagnostic content on a
policy failure (`"- [POLICY/HIGH] the build did not pass"`, no compiler
error, no failing test name). The reviewer suggested giving `CoderAgent` its
own callable `BuildTools`. That fix was rejected: it would let the coder
invoke the target repo's build on its own initiative, bypassing Gate 2's
human approval entirely — the identical bug class `GitTools.commit()` is
already deliberately *not* an `@Tool` to avoid (its own Javadoc: "committing
sits behind Gate 3 (a human gate)... an agent-callable commit tool would let
the coder commit mid-task"). Fixed instead by appending the build's own
output (already bounded to 4 KB by `BuildTools`) to the policy-failure
`Finding`'s message — the coder gets real diagnostic content through the
existing channel, with no new tool and no gate bypass.

**A real, measured cost accepted rather than discovered later:**
`Orchestrator.execute()` constructs `BuildTools` directly and always calls
the real `build("test")` — unmockable. Giving the fixture a working build
means every test that already drove a run through Gate 2 (roughly 15 in
`OrchestratorTest`, plus `RunFlowIntegrationTest`) now spawns one real
~0.85-second Gradle process. Measured before committing to this design
(three fresh-directory runs, each ~0.85s, no network call on a warm cache):
the default suite went from ~11s to ~28–34s. Accepted as the cost of a
fixture whose build genuinely passes, which the policy engine needs to be
meaningful by default.

**Design calls made explicitly:** `PolicyContext` is a record specifically
so later sub-projects (a coverage percentage, a scanner's finding counts)
can extend it without another signature change — already exercised once, by
the `buildRan` fix above. `PolicyResult`'s compact constructor now rejects a
failing result with a blank/null reason, since `PolicyEngine` is an
advertised extension point other sub-projects will implement against, not
just this one shipped rule. A policy-driven cap-exhaustion is now
distinguished in `RunOutcome.reason` from a reviewer-driven one, so an
operator (or API caller) isn't misled into blaming the reviewer.

**Known, deliberately unresolved after this sub-project:** `BuildToolsTest`
asserts `wrapperFound()` at only 2 of `BuildResult`'s 5 return sites (the
other 3 — IOException, interrupted, timeout — are correct by inspection but
untested directly). No `OrchestratorTest` case exercises the actual
`build.wrapperFound()` → `PolicyContext` argument wiring at its real call
site; both constructor arguments are booleans, so an accidental order swap
there would compile silently and is currently guarded only by
`ConfigurablePolicyEngineTest` at the unit level.

---

## Sub-project 3 — Evaluation Mode

**Goal:** PRD §22 ("Evaluation and Benchmark Mode") frames Strategy A (a bare,
autonomous coding agent) vs. Strategy B (the full devflowai pipeline) as "a
major differentiator" — proof that all the orchestration overhead (planner,
reviewer, gates, policy engine) actually earns its keep. Brainstorming scoped
this down deliberately: compare the two strategies using only metrics
devflowai already persists — no hidden-test benchmark harness, no dashboard,
no dollar-cost conversion. Strategy A runs fully autonomously (all three human
gates skipped) rather than a hybrid, and reuses the exact same `coderAgent`
bean/model Strategy B's coder uses, so the comparison never measures a
different model as a confound.

| Task | What it built |
|---|---|
| 1 | `RunExecutor` interface, `RunStrategy` enum (`ORCHESTRATED`/`DIRECT`), `Orchestrator implements RunExecutor` (one-line, zero behavior change); `DirectExecutor` — task → coder → build (metrics only, never gates on it) → commit, deliberately not sharing `Orchestrator`'s private `emit`/`cleanUp` helpers (two small helpers don't justify coupling two independent executors before a third one exists to justify the abstraction) |
| 2 | `SdlcRun` gains `strategy`/`buildSucceeded`; `SdlcRunRecorder` gains `maybeRecordBuildResult`; `RunSummary` surfaces both over the history API |
| 3 | `RunRegistry`/`StartRunRequest`/`RunController`/`OrchestrationConfig` wired end-to-end so an HTTP caller can request either strategy, including a new `directExecutor` Spring bean |
| 4 | Operator page gets a Strategy dropdown on the run-start form and two new past-runs table columns (Strategy, Build) |

**The most serious finding, caught by a task reviewer's independent
verification, not by trusting the implementer's report:** Task 2's new
`SdlcRunRecorder.maybeCreateRun` guard requires a `"strategy"` key in event
data before creating any history row — but `Orchestrator.java`'s own
"Workspace ready" event (the *only* history-row trigger for the default,
main ORCHESTRATED pipeline) never carried one anywhere in the plan's four
tasks, a requirement dropped between architecture notes and the written plan.
Net effect, had it shipped: every default-strategy run would silently never
get a history row — no exception, the `if` guard just evaluates false —
defeating the entire comparison feature for the strategy most people would
actually use. Fixed by tagging `Orchestrator`'s own event with
`RunStrategy.ORCHESTRATED.name()`, mirroring the identical line `DirectExecutor`
already had.

**A second finding from the same review, invisible to any test:**
`SdlcRun.strategy` was declared `@Column(nullable = false)` — Hibernate's
`ddl-auto: update` adding a NOT NULL column with no default to the app's
real, persistent, file-based H2 database (not the in-memory test one) fails
against any pre-existing `sdlc_run` rows, e.g. from earlier sub-projects'
manual `bootRun` verification. The project's established "new nullable
columns need no migration" precedent didn't cover a NOT-NULL one. Fixed by
dropping the constraint at the DB level while keeping the Java-level
guarantee (the constructor still requires a real `RunStrategy` argument for
every row the app creates going forward).

**The final whole-branch review's two findings:** no test proved the
`"strategy"` key actually survives end-to-end from either executor's emitted
events into a persisted row — the exact producer/consumer contract the two
findings above had already broken once on this branch, still undefended.
Closed with two assertions in `RunFlowIntegrationTest` against
`SdlcRunRepository`, one per strategy. Separately, `CLAUDE.md`'s documented
`POST /api/runs` contract didn't mention the new `strategy` field or that a
Direct-strategy run skips the policy engine entirely — both now documented.

**Design calls made explicitly:** `RunExecutor.run(...)` returns
`Orchestrator.RunOutcome` — a type owned by one of its two implementations
rather than by the interface itself, a deliberate, documented "wart" that
avoids a broader rename across every existing `OrchestratorTest` call site
for a purely cosmetic gain; revisit if a third executor ever needs a
strategy-agnostic outcome type. `StartRunRequest` gained its `strategy` field
via a second, delegating 2-arg constructor (mirroring `RunState`'s existing
3-arg-delegates-to-4-arg pattern) specifically so every pre-existing Java
call site — not just HTTP/JSON callers — kept compiling unmodified. Spring
resolves the new `directExecutor` bean parameter purely by
parameter-name-to-bean-name matching (since `Orchestrator` and `DirectExecutor`
are both `RunExecutor`s in the context) — the same mechanism already relied
on for `OrchestrationConfig`'s three same-typed `Agent` parameters, not a new
pattern; verified at runtime by an actual context-booting `@SpringBootTest`,
not just assumed to compile.

**Known, deliberately unfixed gaps from this sub-project:**

- `SdlcRunRecorder.maybeRecordBuildResult` reads only the `"success"` key,
  which is `false` both when a build genuinely fails *and* when
  `BuildTools` found no build wrapper at all — so the past-runs table's
  Build column can show "failed" for a target repo that never had a
  buildable wrapper to begin with. Both strategies are affected identically,
  so the A/B comparison itself isn't skewed, but the column can misreport on
  a feature whose whole purpose is metric fidelity. A spec-level
  simplification (§4 explicitly chose reusing the existing `success` key
  over a new schema field), not a coding defect — fixable later by also
  threading `build.wrapperFound()` through if it matters.
- `RunController` silently routes any unrecognized `strategy` string (a typo
  like `"dircet"`) to `ORCHESTRATED` rather than rejecting it — unlike an
  invalid `repo` string, which returns 400. Detectable after the fact via the
  new Strategy column, but a silently-misrouted run undercuts measurement
  integrity more than a rejected one would.
- `RunRegistry`'s pre-existing race (the async task can call
  `runs.remove(runId)` before `runs.put(runId, handle)` finishes, permanently
  leaking a registry entry) is not new to this sub-project, but Direct runs
  finish far faster than gate-blocked Orchestrated ones, materially widening
  the window. Worth its own follow-up (move the `put` above the `submit`).
- `OrchestrationConfig.directExecutor(...)` is typed to return the
  `RunExecutor` interface rather than the concrete `DirectExecutor` class
  (the `orchestrator` bean beside it uses its concrete type) — loses type
  information for any future by-type injection. Also, the context now holds
  both an `ExecutorService` bean named `runExecutor` and an interface named
  `RunExecutor` — no actual resolution conflict since the types differ, but
  a readability trap for the next person reading this file.

---

## What's actually unverified

**No live call to the Anthropic API has been made anywhere in this codebase.**
Every session so far ran in an environment with no `ANTHROPIC_API_KEY`. What
stands in for it:

- Two integration risks (stale default model, `temperature` rejected by Opus
  5) were resolved by **decompiling the actual resolved Spring AI 2.0.1 jars**
  and tracing the code path — strong static evidence, not a live response.
- Every agent test uses a **stubbed `ChatClient`** — real Mockito mocks
  returning canned `ChatResponse` objects, never a real HTTP call.
- Three `@Tag("live")` tests exist (`AnthropicSpikeTest`,
  `CoderAgentLiveTest`, `EndToEndLiveTest`) that *would* exercise the real
  API — all three compile, are correctly gated, and have never run to a
  real pass.
- Sub-project 1 moved Coder/Reviewer to `claude-sonnet-5` and added a
  Planner on `claude-opus-5`, both at `XHIGH` effort — `EndToEndLiveTest`
  now exercises all three tiers plus `XHIGH` through the real `ChatClient`
  beans in one run, but that run has never happened. The final whole-branch
  review flagged this explicitly and recommended gating any merge on it;
  no `ANTHROPIC_API_KEY` was available in that session, so it's still open.

**Before trusting this for a demo, run:**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export ANTHROPIC_API_KEY=<your key>
./gradlew liveTest
```

That one command now validates the stale-default/temperature landmines,
all three pinned model ids, and `XHIGH` effort in a single pass.

---

## Known, deliberately unfixed gaps

Recorded rather than hidden — each was a real finding, triaged, and either
judged non-blocking or explicitly deferred to a later phase.

| Gap | Why it's not fixed yet |
|---|---|
| ~~The bundled fixture has no `gradlew`/`mvnw`~~ **Resolved by Sub-project 2** | Deliberate in Phase 0–3 (it let `BuildToolsTest` test the missing-wrapper path without a second fixture) until Sub-project 2's build-must-pass policy made a permanently-failing fixture actively harmful, not just cosmetically confusing. A real, working Gradle wrapper is now vendored into the fixture; the missing-wrapper path is still tested, but by deleting the wrapper from one test's own copied workspace rather than relying on the shared fixture lacking one. |
| `GitTools.commit()`'s `git add .` is unconditional | **Re-checked 2026-09-05:** empirically verified (`GitToolsTest.gitignoredBuildOutputNeverReachesStatusChangedFilesOrCommit`) that JGit's `add`/`status` already honor a repo's own `.gitignore` — a gitignored `build/` directory never reaches `changedFiles()`, `status()`, or a commit. The risk this row originally described (any real wrapper corrupting the Gate 3 file list) does not hold for a repo with normal `.gitignore` hygiene, which is nearly all of them. Residual, narrower risk: a target repo whose `.gitignore` is missing or incomplete for its own build tool. Separately, Phase 5 depends on this same unconditional `git add .` to stage `.devflowai/skills/*.md` and `.devflowai/memory.md` — so if a real repo's own `.gitignore` happens to exclude `.devflowai/`, the learning loop's commit would silently no-op instead. **Phase 6 shipped `ClonedWorkspace` without resolving either direction** — its live test clones a real repo but rejects before any commit happens, so neither assumption has been exercised against real repo content yet. Worth a real end-to-end test (clone a repo, actually reach Gate 3, commit) before relying on either. |
| `index.html`'s `showGate()` ignores whether the build passed | Minor — the preceding "Build passed/failed" step line is already visible just above the gate box. |
| Server binds `0.0.0.0` with no authentication | Acceptable for the design's stated scope (single-user, local, non-hardened tool per spec §11) — do not expose this port to an untrusted network. |
| `devflowai.fixture.path` points into `src/test/resources`, not packaged into a boot jar | Fine for `./gradlew bootRun` from source (the documented way to run it); would break if ever containerized. Relevant if/when a Docker image gets built. |

---

## What's left

The original spec's "Phase 7 — the rest of the crew" (planner, test-writer,
doc-writer agents, a routing evaluation harness) is superseded: Sub-project 1
above shipped the Planner piece of it. The rest — test-writer/doc-writer
agents and evaluation — now falls under the PRD-driven sub-project sequence:

- **Not yet scheduled a sub-project number** — the rest of PRD Phase 2:
  architecture review, risk classification, and a documentation stage.
  Requirement analysis and acceptance-criteria extraction shipped on 2026-09-09
  as a dedicated tool-free analyst before the Planner, with a bounded human
  clarification gate. Sub-project 2 deliberately
  shipped only the security/quality gate's one enforceable rule
  (build-must-pass) plus the `PolicyEngine` seam these four stages will each
  need — not all five PRD Phase 2 items, which the original brainstorming
  session judged too large for one sub-project even after PR/CI (Sub-project
  5) was carved out. Each remaining stage gets its own brainstorming pass
  once picked up, informed by how `PolicyEngine`'s one real consumer
  actually behaved rather than designed against all four speculatively.
- **Sub-project 4** — multi-repo, multi-worker, a real mission-control
  dashboard, RBAC. Sub-project 1's minimal past-runs list is its cheap,
  intentional precursor, not an attempt at the real thing.
- **Sub-project 5** — PR/CI/CD and deployment lifecycle (PRD Phase 4).

Not yet re-scoped into that sequence, still worth doing regardless of which
sub-project picks it up:

- **A real end-to-end run against a cloned repo, reaching an actual commit**
  — Phase 6's live test deliberately stops before Gate 2's build to avoid a
  slow, recursively-nested build against devflowai's own test suite. Worth a
  pass against some other small, real, buildable public repo to close the
  `git add .` / `.devflowai/` residual risk noted above, and to prove the
  skill/memory files an actual clone would produce really do get committed.
- A Dockerfile and a deployed instance, so the project has a link to open
  rather than a repo to clone and run.
