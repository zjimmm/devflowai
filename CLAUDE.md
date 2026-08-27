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

## Verified Spring AI 2.0.1 syntax

**Compiled and confirmed via javac** (Task 3, static + compile-time only — no
live API call was possible in that session; `ANTHROPIC_API_KEY` was not
available):

    import com.anthropic.models.messages.OutputConfig;
    import org.springframework.ai.anthropic.AnthropicChatOptions;

    var optionsBuilder = AnthropicChatOptions.builder()
            .model("claude-opus-5")
            .thinkingAdaptive()
            .effort(OutputConfig.Effort.HIGH);

    String reply = chatClientBuilder.build()
            .prompt("...")
            .options(optionsBuilder)   // pass the BUILDER, not .build()'s result
            .call()
            .content();

Two things the brief's draft code got wrong, found only by letting `javac` name
the real symbol (do not guess these from memory):

- `effort(...)` takes `com.anthropic.models.messages.OutputConfig.Effort`
  (from the underlying Anthropic Java SDK), **not**
  `AnthropicChatOptions.OutputConfig.Effort` — there is no `OutputConfig`
  nested inside `AnthropicChatOptions`. Import it directly.
- `ChatClient.ChatClientRequestSpec.options(B)` is generic on
  `B extends ChatOptions.Builder<?>` in Spring AI 2.0.1 — it takes the
  **builder**, not a built `AnthropicChatOptions` instance. Calling
  `.options(optionsBuilder.build())` fails to compile with "inference
  variable B has incompatible bounds"; pass `optionsBuilder` itself. This is
  the shape `ChatClientConfig` (Task 10) must use.
- `thinkingAdaptive()` (no-arg) exists as named and compiles as drafted.

Both `thinkingAdaptive()` and `effort(...)` are also runtime-verified (not just
compiled) in `AnthropicChatOptionsBuilderTest`, which builds real
`AnthropicChatOptions` objects and asserts on the result — no network call, so
it runs in the default `./gradlew test` suite with no API key.

**Temperature / Opus 5 compatibility — partially verified:**

Static bytecode inspection (`javap -c` across `spring-ai-anthropic-2.0.1.jar`,
`spring-ai-model-2.0.1.jar`, and `anthropic-java-core-2.52.0.jar`) traced the
full chain from the options builder to the wire:

1. `DefaultChatOptionsBuilder` (spring-ai-model) declares
   `protected Double temperature;` and its no-arg constructor assigns nothing
   to it — bytecode is just the `super()` call, nothing else. It stays `null`
   unless `.temperature(...)` is called.
2. `AnthropicChatOptions.AbstractBuilder.build()` copies that field straight
   into the `AnthropicChatOptions` constructor via a plain `getfield` — no
   null-coalescing, no default substitution.
3. `AnthropicChatModel`'s internal request-building method explicitly guards
   the call (decompiled bytecode, `if (options.getTemperature() != null) {
   requestBuilder.temperature(options.getTemperature().doubleValue()); }`) —
   when temperature is null, `MessageCreateParams.Builder.temperature(double)`
   is **never invoked at all**. The same guard pattern is used for topP/topK.
4. The underlying Anthropic Java SDK's request DTO
   (`com.anthropic.models.messages.MessageCreateParams.Body`) stores
   `temperature` as a `JsonField<Double>` (exposed as `Optional<Double>`),
   the SDK's standard "unset field" pattern used for every optional param in
   that class. Its own `JsonMissing.Serializer.serialize()` deliberately
   throws `IllegalStateException("JsonMissing cannot be serialized")` if ever
   invoked — only consistent with fields left unset being filtered out
   *before* Jackson reaches that serializer, i.e. omitted from the JSON body
   rather than written as `null`.

This was also confirmed **at runtime**, not just statically, by
`AnthropicChatOptionsBuilderTest.modelPinnedAndTemperatureLeftUnset()` (runs
in the default suite, no key needed):
`AnthropicChatOptions.builder().model("claude-opus-5").build().getTemperature()`
returns `null`.

Three independent layers (the options object, the request-builder call site,
and the SDK's own DTO design) all agree that leaving `.temperature(...)`
unset produces a request that never sets a temperature value. That is strong
static evidence. It is still not proof of what Anthropic's servers actually
receive or how Opus 5 responds — only a live call proves that.

*Aside, not part of this spike's scope:* the same inspection found
`AnthropicChatOptions.DEFAULT_MODEL` compiled to
`Model.CLAUDE_HAIKU_4_5.asString()` in this 2.0.1 jar — not
`claude-sonnet-4-20250514` as stated in the Non-negotiables section above.
`AnthropicChatProperties.toOptions()` never actually applies that constant
when the model is left unconfigured, though (it passes the raw, possibly-null
property straight through), so the practical effect of an unpinned model is
unverified either way. Worth a real check before relying on either claim —
not resolved here.

**NOT YET VERIFIED — requires a real API key:**
Whether an actual request built with
`AnthropicChatOptions.builder().model("claude-opus-5").build()` (no
`.temperature(...)` call) succeeds against the live Opus 5 API without a 400
error. Before trusting this in `ChatClientConfig` (Task 11), run:

    ANTHROPIC_API_KEY=<your key> ./gradlew liveTest --tests '*AnthropicSpikeTest*'

(`liveTest` in `build.gradle.kts` had to be fixed twice to make this command
meaningful — see the comments on that task: it needs explicit
`testClassesDirs`/`classpath` wiring or it reports `NO-SOURCE` and runs
nothing, and the tag exclusion must be scoped to the `test` task by name, not
`tasks.withType<Test>`, or `liveTest`'s own `includeTags("live")` gets
cancelled out.)

If that returns a 400 mentioning temperature, apply the brief's Step 4
fallback options in order.
