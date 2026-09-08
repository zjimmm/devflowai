# GitHub Release Dispatch Implementation Plan

1. Add release request/state/status types and the GitHub PR-merged plus
   workflow-dispatch API seams.
2. Add the configuration-backed GitHub Actions release dispatcher, disabled
   by default.
3. Add `BEFORE_RELEASE`, merged-PR verification, and dispatch routing to both
   execution strategies after a passed CI result.
4. Persist and render release status/Actions URL; validate the new request
   flag in the controller and operator page.
5. Add unit/controller/gated-live coverage and run the full default suite.

## Safety constraints

- Never merge a PR or dispatch a workflow by default.
- Require a real GitHub PR, passed CI, explicit release selection, explicit
  final approval, and a configured workflow before a dispatch.
- Never send workflow inputs, secrets, or task text to GitHub Actions.
- Treat a blocked, skipped, or failed release dispatch as a release outcome;
  it cannot undo the already-created commit or pull request.
