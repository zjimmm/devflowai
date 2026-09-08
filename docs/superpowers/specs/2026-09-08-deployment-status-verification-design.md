# Sub-project 5 (part 4): Deployment Status Verification — Design

## 1. What it is

This slice advances PRD §12.16 by observing the specific GitHub Actions run
that DevFlowAI dispatched for an approved release. It records whether GitHub
reports that run as passed, failed, timed out, or unavailable. It does not
invent HTTP health endpoints or smoke commands because no deployment target
or probe URL is configured.

## 2. Goals

- Poll only the workflow-run ID returned by an approved GitHub release
  dispatch; never search for or infer another run.
- Keep observation read-only, bounded, and separate from CI and release
  dispatch status.
- Report `PENDING`, `PASSED`, `FAILED`, `TIMED_OUT`, or `UNAVAILABLE` through
  SSE and persisted history.
- Treat a failed or unavailable verification as a completed delivery
  observation, not a reason to undo the merged PR or dispatched workflow.

## 3. Non-goals

- Health endpoint, smoke-test, API-contract, log, or error-rate probes.
- Cancelling, rerunning, or modifying the dispatched workflow.
- Automatically rolling back a failed deployment.
- Treating an Actions success result as proof of application health beyond
  what the repository-owned workflow itself verifies.

## 4. Architecture

`GitHubClient.getWorkflowRun(repoUrl, runId)` reads
`GET /repos/{owner}/{repo}/actions/runs/{run_id}` and returns the workflow
run's status, conclusion, and safe HTML URL. A `ReleaseObserver` polls that
single run using the existing five-second interval default and a separate
20-minute verification timeout.

`ReleaseCoordinator` begins verification only after a successful dispatch
with a returned workflow-run ID. A non-completed run is `PENDING`; completed
`success`, `neutral`, or `skipped` is `PASSED`; every other completion is
`FAILED`. API, parsing, or interruption failures are `UNAVAILABLE`.

`SdlcRun.verificationStatus` is nullable and independent from `ciStatus` and
`releaseStatus`. The Actions run URL is reused as the release link. The
operator page adds a Verification history column and renders only text and
safe URLs from SSE data.

## 5. Verification

- Unit tests cover response parsing, classification, timeout, unavailable
  reads, and release dispatch paths in both executor strategies.
- Recorder/controller/UI tests prove verification status reaches history.
- The existing environment-gated GitHub release-dispatch test also exercises
  production observation when a disposable workflow is configured.

## 6. Source

[GitHub's workflow-run API](https://docs.github.com/en/rest/actions/workflow-runs?apiVersion=2022-11-28#get-a-workflow-run)
documents fetching one workflow run by ID and its Actions read permission.
