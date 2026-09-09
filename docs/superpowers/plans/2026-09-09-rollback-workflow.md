# Human-Approved Rollback Workflow Plan

1. Add rollback workflow configuration and a dispatcher backed by GitHub Actions.
2. After a failed deployment verification, require a separate `BEFORE_ROLLBACK` decision before dispatching.
3. Persist and stream rollback status and workflow URL without changing the original release outcome.
4. Surface rollback state in the operations dashboard and cover approval, dispatch, and history behavior with focused tests.
