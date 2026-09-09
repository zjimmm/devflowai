# devflowai — Design

**Status:** approved for implementation planning
**Date:** 2026-08-27
**Supersedes:** `CONCEPT.md` (working name "Bayanihan")

---

## 1. What it is

devflowai takes a plain-language development task, routes it through a crew of
specialized AI agents, and returns a real, inspectable result — a git branch —
plus a record of what it learned doing so.

It differs from the original concept in one significant way: **the crew does not
start cold every run.** Facts and procedural know-how persist between runs as
markdown files committed to git, so run #40 knows things run #1 did not.

**Example.** *"Add input validation to UserController and cover it with tests."*
→ a branch containing the validated controller, its tests, and (if the reviewer
caught something worth remembering) a new skill document describing how to do
this correctly in *this* codebase.

---

## 2. Goals and non-goals

### Goals

- Produce a real artifact — a git branch with a working diff — not chat output.
- Keep each agent's context isolated and the orchestrator's context flat.
- Learn from corrections: a reviewer bounce should make the next run better.
- Stay inspectable: every decision visible via SSE, every lesson readable as markdown.
- Human approval before anything destructive or expensive — and rejection that
  *steers* rather than merely aborts.
- Demonstrate agent orchestration in an enterprise Java stack.

### Non-goals

- **Not** a general-purpose assistant. One job: development tasks on a git repo.
- **Not** multi-tenant. Single-user, single-node, in-memory run state.
- **Not** durable across restarts. An interrupted run is lost by design (§11).
- **Not** a Claude Code replacement. It demonstrates orchestration, it doesn't
  compete on breadth.
- **No** private-repo authentication or branch push-back in the initial scope.

---

## 3. Architecture

Single Spring Boot process. Java 21 (LTS; JDK 21 and 25 are both installed
locally via Homebrew but neither is on `PATH` — see §12 Phase 0).

```
RunController      POST /api/runs
                   GET  /api/runs/{id}/stream   (SSE)
                   POST /api/runs/{id}/approve
Orchestrator       routes, sequences, owns RunState, enforces the loop cap
RunState           task · workspace · step history · phase · pending approval
  Router           LLM: classifies task -> ordered agent list
  CoderAgent       implements changes via FileTools/GitTools
  ReviewerAgent    reads changed files, emits findings
  ScribeAgent      writes skill docs from corrections
  Workspace        interface -> FixtureWorkspace | ClonedWorkspace
  FileTools        @Tool read/write/list, path-confined
  GitTools         @Tool branch/diff/status/commit
  BuildTools       @Tool run the target repo's gradlew/mvnw
  SkillStore       interface -> FileSkillStore
  MemoryStore      durable repo facts
  RunEventPublisher pushes step events to the SSE stream
```

### 3.1 Orchestration pattern

**Central orchestration.** Code owns control flow; agents are pure functions.
`CoderAgent` does not know `ReviewerAgent` exists.

```java
var plan = router.route(task);          // LLM picks: [CODER, REVIEWER]
for (var step : plan.agents()) {
    var result = switch (step) {
        case CODER    -> coder.run(state);
        case REVIEWER -> reviewer.run(state);
    };
    state.record(result);
}
```

This is a **hybrid**, and the split is the point:

- The **router is an LLM**. It does the judgment that is genuinely hard to
  hardcode — is this a bugfix or a feature, does it need tests, does it touch
  docs — and returns an ordered list of agents from a constrained enum.
- **Code executes that plan.** Sequencing is a `switch` and a bounded loop:
  deterministic, unit-testable, steppable in a debugger, and cheap.

**Rejected alternatives**, recorded so the decision can be defended:

| Pattern | Who routes | Deterministic | Cost | Loop risk | Why not |
|---|---|---|---|---|---|
| Handoffs (agents call each other) | each agent | no | high | real | Control flow lives in prompts; can't be unit-tested |
| Agent-as-tool (orchestrator is an LLM) | orchestrator LLM | no | highest | bounded by turns | Pays for flexibility this pipeline doesn't need |

The pipeline is known in advance (plan → code → review → test → doc). Model-directed
control flow earns its cost when the steps can't be predicted; here they can. The
only dynamic element is the review→fix loop, which is a bounded `while`.

