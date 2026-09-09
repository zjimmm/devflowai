# Pixel Agent Operations Design

## Intent

Give the operator UI an original pixel-art identity while keeping it unmistakably useful as an engineering control surface.

## Visual system

- Deep indigo surfaces, crisp pixel frames, and a restrained cyan signal color establish the system identity without turning every component into decoration.
- The six original generated characters are generic fantasy job archetypes, mapped to real DevFlowAI responsibilities: Priest/Planner, Knight/Coder, Wizard/Reviewer, Paladin/Quality Gate, Ranger/Delivery, and Alchemist/Scribe.
- Human-readable system typography carries the working interface; monospaced display type is reserved for headings, states, and metadata.

## Information hierarchy

1. Run setup and current state remain together in the first viewport.
2. Lifecycle progress and Build/CI/Delivery facts remain primary operational information.
3. The agent party reflects the active phase; it does not invent workload, success, or model activity.
4. Activity, approvals, history, and audit retain plain engineering language and preserve the existing control-plane functions.

## Interaction and accessibility

- The left rail contains in-page anchors, not fake product navigation.
- Forms, live regions, SSE controls, audit history, and keyboard focus behavior are retained.
- The layout becomes one column on small screens and preserves readable 16px body text.
