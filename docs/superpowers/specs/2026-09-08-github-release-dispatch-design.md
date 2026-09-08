# Sub-project 5 (part 3): GitHub Release Dispatch — Design

## 1. What it is

This slice begins PRD §12.15 with an opt-in, approval-gated GitHub Actions
release dispatch. It never merges a pull request, creates a deployment
environment, or infers a production action. Instead, a repository owner
configures an existing `workflow_dispatch` workflow that embodies its own
staging/production policy. DevFlowAI verifies that its pull request has been
merged and asks the operator for a final approval before dispatching it.

## 2. Goals

- Keep releases opt-in per run through a new `release` request field and UI
  checkbox. A release always requires `openPr`.
- Dispatch only when the run's remote CI aggregate is `PASSED`, the created
  pull request is confirmed merged, and the operator approves `BEFORE_RELEASE`.
- Send the configured workflow file name and release ref without workflow
  inputs, allowing repository-owned defaults and environments to govern the
  deployment.
- Persist a nullable release status and the returned Actions-run URL when
  available; surface both through SSE and history.
- Finish the DevFlowAI run after dispatch. Observing deployment completion,
  smoke tests, and rollback remain PRD §12.16/future work.

## 3. Non-goals

- Merging pull requests, changing branch protection, or bypassing reviews.
- Dispatching any workflow unless `devflowai.release.workflow` is configured
  and the operator selected release for this run.
- Supplying arbitrary workflow inputs, executing local shell deployment
  commands, or constructing a deploy command from task text.
- Polling Actions deployment completion, verification, remediation, or
  rollback.

## 4. Architecture

### 4.1 Request and lifecycle

`StartRunRequest.release` defaults to false. The controller rejects a release
request without `openPr` or without a configured release workflow. `RunState`
retains this immutable request flag.

After PR creation and a `PASSED` CI result, either executor enters
`WAITING_FOR_RELEASE_APPROVAL` and publishes the `BEFORE_RELEASE` gate. The
operator is instructed to merge the PR through GitHub's ordinary governance
controls before approving. A rejection or timeout records `SKIPPED`; a
non-passing CI result or an unmerged PR records `BLOCKED`; a dispatch API
failure records `FAILED`. These are release outcomes, not failures of the
already-created PR run.

### 4.2 GitHub Actions adapter

`GitHubClient` gains two narrow provider calls:

```java
boolean isPullRequestMerged(String repoUrl, int pullRequestNumber);
WorkflowDispatchResult dispatchWorkflow(String repoUrl, String workflow, String ref);
```

The production client reads the pull request before release and sends
`POST /repos/{owner}/{repo}/actions/workflows/{workflow}/dispatches` with
`ref` and `return_run_details: true`. It forwards no inputs. GitHub Actions
is responsible for the workflow's target environment, required reviewers,
and secrets. The token needs GitHub Actions write permission for dispatch.

`GitHubActionsReleaseDispatcher` owns the configuration boundary:

- `devflowai.release.workflow` is empty by default, disabling releases.
- `devflowai.release.ref` defaults to `main` and is the branch/tag from which
  the configured release workflow runs.

### 4.3 Audit trail and UI

`SdlcRun` gains nullable `releaseStatus` and `releaseUrl`; `RunSummary` and
the history table expose both. The live SSE events contain only a status,
workflow file, ref, PR URL, and Actions run URL—never token values, workflow
inputs, or deployment logs.

## 5. Verification

- Unit tests cover GitHub response parsing, dispatch configuration, successful
  dispatch, rejected approval, unmerged PR, and failed CI for both executors.
- Controller/history/UI tests cover release input validation and persistence.
- A gated live test requires a disposable repository with a merged test PR,
  a `workflow_dispatch` release workflow, and a token with Actions write
  permission. It is excluded from the default suite.

## 6. Source

[GitHub's workflow-dispatch endpoint](https://docs.github.com/en/rest/actions/workflows?apiVersion=2022-11-28#create-a-workflow-dispatch-event)
documents the required `ref`, optional inputs, returned workflow-run details,
and Actions write permission.