**Migration path is preserved:** because agents are already `RunState -> AgentResult`
functions, exposing them as tools to an LLM orchestrator later is a small change.
The reverse — starting agentic and retreating to deterministic — is not.

### 3.2 Context isolation contract

Each agent invocation is its own `ChatClient` call with its own system prompt and
its own message list. Nothing carries between agents implicitly.

```java
record AgentResult(
    String agent,
    Status status,              // OK | NEEDS_WORK | FAILED
    String summary,             // one paragraph, human-readable
    List<String> filesTouched,
    List<Finding> findings,     // reviewer only; empty otherwise
    TokenUsage tokens) {}

record Finding(Origin origin, Severity severity,
               String file, Integer line, String message) {}

enum Origin { REVIEWER, HUMAN }   // file/line are null for HUMAN findings
```

**The orchestrator never holds a diff or file contents.** The reviewer is not
handed the coder's diff — it is told which files changed and reads them off disk
through its own `FileTools`. Orchestrator context therefore stays flat regardless
of change size, which is what makes the isolation claim true rather than aspirational.

The cost of this choice is honest: the reviewer re-reads files the coder already
paid to read. That is the price of a flat orchestrator, and it is the right trade
at this scale — a diff passed through the orchestrator grows its context on every
iteration of the review loop, which is exactly where growth hurts most.

---

## 4. Workspace model

One interface, three possible implementations. Agents never know which they got.

```java
interface Workspace {
    Path root();                 // canonical, absolute
    String branchName();
    void prepare();              // populate + create branch
    void cleanup();
}
```

| Implementation | Populated by | Status |
|---|---|---|
| `FixtureWorkspace` | copy bundled fixture to temp dir | Phase 1 |
| `ClonedWorkspace` | `git clone --depth 1 <url> <tmp>` | Phase 6 |
| `LocalPathWorkspace` | canonicalize a host path | not planned |

**Fixture first.** The fixture is not a lesser workspace — it is what makes the
test suite deterministic. Asserting "the reviewer caught the missing null check"
requires a known `UserController` with a known flaw; you cannot assert that
against a repo that may change or a network that may flake. It earns its place
permanently, and it gets the core loop green fastest.

**Then clone.** Public repos, shallow clone, run, delete. This is the demo layer:
an interviewer can hand over a URL. Private-repo auth, push-back, and container
isolation are explicitly out of initial scope — they are where the cost balloons
and they add nothing to the thesis being demonstrated.

> **Current repository-access limitation:** DevFlowAI currently targets public
> `https://` repositories only. Private-repository cloning requires explicit
> credential support before use; it is not inferred from a supplied repository
> URL. The runtime path is `ClonedWorkspace.prepare()` and `RunRegistry.start()`.

devflowai is a server-side web app, so cloning is not a preference — a server has
no access to a remote user's disk. `LocalPathWorkspace` is coherent only in the
single-user localhost case and is not planned.

### 4.1 The repo string is not a trusted URL — it is untrusted input to a transport dispatcher

Confirmed empirically against the resolved JGit 7.1.0 jar (not assumed):
`Git.cloneRepository().setURI(...)` does not merely fetch a git repo over
HTTPS — it dispatches on the URI's scheme to whichever transport JGit has
registered, and several of those transports do things a server accepting
operator-typed strings must never allow unfiltered:

- **`ext::<command>`** spawns `<command>` as a real subprocess and speaks the
  git protocol over its stdio. A URI of `ext::sh -c "<anything>"` executes
  `<anything>` server-side. Verified live: passing this URI to
  `CloneCommand` throws `TransportException: ... remote hung up
  unexpectedly` — the *shape* of that exception (a live process whose pipe
  closed, not "unsupported protocol") confirms the subprocess was actually
  spawned, not rejected.
- **`file://<path>`** and a **bare path with no scheme at all** both resolve
  against the *server's own filesystem*. Verified live: `setURI(<an
  absolute local path>)` clones successfully with no exception at all —
  JGit silently treats a schemeless string as a local path.

**Therefore:** the `repo` string must be validated against a strict allowlist
— it must start with exactly `https://` — *before* it is passed to
`CloneCommand`, never after, and never by scanning for known-bad substrings.
An allowlist on the prefix is what closes all three vectors above at once,
since none of `ext::`, `file://`, or a bare path starts with `https://`.
`http://` is deliberately excluded too: the demo layer this phase serves
(GitHub, GitLab, and similar public hosts) is universally HTTPS, and there is
no reason to accept a scheme that sends whatever the URL contains in the
clear.

