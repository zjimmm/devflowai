# Sub-project 5 (part 1): PR Creation — Design

## 1. What it is

Right now devflowai's output stops at "a local git branch with commits" —
`AbstractGitWorkspace.cleanup()`, called unconditionally on every exit path
per an existing non-negotiable, deletes the entire temp workspace the moment
a run ends. Nothing survives except the audit-trail database rows and
whatever the operator watched live over SSE. Even a fully approved,
committed `ClonedWorkspace` run's branch is permanently destroyed.

This sub-project closes that gap: an operator can opt a `ClonedWorkspace` run
into pushing its branch to the real remote and opening a real GitHub pull
request, so the work actually lands somewhere durable.

This is a deliberate narrowing of the PRD's Phase 4 (§12.13–12.16), which
bundles four separable things: PR Creation (§12.13), CI/CD Validation
(§12.14), Deployment/Release Approval (§12.15), and Post-Deployment
Verification (§12.16). Only §12.13 is built here. §12.14 (watching GitHub
Actions checks on the opened PR) is a natural follow-on sub-project once PR
creation exists — it needs its own design, not a rider on this one. §12.15
and §12.16 assume a real staging/production deployment target, which
conflicts with this project's stated single-user/local/non-hardened design
scope (spec §11) and has no obvious shape for this tool yet; they are not
scoped into any sub-project until that changes.

## 2. Goals

- After a successful, approved commit on a `ClonedWorkspace` run, an operator
  who opted in gets a real pushed branch and a real GitHub PR, with a link
  back in the UI.
- Works identically for both run strategies (Orchestrated and Direct) — the
  opt-in is decided before the run starts, not a new mid-run gate.
- PR content (title, summary, testing, review, security sections) is
  assembled from data the run already produced — no new LLM call.
- A push failure is loud and unambiguous about what was lost. A PR-creation
  failure, once the push already succeeded, degrades gracefully — nothing is
  lost, the operator can open the PR by hand.

## 3. Non-goals

- CI/CD status polling or remediation (§12.14) — future sub-project.
- Deployment, staging, release approval, post-deployment verification
  (§12.15, §12.16) — no target environment defined for this tool; out of
  scope until one is.
- Any non-GitHub forge (GitLab, Bitbucket, etc.) — `openPr: true` against a
  non-GitHub `https://` URL is a clear rejection, not a best-effort attempt.
- Automatic/default-on PR creation — this is opt-in per run, never inferred.
- A retry loop or its own review cycle for the push/PR step — one-shot,
  consistent with this being "close the loop," not a new orchestrated stage.
- GitHub App authentication — a personal access token via environment
  variable, matching the existing `ANTHROPIC_API_KEY` pattern.

## 4. Architecture

### 4.1 Request shape

`StartRunRequest` gains a fourth field: `Boolean openPr` (nullable, defaults
to `false`/absent — same nullable-with-default pattern `strategy` already
uses). `RunController` validates: `openPr == true` combined with
`repo == "fixture"` is rejected with a 400 ("Opening a PR requires a real
repository, not the bundled fixture"), the same shape used today for an
invalid repo URL.

### 4.2 Lifecycle placement

A new `RunPhase.OPENING_PR` value, inserted between `COMMITTING` and `DONE`
in the enum — surfaced identically to every other phase via the existing SSE
`emit()` mechanism, no new event type.

For `Orchestrator` (Strategy B): after the existing commit step (today the
last step before the `"done"` emit and `RunOutcome`), if `openPr` was
requested, the run transitions to `OPENING_PR`, pushes, and attempts PR
creation before emitting `"done"`.

For `DirectExecutor` (Strategy A): identical placement, after its own
unconditional commit step.

Neither executor's core loop changes shape — this is one more optional step
appended after the point each already reaches today, not a new gate and not
a change to either's existing bounded-iteration or empty-changeset logic.

### 4.3 `GitHubClient` — the provider-neutral seam

A new interface, `ai.devflow.tools.GitHubClient`, mirroring how
`CodingWorker` already isolates the LLM provider from the agents that use
it:

```java
public interface GitHubClient {
    void push(Workspace workspace, String branchName) throws GitHubClientException;
    PullRequestResult openPullRequest(String repoUrl, String branchName, String title, String body)
            throws GitHubClientException;
}

public record PullRequestResult(String url, int number) {}

public class GitHubClientException extends Exception {
    public GitHubClientException(String message, Throwable cause) { super(message, cause); }
    public GitHubClientException(String message) { super(message); }
}
```

`GitHubApiClient` is the one production implementation:

- `push(...)` opens the workspace's repo via JGit (matching the pattern
  `GitTools` already uses — `Git.open(workspace.root().toFile())`) and calls
  `git.push()` with
  `.setCredentialsProvider(new UsernamePasswordCredentialsProvider("x-access-token", token))`,
  pushing `branchName` to `origin`.
