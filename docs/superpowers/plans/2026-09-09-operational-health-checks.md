# Operational Health Checks Plan

1. Add a configuration-backed, HTTPS-only, timeout-bounded health observer that does not follow redirects.
2. Invoke it after successful release verification in both execution strategies.
3. Require the existing rollback approval if the configured health check fails; do nothing automatically for unavailable checks.
4. Persist, stream, and render health results, with focused unit, history, controller, and static-page coverage.