A clone is also an unbounded network operation with nothing today to bound
it (unlike `BuildTools`, which already has a configurable timeout for the
build it runs) — `ClonedWorkspace.prepare()` needs the same kind of timeout,
for the same reason: a hung remote must not hang a run forever.

**The allowlist holds through the full transport lifetime, not just at the
door.** Verified by disassembling the resolved JGit 7.1.0 jar: `TransportHttp`
sets `setInstanceFollowRedirects(false)` and handles redirects itself, gated
by `isValidRedirect`, whose logic refuses a redirect whose new scheme isn't
either the same protocol or literally `https` — so a base `https://` URL
whose server issues a `Location: file://…` or `ext::…` redirect is refused,
not silently followed out of the allowed scheme.

**What the allowlist deliberately does not close:** a well-formed `https://`
URL pointing at a private or link-local address (`https://10.0.0.1/x.git`,
`https://169.254.169.254/…`) passes the check — this is an accepted risk
under §11's single-user, non-multi-tenant framing, not an oversight. A
future phase serving untrusted operators (rather than the single local user
this tool is scoped for) would need to add destination validation on top of
the scheme check.

---

## 5. Run lifecycle

1. `POST /api/runs {task, repo}` → `RunState` created, `runId` returned
   (`repo` is `"fixture"` or an `https://` git URL — see §4.1)
2. Client opens SSE on `/api/runs/{id}/stream`
3. Workspace prepared; branch `devflowai/<runId>` created
4. **Router call** — given the task and the skill index (§6.2), returns *both* the
   ordered agent list and the names of 0–3 relevant skills. One call, not two.
5. Selected skills read in full, memory loaded; both injected into the coder's system prompt
6. **Gate 1** — before any file write
7. Coder runs via `FileTools` → `AgentResult`
8. Reviewer reads changed files → findings
9. `NEEDS_WORK` and `iterations < 3` → back to step 7 with findings appended
10. **Gate 2** — before running the build (arbitrary code execution, §9)

   *Gates 1 and 2 may be rejected **with a reason**, which returns to step 7
   rather than ending the run (§5.2).*
11. Build runs via the target repo's wrapper; result recorded
12. Skill extraction, if the run met the trigger condition (§6.3)
13. **Gate 3** — before committing
14. Commit code + any new skill doc to the branch; SSE emits `done`

### 5.1 Approval gate mechanism

SSE is server→client only and cannot carry an approval. The gate needs two halves.

The orchestrator runs on its own thread, not the request thread. On reaching a
gate it parks a `CompletableFuture<ApprovalDecision>` in `RunState` and emits an
SSE event describing exactly what is about to happen (the files to be written,
the build command to be run, the diff to be committed).

`POST /api/runs/{id}/approve {approved: bool, reason: string?}` completes that
future. A configurable timeout (default 10 min) aborts the run and triggers
`workspace.cleanup()`.

This is in-memory and does not survive a restart. See §11.

### 5.2 Rejection with a reason — the human as third reviewer

```java
record ApprovalDecision(boolean approved, String reason) {}   // reason nullable
```

A bare rejection is a kill switch, which in practice is too blunt: "no, not like
*that*" would end the run and re-pay for every step. So a rejection may carry a
reason, and the reason **becomes a `Finding` with `origin = HUMAN`** — the same
type the reviewer already emits, fed into the same bounded loop. No new control
flow, no conversation state, no chat surface.

Semantics differ per gate, because what is being decided differs:

| Gate | Approve | Reject **with** reason | Reject **without** reason |
|---|---|---|---|
| 1 (before write) | proceed | → `Finding(HUMAN)` → back to coder | abort + cleanup |
| 2 (before build) | run build | → `Finding(HUMAN)` → back to coder | abort + cleanup |
| 3 (before commit) | commit code + skill | re-run Scribe with the reason as guidance (once) | commit code, **discard the skill draft** |

Gate 3's third column matters: it is how you keep a good change while throwing
away a bad lesson, without losing the run.

