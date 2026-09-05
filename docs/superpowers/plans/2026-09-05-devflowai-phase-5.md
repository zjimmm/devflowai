# devflowai Phase 5 Implementation Plan — Skills, memory, and the ScribeAgent

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make devflowai learn from its own corrections — a reviewer bounce or a human rejection writes a skill doc and/or a durable fact to disk, and a later run on the same repo reads it back before the coder's first token is spent.

**Architecture:** Two new host-side stores (`SkillStore`/`FileSkillStore`, `MemoryStore`/`FileMemoryStore`) persist learning outside any run's disposable workspace, keyed by repo. Before Gate 1, the orchestrator loads memory whole and asks a cheap Haiku-tier `SkillPicker` to select ≤3 relevant skills from a frontmatter-only index; both are injected into the coder's prompt via the existing (already-wired but previously unused) `RunState.loadedSkills()` plumbing plus a new `RunState.memory()` field. After a corrected run's Gate 3, a tool-free `Scribe` drafts what was learned from the accumulated findings and the diff; `SkillStore`/`MemoryStore` (ordinary code, never the Scribe itself) write it only once Gate 3 resolves — approval writes it, an unreasoned rejection commits the code anyway and discards only the draft, a reasoned rejection re-drafts once without re-running the coder.

**Tech Stack:** Java 21 · Spring Boot 4.1.1 (spring-webmvc 7.0.9) · Spring AI 2.0.1 · Gradle 9.5.1 · JUnit 5 + AssertJ + Mockito

