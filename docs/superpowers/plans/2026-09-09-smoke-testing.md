# Staging Smoke Testing Plan

1. Extract the safe HTTPS probe shared by health and smoke checks.
2. Add configured multi-endpoint smoke execution after successful staging verification.
3. Block production approval unless every configured endpoint passes.
4. Persist and render smoke status and endpoint links with focused coordinator, configuration, history, and UI tests.