**Loop accounting.** Human rejections count against a separate `humanIterations`
cap (default 5), *not* the reviewer's cap of 3. The reviewer cap exists to stop
two models ping-ponging at your expense; a human deliberately steering should not
be cut off by it.

This makes the operator a genuine third reviewer rather than a veto, and it is
the only steering mechanism in the product — see §5.3.

### 5.3 User interface

**devflowai is not a chatbot.** There is no conversation with an agent; the agents
are invisible infrastructure. The operator sees *that* the reviewer bounced the
coder and *what* it found, but never addresses the reviewer. The entire
interaction surface is four things:

| # | Operator action | When |
|---|---|---|
| 1 | Pick a repo — bundled fixture, or paste a git URL | start |
| 2 | Type the task, click **Run** | start |
| 3 | Watch steps stream in — read-only | throughout |
| 4 | **Approve**, or **Reject** with an optional reason (§5.2) | 3 gates |

One page, three states: *idle* → *streaming* → *paused at a gate* → streaming.

```
┌────────────────────────────────────────────────────┐
│  devflowai                                         │
│                                                    │
│  Repo  [ fixture ▾ ]   or  [ https://github.com/… ]│
│  Task  [ Add input validation to UserController… ] │
│                                        [  Run  ]   │
├────────────────────────────────────────────────────┤
│  ● Workspace ready — branch devflowai/a3f1         │
│  ● Router → CODER, REVIEWER                        │
│      ↳ loaded skill: spring-controller-validation  │
│  ● Coder — writing…                                │
│                                                    │
│  ⏸  ABOUT TO RUN THE BUILD — 3 files changed       │
│       UserRequest.java        (new)                │
│       UserController.java     (modified)           │
│       UserControllerTest.java (new)                │
│                                                    │
│    reason (optional) [______________________]      │
│                     [ Approve ]    [ Reject ]      │
└────────────────────────────────────────────────────┘
```

**Gate 1 is a pre-flight confirmation, not a file-write guard.** Before the
coder's first call no file list exists — the model decides what to write during
its turn. Gate 1 shows the repo, branch and task, and confirms "spend Opus 5
tokens on this"; the file list appears at Gate 2, where it is real. Filesystem
safety is `PathGuard` (§9), not a gate.

Everything above the divider is driven by the SSE stream. The two buttons
`POST /approve`. No other endpoint is reachable from the UI.

Server-rendered HTML plus a small amount of vanilla JavaScript for `EventSource`
and the two `fetch` calls. A JavaScript framework would be more machinery than
this surface justifies, and the page is not the part of the project worth
demonstrating.

---

## 6. The learning loop

Two distinct mechanisms. Hermes blurs them; devflowai keeps them separate because
they are written, retrieved, and scoped differently.

| | Memory | Skills |
|---|---|---|
| Holds | durable facts | procedural know-how |
| Example | "tests use JUnit 5 + AssertJ" | "how to add validation to a controller here" |
| Written by | ScribeAgent, on new repo facts | ScribeAgent, after a correction |
| Retrieved | always, whole file | selectively, 0–3 per run |
| Stops | repeated questions | repeated mistakes |

### 6.1 Format

`.devflowai/skills/<slug>.md` — deliberately echoing the Hermes and Claude Code
skill format, so the convention is familiar and the file is useful to a human:

```markdown
---
name: spring-controller-validation
description: Adding bean validation to a Spring MVC controller
triggers: [validation, controller, "@Valid", request body]
learned_from: run-2026-08-27-a3f1
---

## Steps
1. Annotate the DTO fields with `jakarta.validation` constraints
2. Add `@Valid` to the controller method parameter
3. Add a `@ControllerAdvice` handler if the project lacks one

## Pitfalls
- `@Valid` on the entity instead of the DTO silently does nothing

## Verification
- MockMvc test asserting 400 and the expected field errors
```

`.devflowai/memory.md` — a flat list of durable repo facts, loaded whole.

### 6.2 Retrieval

The orchestrator reads **only frontmatter** from every skill file (cheap — the
files are small and only headers are parsed), builds an index of
`name + description + triggers`, and hands it to the router. The router returns
0–3 relevant skill names. Those files are then read in full and injected into the
coder's system prompt.

