# Operator UI Overhaul — Design

## Product surface

The operator page is a working surface for starting, monitoring, approving,
and inspecting SDLC runs. The first viewport puts run setup beside live
execution state; it does not use a hero, promotional copy, or decorative
imagery.

## Visual direction

The interface uses a calm operations-console treatment: structured panels,
high-contrast status signals, restrained blue/teal accents, and compact
metadata. Typography and spacing make active work legible before visual
decoration. The UI remains a no-framework, responsive HTML/CSS/JavaScript
surface and respects light and dark system themes.

## Information hierarchy

1. Run setup and primary action.
2. Current run status, lifecycle groups, and delivery facts.
3. Live event stream and the approval decision when a gate is pending.
4. Historical runs and their read-only audit timelines.

The design only presents data the current backend actually supplies. It does
not add placeholder charts, fake model costs, tool logs, or artifact previews.
Those belong to future backend slices once their data exists.

## Interaction and accessibility

- Preserve all existing start, SSE, approval, history, and audit endpoints.
- Use semantic form controls, visible focus treatment, text status in addition
  to color, and text-safe DOM rendering for operator and server data.
- Collapse multi-column layouts into a single column on narrow screens without
  hiding any workflow action.