**Spec:** `docs/superpowers/specs/2026-08-27-devflowai-design.md` (§6 primarily; §5.2's gate table for Gate 3 semantics; §8 for tool access; §3 architecture diagram)

## Global Constraints

- **Java 21.** JDK 21 is at `/opt/homebrew/opt/openjdk@21` and is NOT on `PATH`. `export JAVA_HOME=/opt/homebrew/opt/openjdk@21` before every `./gradlew` invocation.
- **Pin every model explicitly.** Router/skill-picker/scribe `claude-haiku-4-5`; coder/reviewer `claude-opus-5`. Never call `.temperature(...)` — Opus 5 rejects it with HTTP 400, and it is unnecessary for Haiku-tier calls too, so this plan never calls it anywhere.
- **The repo is public.** No API key in any tracked file. `ANTHROPIC_API_KEY` from environment only.
- **Base package:** `ai.devflow`
- **The orchestrator never holds file contents or diffs.** `Orchestrator.java` itself must not gain a local variable holding a diff or file body. The Scribe is the one exception-shaped case in this phase, and it resolves *without* breaking this rule — see "The Scribe and the diff" below.
- **Every path-taking tool goes through `PathGuard`.** `SkillStore`/`MemoryStore` are deliberately NOT `@Tool` methods and are never given to an LLM — they are ordinary Java called only by `Orchestrator`, exactly like `GitTools.commit()` already is (Phase 3's C1 fix). They read/write under `~/.devflowai/...`, outside any workspace, so `PathGuard` (which confines paths to a workspace root) does not apply to them and must not be retrofitted onto them.
- **Review loop cap: 3. Human steering cap: 5. Separate counters.** Unchanged by this phase.
- **`GitTools.commit` is NOT a `@Tool`.** Do not re-annotate it. This phase relies on its existing unconditional `git add .` to pick up any skill/memory file written into the workspace before commit — no `GitTools` change is needed or made.
- **`ReviewerAgent` gets `ReadOnlyFileTools`, never `FileTools`.** Unchanged. The new `Scribe` gets neither — see below.

---

## Design decisions this plan makes (spec is silent or the current code has moved past it)

**1. No general multi-agent router yet.** Spec §5 step 4 describes a single "Router call" that returns both an ordered agent list *and* the skill names. No `RouterAgent` was ever built in Phases 0–4 — `Orchestrator`'s constructor hardcodes `coder` then `reviewer` unconditionally, and the `@Qualifier("router")` `ChatClient` bean in `ChatClientConfig` has sat unused since it was added. There is still only one agent sequence in this codebase (Planner/test-writer/doc-writer are Phase 7), so inventing a general routing decision now would have nothing real to decide. This plan reuses the existing `router`-qualified Haiku bean for a narrowly-scoped `SkillPicker` (task + skill index → 0–3 names) and does not add agent-list routing. Revisit when Phase 7 adds more agents.

**2. `repoSlug` is fixed to the literal `"fixture"` for now.** Spec §6.4's `~/.devflowai/skills/<repo-slug>/` needs a real identity per repo; the only workspace today is the bundled fixture (`ClonedWorkspace` is Phase 6). `devflowai.skills.repo-slug` is a config property defaulting to `"fixture"` so Phase 6 can override or replace it with a real derivation from the repo URL without touching this phase's code.

**3. "Merges it into an existing one (matched by name)" (§6.3) means whole-file overwrite by slug.** `FileSkillStore.write` computes a slug from the draft's `name` and overwrites `<repoSlug>/<slug>.md` if it exists. True content-level merging (showing the Scribe the prior file so it can revise it) is a real future enhancement but is not required for the write-trigger or the Gate 3 review flow to be correct, and the spec doesn't mandate the merge algorithm — it only requires that a second lesson on the same topic lands in the same file, which overwrite satisfies.

**4. The Scribe and the diff.** Spec §8 says the scribe gets *no* tools — not even `GitTools.diff` — yet §6.3 says it "receives... the final diff." These are reconciled the same way `CoderAgent.buildPrompt()` already reads `state.openFindings()`/`state.loadedSkills()` directly in Java to build its *own* prompt string: `ScribeAgent.draft(...)` calls `state.gitTools().diff()` — a plain, non-`@Tool` method already used this way by `GitTools.commit()`/`changedFiles()` — directly in its own Java code, and embeds the resulting string in the prompt text it sends. The model never sees a callable diff tool (satisfying §8), and `Orchestrator.java` itself never touches the diff (satisfying the orchestrator-context-isolation rule) — only `ScribeAgent`'s own method body does, exactly the same shape as `CoderAgent`'s existing prompt-building code.

**5. Spec correction — the write trigger cannot be the literal `reviewIterations >= 2`.** Spec §6.3: "A skill is written when `reviewIterations >= 2`," and separately, "[a human rejection] already trips this trigger by forcing another iteration." Reading `Orchestrator.execute()` (built in Phase 4, after this line of the spec was written) shows this second claim is false as the code stands: `decrementReviewIteration` **rolls the counter back** on every human correction, specifically so a human round doesn't consume the reviewer's separate cap. Trace it: pass 1 increments `reviewIterations` 0→1; if the reviewer says `OK` and a human then rejects Gate 2 or 3 *with a reason*, the rollback puts it back to 0 before the loop continues, and the next pass increments it back to 1 — never 2, no matter how many human-only correction rounds occur. A run corrected only by a human, with the reviewer approving immediately every time, would never trip a literal `reviewIterations >= 2` check. This plan uses the corrected trigger that actually matches the spec's *stated intent*:

    boolean shouldExtract = state.reviewIterations() >= 2 || state.humanIterations() >= 1;

Task 8 includes a regression test for exactly this case (a human-only-corrected run where `reviewIterations` stays at 1).

**6. Spec-mandated Gate 3 behavior change, not yet implemented.** Spec §5.2's gate table gives Gate 3 different reject semantics than Gates 1/2, which the current Phase 4 code does not yet have (Gate 3 today aborts on any unreasoned rejection, identically to Gates 1/2):

| Gate 3 outcome | Behavior this plan implements |
|---|---|
| Approve | Write skill (if any) + memory fact (if any) to both stores, then commit |
| Reject **with** reason, a draft exists | Re-run the Scribe once with the reason as guidance; re-show Gate 3 with the revised draft — the coder is **not** re-invoked |
| Reject **with** reason, no draft exists | Falls back to Phase 4's existing behavior: a `Finding(HUMAN)` and back to the coder — there is nothing to relearn, so the objection must be about the code |
| Reject **without** reason | Commit the code anyway; discard the draft entirely (write nothing to either store) |

This is why `Orchestrator.execute()`'s Gate 3 block needs a labeled loop (Task 8) rather than the single `continue` the other two gates use — a Gate-3 retry must return to Gate 3 itself, not to the top of the coder/reviewer loop.

---

## File Structure

```
src/main/java/ai/devflow/
  skill/
    SkillIndexEntry.java       NEW — record(name, description, List<String> triggers)
    SkillDraft.java            NEW — record(name, description, List<String> triggers, body) + slug()
    ScribeDraft.java           NEW — record(SkillDraft skill, String memoryFact) + EMPTY + isEmpty()
    SkillFileFormat.java       NEW — render(SkillDraft, learnedFromRunId) / parseIndexEntry(rawContent)
    SkillStore.java            NEW — interface: index/readFull/write
    FileSkillStore.java        NEW — host-side impl, root injected
  memory/
    MemoryStore.java           NEW — interface: read/append
    FileMemoryStore.java       NEW — host-side impl, root injected
  agent/
    SkillPicker.java           NEW — interface: pick(task, index) -> List<String>
    SkillPickerAgent.java      NEW — implements SkillPicker via the router-qualified ChatClient
    Scribe.java                NEW — interface: draft(state, findings, humanGuidance) -> ScribeDraft
    ScribeAgent.java           NEW — implements Scribe via a new scribe-qualified ChatClient
    CoderAgent.java            MODIFIED — injects state.memory() as its own prompt section
  orchestrator/
    RunState.java              MODIFIED — memory, allFindings, pendingScribeDraft fields
    Orchestrator.java          MODIFIED — knowledge loading before Gate 1; Gate 3 restructure + trigger
  config/
    ChatClientConfig.java      MODIFIED — new scribeChatClient bean
    OrchestrationConfig.java   MODIFIED — wires SkillPicker/Scribe/SkillStore/MemoryStore, repoSlug
src/main/resources/
  static/index.html            MODIFIED — Gate 3 shows the draft; loaded-skills step needs no change (generic log() already renders it)
  application.yml               MODIFIED — devflowai.skills.repo-slug

src/test/java/ai/devflow/...    mirrors main
```

**Why this split:** `skill/` and `memory/` are separate packages because they are genuinely different responsibilities with different retrieval semantics (spec §6's own table) even though they are written by the same trigger — folding them into one package would blur that distinction the spec is deliberate about. `SkillPicker`/`Scribe` are interfaces (not just the concrete `*Agent` classes) specifically so `OrchestratorTest` can supply trivial lambda test doubles, mirroring how `Orchestrator` already depends on the `Agent` interface rather than `CoderAgent`/`ReviewerAgent` concretely.

---

# Task 1: Skill data model and file format

**Files:**
- Create: `src/main/java/ai/devflow/skill/SkillIndexEntry.java`
- Create: `src/main/java/ai/devflow/skill/SkillDraft.java`
- Create: `src/main/java/ai/devflow/skill/ScribeDraft.java`
- Create: `src/main/java/ai/devflow/skill/SkillFileFormat.java`
- Test: `src/test/java/ai/devflow/skill/SkillFileFormatTest.java`

**Interfaces:**
- Produces: `SkillIndexEntry(String name, String description, List<String> triggers)`; `SkillDraft(String name, String description, List<String> triggers, String body)` with `.slug()`; `ScribeDraft(SkillDraft skill, String memoryFact)` with static `EMPTY` and `.isEmpty()`; `SkillFileFormat.render(SkillDraft, String learnedFromRunId)` and `SkillFileFormat.parseIndexEntry(String fileContent)` (returns `null` if no frontmatter found).

- [ ] **Step 1: Write the records**

`src/main/java/ai/devflow/skill/SkillIndexEntry.java`:

```java
package ai.devflow.skill;

import java.util.List;

/** The cheap, frontmatter-only view of a skill file (spec §6.2) — no body. */
public record SkillIndexEntry(String name, String description, List<String> triggers) {}
```

`src/main/java/ai/devflow/skill/SkillDraft.java`:

```java
package ai.devflow.skill;

import java.util.List;

public record SkillDraft(String name, String description, List<String> triggers, String body) {

    /** Filename-safe slug, used to match "an existing skill by name" (spec §6.3). */
    public String slug() {
        String s = name.toLowerCase().trim().replaceAll("[^a-z0-9]+", "-");
        return s.replaceAll("(^-+|-+$)", "");
    }
}
```

`src/main/java/ai/devflow/skill/ScribeDraft.java`:

```java
package ai.devflow.skill;

/**
 * What the Scribe decided to keep from one correction (spec §6.3): a skill,
 * a durable memory fact, both, or neither if the correction taught nothing
 * generalizable.
 */
public record ScribeDraft(SkillDraft skill, String memoryFact) {

    public static final ScribeDraft EMPTY = new ScribeDraft(null, null);

    public boolean isEmpty() {
        return skill == null && (memoryFact == null || memoryFact.isBlank());
    }
}
```

- [ ] **Step 2: Write the failing tests for `SkillFileFormat`**

Create `src/test/java/ai/devflow/skill/SkillFileFormatTest.java`:

```java
package ai.devflow.skill;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SkillFileFormatTest {

    @Test
    void rendersFrontmatterThenBody() {
        var draft = new SkillDraft("spring-controller-validation",
                "Adding bean validation to a Spring MVC controller",
                List.of("validation", "controller", "@Valid"),
                "## Steps\n1. Annotate the DTO fields\n");

        String rendered = SkillFileFormat.render(draft, "run-2026-08-27-a3f1");

        assertThat(rendered).startsWith("---\n");
        assertThat(rendered).contains("name: spring-controller-validation\n");
        assertThat(rendered).contains("description: Adding bean validation to a Spring MVC controller\n");
        assertThat(rendered).contains("triggers: [validation, controller, @Valid]\n");
        assertThat(rendered).contains("learned_from: run-2026-08-27-a3f1\n");
        assertThat(rendered).contains("## Steps\n1. Annotate the DTO fields\n");
    }

    @Test
    void roundTripsThroughParseIndexEntry() {
        var draft = new SkillDraft("spring-controller-validation",
                "Adding bean validation to a Spring MVC controller",
                List.of("validation", "controller", "@Valid"),
                "## Steps\n1. Annotate the DTO fields\n");

        String rendered = SkillFileFormat.render(draft, "run-1");
        SkillIndexEntry entry = SkillFileFormat.parseIndexEntry(rendered);

        assertThat(entry).isNotNull();
        assertThat(entry.name()).isEqualTo("spring-controller-validation");
        assertThat(entry.description()).isEqualTo("Adding bean validation to a Spring MVC controller");
        assertThat(entry.triggers()).containsExactly("validation", "controller", "@Valid");
    }

    // The spec's own §6.1 example has a quoted item ("@Valid") next to an
    // unquoted multi-word one (request body) in the same trigger list — the
    // parser must handle both without a YAML library (see this plan's design
    // note on hand-rolling this instead of pulling in one).
    @Test
    void parsesTheSpecsOwnExampleVerbatim() {
        String content = """
            ---
            name: spring-controller-validation
            description: Adding bean validation to a Spring MVC controller
            triggers: [validation, controller, "@Valid", request body]
            learned_from: run-2026-08-27-a3f1
            ---

            ## Steps
            1. Annotate the DTO fields with `jakarta.validation` constraints
            """;

        SkillIndexEntry entry = SkillFileFormat.parseIndexEntry(content);

        assertThat(entry.name()).isEqualTo("spring-controller-validation");
        assertThat(entry.triggers()).containsExactly("validation", "controller", "@Valid", "request body");
    }

    @Test
    void returnsNullWhenThereIsNoFrontmatter() {
        assertThat(SkillFileFormat.parseIndexEntry("just a plain markdown file\nwith no frontmatter\n")).isNull();
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
./gradlew test --tests 'ai.devflow.skill.SkillFileFormatTest'
```

Expected: compile failure — `SkillFileFormat` does not exist yet.

- [ ] **Step 4: Implement `SkillFileFormat`**

Create `src/main/java/ai/devflow/skill/SkillFileFormat.java`:

```java
package ai.devflow.skill;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders a {@link SkillDraft} to the on-disk markdown-with-frontmatter shape
 * (spec §6.1) and parses just the frontmatter back out for the cheap index
 * the picker reads (spec §6.2). Hand-rolled rather than a YAML library: the
 * frontmatter shape is narrow and fully controlled by {@link #render} — this
 * only ever has to parse what this same class wrote.
 */
public final class SkillFileFormat {

    private SkillFileFormat() {}

    public static String render(SkillDraft draft, String learnedFromRunId) {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("name: ").append(draft.name()).append('\n');
        sb.append("description: ").append(draft.description()).append('\n');
        sb.append("triggers: [").append(String.join(", ", draft.triggers())).append("]\n");
        sb.append("learned_from: ").append(learnedFromRunId).append('\n');
        sb.append("---\n\n");
        sb.append(draft.body());
        return sb.toString();
    }

    /** Parses only the frontmatter block. Returns null if the file has none. */
    public static SkillIndexEntry parseIndexEntry(String fileContent) {
        String[] lines = fileContent.split("\n", -1);
        if (lines.length == 0 || !lines[0].strip().equals("---")) return null;

        String name = null;
        String description = null;
        List<String> triggers = List.of();

        int i = 1;
        for (; i < lines.length && !lines[i].strip().equals("---"); i++) {
            String line = lines[i];
            if (line.startsWith("name:")) {
                name = line.substring("name:".length()).strip();
            } else if (line.startsWith("description:")) {
                description = line.substring("description:".length()).strip();
            } else if (line.startsWith("triggers:")) {
                triggers = parseTriggers(line.substring("triggers:".length()).strip());
            }
        }
        if (i == lines.length) return null; // never found the closing '---'
        if (name == null || description == null) return null;
        return new SkillIndexEntry(name, description, triggers);
    }

    private static List<String> parseTriggers(String bracketed) {
        String inner = bracketed.strip();
        if (inner.startsWith("[") && inner.endsWith("]")) {
            inner = inner.substring(1, inner.length() - 1);
        }
        if (inner.isBlank()) return List.of();
        List<String> items = new ArrayList<>();
        for (String raw : inner.split(",")) {
            String item = raw.strip();
            if (item.length() >= 2 && item.startsWith("\"") && item.endsWith("\"")) {
                item = item.substring(1, item.length() - 1);
            }
            items.add(item);
        }
        return items;
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
./gradlew test --tests 'ai.devflow.skill.SkillFileFormatTest'
```

Expected: PASS (4 tests).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/ai/devflow/skill/SkillIndexEntry.java \
        src/main/java/ai/devflow/skill/SkillDraft.java \
        src/main/java/ai/devflow/skill/ScribeDraft.java \
        src/main/java/ai/devflow/skill/SkillFileFormat.java \
        src/test/java/ai/devflow/skill/SkillFileFormatTest.java
git commit -m "$(cat <<'EOF'
feat: skill data model and on-disk file format

SkillIndexEntry/SkillDraft/ScribeDraft plus a hand-rolled renderer and
frontmatter parser for the markdown-with-frontmatter shape spec §6.1
defines. No YAML library: the format is narrow and fully controlled by
render(), so this only ever parses what it wrote.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_019frjxEdfkt5pkfAJJSixtZ
EOF
)"
```

---

# Task 2: SkillStore and FileSkillStore

**Files:**
- Create: `src/main/java/ai/devflow/skill/SkillStore.java`
- Create: `src/main/java/ai/devflow/skill/FileSkillStore.java`
- Test: `src/test/java/ai/devflow/skill/FileSkillStoreTest.java`

**Interfaces:**
- Consumes: `SkillDraft`, `SkillIndexEntry`, `SkillFileFormat` (Task 1)
- Produces: `SkillStore` interface with `index(String repoSlug)`, `readFull(String repoSlug, String name)`, `write(String repoSlug, String runId, SkillDraft draft)`; `FileSkillStore(Path root)` constructor.

- [ ] **Step 1: Write the interface**

`src/main/java/ai/devflow/skill/SkillStore.java`:

```java
package ai.devflow.skill;

import java.util.List;

public interface SkillStore {

    /** Frontmatter-only, cheap (spec §6.2). Empty if the repo has no skills yet. */
    List<SkillIndexEntry> index(String repoSlug);

    /** Full file content for a skill already known to exist, by name. */
    String readFull(String repoSlug, String name);

    /**
     * Writes host-side, matched by {@code draft.slug()} (spec §6.3's "merges
     * into an existing one"). Returns the rendered markdown so the caller can
     * also write the same content into the run's workspace to be committed.
     */
    String write(String repoSlug, String runId, SkillDraft draft);
}
```

- [ ] **Step 2: Write the failing tests**

Create `src/test/java/ai/devflow/skill/FileSkillStoreTest.java`:

```java
package ai.devflow.skill;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FileSkillStoreTest {

    @TempDir Path root;
    SkillStore store;

    @BeforeEach
    void setUp() { store = new FileSkillStore(root); }

    @Test
    void indexIsEmptyForARepoWithNoSkillsYet() {
        assertThat(store.index("fixture")).isEmpty();
    }

    @Test
    void writeThenIndexFindsIt() {
        var draft = new SkillDraft("spring-controller-validation", "Adding bean validation",
                List.of("validation"), "## Steps\n1. Do it\n");

        store.write("fixture", "run-1", draft);
        List<SkillIndexEntry> index = store.index("fixture");

        assertThat(index).hasSize(1);
        assertThat(index.get(0).name()).isEqualTo("spring-controller-validation");
        assertThat(index.get(0).description()).isEqualTo("Adding bean validation");
    }

    @Test
    void readFullReturnsTheBody() {
        var draft = new SkillDraft("spring-controller-validation", "d", List.of(), "## Steps\n1. Do it\n");
        store.write("fixture", "run-1", draft);

        assertThat(store.readFull("fixture", "spring-controller-validation")).contains("## Steps\n1. Do it\n");
    }

    @Test
    void readFullReturnsEmptyForAnUnknownSkill() {
        assertThat(store.readFull("fixture", "does-not-exist")).isEmpty();
    }

    @Test
    void writingAgainWithTheSameNameOverwritesRatherThanDuplicating() {
        var first = new SkillDraft("spring-controller-validation", "first version", List.of(), "v1");
        var second = new SkillDraft("spring-controller-validation", "second version", List.of(), "v2");

        store.write("fixture", "run-1", first);
        store.write("fixture", "run-2", second);

        List<SkillIndexEntry> index = store.index("fixture");
        assertThat(index).hasSize(1);
        assertThat(index.get(0).description()).isEqualTo("second version");
        assertThat(store.readFull("fixture", "spring-controller-validation")).contains("v2");
    }

    @Test
    void differentReposAreIsolated() {
        store.write("repo-a", "run-1", new SkillDraft("s", "d", List.of(), "a"));
        store.write("repo-b", "run-1", new SkillDraft("s", "d", List.of(), "b"));

        assertThat(store.readFull("repo-a", "s")).contains("a");
        assertThat(store.readFull("repo-b", "s")).contains("b");
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
./gradlew test --tests 'ai.devflow.skill.FileSkillStoreTest'
```

Expected: compile failure — `FileSkillStore` does not exist yet.

- [ ] **Step 4: Implement `FileSkillStore`**

`src/main/java/ai/devflow/skill/FileSkillStore.java`:

```java
package ai.devflow.skill;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Host-side source of truth for skills, keyed by repo (spec §6.4): a run's
 * workspace is a temp directory that gets deleted on every exit path, so
 * skills written only there would evaporate with it. {@code root} is
 * injected so tests use a scratch directory instead of the real
 * {@code ~/.devflowai/skills}.
 */
public class FileSkillStore implements SkillStore {

    private final Path root;

    public FileSkillStore(Path root) { this.root = root; }

    @Override
    public List<SkillIndexEntry> index(String repoSlug) {
        Path dir = root.resolve(repoSlug);
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> files = Files.list(dir)) {
            List<SkillIndexEntry> entries = new ArrayList<>();
            for (Path file : files.filter(p -> p.toString().endsWith(".md")).sorted().toList()) {
                SkillIndexEntry entry = SkillFileFormat.parseIndexEntry(Files.readString(file));
                if (entry != null) entries.add(entry);
            }
            return entries;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public String readFull(String repoSlug, String name) {
        Path file = root.resolve(repoSlug).resolve(name + ".md");
        try {
            return Files.exists(file) ? Files.readString(file) : "";
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public String write(String repoSlug, String runId, SkillDraft draft) {
        String rendered = SkillFileFormat.render(draft, runId);
        Path dir = root.resolve(repoSlug);
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(draft.slug() + ".md"), rendered);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return rendered;
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
./gradlew test --tests 'ai.devflow.skill.FileSkillStoreTest'
```

Expected: PASS (6 tests).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/ai/devflow/skill/SkillStore.java \
        src/main/java/ai/devflow/skill/FileSkillStore.java \
        src/test/java/ai/devflow/skill/FileSkillStoreTest.java
git commit -m "$(cat <<'EOF'
feat: FileSkillStore, the host-side source of truth for learned skills

Keyed by repo slug so a lesson from one repo can't pollute another
(spec §6.4). Root is injected so tests never touch a real home
directory. Writing again with the same name overwrites the file rather
than duplicating it, matching spec §6.3's "merges by name".

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_019frjxEdfkt5pkfAJJSixtZ
EOF
)"
```

---

# Task 3: MemoryStore and FileMemoryStore

**Files:**
- Create: `src/main/java/ai/devflow/memory/MemoryStore.java`
- Create: `src/main/java/ai/devflow/memory/FileMemoryStore.java`
- Test: `src/test/java/ai/devflow/memory/FileMemoryStoreTest.java`

**Interfaces:**
- Produces: `MemoryStore` interface with `read(String repoSlug)`, `append(String repoSlug, String fact)`; `FileMemoryStore(Path root)`.

- [ ] **Step 1: Write the interface**

`src/main/java/ai/devflow/memory/MemoryStore.java`:

```java
package ai.devflow.memory;

/** A flat, whole-file list of durable repo facts, keyed by repo (spec §6). */
public interface MemoryStore {

    /** Whole-file content, or "" if the repo has no memory yet. */
    String read(String repoSlug);

    /** Appends a fact as a new bullet line, skipping an exact duplicate. Returns the file's content after the append. */
    String append(String repoSlug, String fact);
}
```

- [ ] **Step 2: Write the failing tests**

Create `src/test/java/ai/devflow/memory/FileMemoryStoreTest.java`:

```java
package ai.devflow.memory;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FileMemoryStoreTest {

    @TempDir Path root;
    MemoryStore store;

    @BeforeEach
    void setUp() { store = new FileMemoryStore(root); }

    @Test
    void readIsEmptyForARepoWithNoMemoryYet() {
        assertThat(store.read("fixture")).isEmpty();
    }

    @Test
    void appendThenReadFindsTheFact() {
        store.append("fixture", "tests use JUnit 5 + AssertJ");

        assertThat(store.read("fixture")).contains("tests use JUnit 5 + AssertJ");
    }

    @Test
    void appendingTheSameFactTwiceDoesNotDuplicateIt() {
        store.append("fixture", "tests use JUnit 5 + AssertJ");
        store.append("fixture", "tests use JUnit 5 + AssertJ");

        String content = store.read("fixture");
        int occurrences = content.split("tests use JUnit 5", -1).length - 1;
        assertThat(occurrences).isEqualTo(1);
    }

    @Test
    void appendingADifferentFactKeepsBoth() {
        store.append("fixture", "tests use JUnit 5 + AssertJ");
        store.append("fixture", "the build tool is Gradle");

        String content = store.read("fixture");
        assertThat(content).contains("tests use JUnit 5 + AssertJ");
        assertThat(content).contains("the build tool is Gradle");
    }

    @Test
    void differentReposAreIsolated() {
        store.append("repo-a", "fact a");
        store.append("repo-b", "fact b");

        assertThat(store.read("repo-a")).contains("fact a").doesNotContain("fact b");
        assertThat(store.read("repo-b")).contains("fact b").doesNotContain("fact a");
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
./gradlew test --tests 'ai.devflow.memory.FileMemoryStoreTest'
```

Expected: compile failure — `FileMemoryStore` does not exist yet.

- [ ] **Step 4: Implement `FileMemoryStore`**

`src/main/java/ai/devflow/memory/FileMemoryStore.java`:

```java
package ai.devflow.memory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class FileMemoryStore implements MemoryStore {

    private final Path root;

    public FileMemoryStore(Path root) { this.root = root; }

    @Override
    public String read(String repoSlug) {
        Path file = root.resolve(repoSlug + ".md");
        try {
            return Files.exists(file) ? Files.readString(file) : "";
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public String append(String repoSlug, String fact) {
        String existing = read(repoSlug);
        String line = "- " + fact.strip();
        if (existing.lines().anyMatch(l -> l.strip().equals(line))) {
            return existing;
        }
        String updated = existing.isBlank() ? line + "\n" : existing.stripTrailing() + "\n" + line + "\n";
        try {
            Files.createDirectories(root);
            Files.writeString(root.resolve(repoSlug + ".md"), updated);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return updated;
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
./gradlew test --tests 'ai.devflow.memory.FileMemoryStoreTest'
```

Expected: PASS (5 tests).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/ai/devflow/memory/MemoryStore.java \
        src/main/java/ai/devflow/memory/FileMemoryStore.java \
        src/test/java/ai/devflow/memory/FileMemoryStoreTest.java
git commit -m "$(cat <<'EOF'
feat: FileMemoryStore, host-side durable repo facts

Whole-file, append-only, deduplicated by exact line match. Kept in its
own package from skills (ai.devflow.memory vs ai.devflow.skill): spec
§6's table treats them as different responsibilities with different
retrieval semantics even though the same Scribe call can produce both.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_019frjxEdfkt5pkfAJJSixtZ
EOF
)"
```

---

# Task 4: RunState extensions + memory injection into the coder's prompt

**Files:**
- Modify: `src/main/java/ai/devflow/orchestrator/RunState.java`
- Modify: `src/main/java/ai/devflow/agent/CoderAgent.java`
- Modify: `src/test/java/ai/devflow/orchestrator/RunStateTest.java`
- Modify: `src/test/java/ai/devflow/agent/CoderAgentTest.java`

**Interfaces:**
- Consumes: `ScribeDraft` (Task 1)
- Produces: `RunState.memory()`/`setMemory(String)`; `RunState.allFindings()` (never cleared by `clearFindings()`); `RunState.pendingScribeDraft()`/`setPendingScribeDraft(ScribeDraft)`.

- [ ] **Step 1: Write the failing tests**

Add to `src/test/java/ai/devflow/orchestrator/RunStateTest.java` (open the existing file and add these test methods inside the class):

```java
    @Test
    void memoryDefaultsToBlankAndCanBeSet() {
        var state = new RunState("r", "t", workspace);
        assertThat(state.memory()).isEmpty();

        state.setMemory("tests use JUnit 5");
        assertThat(state.memory()).isEqualTo("tests use JUnit 5");
    }

    @Test
    void allFindingsAccumulatesAcrossClearFindingsCalls() {
        var state = new RunState("r", "t", workspace);

        state.addFindings(List.of(Finding.fromHuman("first correction")));
        state.clearFindings(); // simulates the coder having consumed it
        state.addFindings(List.of(Finding.fromHuman("second correction")));

        assertThat(state.openFindings()).hasSize(1); // only the second is still "open"
        assertThat(state.allFindings())
                .as("the Scribe needs everything that ever caused a bounce, not just what's still pending")
                .hasSize(2);
    }

    @Test
    void pendingScribeDraftDefaultsToEmpty() {
        var state = new RunState("r", "t", workspace);
        assertThat(state.pendingScribeDraft().isEmpty()).isTrue();

        var draft = new ScribeDraft(new SkillDraft("s", "d", List.of(), "b"), null);
        state.setPendingScribeDraft(draft);
        assertThat(state.pendingScribeDraft()).isEqualTo(draft);
    }
```

Add the matching imports at the top of that file (alongside the existing ones):

```java
import ai.devflow.skill.ScribeDraft;
import ai.devflow.skill.SkillDraft;
```

Add to `src/test/java/ai/devflow/agent/CoderAgentTest.java` (new test method):

```java
    @Test
    void memoryIsIncludedInThePromptAsItsOwnSection() throws Exception {
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(client.prompt().user(any(String.class)).tools(any(Object[].class)).call().chatResponse())
                .thenReturn(responseWith("done", 10, 5));

        var agent = new CoderAgent(client);
        var state = new RunState("coder-test", "fix it", workspace);
        state.setMemory("tests use JUnit 5 + AssertJ");

        agent.run(state);

        var promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(client.prompt(), atLeastOnce()).user(promptCaptor.capture());
        assertThat(promptCaptor.getValue()).contains("tests use JUnit 5 + AssertJ");
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
./gradlew test --tests 'ai.devflow.orchestrator.RunStateTest' --tests 'ai.devflow.agent.CoderAgentTest'
```

Expected: compile failure — `RunState.memory()`, `.allFindings()`, `.pendingScribeDraft()` don't exist yet.

- [ ] **Step 3: Implement the `RunState` additions**

In `src/main/java/ai/devflow/orchestrator/RunState.java`, add the import and three fields, then the accessors, then widen `addFindings`:

```java
import ai.devflow.skill.ScribeDraft;
```

```java
    private final List<Finding> allFindings = new ArrayList<>();
    private String memory = "";
    private ScribeDraft pendingScribeDraft = ScribeDraft.EMPTY;
```

```java
    public synchronized List<Finding> allFindings() { return List.copyOf(allFindings); }
    public synchronized String memory() { return memory; }
    public synchronized ScribeDraft pendingScribeDraft() { return pendingScribeDraft; }

    public synchronized void setMemory(String memory) { this.memory = memory == null ? "" : memory; }
    public synchronized void setPendingScribeDraft(ScribeDraft draft) {
        this.pendingScribeDraft = draft == null ? ScribeDraft.EMPTY : draft;
    }
```

Replace the existing `addFindings` method:

```java
    public synchronized void addFindings(List<Finding> findings) {
        openFindings.addAll(findings);
        allFindings.addAll(findings); // never cleared -- the Scribe's input at the end of the run
    }
```

- [ ] **Step 4: Implement the `CoderAgent` memory section**

In `src/main/java/ai/devflow/agent/CoderAgent.java`, in `buildPrompt`, insert a new block right after the task line and before the existing skills block:

```java
        if (!state.memory().isBlank()) {
            sb.append("Known facts about this repository from previous runs:\n")
              .append(state.memory())
              .append("\n\n");
        }
```

- [ ] **Step 5: Run the tests to verify they pass**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
./gradlew test --tests 'ai.devflow.orchestrator.RunStateTest' --tests 'ai.devflow.agent.CoderAgentTest'
```

Expected: PASS (all `RunStateTest` and `CoderAgentTest` cases, including the 4 new ones).

- [ ] **Step 6: Run the full suite to confirm nothing else broke**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
./gradlew test
```

Expected: PASS (previous 96 tests plus the new ones from Tasks 1–4).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/ai/devflow/orchestrator/RunState.java \
        src/main/java/ai/devflow/agent/CoderAgent.java \
        src/test/java/ai/devflow/orchestrator/RunStateTest.java \
        src/test/java/ai/devflow/agent/CoderAgentTest.java
git commit -m "$(cat <<'EOF'
feat: RunState carries memory and a running findings history

memory() is a separate field from loadedSkills() -- spec §6's table
treats memory and skills as different retrieval semantics even though
both get injected into the coder's prompt. allFindings() never gets
cleared by clearFindings(), unlike openFindings(): the Scribe needs
everything that ever caused a bounce across the whole run, not just
what's still pending for the coder's next attempt.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_019frjxEdfkt5pkfAJJSixtZ
EOF
)"
```

---

# Task 5: SkillPicker / SkillPickerAgent

**Files:**
- Create: `src/main/java/ai/devflow/agent/SkillPicker.java`
- Create: `src/main/java/ai/devflow/agent/SkillPickerAgent.java`
- Test: `src/test/java/ai/devflow/agent/SkillPickerAgentTest.java`

**Interfaces:**
- Consumes: `SkillIndexEntry` (Task 1), existing `@Qualifier("router")` `ChatClient` bean (unused until now)
- Produces: `SkillPicker` interface (`pick(String task, List<SkillIndexEntry> index) -> List<String>`); `SkillPickerAgent(ChatClient)` implementing it.

- [ ] **Step 1: Write the interface**

`src/main/java/ai/devflow/agent/SkillPicker.java`:

```java
package ai.devflow.agent;

import ai.devflow.skill.SkillIndexEntry;

import java.util.List;

/**
 * Selects 0-3 skills relevant to a task from the cheap frontmatter index
 * (spec §6.2). Not an {@link Agent}: its shape is {@code (task, index) ->
 * names}, not {@code RunState -> AgentResult} -- there is no file-changing
 * work to report. An interface (not just the concrete {@code
 * SkillPickerAgent}) so tests can supply a trivial lambda double.
 */
public interface SkillPicker {
    List<String> pick(String task, List<SkillIndexEntry> index);
}
```

- [ ] **Step 2: Write the failing tests**

Create `src/test/java/ai/devflow/agent/SkillPickerAgentTest.java`:

```java
package ai.devflow.agent;

import ai.devflow.skill.SkillIndexEntry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SkillPickerAgentTest {

    private static ChatResponse responseWith(String text) {
        var generation = new Generation(new AssistantMessage(text));
        var metadata = ChatResponseMetadata.builder().usage(new DefaultUsage(10, 5)).build();
        return new ChatResponse(List.of(generation), metadata);
    }

    @Test
    void emptyIndexNeverCallsTheModel() {
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);

        List<String> picked = new SkillPickerAgent(client).pick("add validation", List.of());

        assertThat(picked).isEmpty();
        verifyNoInteractions(client);
    }

    @Test
    void parsesSelectedSkillNames() {
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(client.prompt().user(any(String.class)).call().chatResponse())
                .thenReturn(responseWith("""
                    {"skills":["spring-controller-validation"]}
                    """));

        var index = List.of(new SkillIndexEntry("spring-controller-validation", "d", List.of("validation")));
        List<String> picked = new SkillPickerAgent(client).pick("add validation", index);

        assertThat(picked).containsExactly("spring-controller-validation");
    }

    @Test
    void noneApplyReturnsEmptyList() {
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(client.prompt().user(any(String.class)).call().chatResponse())
                .thenReturn(responseWith("""
                    {"skills":[]}
                    """));

        var index = List.of(new SkillIndexEntry("unrelated-skill", "d", List.of()));
        assertThat(new SkillPickerAgent(client).pick("add validation", index)).isEmpty();
    }

    @Test
    void malformedResponseFailsClosedToNoSkills() {
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(client.prompt().user(any(String.class)).call().chatResponse())
                .thenReturn(responseWith("not json at all"));

        var index = List.of(new SkillIndexEntry("s", "d", List.of()));
        assertThat(new SkillPickerAgent(client).pick("t", index)).isEmpty();
    }

    @Test
    void moreThanThreeNamesAreTruncated() {
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(client.prompt().user(any(String.class)).call().chatResponse())
                .thenReturn(responseWith("""
                    {"skills":["a","b","c","d"]}
                    """));

        var index = List.of(new SkillIndexEntry("a", "", List.of()));
        assertThat(new SkillPickerAgent(client).pick("t", index)).hasSize(3);
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
./gradlew test --tests 'ai.devflow.agent.SkillPickerAgentTest'
```

Expected: compile failure — `SkillPickerAgent` does not exist yet.

- [ ] **Step 4: Implement `SkillPickerAgent`**

`src/main/java/ai/devflow/agent/SkillPickerAgent.java`:

```java
package ai.devflow.agent;

import ai.devflow.skill.SkillIndexEntry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;

import java.util.ArrayList;
import java.util.List;

public class SkillPickerAgent implements SkillPicker {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_SKILLS = 3;

    private final ChatClient chatClient;

    public SkillPickerAgent(ChatClient chatClient) { this.chatClient = chatClient; }

    @Override
    public List<String> pick(String task, List<SkillIndexEntry> index) {
        if (index.isEmpty()) return List.of();

        String catalogue = index.stream()
                .map(e -> "- %s: %s (triggers: %s)".formatted(e.name(), e.description(), String.join(", ", e.triggers())))
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");

        String prompt = """
            Task: %s

            Available skills learned from previous runs on this repository:
            %s

            Pick at most %d skills relevant to this task. Reply with ONLY this JSON:
            {"skills":["name1","name2"]}
            If none apply, reply {"skills":[]}.
            """.formatted(task, catalogue, MAX_SKILLS);

        ChatResponse response = chatClient.prompt().user(prompt).call().chatResponse();
        return parse(textOf(response));
    }

    private static String textOf(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return "";
        }
        String text = response.getResult().getOutput().getText();
        return text == null ? "" : text;
    }

    private List<String> parse(String raw) {
        try {
            String json = raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1);
            JsonNode node = MAPPER.readTree(json);
            List<String> names = new ArrayList<>();
            for (JsonNode n : node.path("skills")) {
                String name = n.asText(null);
                if (name != null && !name.isBlank()) names.add(name);
            }
            return names.size() > MAX_SKILLS ? names.subList(0, MAX_SKILLS) : names;
        } catch (Exception e) {
            // Fail closed to "no skills": a malformed picker response must
            // never crash the run over a cost optimization.
            return List.of();
        }
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
./gradlew test --tests 'ai.devflow.agent.SkillPickerAgentTest'
```

Expected: PASS (5 tests).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/ai/devflow/agent/SkillPicker.java \
        src/main/java/ai/devflow/agent/SkillPickerAgent.java \
        src/test/java/ai/devflow/agent/SkillPickerAgentTest.java
git commit -m "$(cat <<'EOF'
feat: SkillPickerAgent selects relevant skills from the frontmatter index

Reuses the router-qualified Haiku ChatClient bean that has sat unused
since Phase 0-3 (see this plan's design note on why a general
multi-agent router isn't built yet). Fails closed to an empty list on
any malformed response or an empty index, without ever calling the
model when there's nothing to pick from.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_019frjxEdfkt5pkfAJJSixtZ
EOF
)"
```

---

# Task 6: Scribe / ScribeAgent + the scribe ChatClient bean

**Files:**
- Create: `src/main/java/ai/devflow/agent/Scribe.java`
- Create: `src/main/java/ai/devflow/agent/ScribeAgent.java`
- Modify: `src/main/java/ai/devflow/config/ChatClientConfig.java`
- Test: `src/test/java/ai/devflow/agent/ScribeAgentTest.java`

**Interfaces:**
- Consumes: `Finding`, `RunState.gitTools()` (plain method, not a `@Tool`), `ScribeDraft`/`SkillDraft` (Task 1)
- Produces: `Scribe` interface (`draft(RunState, List<Finding>, String humanGuidance) -> ScribeDraft`); `ScribeAgent(ChatClient)` implementing it; `@Qualifier("scribe") ChatClient` bean.

- [ ] **Step 1: Write the interface**

`src/main/java/ai/devflow/agent/Scribe.java`:

```java
package ai.devflow.agent;

import ai.devflow.orchestrator.RunState;
import ai.devflow.skill.ScribeDraft;

import java.util.List;

/**
 * Writes down what a corrected run just learned (spec §6.3). An interface
 * (not just the concrete {@code ScribeAgent}) so {@code OrchestratorTest} can
 * supply a trivial lambda double instead of mocking a {@code ChatClient}.
 */
public interface Scribe {
    /** {@code humanGuidance} is non-null only on a Gate-3 re-draft (spec §5.2). */
    ScribeDraft draft(RunState state, List<Finding> findings, String humanGuidance);
}
```

- [ ] **Step 2: Add the `scribe` ChatClient bean**

In `src/main/java/ai/devflow/config/ChatClientConfig.java`, add a new bean method after `routerChatClient`:

```java
    @Bean @Qualifier("scribe")
    ChatClient scribeChatClient(ChatClient.Builder builder) {
        return builder.defaultOptions(options(ModelNames.HAIKU))
                .defaultSystem("""
                    You are the Scribe in an automated development crew. You
                    never touch files yourself. Given a task, the findings
                    that caused a correction, and the final diff, decide what
                    is worth remembering so the next run does not repeat the
                    mistake. Answer only in the requested JSON shape. No prose.
                    """)
                .build();
    }
```

- [ ] **Step 3: Write the failing tests**

Create `src/test/java/ai/devflow/agent/ScribeAgentTest.java`:

```java
package ai.devflow.agent;

import ai.devflow.orchestrator.RunState;
import ai.devflow.workspace.*;
import org.junit.jupiter.api.*;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ScribeAgentTest {

    Workspace workspace;

    @BeforeEach
    void setUp() throws Exception {
        workspace = new FixtureWorkspace(Path.of("src/test/resources/fixture"), "scribe-test");
        workspace.prepare();
    }

    @AfterEach
    void tearDown() throws Exception { workspace.cleanup(); }

    private static ChatResponse responseWith(String text) {
        var generation = new Generation(new AssistantMessage(text));
        var metadata = ChatResponseMetadata.builder().usage(new DefaultUsage(10, 5)).build();
        return new ChatResponse(List.of(generation), metadata);
    }

    @Test
    void parsesASkillAndAMemoryFact() {
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(client.prompt().user(any(String.class)).call().chatResponse())
                .thenReturn(responseWith("""
                    {"skill": {"name":"spring-controller-validation","description":"d",
                               "triggers":["validation"],"body":"## Steps\\n1. Do it\\n"},
                     "memoryFact": "tests use JUnit 5"}
                    """));

        var state = new RunState("scribe-test", "add validation", workspace);
        var draft = new ScribeAgent(client).draft(state, List.of(Finding.fromHuman("use a DTO")), null);

        assertThat(draft.isEmpty()).isFalse();
        assertThat(draft.skill().name()).isEqualTo("spring-controller-validation");
        assertThat(draft.memoryFact()).isEqualTo("tests use JUnit 5");
    }

    @Test
    void bothFieldsNullIsAnEmptyDraft() {
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(client.prompt().user(any(String.class)).call().chatResponse())
                .thenReturn(responseWith("""
                    {"skill": null, "memoryFact": null}
                    """));

        var state = new RunState("scribe-test", "t", workspace);
        var draft = new ScribeAgent(client).draft(state, List.of(), null);

        assertThat(draft.isEmpty()).isTrue();
    }

    @Test
    void malformedResponseFailsClosedToEmpty() {
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(client.prompt().user(any(String.class)).call().chatResponse())
                .thenReturn(responseWith("not json at all"));

        var state = new RunState("scribe-test", "t", workspace);
        var draft = new ScribeAgent(client).draft(state, List.of(), null);

        assertThat(draft.isEmpty()).isTrue();
    }

    @Test
    void anUnnamedSkillIsDroppedButTheMemoryFactSurvives() {
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(client.prompt().user(any(String.class)).call().chatResponse())
                .thenReturn(responseWith("""
                    {"skill": {"name":"","description":"d","triggers":[],"body":"b"},
                     "memoryFact": "the build tool is Gradle"}
                    """));

        var state = new RunState("scribe-test", "t", workspace);
        var draft = new ScribeAgent(client).draft(state, List.of(), null);

        assertThat(draft.skill()).isNull();
        assertThat(draft.memoryFact()).isEqualTo("the build tool is Gradle");
    }

    @Test
    void humanGuidanceIsIncludedInThePromptOnARedraft() {
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(client.prompt().user(any(String.class)).call().chatResponse())
                .thenReturn(responseWith("""
                    {"skill": null, "memoryFact": null}
                    """));

        var state = new RunState("scribe-test", "t", workspace);
        new ScribeAgent(client).draft(state, List.of(), "that lesson was wrong, focus on the test instead");

        var promptCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(client.prompt(), atLeastOnce()).user(promptCaptor.capture());
        assertThat(promptCaptor.getValue()).contains("that lesson was wrong, focus on the test instead");
    }
}
```

- [ ] **Step 4: Run the tests to verify they fail**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
./gradlew test --tests 'ai.devflow.agent.ScribeAgentTest'
```

Expected: compile failure — `ScribeAgent` does not exist yet.

- [ ] **Step 5: Implement `ScribeAgent`**

`src/main/java/ai/devflow/agent/ScribeAgent.java`:

```java
package ai.devflow.agent;

import ai.devflow.orchestrator.RunState;
import ai.devflow.skill.ScribeDraft;
import ai.devflow.skill.SkillDraft;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Deliberately tool-free (spec §8): it never touches the filesystem itself.
 * It reads the diff via {@code state.gitTools().diff()} -- a plain Java call,
 * not a {@code @Tool} the model can invoke -- exactly the way {@code
 * CoderAgent.buildPrompt()} already pulls other RunState data directly into
 * its own prompt. {@code SkillStore}/{@code MemoryStore} write the result
 * only after Gate 3 resolves (Task 8); this class never writes anything.
 */
public class ScribeAgent implements Scribe {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ChatClient chatClient;

    public ScribeAgent(ChatClient chatClient) { this.chatClient = chatClient; }

    @Override
    public ScribeDraft draft(RunState state, List<Finding> findings, String humanGuidance) {
        String findingsText = findings.isEmpty()
                ? "(none recorded)"
                : findings.stream()
                        .map(f -> "- [%s/%s] %s".formatted(f.origin(), f.severity(), f.message()))
                        .collect(Collectors.joining("\n"));

        String guidance = humanGuidance == null || humanGuidance.isBlank()
                ? ""
                : "\n\nThe operator rejected your previous draft and said: %s\nRevise accordingly.\n".formatted(humanGuidance);

        String prompt = """
            Task: %s

            The coder was corrected during this run. Findings that caused the correction:
            %s

            Final diff:
            %s
            %s
            Decide what is worth remembering from this correction. Reply with ONLY this JSON:
            {"skill": {"name":"kebab-case-slug","description":"...","triggers":["...","..."],"body":"## Steps\\n...\\n## Pitfalls\\n...\\n## Verification\\n..."} or null,
             "memoryFact": "a short durable fact about this repository" or null}
            Use null for either field if this correction taught nothing generalizable. Do not invent a lesson just to fill the field.
            """.formatted(state.task(), findingsText, state.gitTools().diff(), guidance);

        ChatResponse response = chatClient.prompt().user(prompt).call().chatResponse();
        return parse(textOf(response));
    }

    private static String textOf(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return "";
        }
        String text = response.getResult().getOutput().getText();
        return text == null ? "" : text;
    }

    private ScribeDraft parse(String raw) {
        try {
            String json = raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1);
            JsonNode node = MAPPER.readTree(json);

            SkillDraft skill = null;
            JsonNode skillNode = node.path("skill");
            if (skillNode.isObject()) {
                List<String> triggers = new ArrayList<>();
                for (JsonNode t : skillNode.path("triggers")) triggers.add(t.asText());
                SkillDraft candidate = new SkillDraft(
                        skillNode.path("name").asText(""),
                        skillNode.path("description").asText(""),
                        triggers,
                        skillNode.path("body").asText(""));
                if (!candidate.name().isBlank()) skill = candidate; // an unnamed skill can't be filed or matched later
            }

            String memoryFact = node.path("memoryFact").isTextual() ? node.path("memoryFact").asText() : null;

            return new ScribeDraft(skill, memoryFact);
        } catch (Exception e) {
            // Fail closed to "nothing learned": a malformed scribe response
            // must never block or corrupt an already-validated commit.
            return ScribeDraft.EMPTY;
        }
    }
}
```

- [ ] **Step 6: Run the tests to verify they pass**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
./gradlew test --tests 'ai.devflow.agent.ScribeAgentTest'
```

Expected: PASS (5 tests).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/ai/devflow/agent/Scribe.java \
        src/main/java/ai/devflow/agent/ScribeAgent.java \
        src/main/java/ai/devflow/config/ChatClientConfig.java \
        src/test/java/ai/devflow/agent/ScribeAgentTest.java
git commit -m "$(cat <<'EOF'
feat: ScribeAgent drafts a skill and/or memory fact from a correction

Tool-free per spec §8: it reads the diff via the existing plain
gitTools().diff() call rather than a callable tool, the same shape
CoderAgent already uses to pull RunState data into its own prompt. A
new haiku-tier scribeChatClient bean; fails closed to ScribeDraft.EMPTY
on any malformed response.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_019frjxEdfkt5pkfAJJSixtZ
EOF
)"
```

---

# Task 7: Orchestrator loads memory/skills before Gate 1, wired end-to-end

This task changes `Orchestrator`'s constructor signature, so it also updates
`OrchestrationConfig` (the only production caller of that constructor) in the
same task — otherwise the whole `main` source set would fail to compile
between commits, which would leave a fresh subagent picking up the next task
staring at an unrelated build failure. Task 8 (the Gate 3 rewrite) only
changes this constructor's method *bodies*, never its signature, so it stays
safely independent of this one.

**Files:**
- Modify: `src/main/java/ai/devflow/orchestrator/Orchestrator.java`
- Modify: `src/test/java/ai/devflow/orchestrator/OrchestratorTest.java`
- Modify: `src/main/java/ai/devflow/config/OrchestrationConfig.java`
- Modify: `src/main/resources/application.yml`
- Test: `src/test/java/ai/devflow/config/OrchestrationConfigTest.java` (new — a thin context-loads smoke test)

**Interfaces:**
- Consumes: `SkillPicker`, `Scribe` (Tasks 5–6), `SkillStore`, `MemoryStore` (Tasks 2–3)
- Produces: `Orchestrator`'s constructor gains `SkillPicker skillPicker, Scribe scribe, SkillStore skillStore, MemoryStore memoryStore, String repoSlug` (inserted after `reviewer`, before `events` — the two LLM-callable collaborators grouped with the other agent-shaped one before the plumbing params); a fully-wired Spring context; `devflowai.skills.repo-slug` config property (default `fixture`).

- [ ] **Step 1: Write the failing tests**

In `src/test/java/ai/devflow/orchestrator/OrchestratorTest.java`, add the imports:

```java
import ai.devflow.memory.MemoryStore;
import ai.devflow.skill.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
```

Add these test-double classes alongside the existing `ScriptedAgent`/`ExplodingAgent`:

```java
    /** In-memory SkillStore double: starts with the given entries, records what gets written. */
    static class FakeSkillStore implements SkillStore {
        final List<SkillIndexEntry> entries;
        final Map<String, String> full = new HashMap<>();
        final List<SkillDraft> written = new ArrayList<>();
        FakeSkillStore() { this(List.of()); }
        FakeSkillStore(List<SkillIndexEntry> entries) { this.entries = entries; }
        @Override public List<SkillIndexEntry> index(String repoSlug) { return entries; }
        @Override public String readFull(String repoSlug, String name) { return full.getOrDefault(name, ""); }
        @Override public String write(String repoSlug, String runId, SkillDraft draft) {
            written.add(draft);
            return SkillFileFormat.render(draft, runId);
        }
    }

    static class FakeMemoryStore implements MemoryStore {
        String content = "";
        final List<String> appended = new ArrayList<>();
        @Override public String read(String repoSlug) { return content; }
        @Override public String append(String repoSlug, String fact) { appended.add(fact); return content; }
    }
```

Replace the existing `orchestrator(Agent, Agent)` helper with two overloads:

```java
    private Orchestrator orchestrator(Agent coder, Agent reviewer) {
        return orchestrator(coder, reviewer, (task, index) -> List.of(),
                (state, findings, reason) -> ScribeDraft.EMPTY, new FakeSkillStore(), new FakeMemoryStore());
    }

    private Orchestrator orchestrator(Agent coder, Agent reviewer, SkillPicker picker, Scribe scribe,
                                      SkillStore skillStore, MemoryStore memoryStore) {
        return new Orchestrator(coder, reviewer, picker, scribe, skillStore, memoryStore, "fixture",
                events, 3, 5, Duration.ofMinutes(1));
    }
```

Add these new tests:

```java
    @Test
    void loadedSkillsAndMemoryReachTheCoderThroughRunState() throws Exception {
        var coder = writingCoder("done");
        var reviewer = new ScriptedAgent("reviewer",
                List.of(AgentResult.ok("reviewer", "ok", List.of(), TokenUsage.NONE)));

        var skillStore = new FakeSkillStore(
                List.of(new SkillIndexEntry("spring-validation", "desc", List.of("validation"))));
        skillStore.full.put("spring-validation", "## Steps\n1. Add @Valid\n");
        var memoryStore = new FakeMemoryStore();
        memoryStore.content = "- tests use JUnit 5";
        SkillPicker picker = (task, index) -> List.of("spring-validation");

        var state = new RunState("g14", "t", workspace);
        var gate = new ApprovalGate(Duration.ofSeconds(10));
        var orchestrator = orchestrator(coder, reviewer, picker, (s, f, r) -> ScribeDraft.EMPTY, skillStore, memoryStore);

        runApprovingAll(orchestrator, state, gate);

        assertThat(state.memory()).isEqualTo("- tests use JUnit 5");
        assertThat(state.loadedSkills()).containsExactly("## Steps\n1. Add @Valid\n");
    }

    @Test
    void emptySkillIndexNeverInvokesThePicker() throws Exception {
        var coder = writingCoder("done");
        var reviewer = new ScriptedAgent("reviewer",
                List.of(AgentResult.ok("reviewer", "ok", List.of(), TokenUsage.NONE)));
        SkillPicker explodingPicker = (task, index) -> { throw new AssertionError("picker should not be called"); };

        var state = new RunState("g15", "t", workspace);
        var gate = new ApprovalGate(Duration.ofSeconds(10));
        var orchestrator = orchestrator(coder, reviewer, explodingPicker, (s, f, r) -> ScribeDraft.EMPTY,
                new FakeSkillStore(), new FakeMemoryStore());

        var outcome = runApprovingAll(orchestrator, state, gate);

        assertThat(outcome.approved()).isTrue();
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
./gradlew test --tests 'ai.devflow.orchestrator.OrchestratorTest'
```

Expected: compile failure — `Orchestrator`'s constructor doesn't accept these new parameters yet, and every existing test in this file fails to compile too (the shared helper's signature changed). That's expected and resolves in the next step.

- [ ] **Step 3: Update `Orchestrator`'s constructor and add `loadKnowledge`**

In `src/main/java/ai/devflow/orchestrator/Orchestrator.java`, add imports:

```java
import ai.devflow.memory.MemoryStore;
import ai.devflow.skill.SkillIndexEntry;
```

Replace the fields and constructor:

```java
    private final Agent coder;
    private final Agent reviewer;
    private final SkillPicker skillPicker;
    private final Scribe scribe;
    private final SkillStore skillStore;
    private final MemoryStore memoryStore;
    private final String repoSlug;
    private final RunEventPublisher events;
    private final int maxReviewIterations;
    private final int maxHumanIterations;
    private final Duration buildTimeout;

    public Orchestrator(Agent coder, Agent reviewer, SkillPicker skillPicker, Scribe scribe,
                        SkillStore skillStore, MemoryStore memoryStore, String repoSlug,
                        RunEventPublisher events, int maxReviewIterations, int maxHumanIterations,
                        Duration buildTimeout) {
        this.coder = coder;
        this.reviewer = reviewer;
        this.skillPicker = skillPicker;
        this.scribe = scribe;
        this.skillStore = skillStore;
        this.memoryStore = memoryStore;
        this.repoSlug = repoSlug;
        this.events = events;
        this.maxReviewIterations = maxReviewIterations;
        this.maxHumanIterations = maxHumanIterations;
        this.buildTimeout = buildTimeout;
    }
```

Add the import needed by `SkillStore` too:

```java
import ai.devflow.skill.SkillStore;
```

In `execute(...)`, right after the existing `emit(state, "step", "Workspace ready — ...", ...)` line and before the `// ---- Gate 1` comment, add the call:

```java
        loadKnowledge(state);

```

Add the new private method (near `emit`/`cleanUp` at the bottom of the class):

```java
    /** Loads memory whole and picks ≤3 relevant skills, before any Opus 5 call (spec §5 steps 4-5). */
    private void loadKnowledge(RunState state) {
        String memory = memoryStore.read(repoSlug);
        if (!memory.isBlank()) state.setMemory(memory);

        List<SkillIndexEntry> index = skillStore.index(repoSlug);
        if (index.isEmpty()) return; // never spend a call picking from nothing

        List<String> names = skillPicker.pick(state.task(), index);
        for (String name : names) {
            String full = skillStore.readFull(repoSlug, name);
            if (!full.isBlank()) state.addLoadedSkill(full);
        }
        if (!names.isEmpty()) {
            emit(state, "step", "Loaded " + names.size() + " skill(s): " + String.join(", ", names),
                    Map.of("skills", names));
        }
    }
```

- [ ] **Step 4: Update `application.yml`**

Add under the existing `devflowai:` key (alongside `review`, `gate`, `build`, `fixture`):

```yaml
  skills:
    repo-slug: fixture
```

- [ ] **Step 5: Rewrite `OrchestrationConfig`**

Replace the whole file:

```java
package ai.devflow.config;

import ai.devflow.agent.*;
import ai.devflow.event.RunEventPublisher;
import ai.devflow.memory.FileMemoryStore;
import ai.devflow.memory.MemoryStore;
import ai.devflow.orchestrator.Orchestrator;
import ai.devflow.orchestrator.RunRegistry;
import ai.devflow.skill.FileSkillStore;
import ai.devflow.skill.SkillStore;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Wires the crew.
 *
 * <p>The agents are singletons and hold no per-run state — everything they
 * need arrives in {@code RunState}, including its {@code GitTools}. That is
 * what makes singleton scope safe here.
 */
@Configuration
public class OrchestrationConfig {

    @Bean
    Agent coderAgent(@Qualifier("coder") ChatClient coderChatClient) {
        return new CoderAgent(coderChatClient);
    }

    @Bean
    Agent reviewerAgent(@Qualifier("reviewer") ChatClient reviewerChatClient) {
        return new ReviewerAgent(reviewerChatClient);
    }

    @Bean
    SkillPicker skillPickerAgent(@Qualifier("router") ChatClient routerChatClient) {
        return new SkillPickerAgent(routerChatClient);
    }

    @Bean
    Scribe scribeAgent(@Qualifier("scribe") ChatClient scribeChatClient) {
        return new ScribeAgent(scribeChatClient);
    }

    /** Host-side, keyed by repo (spec §6.4) -- fixed for the bundled fixture; Phase 6's ClonedWorkspace will need a slug derived from the repo URL. */
    @Bean
    SkillStore skillStore() {
        return new FileSkillStore(Path.of(System.getProperty("user.home"), ".devflowai", "skills"));
    }

    @Bean
    MemoryStore memoryStore() {
        return new FileMemoryStore(Path.of(System.getProperty("user.home"), ".devflowai", "memory"));
    }

    /** One thread per in-flight run; runs block for minutes at gates. */
    @Bean(destroyMethod = "shutdownNow")
    ExecutorService runExecutor() {
        return Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "devflowai-run");
            t.setDaemon(true);
            return t;
        });
    }

    @Bean
    Orchestrator orchestrator(Agent coderAgent, Agent reviewerAgent, SkillPicker skillPicker, Scribe scribe,
                              SkillStore skillStore, MemoryStore memoryStore, RunEventPublisher events,
                              @Value("${devflowai.skills.repo-slug:fixture}") String repoSlug,
                              @Value("${devflowai.review.max-iterations:3}") int maxReviewIterations,
                              @Value("${devflowai.review.max-human-iterations:5}") int maxHumanIterations,
                              @Value("${devflowai.build.timeout-minutes:5}") long buildTimeoutMinutes) {
        return new Orchestrator(coderAgent, reviewerAgent, skillPicker, scribe, skillStore, memoryStore, repoSlug,
                events, maxReviewIterations, maxHumanIterations, Duration.ofMinutes(buildTimeoutMinutes));
    }

    @Bean
    RunRegistry runRegistry(Orchestrator orchestrator, RunEventPublisher events, ExecutorService runExecutor,
                            @Value("${devflowai.fixture.path:src/test/resources/fixture}") String fixturePath,
                            @Value("${devflowai.gate.timeout-minutes:10}") long gateTimeoutMinutes) {
        return new RunRegistry(orchestrator, events, runExecutor,
                Path.of(fixturePath), Duration.ofMinutes(gateTimeoutMinutes));
    }
}
```

- [ ] **Step 6: Write a context-loads smoke test**

Create `src/test/java/ai/devflow/config/OrchestrationConfigTest.java`:

```java
package ai.devflow.config;

import ai.devflow.orchestrator.Orchestrator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/** Proves the new Phase 5 beans wire together without a real API key. */
@SpringBootTest(properties = "spring.ai.anthropic.api-key=test-key-not-used")
class OrchestrationConfigTest {

    @Autowired Orchestrator orchestrator;

    @Test
    void theFullyWiredOrchestratorExists() {
        assertThat(orchestrator).isNotNull();
    }
}
```

- [ ] **Step 7: Run the full suite**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
./gradlew test
```

Expected: PASS — the whole suite compiles and passes, including the new `OrchestratorTest`, `OrchestrationConfigTest`, and every pre-existing test.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/ai/devflow/orchestrator/Orchestrator.java \
        src/test/java/ai/devflow/orchestrator/OrchestratorTest.java \
        src/main/java/ai/devflow/config/OrchestrationConfig.java \
        src/main/resources/application.yml \
        src/test/java/ai/devflow/config/OrchestrationConfigTest.java
git commit -m "$(cat <<'EOF'
feat: orchestrator loads memory/skills before Gate 1, wired end-to-end

loadKnowledge() runs once, right after the workspace is ready and
before any Opus 5 call: reads memory.md whole, and -- only if the
skill index isn't empty -- asks the SkillPicker for up to 3 relevant
skills and injects their full bodies via the existing (previously
unused) RunState.addLoadedSkill(). OrchestratorTest's shared
orchestrator() helper now builds no-op fakes so every prior test in
this file keeps passing unchanged.

OrchestrationConfig wires FileSkillStore/FileMemoryStore at
~/.devflowai/skills and ~/.devflowai/memory respectively -- outside
any workspace, surviving the temp-directory cleanup every run does on
exit (spec §6.4). devflowai.skills.repo-slug defaults to "fixture"
(this plan's design note #2); Phase 6's ClonedWorkspace will need a
real derivation.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_019frjxEdfkt5pkfAJJSixtZ
EOF
)"
```

---

# Task 8: Orchestrator — the write trigger and the Gate 3 rewrite

**Files:**
- Modify: `src/main/java/ai/devflow/orchestrator/Orchestrator.java`
- Modify: `src/test/java/ai/devflow/orchestrator/OrchestratorTest.java`

**Interfaces:**
- Consumes: everything from Task 7
- Produces: `Orchestrator.execute()`'s Gate 3 block rewritten per this plan's design note #6 above.

- [ ] **Step 1: Write the failing tests**

Add to `src/test/java/ai/devflow/orchestrator/OrchestratorTest.java`:

```java
    /** Approves every gate up to (but not including) the given gate, then returns with that gate pending. */
    private void approveUntil(ApprovalGate gate, Future<?> outcome, Gate target) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while (gate.pending() != target) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("gate " + target + " never arrived");
            if (gate.pending() != null) gate.decide(ApprovalDecision.approve());
            Thread.sleep(5);
        }
    }

    @Test
    void cleanFirstPassRunNeverInvokesTheScribe() throws Exception {
        var coder = writingCoder("done");
        var reviewer = new ScriptedAgent("reviewer",
                List.of(AgentResult.ok("reviewer", "looks good", List.of(), TokenUsage.NONE)));
        Scribe explodingScribe = (state, findings, reason) -> { throw new AssertionError("scribe should not be called"); };

        var state = new RunState("g16", "t", workspace);
        var gate = new ApprovalGate(Duration.ofSeconds(10));
        var orchestrator = orchestrator(coder, reviewer, (task, index) -> List.of(), explodingScribe,
                new FakeSkillStore(), new FakeMemoryStore());

        var outcome = runApprovingAll(orchestrator, state, gate);

        assertThat(outcome.approved()).isTrue();
    }

    @Test
    void reviewerBounceTwiceTriggersSkillExtractionOnApproval() throws Exception {
        var coder = writingCoder("attempt");
        var bounce = AgentResult.needsWork("reviewer", "nope",
                List.of(new Finding(Finding.Origin.REVIEWER, Finding.Severity.HIGH, "A.java", 1, "still wrong")), TokenUsage.NONE);
        var ok = AgentResult.ok("reviewer", "looks good now", List.of(), TokenUsage.NONE);
        var reviewer = new ScriptedAgent("reviewer", List.of(bounce, ok));
        Scribe scribe = (state, findings, reason) ->
                new ScribeDraft(new SkillDraft("bounce-lesson", "d", List.of("x"), "body"), "tests use JUnit 5");
        var skillStore = new FakeSkillStore();
        var memoryStore = new FakeMemoryStore();

        var state = new RunState("g17", "t", workspace);
        var gate = new ApprovalGate(Duration.ofSeconds(10));
        var orchestrator = orchestrator(coder, reviewer, (task, index) -> List.of(), scribe, skillStore, memoryStore);

        var outcome = runApprovingAll(orchestrator, state, gate);

        assertThat(outcome.approved()).isTrue();
        assertThat(state.reviewIterations()).isEqualTo(2);
        assertThat(skillStore.written).extracting(SkillDraft::name).containsExactly("bounce-lesson");
        assertThat(memoryStore.appended).containsExactly("tests use JUnit 5");
    }

    // Regression for this plan's design note #5: decrementReviewIteration
    // (Phase 4) rolls reviewIterations back on every human correction so it
    // doesn't consume the reviewer's separate cap -- which means a purely
    // human-corrected run can finish with reviewIterations stuck at 1,
    // never reaching a literal ">= 2". The trigger must also check
    // humanIterations, or a human's own correction would never be learned.
    @Test
    void humanCorrectionAloneTriggersSkillExtractionEvenThoughReviewIterationsStaysAtOne() throws Exception {
        var coder = writingCoder("attempt");
        var reviewer = new ScriptedAgent("reviewer",
                List.of(AgentResult.ok("reviewer", "looks good", List.of(), TokenUsage.NONE)));
        Scribe scribe = (state, findings, reason) ->
                new ScribeDraft(new SkillDraft("human-taught", "d", List.of(), "body"), null);
        var skillStore = new FakeSkillStore();

        var state = new RunState("g18", "t", workspace);
        var gate = new ApprovalGate(Duration.ofSeconds(10));
        var orchestrator = orchestrator(coder, reviewer, (task, index) -> List.of(), scribe, skillStore, new FakeMemoryStore());

        Future<Orchestrator.RunOutcome> f = pool.submit(() -> orchestrator.run(state, gate));
        while (gate.pending() != Gate.PRE_FLIGHT) Thread.sleep(5);
        gate.decide(ApprovalDecision.approve());
        while (gate.pending() != Gate.BEFORE_BUILD) Thread.sleep(5);
        gate.decide(ApprovalDecision.rejectWith("use a DTO"));

        approveGatesUntilDone(gate, f);
        f.get(20, TimeUnit.SECONDS);

        assertThat(state.reviewIterations())
                .as("the rollback that protects the reviewer's cap must not also hide a human correction from the Scribe")
                .isEqualTo(1);
        assertThat(state.humanIterations()).isEqualTo(1);
        assertThat(skillStore.written).extracting(SkillDraft::name).containsExactly("human-taught");
    }

    @Test
    void gate3RejectWithReasonRerunsOnlyTheScribeNotTheCoder() throws Exception {
        var coder = writingCoder("attempt");
        var bounce = AgentResult.needsWork("reviewer", "nope",
                List.of(new Finding(Finding.Origin.REVIEWER, Finding.Severity.HIGH, "A.java", 1, "still wrong")), TokenUsage.NONE);
        var ok = AgentResult.ok("reviewer", "looks good now", List.of(), TokenUsage.NONE);
        var reviewer = new ScriptedAgent("reviewer", List.of(bounce, ok));

        var scribeCalls = new AtomicInteger(0);
        Scribe scribe = (state, findings, reason) -> {
            int n = scribeCalls.incrementAndGet();
            String name = n == 1 ? "first-draft" : "revised-draft";
            return new ScribeDraft(new SkillDraft(name, "d", List.of(), "body"), null);
        };
        var skillStore = new FakeSkillStore();

        var state = new RunState("g19", "t", workspace);
        var gate = new ApprovalGate(Duration.ofSeconds(10));
        var orchestrator = orchestrator(coder, reviewer, (task, index) -> List.of(), scribe, skillStore, new FakeMemoryStore());

        Future<Orchestrator.RunOutcome> f = pool.submit(() -> orchestrator.run(state, gate));
        approveUntil(gate, f, Gate.BEFORE_COMMIT);
        gate.decide(ApprovalDecision.rejectWith("wrong lesson"));
        while (gate.pending() != Gate.BEFORE_COMMIT) Thread.sleep(5);
        gate.decide(ApprovalDecision.approve());

        var outcome = f.get(20, TimeUnit.SECONDS);

        assertThat(outcome.approved()).isTrue();
        assertThat(coder.calls).as("Gate 3's retry must re-run only the Scribe, not the coder").isEqualTo(2);
        assertThat(scribeCalls.get()).isEqualTo(2);
        assertThat(skillStore.written).extracting(SkillDraft::name).containsExactly("revised-draft");
    }

    @Test
    void gate3RejectWithoutReasonCommitsCodeButDiscardsTheDraft() throws Exception {
        var coder = writingCoder("attempt");
        var bounce = AgentResult.needsWork("reviewer", "nope",
                List.of(new Finding(Finding.Origin.REVIEWER, Finding.Severity.HIGH, "A.java", 1, "still wrong")), TokenUsage.NONE);
        var ok = AgentResult.ok("reviewer", "looks good now", List.of(), TokenUsage.NONE);
        var reviewer = new ScriptedAgent("reviewer", List.of(bounce, ok));
        Scribe scribe = (state, findings, reason) ->
                new ScribeDraft(new SkillDraft("a-lesson", "d", List.of(), "body"), null);
        var skillStore = new FakeSkillStore();

        var state = new RunState("g20", "t", workspace);
        var gate = new ApprovalGate(Duration.ofSeconds(10));
        var orchestrator = orchestrator(coder, reviewer, (task, index) -> List.of(), scribe, skillStore, new FakeMemoryStore());

        Future<Orchestrator.RunOutcome> f = pool.submit(() -> orchestrator.run(state, gate));
        approveUntil(gate, f, Gate.BEFORE_COMMIT);
        gate.decide(ApprovalDecision.reject()); // no reason

        var outcome = f.get(20, TimeUnit.SECONDS);

        assertThat(outcome.approved())
                .as("Gate 3 alone commits validated code even on a bare rejection -- spec §5.2's Gate-3 row")
                .isTrue();
        assertThat(skillStore.written).isEmpty();
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
./gradlew test --tests 'ai.devflow.orchestrator.OrchestratorTest'
```

Expected: the five new tests fail (or the whole class fails to run) because Gate 3's current logic always aborts on rejection and never calls the Scribe.

- [ ] **Step 3: Rewrite Gate 3 in `Orchestrator.execute()`**

Add imports:

```java
import ai.devflow.skill.ScribeDraft;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
```

This is two separate edits to the same method.

**Edit A — label the outer loop.** Find this, near the top of `execute()` (right after the pre-flight Gate 1 handling):

```java
        AgentResult lastReview = null;

        while (state.reviewIterations() < maxReviewIterations) {
            state.incrementReviewIterations();
```

Add the label directly above the `while`, changing nothing else on those lines:

```java
        AgentResult lastReview = null;

        reviewLoop:
        while (state.reviewIterations() < maxReviewIterations) {
            state.incrementReviewIterations();
```

**Edit B — replace the Gate 3 section.** Everything from here down to the loop's closing brace is unchanged by this task (Gate 2, the build) until you reach the existing `// ---- Gate 3: before the commit --------------------------------` comment. Replace that comment and everything below it through the original `return new RunOutcome(true, "Approved by reviewer and operator", state);` line with:

```java
            // ---- Gate 3: before the commit --------------------------------
            // C1-b: a run that changed nothing must never be reported approved.
            if (changed.isEmpty()) {
                return failed(state, "Refusing to approve: the coder made no changes");
            }

            boolean shouldExtract = state.reviewIterations() >= 2 || state.humanIterations() >= 1;
            ScribeDraft draft = shouldExtract
                    ? scribe.draft(state, state.allFindings(), null)
                    : ScribeDraft.EMPTY;
            state.setPendingScribeDraft(draft);

            while (true) {
                emit(state, "gate", "About to commit " + changed.size() + " file(s)",
                        gate3Data(changed, build.success(), draft));
                ApprovalDecision beforeCommit = gate.await(Gate.BEFORE_COMMIT);

                if (beforeCommit.approved()) break;

                if (!beforeCommit.hasReason()) {
                    // Gate 3 alone: the code is already reviewed and built, so
                    // an unreasoned rejection commits it anyway and discards
                    // only the draft -- spec §5.2's Gate-3 row differs from
                    // Gates 1/2, where an unreasoned rejection aborts.
                    draft = ScribeDraft.EMPTY;
                    state.setPendingScribeDraft(draft);
                    emit(state, "step", "Operator declined the lesson; committing the code anyway", Map.of());
                    break;
                }

                if (state.humanIterations() >= maxHumanIterations) {
                    return aborted(state, "Rejected before the commit");
                }
                state.incrementHumanIterations();

                if (!draft.isEmpty()) {
                    draft = scribe.draft(state, state.allFindings(), beforeCommit.reason());
                    state.setPendingScribeDraft(draft);
                    emit(state, "step", "Operator asked for a different lesson: " + beforeCommit.reason(), Map.of());
                    continue; // re-show Gate 3 with the revised draft only -- the coder is not re-invoked
                }

                // Nothing was learned this run -- the objection must be about the code.
                state.addFindings(List.of(Finding.fromHuman(beforeCommit.reason())));
                decrementReviewIteration(state);
                emit(state, "step", "Operator sent it back: " + beforeCommit.reason(), Map.of());
                continue reviewLoop;
            }

            // ---- Persist the (possibly empty) draft, then commit -----------
            if (!draft.isEmpty()) {
                if (draft.skill() != null) {
                    skillStore.write(repoSlug, state.runId(), draft.skill());
                    writeIntoWorkspace(state, draft.skill());
                }
                if (draft.memoryFact() != null && !draft.memoryFact().isBlank()) {
                    String updated = memoryStore.append(repoSlug, draft.memoryFact());
                    writeMemoryIntoWorkspace(state, updated);
                }
            }

            state.setPhase(RunPhase.COMMITTING);
            String committed = state.gitTools().commit("devflowai: " + state.task());
            emit(state, "step", committed, Map.of());

            state.setPhase(RunPhase.DONE);
            emit(state, "done", "Approved and committed on " + state.workspace().branchName(),
                    Map.of("branch", state.workspace().branchName(),
                           "inputTokens", state.totalTokens().input(),
                           "outputTokens", state.totalTokens().output()));
            return new RunOutcome(true, "Approved by reviewer and operator", state);
        }
```

Add the three new private helper methods near `decrementReviewIteration`:

```java
    private Map<String, Object> gate3Data(List<String> changed, boolean buildPassed, ScribeDraft draft) {
        Map<String, Object> data = new HashMap<>();
        data.put("gate", Gate.BEFORE_COMMIT.name());
        data.put("filesTouched", changed);
        data.put("buildPassed", buildPassed);
        if (draft.skill() != null) {
            data.put("skillDraft", Map.of("name", draft.skill().name(), "description", draft.skill().description()));
        }
        if (draft.memoryFact() != null && !draft.memoryFact().isBlank()) {
            data.put("memoryFact", draft.memoryFact());
        }
        return data;
    }

    /**
     * Best-effort: a write failure here must not block an already-validated
     * commit. Not independently tested against a post-run filesystem check --
     * the workspace is deleted by cleanUp() before any external test could
     * observe it; SkillStore.write() being called with the right draft (see
     * OrchestratorTest) is the externally-observable proof this ran.
     */
    private void writeIntoWorkspace(RunState state, ai.devflow.skill.SkillDraft skill) {
        try {
            Path dir = state.workspace().root().resolve(".devflowai/skills");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(skill.slug() + ".md"),
                    ai.devflow.skill.SkillFileFormat.render(skill, state.runId()));
        } catch (IOException e) {
            events.publish(state.runId(), RunEvent.of("warn", "Could not write the skill into the branch: " + e.getMessage()));
        }
    }

    private void writeMemoryIntoWorkspace(RunState state, String updatedMemoryContent) {
        try {
            Path dir = state.workspace().root().resolve(".devflowai");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("memory.md"), updatedMemoryContent);
        } catch (IOException e) {
            events.publish(state.runId(), RunEvent.of("warn", "Could not write memory.md into the branch: " + e.getMessage()));
        }
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
./gradlew test --tests 'ai.devflow.orchestrator.OrchestratorTest'
```

Expected: PASS — the 8 pre-existing `OrchestratorTest` cases, plus the 2 from Task 7 and the 5 new ones from this task (15 total in this file).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/ai/devflow/orchestrator/Orchestrator.java \
        src/test/java/ai/devflow/orchestrator/OrchestratorTest.java
git commit -m "$(cat <<'EOF'
feat: skill/memory write trigger and the Gate 3 rewrite (spec §5.2, §6.3)

Trigger is reviewIterations >= 2 OR humanIterations >= 1, not the
spec's literal "reviewIterations >= 2" alone -- see this plan's design
note #5 for why the existing human-correction rollback makes the
literal form miss a purely human-corrected run. Regression test
included.

Gate 3 now has its own labeled retry loop, distinct from Gates 1/2:
approval writes the draft to both stores then commits; a reasoned
rejection re-runs only the Scribe and re-shows Gate 3 (the coder is
never re-invoked); an unreasoned rejection commits the already-
validated code anyway and discards only the draft, rather than
aborting the whole run the way Gates 1/2 do.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_019frjxEdfkt5pkfAJJSixtZ
EOF
)"
```

---

# Task 9: Operator page shows the loaded skills and the Gate 3 draft

**Files:**
- Modify: `src/main/resources/static/index.html`

**Interfaces:**
- Consumes: the `"step"` event's existing generic rendering (unchanged); Gate 3's new `skillDraft`/`memoryFact` event data fields (Task 8).

- [ ] **Step 1: Extend `showGate` to render the draft**

The "Loaded N skill(s): ..." step event needs no frontend change at all — it is a plain `type: "step"` event, and the existing `onEvent` handler already routes every non-gate event through the generic `log(payload.type, payload.message, payload.data)` call, which renders `payload.message` as-is.

Gate 3's draft preview does need a change. In `src/main/resources/static/index.html`, replace the `showGate` function:

```javascript
  function showGate(message, data) {
    $('gate-title').textContent = message;
    const files = (data && data.filesTouched) || [];
    let detail = files.length ? files.join('\n') : '';
    if (data && data.skillDraft) {
      detail += (detail ? '\n\n' : '') + `Learned: ${data.skillDraft.name} — ${data.skillDraft.description}`;
    }
    if (data && data.memoryFact) {
      detail += (detail ? '\n' : '') + `Remembered: ${data.memoryFact}`;
    }
    $('gate-detail').textContent = detail;
    $('reason').value = '';
    $('gate').classList.remove('hidden');
  }
```

- [ ] **Step 2: Verify manually in the browser**

There is no JS test harness in this project (a single static HTML file, no build step) — verification here is manual, the same way the document-attach feature was verified earlier this session.

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
./gradlew bootRun
```

Open `http://localhost:8080`, start a run, and approve through to Gate 3. Without a live `ANTHROPIC_API_KEY` this will fail before reaching Gate 3 (as seen earlier this session) — full visual confirmation of the draft preview requires either a live key (Task 10's automated test covers the logic without one) or temporarily hardcoding a fake `"gate"` SSE event in the browser console for a one-off visual check:

```javascript
showGate("About to commit 2 file(s)", {filesTouched: ["A.java"], skillDraft: {name: "spring-controller-validation", description: "Adding bean validation"}, memoryFact: "tests use JUnit 5"});
```

Confirm the gate box shows the file list, then "Learned: spring-controller-validation — Adding bean validation", then "Remembered: tests use JUnit 5".

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/static/index.html
git commit -m "$(cat <<'EOF'
feat: operator page previews the skill/memory draft at Gate 3

The "loaded N skill(s)" step needed no change -- it already flows
through the existing generic step-event renderer. Gate 3's box now
also shows what will be learned, so a bad lesson is visible before
the operator approves it (spec §6.3: "Gate 3 covers the draft").

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_019frjxEdfkt5pkfAJJSixtZ
EOF
)"
```

---

# Task 10: The Learning test — full-stack proof a corrected run writes a skill

**Files:**
- Modify: `src/test/java/ai/devflow/web/RunFlowIntegrationTest.java`

**Interfaces:**
- Consumes: `@TestBean` overrides of `scribeAgent`, `skillStore`, `memoryStore` (bean names from Task 7); the existing `stubReviewerAgent`/`stubCoderAgent` pattern.

This is spec §12's named "Learning test": *"run the same task twice; assert a skill file exists after run 1."* The host-side store is where this matters (spec §6.4) — the workspace copy is deleted by `Orchestrator`'s guaranteed cleanup before any external test could observe it (this plan's Task 8 design note on `writeIntoWorkspace` explains why that half is not independently re-tested here).

- [ ] **Step 1: Write the failing test**

Add these fields and static methods to `RunFlowIntegrationTest` (alongside the existing `@TestBean` fields and `stubCoderAgent`/`stubReviewerAgent`):

```java
    @TestBean(name = "scribeAgent", methodName = "stubScribeAgent")
    Scribe scribeAgentOverride;

    @TestBean(name = "skillStore", methodName = "stubSkillStore")
    SkillStore skillStoreOverride;

    @TestBean(name = "memoryStore", methodName = "stubMemoryStore")
    MemoryStore memoryStoreOverride;

    static Scribe stubScribeAgent() {
        return (state, findings, humanGuidance) -> new ScribeDraft(
                new SkillDraft("validation-fixture", "Validating input on the fixture controller",
                        List.of("validation"), "## Steps\n1. Add @Valid to the controller parameter\n"),
                "tests use JUnit 5");
    }

    static SkillStore stubSkillStore() throws java.io.IOException {
        return new FileSkillStore(Files.createTempDirectory("skills-test"));
    }

    static MemoryStore stubMemoryStore() throws java.io.IOException {
        return new FileMemoryStore(Files.createTempDirectory("memory-test"));
    }
```

Add the imports:

```java
import ai.devflow.memory.FileMemoryStore;
import ai.devflow.memory.MemoryStore;
import ai.devflow.skill.FileSkillStore;
import ai.devflow.skill.ScribeDraft;
import ai.devflow.skill.SkillDraft;
import ai.devflow.skill.SkillIndexEntry;
import ai.devflow.skill.SkillStore;
```

Replace `stubReviewerAgent` so it bounces exactly once per run, driven only by that run's own `RunState.history()` (safe under a cached Spring context shared across test methods — see the design note below the test):

```java
    static Agent stubReviewerAgent() {
        return new Agent() {
            @Override public String name() { return "reviewer"; }
            @Override public AgentResult run(RunState s) {
                boolean firstReviewForThisRun = s.history().stream().noneMatch(r -> r.agent().equals("reviewer"));
                if (firstReviewForThisRun && s.task().contains("needs-a-correction")) {
                    return AgentResult.needsWork("reviewer", "found an issue",
                            List.of(new Finding(Finding.Origin.REVIEWER, Finding.Severity.HIGH,
                                    "Added.java", 1, "missing validation")),
                            TokenUsage.NONE);
                }
                return AgentResult.ok("reviewer", "looks correct", List.of(), TokenUsage.NONE);
            }
        };
    }
```

Add the new test:

```java
    @Test
    void aBounceThenFixWritesASkillIntoTheHostSideStore() throws Exception {
        String body = mvc.perform(post("/api/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new StartRunRequest("add validation, needs-a-correction", "fixture"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String runId = json.readTree(body).get("runId").asText();
        RunHandle handle = registry.find(runId);
        assertThat(handle).isNotNull();

        long deadline = System.currentTimeMillis() + 60_000;
        while (!handle.task().isDone()) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("run never finished");
            if (handle.gate().pending() != null) {
                mvc.perform(post("/api/runs/" + runId + "/approve")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(new ApproveRequest(true, null))))
                        .andExpect(status().isOk());
            }
            Thread.sleep(10);
        }

        assertThat(handle.state().phase()).isEqualTo(RunPhase.DONE);
        assertThat(handle.state().reviewIterations()).isEqualTo(2);

        List<SkillIndexEntry> index = skillStoreOverride.index("fixture");
        assertThat(index).extracting(SkillIndexEntry::name).contains("validation-fixture");
    }
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
./gradlew test --tests 'ai.devflow.web.RunFlowIntegrationTest'
```

Expected: compile failure until the imports/fields above are all in place; then, before Tasks 1–9 exist, it would fail because there is nothing to bounce off of. By this point in the plan it should compile cleanly against the finished `Orchestrator`.

- [ ] **Step 3: Run the whole class to confirm the two pre-existing tests still pass**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
./gradlew test --tests 'ai.devflow.web.RunFlowIntegrationTest'
```

Expected: PASS (3 tests) — `aRunStartsPausesAtEachGateAndCompletesWhenApproved` and `rejectingPreFlightEndsTheRun` are unaffected because their task strings don't contain `"needs-a-correction"`, so the widened `stubReviewerAgent` still returns `OK` immediately for them, identical to its old behavior.

- [ ] **Step 4: Run the full suite**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
./gradlew test
```

Expected: PASS, full suite.

- [ ] **Step 5: Commit**

```bash
git add src/test/java/ai/devflow/web/RunFlowIntegrationTest.java
git commit -m "$(cat <<'EOF'
test: full-stack proof a corrected run writes a skill (spec §12)

HTTP start -> real Orchestrator -> a reviewer stub that bounces once
for tasks containing "needs-a-correction" (keyed off that run's own
history, so it's safe under RunFlowIntegrationTest's cached Spring
context) -> a stubbed Scribe -> approve through all three gates ->
assert the skill exists in the host-side FileSkillStore, pointed at a
scratch temp directory via @TestBean so this never touches a real
~/.devflowai. This is spec §12's named "Learning test".

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_019frjxEdfkt5pkfAJJSixtZ
EOF
)"
```

---

## Spec coverage check

- §6.1 format (frontmatter + body) → Task 1
- §6.2 retrieval (frontmatter-only index, LLM-pick ≤3, full read on selection) → Tasks 2, 5, 7
- §6.3 write trigger, Scribe I/O shape, tool-free → Tasks 4, 6, 8 (with the corrected trigger condition — design note #5)
- §6.4 storage/scoping (host-side source of truth, repo-keyed, also committed to the branch) → Tasks 2, 3, 7 (host paths + repoSlug wiring), 8 (committed into the branch)
- §5.2 Gate 3's distinct approve/reject-with-reason/reject-without-reason semantics → Task 8
- §5.3 UI surfacing → Task 9
- §12 "Learning test" → Task 10
- §7 model tiers (router/skill-picker/scribe on Haiku) → Tasks 5, 6 (reuses the existing `router` bean; adds `scribe`)
- §8 tool access (scribe gets none) → Task 6's design note #4

Phase 7 (Planner/test-writer/doc-writer, a routing evaluation harness) and Phase 6 (`ClonedWorkspace`, a real `repoSlug` derivation) are out of scope for this plan by design — see this plan's design notes #1 and #2.
