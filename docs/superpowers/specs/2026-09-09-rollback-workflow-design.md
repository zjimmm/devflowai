# Human-Approved Rollback Workflow Design

**Goal:** provide a safe response when release-workflow verification reports a failed deployment.

## Scope

- Dispatch a configured GitHub Actions rollback workflow only after deployment verification returns `FAILED`.
- Pause at a `BEFORE_ROLLBACK` approval gate before any rollback dispatch.
- Preserve the release result and record rollback state independently in events, run history, and the dashboard.

## Safety Rules

- Never roll back for `PENDING`, `PASSED`, `TIMED_OUT`, or `UNAVAILABLE` verification states.
- Never dispatch without a nonblank `DEVFLOWAI_ROLLBACK_WORKFLOW` configuration and operator approval.
- When no rollback workflow is configured, emit `UNAVAILABLE` and direct the operator to intervene manually.
- When the operator declines, record `SKIPPED`; when dispatch fails, record `FAILED`.

## Configuration

```yaml
devflowai:
  rollback:
    workflow: ${DEVFLOWAI_ROLLBACK_WORKFLOW:}
    ref: ${DEVFLOWAI_ROLLBACK_REF:main}
```

The configured workflow receives the repository and ref through the existing GitHub Actions workflow-dispatch client. It is the workflow owner's responsibility to define the actual rollback steps.

## Non-Goals

- Automatically selecting a prior deployment version.
- Automatically cancelling, reverting, or observing the rollback workflow.
- Treating verification timeouts or unavailable GitHub data as proof of a failed deployment.
