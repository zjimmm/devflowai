# devflowai — Status

**Last updated:** 2026-09-05
**Branch:** `main` — 71 commits, all merged, `./gradlew clean test`: 154 tests, 0 failures
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
| [Phase 5](docs/superpowers/plans/2026-09-05-devflowai-phase-5.md) | 10 | 1 (1 commit) | ✅ Merged |
| [Phase 6](docs/superpowers/plans/2026-09-05-devflowai-phase-6.md) | 6 | 2 (1 commit each) | ✅ Merged |

All four plans were executed subagent-driven: a fresh implementer per task, an
independent reviewer per task (never the implementer grading its own work), a
fix loop for anything the reviewer flagged, and one broad whole-branch review
at the end of each plan. The ledgers are gone (deleted per process once each
plan's final review went clean) — the git history below is now the record.

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
| `GitTools.commit()`'s `git add .` is unconditional | **Re-checked 2026-09-05:** empirically verified (`GitToolsTest.gitignoredBuildOutputNeverReachesStatusChangedFilesOrCommit`) that JGit's `add`/`status` already honor a repo's own `.gitignore` — a gitignored `build/` directory never reaches `changedFiles()`, `status()`, or a commit. The risk this row originally described (any real wrapper corrupting the Gate 3 file list) does not hold for a repo with normal `.gitignore` hygiene, which is nearly all of them. Residual, narrower risk: a target repo whose `.gitignore` is missing or incomplete for its own build tool. Separately, Phase 5 depends on this same unconditional `git add .` to stage `.devflowai/skills/*.md` and `.devflowai/memory.md` — so if a real repo's own `.gitignore` happens to exclude `.devflowai/`, the learning loop's commit would silently no-op instead. **Phase 6 shipped `ClonedWorkspace` without resolving either direction** — its live test clones a real repo but rejects before any commit happens, so neither assumption has been exercised against real repo content yet. Worth a real end-to-end test (clone a repo, actually reach Gate 3, commit) before relying on either. |
| `index.html`'s `showGate()` ignores whether the build passed | Minor — the preceding "Build passed/failed" step line is already visible just above the gate box. |
| Server binds `0.0.0.0` with no authentication | Acceptable for the design's stated scope (single-user, local, non-hardened tool per spec §11) — do not expose this port to an untrusted network. |
| `devflowai.fixture.path` points into `src/test/resources`, not packaged into a boot jar | Fine for `./gradlew bootRun` from source (the documented way to run it); would break if ever containerized. Relevant if/when a Docker image gets built. |

---

## What's left — Phase 7

Sketched in the original spec, not planned in task-by-task detail yet:

- **Phase 7 — the rest of the crew.** Planner, test-writer, doc-writer agents,
  plus a routing evaluation harness.
- **A real end-to-end run against a cloned repo, reaching an actual commit**
  — Phase 6's live test deliberately stops before Gate 2's build to avoid a
  slow, recursively-nested build against devflowai's own test suite. Worth a
  pass against some other small, real, buildable public repo to close the
  `git add .` / `.devflowai/` residual risk noted above, and to prove the
  skill/memory files an actual clone would produce really do get committed.

Also on the table, discussed but not started: a Dockerfile and a deployed
instance, so the project has a link an interviewer can open rather than a
repo they have to clone and run.
