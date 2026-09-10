# Requirement Analysis Design

**Goal:** turn an informal task into explicit, testable requirements before repository planning or code changes begin.

## Lifecycle

- A dedicated Requirement Analyst runs after `PRE_FLIGHT` approval and before the Planner.
- It produces functional requirements, assumptions, edge cases, acceptance criteria, and missing information as validated JSON.
- The analysis is stored on `RunState`, counted in run token usage, emitted to the audit stream, and supplied to the Planner.
- The Planner remains a separate read-only role and must produce a plan that satisfies every acceptance criterion.

## Clarification Gate

- The analyst marks only consequential ambiguity as `clarificationRequired` and supplies one focused question.
- DevFlowAI pauses at `REQUIREMENTS_CLARIFICATION` before planning or coding.
- Approving accepts the documented assumptions and continues.
- Rejecting with guidance records the operator's clarification and re-runs analysis; rejecting without guidance aborts.
- Clarification is bounded to three re-analysis rounds after the initial analysis and fails closed if it does not converge.

## Persistence And UI

- Run history stores `requirementStatus` and `acceptanceCriteriaCount` for a compact durable summary.
- Full structured output remains in immutable run audit events.
- The operator UI renders requirement and acceptance-criteria details, clarification guidance, lifecycle phases, and the history summary.

## Safety Rules

- The analyst receives no workspace tools and cannot inspect or modify repository files.
- Required functional requirements and acceptance criteria must be non-empty; malformed model output fails the run.
- The original task is preserved and included in every analysis round.
- The direct execution strategy remains a deliberately unstructured baseline and skips this stage.

## Non-Goals

- Repository-aware architecture planning; that remains the Planner's responsibility.
- Automated product decisions when material information is missing.
- Replacing deterministic build, review, policy, staging, or release gates.
