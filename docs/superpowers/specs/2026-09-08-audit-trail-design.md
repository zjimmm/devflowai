# Audit Trail — Design

## Scope

This slice advances PRD §20 by preserving an immutable, chronological audit
timeline for each SDLC run. It records every emitted run event and every
accepted human approval, then provides a read-only timeline endpoint and
operator-page view.

## Recorded data

Each `RunAuditEntry` stores the run ID, actor (`SYSTEM` or `OPERATOR`), action,
stage or gate, message, timestamp, and bounded JSON metadata. System entries
come from `RunRecorded`; operator entries come from `ApprovalRecorded` after a
gate decision is accepted.

The metadata is capped at 8,000 characters. If an event payload is larger, the
entry records that it was truncated and retains only its keys. This avoids
storing unbounded task documents, raw build output, or diffs while preserving
the event's lifecycle context.

An audit serialization or write failure is isolated from the existing run and
history recorders, so an optional audit defect cannot interrupt a live run.

## API and UI

`GET /api/runs/{runId}/audit` returns chronological, presentation-safe summary
records for a live or persisted run. The operator history table has a View
control that loads these records into a text-only audit timeline.

## Non-goals

- Retroactively reconstructing events from existing run rows.
- Adding tool-command capture; the current tool layer does not yet emit
  structured execution records.
- Adding any mutation or deletion API for audit entries.
