# devflowai — Status

**Last updated:** 2026-08-30
**Branch:** `main` — 45 commits, all merged, `./gradlew clean test`: 96 tests, 0 failures
**Spec:** `docs/superpowers/specs/2026-08-27-devflowai-design.md`

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

Both plans were executed subagent-driven: a fresh implementer per task, an
independent reviewer per task (never the implementer grading its own work), a
fix loop for anything the reviewer flagged, and one broad whole-branch review
at the end of each plan. The ledgers for both are gone (deleted per process
once each plan's final review went clean) — the git history below is now the
record.

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
  re-enable with zero explanation. Partially fixed (see below).
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

**Before trusting this for a demo, run:**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export ANTHROPIC_API_KEY=<your key>
./gradlew liveTest
```

---

## Known, deliberately unfixed gaps

Recorded rather than hidden — each was a real finding, triaged, and either
judged non-blocking or explicitly deferred to a later phase.

| Gap | Why it's not fixed yet |
|---|---|
| The bundled fixture has no `gradlew`/`mvnw` | Every real run's Gate 2 will show "Build failed: no wrapper found." Deliberate in Phase 0–3 (it's what lets `BuildToolsTest` test the missing-wrapper path) — but Phase 4's web page is the first thing to make this visible to an operator. |
| `GitTools.commit()`'s `git add .` is unconditional | Currently harmless (the fixture has no wrapper, so no build artifacts exist to accidentally stage) — but will corrupt the Gate 3 file list the moment a real build wrapper exists. **Must be fixed before Phase 6** (`ClonedWorkspace` will point at real repos, which typically do have wrappers). |
| A run failing before any SSE client connects still results in a silent 404 | The Phase 4 final-review fix closed the *common* case (a client subscribing slightly late to a still-running execution) but not this rarer one. Cheapest fix not yet done: one line in `index.html` so a lost connection logs something instead of silently re-enabling the Run button. |
| `index.html`'s `showGate()` ignores whether the build passed | Minor — the preceding "Build passed/failed" step line is already visible just above the gate box. |
| Server binds `0.0.0.0` with no authentication | Acceptable for the design's stated scope (single-user, local, non-hardened tool per spec §11) — do not expose this port to an untrusted network. |
| `devflowai.fixture.path` points into `src/test/resources`, not packaged into a boot jar | Fine for `./gradlew bootRun` from source (the documented way to run it); would break if ever containerized. Relevant if/when a Docker image gets built. |

---

## What's left — Phases 5–7

Sketched in the original spec, not planned in task-by-task detail yet:

- **Phase 5 — skills and memory.** The differentiating idea from the original
  concept: the system learns from reviewer bounces and stops repeating
  mistakes, written as markdown committed alongside the code. Nothing built.
- **Phase 6 — `ClonedWorkspace`.** Point devflowai at a real git URL instead
  of only the bundled fixture. Blocked on the `git add .` fix above.
- **Phase 7 — the rest of the crew.** Planner, test-writer, doc-writer agents,
  plus a routing evaluation harness.

Also on the table, discussed but not started: a Dockerfile and a deployed
instance, so the project has a link an interviewer can open rather than a
repo they have to clone and run.