- `openPullRequest(...)` parses `owner`/`repo` from the same `https://` URL
  `ClonedWorkspace` already validated (reusing the existing
  `RunRegistry.normalizeRepoUrl`-style stripping, not a new parser), fetches
  the repo's `default_branch` via `GET /repos/{owner}/{repo}`, then calls
  `POST /repos/{owner}/{repo}/pulls` with `{title, body, head: branchName,
  base: default_branch}` using a `RestClient` bean, and returns the response's
  `html_url`/`number`.

The token is read once at startup from `DEVFLOWAI_GITHUB_TOKEN` (an
environment variable, following `ANTHROPIC_API_KEY`'s exact pattern — never
a tracked file, this repo is public) and injected into `GitHubApiClient`'s
constructor. If the variable is absent, `GitHubApiClient` still constructs
(no crash at startup, matching how a missing Anthropic key doesn't block
`bootRun` either) but every call fails fast with a clear
`GitHubClientException("DEVFLOWAI_GITHUB_TOKEN is not set")`.

### 4.4 PR content assembly

A new, small, pure-function class, `ai.devflow.orchestrator.PullRequestContent`,
takes a finished `RunState` and a `RunStrategy` and returns a `{title, body}`
pair. All inputs already exist on `RunState` — no new LLM call:

- **Title**: `state.task()`, first line only, capped at 72 characters (GitHub
  itself doesn't hard-limit titles, but a clean cap avoids an ugly wrapped
  title in list views).
- **Summary**: the last `AgentResult` in `state.history()` whose agent name
  is `"coder"` — its `.summary()` field, verbatim.
- **Testing**: derived from the last `"success"`-keyed build event data this
  run emitted — rendered as `"Build passed."` or `"Build failed."` plus the
  build's own (already 4KB-truncated, per Sub-project 2) output tail, exactly
  the same text already attached to a policy-failure `Finding`.
- **AI Review** (Orchestrated only; omitted entirely for Direct, since there
  was no review): a count of `state.allFindings()` filtered to
  `Finding.Origin.REVIEWER` — `"N finding(s) identified and resolved."`, or
  omitted if zero.
- **Security**: a count of `state.allFindings()` filtered to
  `Finding.Origin.POLICY` — `"No policy violations."` if zero, otherwise a
  one-line list of what was flagged.
- A closing line: `"devflowai run: {runId}"` (plain text, not a link — there
  is no public URL for the local audit trail a remote PR viewer could open).

### 4.5 Failure semantics

**Push failure** (network error, stale ref, auth failure): the run ends
`FAILED`. Critically, the local commit already exists in the workspace,
which `cleanUp()` (in the existing `finally` block, per the non-negotiable
that cleanup runs on every exit path) is about to delete — so a push failure
after a successful commit is a real, irreversible loss of already-approved
work, not just a normal failed run. The emitted `"error"` event's message
says so explicitly: `"Push failed: {cause}. The commit was made locally but
never reached the remote — it is now lost."` This is the one failure mode in
this codebase that follows an already-irreversible action (the commit), so
it gets a message that says so plainly rather than reusing a generic
failure-reason string.

**PR-creation failure** (bad token, API error, insufficient permissions),
once the push already succeeded: the run still ends `DONE` — nothing was
lost, since the branch is safely on the remote regardless. The `"done"`
event's data simply omits `prUrl`. A separate `"warn"` event is emitted
alongside it: `"Branch pushed, but opening the PR failed: {cause}. Open it
manually from {branchName}."`

**Success**: the `"done"` event's data gains a `prUrl` field
(`Map.of(..., "prUrl", result.url())`).

### 4.6 UI

