# Sub-project 5 (part 2): CI Validation — Design

## 1. What it is

PR creation now leaves a durable GitHub branch and pull request, but a run
finishes before GitHub Actions reports whether that branch is healthy. This
sub-project observes the configured GitHub checks after a DevFlowAI-created
PR opens, streams their result to the operator, and records the final
aggregate separately from the local build result.

It completes PRD §12.14 only. Deployment, release approval,
post-deployment verification, webhooks, and automatic remediation stay out
of scope.

## 2. Goals

- Observe GitHub check runs for the pushed DevFlowAI branch after a PR opens.
- Keep the SSE stream and in-flight run alive until checks reach a terminal
  aggregate result or a bounded timeout.
- Persist a nullable CI aggregate (`PENDING`, `PASSED`, `FAILED`,
  `TIMED_OUT`, or `UNAVAILABLE`) independently of `buildSucceeded`, which
  records a local build only.
- Show the aggregate in the live log and the past-runs table.
- Work for both Orchestrated and Direct strategies without adding an LLM
  call, approval gate, retry loop, deployment action, or write to GitHub.

## 3. Non-goals

- Creating, modifying, rerunning, or cancelling GitHub checks.
- GitHub webhooks or background observation after the browser/run ends.
- Triggering a remediation run on CI failure.
- Observing legacy commit statuses, non-GitHub forges, deployment status, or
  release environments.
- Treating a failed or unavailable remote CI result as a failed DevFlowAI
  run: the approved commit and PR already exist safely on GitHub.

## 4. Architecture

### 4.1 GitHub read seam

`GitHubClient` gains a read-only method:

```java
List<CiCheck> listCiChecks(String repoUrl, String ref) throws GitHubClientException;
```

`GitHubApiClient` calls GitHub's documented
`GET /repos/{owner}/{repo}/commits/{ref}/check-runs?filter=latest&per_page=100`
endpoint. `ref` is the branch pushed for the run; GitHub supports a branch,
tag, or commit SHA for this endpoint. The existing `DEVFLOWAI_GITHUB_TOKEN`
is reused, but the read needs only GitHub's Checks read permission for a
fine-grained token. No token, malformed response, or HTTP error becomes a
`GitHubClientException`.

`CiCheck` carries only safe, operator-useful data: name, status, conclusion,
and details URL. No check output, secrets, or annotations enter SSE or the
database.

### 4.2 Bounded observation

A `CiObserver` abstraction lets executors depend on a one-shot observation
operation while tests supply an immediate deterministic fake. Its production
implementation polls `GitHubClient` at a configurable interval (default
five seconds) until all observed checks are completed or the configurable
timeout expires (default 15 minutes).

- No checks yet, or any check still queued/in progress: `PENDING`.
- At least one check and every check completed with a success-like
  conclusion (`success`, `neutral`, or `skipped`): `PASSED`.
- Once every observed check is complete, any failure-like conclusion
  (`failure`, `timed_out`, `cancelled`, `action_required`,
  `startup_failure`, or `stale`): `FAILED`.
- The deadline arrives while the aggregate remains `PENDING`:
  `TIMED_OUT`.

Each observation emits a `step` event containing the aggregate and a list
of check summaries. The terminal result is returned to the executor.

### 4.3 Lifecycle and failure semantics

`RunPhase.VALIDATING_CI` follows `OPENING_PR`. Only a successfully created
PR enters it; a PR creation failure already follows the existing
graceful-degradation path and does not attempt CI observation.

The executor waits synchronously so the existing run executor, SSE history,
cleanup, and `RunRegistry` lifecycle remain coherent. This is acceptable:
the run executor already intentionally blocks at human gates. On a terminal
CI result, the run emits its normal `done` event with `ciStatus`. A failed
CI check still ends DevFlowAI's run `DONE` because the system observed a
valid external result rather than failing to create the approved PR.

An API, parsing, timeout-interruption, or missing-token failure emits a
warning, records `UNAVAILABLE`, and then finishes `DONE`; the operator can
use the PR link to inspect GitHub manually. No automatic remediation occurs.

### 4.4 Audit trail and UI

`SdlcRun` gains a nullable `ciStatus`, populated by `SdlcRunRecorder` from
event data. `RunSummary` exposes it to `/api/runs/history`. This is distinct
from the existing nullable `buildSucceeded` value, which remains exclusively
about the local build.

The operator page renders check lines in the live log with `textContent` and
adds a CI column to history. A check's details URL is linked only via an
`href`; no URL, name, or conclusion is interpolated into `innerHTML`.

## 5. Verification

- Unit tests cover GitHub response parsing, aggregate classification,
  timeout/unavailable behavior, and both executor strategies.
- Controller/history and recorder tests prove `ciStatus` survives into the
  API/UI model.
- A gated live test against a disposable GitHub repository with a simple
  Actions workflow proves the production polling path. It is excluded from
  the default suite and requires the existing GitHub token plus a configured
  scratch repository.

## 6. Sources

[GitHub's official Checks API](https://docs.github.com/en/rest/checks/runs?apiVersion=2022-11-28)
documents listing check runs by branch, tag, or SHA and its read permission
requirements. GitHub's commit-status API is not included in this first slice;
it remains a follow-up if repositories relying only on legacy statuses need
support.
