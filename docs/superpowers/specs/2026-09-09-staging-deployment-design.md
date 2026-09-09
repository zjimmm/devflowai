# Staging Deployment Integration Design

**Goal:** place a verified staging deployment between a merged pull request and production release approval.

## Lifecycle

When `DEVFLOWAI_STAGING_WORKFLOW` is configured for a release-enabled run:

1. DevFlowAI pauses at `BEFORE_STAGING` so the operator can merge the pull request and approve staging dispatch.
2. It confirms the pull request is merged, then dispatches the configured GitHub Actions staging workflow.
3. It observes that workflow with the existing bounded workflow-run observer.
4. Only a `PASSED` staging verification opens the existing `BEFORE_RELEASE` production approval gate.
5. Rejected, blocked, failed, timed-out, or unavailable staging prevents production workflow dispatch.

When staging is not configured, the existing release flow remains unchanged.

## Configuration

```yaml
devflowai:
  staging:
    workflow: ${DEVFLOWAI_STAGING_WORKFLOW:}
    ref: ${DEVFLOWAI_STAGING_REF:main}
```

The configured repository-owned workflow determines how staging is deployed. DevFlowAI sends no workflow inputs, secrets, or task content.

## Safety Rules

- Staging is disabled by default.
- A real GitHub pull request, passed CI, merged state, and explicit staging approval are required.
- Production release remains independently approval-gated after staging passes.
- Staging failure never falls through to production and never triggers a production rollback workflow.

## Non-Goals

- Provisioning staging infrastructure.
- Selecting environment-specific secrets or workflow inputs.
- Running smoke tests inside DevFlowAI; that is the next delivery-lifecycle increment.