The operator page gains one checkbox on the start form, alongside the
existing Strategy dropdown: `☐ Open a pull request when done` (disabled/
hidden when the fixture repo is selected, matching the existing pattern
where the repo-URL field already interacts with the repo dropdown). On a
`"done"` event whose data includes `prUrl`, the log line renders a real
`<a href>` link instead of plain text — the one place this page ever renders
a link from run data, so it uses `textContent` for the link's label and only
the `href` attribute (never `innerHTML`) to stay consistent with the
established XSS-safety precedent, since `prUrl` originates from GitHub's own
API response, not free-text input, but the precedent is "never trust
interpolated data into innerHTML" regardless of source.

## 5. What gets persisted

`SdlcRun` gains one new nullable column: `String prUrl`. `SdlcRunRecorder`
gains a `maybeRecordPrUrl` handler reading the `"done"` event's `prUrl` key,
following the exact same pattern `maybeRecordBuildResult` already
established in Sub-project 3. `RunSummary` surfaces it. The past-runs table
gains one more column showing a link when present, blank otherwise — same
`document.createElement`/`textContent`-then-set-`href` pattern as the log
line above.

No new entity, no new endpoint — this is the same "extend what already
exists" shape every prior sub-project's persistence work has taken.

## 6. Testing

Following the existing live-test pattern (`AnthropicSpikeTest`,
`CoderAgentLiveTest`, `EndToEndLiveTest` — real network calls, `@Tag("live")`,
gated on an environment variable, skipped by default):

- `GitHubClient` is stubbed (a hand-written fake or Mockito mock) in every
  ordinary test — `OrchestratorTest`, `DirectExecutorTest`, and a new
  `PullRequestContentTest` for the body-assembly logic, none of which ever
  touch a real network.
- One new gated live test, `GitHubIntegrationLiveTest`, tagged `@Tag("live")`
  and skipped unless both `ANTHROPIC_API_KEY` and a new
  `DEVFLOWAI_LIVETEST_REPO` environment variable are set. `DEVFLOWAI_LIVETEST_REPO`
  points at a small, disposable, real GitHub repo set up specifically for
  this (not devflowai's own repo, to avoid cluttering it with test branches
  and PRs). The test runs a Direct-strategy `ClonedWorkspace` run against
  that repo with `openPr: true` end-to-end and asserts a real PR was opened.
- Push-failure and PR-failure branching (§4.5) get ordinary unit tests
  against the stubbed `GitHubClient` throwing `GitHubClientException` from
  each method independently — no network needed to prove the branching logic
  is correct.

## 7. Design decisions made explicitly

- **`GitHubClient` lives in `ai.devflow.tools`**, alongside `GitTools`/
  `BuildTools`/`FileTools` — it's a tool in the same sense those are:
  imperative, non-agent-callable (like `GitTools.commit()`, this is
  Java-only, invoked by the orchestrator/executor, never an `@Tool` an LLM
  could call — pushing and opening a PR are exactly the kind of
  irreversible, externally-visible action this codebase already keeps
  behind human-gated Java code, not agent discretion).
- **One PR content assembler, not two.** Rather than special-casing
  Orchestrated vs. Direct as separate code paths, `PullRequestContent`
  takes the `RunStrategy` as a parameter and conditionally includes the
  "AI Review" section — keeping the two strategies' PR bodies structurally
  identical except for the one section that's genuinely strategy-dependent
  (there is no review to report for Direct).
- **The push step is not wrapped in the same private `emit`/`cleanUp`
  helper-sharing debate Sub-project 3 already had between `Orchestrator`
  and `DirectExecutor`.** Both executors call the same `GitHubClient`
  directly and build their own emit calls with their own existing `emit()`
  helpers — no new shared class between the two executors, consistent with
  that prior decision to keep them independent until a third executor
  justifies an abstraction.

## 8. Ship order

1. `GitHubClient` interface + `GitHubApiClient` implementation + `PullRequestResult`/`GitHubClientException` + unit tests against a stub.
2. `PullRequestContent` (PR body/title assembly) + unit tests.
3. `RunPhase.OPENING_PR` + `Orchestrator` and `DirectExecutor` wiring (push → PR → done/warn/error branching per §4.5) + `StartRunRequest`/`RunController` validation (`openPr` + fixture rejection) + `OrchestrationConfig`'s new `GitHubClient` bean.
4. `SdlcRun`/`SdlcRunRecorder`/`RunSummary` gain `prUrl` + UI checkbox + PR-link rendering in the log and past-runs table.
5. The gated `GitHubIntegrationLiveTest` (requires `DEVFLOWAI_LIVETEST_REPO` to be set up first, outside this codebase).
