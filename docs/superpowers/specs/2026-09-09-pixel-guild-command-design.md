# Pixel Guild Command Design

## Intent

Turn the operator UI into an original pixel-art command center that makes DevFlowAI's governed delivery workflow feel tangible without disguising operational data as a game simulation.

## Visual system

- Muted stone-green panels, beveled light borders, painted portrait tiles, and restrained success, warning, and failure accents evoke a classic JRPG party-status menu without copying Final Fantasy IX art, characters, or interface assets.
- The six original generated characters are generic fantasy job archetypes, mapped to real DevFlowAI responsibilities: Priest/Planner, Knight/Coder, Wizard/Reviewer, Paladin/Quality Gate, Ranger/Delivery Scout, and Alchemist/Scribe.

## Information hierarchy

1. The party roster leads the page, with one dense portrait-led row per real workflow specialist.
2. Lifecycle progress and Build/CI/Delivery facts remain primary operational information.
3. The party roster reflects the active phase; it does not invent workload, success, or model activity.
4. Signal feed, approval command seal, chronicle, and immutable record preserve the existing control-plane functions.

## Interaction and accessibility

- The left rail contains in-page anchors, not fake product navigation.
- Forms, live regions, SSE controls, audit history, and keyboard focus behavior are retained.
- The layout becomes one column on small screens and preserves readable 16px body text.
