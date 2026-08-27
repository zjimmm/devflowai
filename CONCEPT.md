# Bayanihan — AI Software Development Workflow

> **Working name.** *Bayanihan* is the Filipino tradition of a community coming together
> to carry a shared load. It fits: this project is a crew of specialized AI agents that
> collaborate to carry a single development task from request to finished code.

---

## The idea in one sentence

An **orchestrator** takes a plain-language development task, routes it through a crew of
**specialized AI agents** (plan → code → review → test → document), and returns a real,
inspectable result — a git branch or diff — not just chat output.

## The problem it addresses

A single LLM asked to "add validation to this controller and test it" tries to do
everything in one context and does each part worse. Real development is a pipeline of
distinct skills: planning, writing code, critiquing it, testing it, documenting it.
This project models that pipeline as separate agents, each focused on one job, coordinated
by an orchestrator — the same way a team divides work.

## What it does (example)

**Input:** *"Add input validation to UserController and cover it with tests."*

1. **Orchestrator** classifies the task and decides which agents run, in what order.
2. **Planner** breaks it into concrete steps.
3. **Coder** implements the changes (has file, git, and build tools).
4. **Reviewer** critiques quality and security; can send work back to the Coder.
5. **Test-writer** generates and runs tests.
6. **Doc-writer** updates the docs.

**Output:** a git branch with the changes, plus a short summary of what each agent did.

## How the user interacts with it

A simple web page: the user types the task, clicks Run, and watches each agent's step
stream in live (via Server-Sent Events). Before anything risky — committing files, running
the build — the workflow **pauses for approval**, so the user stays in control. The live
streaming and the approval gate are what make an agent system feel real instead of a
black box.

## The core design ideas

- **Orchestration over a single mega-prompt.** A router decides which agents run; agents
  hand off through a shared workflow state.
- **Context isolation.** Each agent works in its own context window and returns only the
  essentials to the orchestrator, keeping it lean and reliable.
- **Tools, not talk.** Agents call real tools (`@Tool` methods) to edit files, run git, and
  run the build — so the system *acts*, it doesn't just describe.
- **A bounded review→fix loop.** The reviewer can send code back to the coder, capped at a
  few iterations to control cost and latency.
- **Human-in-the-loop approval** before destructive or expensive steps.

## Tech stack

**Pure Java / Spring Boot** (Spring AI for the agent layer — ChatClient, tool calling,
subagent/routing patterns, vector store for retrieval). No second language: the agent work
here is LLM calls plus tools, all of which Spring AI covers natively. A Python sidecar
would only be added if a concrete feature demanded it (e.g. a Python-only ML library) —
and it doesn't.

## A note on cost

Each agent step is an LLM API call billed by tokens, so one task is several calls. Kept
small by design: capped loops, a cheap model for routing and a stronger one only for code
generation, trimmed context per agent, a provider spend limit, and per-run token logging.
For personal testing and demos this runs to a few dollars total.

## Why this is worth building (portfolio angle)

- Demonstrates **modern agent orchestration in an enterprise Java stack** — rare, and
  exactly the ground enterprise employers hire for.
- Produces a **real artifact** (a branch/diff), which beats a text-only demo.
- Gives concrete things to defend in an interview: routing accuracy, tool-calling
  reliability, the bounded review loop, human-in-the-loop guardrails, sandboxing, and a
  small evaluation harness proving the routing works.
- Shows **judgment about when *not* to add complexity** (pure Java, Python only if needed) —
  a more senior signal than reaching for a polyglot setup by default.

## Scope discipline

Start with **orchestrator + coder + reviewer + the review loop**. That alone is a complete,
defensible agent system. Add planner, test-writer, and doc-writer once the core loop is
solid. Building all six agents before the loop works is the usual trap.