Retrieval is LLM-pick over an index, not semantic search. This is sufficient well
past the few dozen skills a demo will accumulate. A vector store is a deliberate
Phase-later decision (§11) — it should be added when keyword-pick is *measured* to
be the bottleneck, not before.

### 6.3 Write trigger

**A skill is written when `reviewIterations >= 2`.**

That condition means the reviewer bounced the coder and the coder then fixed it —
a free, precise signal that a mistake was made *and corrected*, requiring no extra
LLM call to judge whether the run was interesting. The bounded review loop stops
being purely a cost-control mechanism and becomes the system's source of training
signal.

`ScribeAgent` receives the task, the findings that caused the bounce, and the final
diff. It returns a `SkillDraft`; `SkillStore` writes it as a new skill or merges it
into an existing one (matched by `name`). The scribe holds no tools (§8). Gate 3
covers the draft, so a garbage lesson never commits unreviewed.

Memory is written on the same trigger when the Scribe identifies a durable fact
rather than a procedure.

**Human rejections are the highest-value signal available.** A `Finding` with
`origin = HUMAN` (§5.2) is a correction the operator cared enough to type, and it
already trips this trigger by forcing another iteration. The Scribe weights those
above machine findings when drafting, so a preference stated once is written down
and does not need stating twice. This is the mechanism by which devflowai learns
*your* conventions rather than only generic correctness.

### 6.4 Storage and scoping

**Source of truth is host-side**, keyed by repo identity:
`~/.devflowai/skills/<repo-slug>/`. This matters: the workspace is a temp dir that
gets deleted, so skills written only there would evaporate with it.

Each run **also** writes the skills into the branch under `.devflowai/skills/`, so
that `git log .devflowai/skills/` shows the system learning run by run. That log
is the strongest demo artifact the project produces — it beats any chat transcript,
because it is a diff a reviewer can read.

Scoping by repo means a lesson learned about repo X cannot pollute repo Y.

---

## 7. Models and cost

| Role | Model | Rate /MTok |
|---|---|---|
| Router, skill-picker, scribe | `claude-haiku-4-5` | $1 / $5 |
| Coder, reviewer | `claude-opus-5` | $5 / $25 |

Coder and reviewer use adaptive thinking with `effort: HIGH`. Router and scribe
run at default effort — they are classification and summarization.

**Estimated cost:** a six-step run at roughly 15k input / 3k output tokens lands
near $0.15. Dozens of demo runs stay inside a few dollars. Controls: capped review
loop, cheap model for routing, per-agent context trimming, per-run token logging
via `AgentResult.tokens`, and a provider spend limit.

### 7.1 Two verified integration landmines

Both confirmed against Spring AI 2.0.1 documentation, both to be resolved in the
Phase 0 spike before any other code is written:

1. **The Anthropic starter's default model is stale** — `claude-sonnet-4-20250514`.
   Every `ChatClient` must set its model explicitly or silently run a year-old model.
2. **`temperature` defaults to `1.0`** in Spring AI's Anthropic options, and
   **Opus 5 rejects sampling parameters with a 400.** This must be unset, not
   merely left at default.

Spring AI 2.0.1 does expose the modern surface — `.thinkingAdaptive()` and
`.effort(LOW|MEDIUM|HIGH|MAX)` — so no escape hatch is needed once these two are handled.

---

## 8. Tool surface

Tools are Spring AI `@Tool` methods. Every path-taking tool canonicalizes its
argument and rejects anything resolving outside `workspace.root()`.

