# Operational Health Checks Design

**Goal:** verify that a deployed service answers a configured health endpoint after its release workflow succeeds.

## Scope

- Run one configured HTTP `GET` health check only after the release workflow verifies as `PASSED`.
- Treat a `2xx` response as `PASSED`, any other HTTP response as `FAILED`, and transport, timeout, or interruption errors as `UNAVAILABLE`.
- Stream and persist the health result and endpoint alongside independent release, workflow-verification, and rollback outcomes.
- Route a failed health check through the existing human-approved rollback gate. An unavailable health check never triggers rollback.

## Safety Rules

- The endpoint is instance configuration, never a value supplied in a run request or task prompt.
- It must be an absolute `https://` URL with no embedded credentials, query, or fragment.
- Requests are bounded by a configurable timeout and do not follow redirects.
- Only one `GET` request is made; no smoke-test scripts, arbitrary probes, retries, or remediation run in this increment.

## Configuration

```yaml
devflowai:
  health:
    url: ${DEVFLOWAI_HEALTH_URL:}
    timeout-seconds: ${DEVFLOWAI_HEALTH_TIMEOUT_SECONDS:10}
```

Leaving `DEVFLOWAI_HEALTH_URL` blank disables the check. Its absence does not change a successful release outcome.

## Non-Goals

- Repository-defined health endpoints or user-provided URLs.
- Content/body assertions, API contract checks, log inspection, or error-rate analysis.
- Automatic rollback, rollback retries, or automatic recovery from an unavailable endpoint.
