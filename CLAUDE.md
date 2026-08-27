# devflowai

An AI agent crew that takes a plain-language development task and returns a git
branch. Orchestrator routes → coder implements → reviewer critiques → bounded
loop → commit. Learns by writing skill docs when the reviewer bounces the coder.

Design spec: `docs/superpowers/specs/2026-08-27-devflowai-design.md`
Plan: `docs/superpowers/plans/2026-08-27-devflowai-phases-0-3.md`

## Build and test

JDK 21 is at /opt/homebrew/opt/openjdk@21 and is NOT on PATH. Export JAVA_HOME
before every `./gradlew` invocation — the wrapper script needs a JVM to bootstrap
Gradle itself, before Gradle ever reads gradle.properties or resolves toolchains.
No gradle.properties setting can substitute for this.

    export JAVA_HOME=/opt/homebrew/opt/openjdk@21
    ./gradlew test          # full suite; most tests stub ChatClient and cost nothing
    ./gradlew bootRun       # also needs ANTHROPIC_API_KEY in the environment

## Non-negotiables

- **Pin every model explicitly.** Spring AI's Anthropic starter defaults to
  `claude-sonnet-4-20250514`, which is stale. Router/scribe use
  `claude-haiku-4-5`; coder/reviewer use `claude-opus-5`.
- **Never send `temperature`.** Spring AI defaults it to 1.0 and Opus 5 rejects
  sampling parameters with HTTP 400. See `config/ChatClientConfig.java`.
- **The orchestrator never holds file contents or diffs.** Only `AgentResult`
  records. If you find yourself passing a diff through `Orchestrator`, stop —
  that breaks the context-isolation guarantee the whole design rests on.
- **Every path-taking tool goes through `PathGuard`.** No exceptions. A tool that
  calls `Paths.get()` directly is a security bug.
- **Agents never call each other.** They return to the orchestrator, which decides
  what runs next.
- **This repo is public.** No API key in any tracked file, ever.

## Architecture in one paragraph

Central orchestration, not handoffs and not agent-as-tool. An LLM router does the
judgment (which agents, in what order); Java code does the sequencing (a `switch`
and a bounded `while`). Agents are pure `RunState -> AgentResult` functions. This
was chosen deliberately because the pipeline is known in advance — see §3.1 of the
spec for the rejected alternatives and why.

## Skill format

`.devflowai/skills/*.md` (written by the Scribe agent at runtime) uses the same
frontmatter shape as `.claude/skills/*/SKILL.md`. That symmetry is intentional.