| Tool | Methods |
|---|---|
| `FileTools` | `readFile`, `writeFile`, `listFiles`, `searchFiles` |
| `GitTools` | `createBranch`, `diff`, `status`, `commit` |
| `BuildTools` | `runBuild` (the target repo's `gradlew`/`mvnw`), `runTests` |

Agents get different subsets: the coder gets all three; the reviewer gets
`FileTools` read-only plus `GitTools.diff`; the scribe gets **none**.

The scribe is deliberately tool-free. It returns a structured `SkillDraft`
(frontmatter fields plus body) and `SkillStore` — ordinary code — writes the file.
This keeps the one agent that produces *committed, persistent* artifacts unable to
touch the filesystem directly, and makes Gate 3 trivial: the draft is inspectable
before anything is written.

---

## 9. Security and containment

devflowai runs untrusted code by design — `BuildTools` executes the *target*
repo's build wrapper, which is arbitrary code execution as a feature, not a bug.
This is true of every coding agent and is worth being able to discuss precisely.

Initial-scope controls:

- **Path confinement.** Canonicalize every tool path against `workspace.root()`
  and reject escapes. `../../.ssh/id_rsa` is a file path too.
- **Repo-URL scheme confinement (Phase 6).** The same class of control as path
  confinement, one layer up the stack: the operator-supplied `repo` string is
  confined to `https://` before it ever reaches `ClonedWorkspace`/JGit. See
  §4.1 for the live-verified reason this is load-bearing, not defensive
  overkill — JGit's `ext::` transport executes an arbitrary command, and a
  bare or `file://` path reads the *server's* filesystem.
- **Gate 2** requires explicit human approval before any build runs, and the SSE
  event names the exact command.
- **Temp-dir isolation.** Each run gets a fresh directory, deleted on completion
  or timeout.
- **No secrets in the workspace.** The API key lives in the environment, never in
  a file the agents can read.
- **Public repo hygiene.** `zjimmm/devflowai` is public: `.gitignore` excludes
  `.env` and local property files from the first commit.

Explicitly **not** in initial scope: container/VM isolation, network egress
restrictions, resource limits on the build. The honest position is that gate 2 plus
temp-dir isolation is proportionate for a single-user local tool, and containerized
execution is the correct next step before this could run anyone else's code
unattended.

---

## 10. Testing

- **The fixture makes the suite deterministic.** A bundled Spring Boot project with
  a `UserController` carrying a known flaw.
- **Integration test:** run the loop against the fixture; assert the reviewer
  produced ≥1 finding, the final diff contains `@Valid`, and the build passes.
- **Learning test:** run the same task twice; assert a skill file exists after run 1
  and that run 2 retrieves it. This is the test that proves the central claim.
- **Steering test:** reject Gate 1 with a reason; assert a `Finding(HUMAN, …)` is
  appended, the coder runs again, and `humanIterations` — not the reviewer cap —
  is what increments.
- **Agent unit tests** use a stubbed `ChatClient`, so the bulk of the suite costs
  nothing and runs in CI without an API key.
- **Routing eval harness:** N labelled tasks, assert the router picks the correct
  agent sequence; report accuracy. This puts the evaluation harness from the
  original concept's portfolio section into actual scope.

---

## 11. Known limitations and upgrade paths

Stated deliberately — each is a defensible trade with a known next step.

| Limitation | Why it's acceptable | Upgrade path |
|---|---|---|
| Run state is in-memory; a restart loses an in-flight run | Runs are minutes, single user | Persist `RunState`; adopt a durable workflow engine |
| Skill retrieval is LLM-pick, not semantic | Sufficient for dozens of skills | Embed into Spring AI `VectorStore` behind the existing `SkillStore` interface |
| No container isolation around builds | Gate 2 + temp dir is proportionate for local single-user | Run builds in a container with no network and a CPU/memory cap |
| Public repos only | Auth adds no value to the thesis | Deploy keys or a GitHub App |
| Single node, no queue | One user, one run at a time | Queue + worker pool |

---

## 12. Ship order

Deterministic parts first; LLM-dependent parts once the ground is solid. Every
phase ends in something demonstrable.

| Phase | Deliverable | Proves |
|---|---|---|
| **0** | Repo init, Spring Boot skeleton, JDK on `PATH`, **the §7.1 spike** | The stack talks to Opus 5 correctly |
| **1** | `Workspace` + fixture + `FileTools`/`GitTools`/`BuildTools` | Tools work, fully unit-tested, no LLM involved |
| **2** | `CoderAgent` end to end | A real branch from a real task |
| **3** | `ReviewerAgent` + bounded review loop | The core agent system |
| **4** | SSE stream + web page + approval gates | It feels real, not a black box |
| **5** | Skills + memory + `ScribeAgent` | It learns |
| **6** | `ClonedWorkspace` | An interviewer can hand it a URL |
| **7** | Planner, test-writer, doc-writer | The full crew |

Phases 0–3 constitute a complete, defensible agent system. Phase 5 is what makes
it distinctive. Phases 6–7 are upside.

The original concept's scope-discipline warning stands and is the reason for this
ordering: building all six agents before the loop works is the usual trap.
