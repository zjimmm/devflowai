# Staging Smoke Testing Design

**Goal:** prove basic staging behavior before an operator can approve production release.

## Lifecycle

- Smoke tests run only after a configured staging workflow verifies as `PASSED`.
- Every configured endpoint receives one timeout-bounded HTTPS `GET` request.
- All endpoints must return `2xx` before the `BEFORE_RELEASE` production approval gate appears.
- A failed, unavailable, or blocked smoke test prevents production workflow dispatch.

## Configuration

```yaml
devflowai:
  smoke:
    urls: ${DEVFLOWAI_SMOKE_URLS:}
    timeout-seconds: ${DEVFLOWAI_SMOKE_TIMEOUT_SECONDS:10}
```

`DEVFLOWAI_SMOKE_URLS` is a comma-separated list. Leaving it blank disables smoke testing and preserves the existing staging/release behavior.

## Safety Rules

- Endpoints are instance configuration, never run-request or prompt content.
- Every endpoint must be an absolute `https://` URL without credentials, query parameters, or fragments.
- Redirects are not followed, endpoints are deduplicated, and requests run sequentially with a per-request timeout.
- Smoke-test failure blocks production but does not invoke the production rollback workflow because production has not been dispatched.

## Non-Goals

- Executing arbitrary repository scripts or shell commands.
- Assertions against response bodies, authentication flows, or multi-step transactions.
- Load, performance, contract, log, or error-rate testing.
