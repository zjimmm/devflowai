# CI Validation Implementation Plan

## Goal

Observe GitHub checks after DevFlowAI opens a pull request, report a bounded
aggregate result through SSE and history, and never trigger remediation or
deployment automatically.

## Tasks

1. Add CI value types and the GitHub Checks API read method with parser tests.
2. Add the bounded `CiObserver` polling service and deterministic unit tests.
3. Wire `VALIDATING_CI` into both executors after successful PR creation;
   preserve `DONE` on failed, timed-out, or unavailable CI observation.
4. Persist `ciStatus`, expose it through history, and render live/history UI
   data safely.
5. Add integration and gated-live coverage; run focused then full tests.

## Global constraints

- GitHub-only and read-only; reuse `DEVFLOWAI_GITHUB_TOKEN` without logging it.
- Poll only after a real PR opens; do not alter non-PR runs.
- Default poll interval: five seconds. Default timeout: 15 minutes.
- Remote CI is distinct from the local build and never changes an already
  committed run into `FAILED`.
- No webhook receiver, CI retry/remediation, deployment, or release logic.
- Every browser-rendered CI string uses `textContent`; links assign only
  `href`, `target`, and `rel`.
