# devflowai Phase 6 Implementation Plan — ClonedWorkspace

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Point devflowai at a real public git repository instead of only the bundled fixture — "an interviewer can hand over a URL" (spec §12).

**Architecture:** A new `ClonedWorkspace implements Workspace` shallow-clones (`--depth 1`) a repo the operator supplies, behind the same `Workspace` interface `FixtureWorkspace` already implements — agents never know which they got (spec §4). The operator-supplied `repo` string is not a trusted URL; it's untrusted input to a transport dispatcher (spec §4.1, live-verified against JGit 7.1.0): it must pass a strict `https://`-prefix allowlist *before* it ever reaches JGit, because JGit's `ext::` transport executes an arbitrary subprocess and both `file://` and a bare schemeless path silently clone from the server's own filesystem. `RunRegistry` branches on the `repo` string to choose the workspace type, and Phase 5's `repoSlug` (previously a single static config value, fine when only the fixture existed) becomes genuinely per-run, carried on `RunState`.

**Tech Stack:** Java 21 · Spring Boot 4.1.1 · JGit 7.1.0.202411261347-r · Gradle 9.5.1 · JUnit 5 + AssertJ + Mockito

**Spec:** `docs/superpowers/specs/2026-08-27-devflowai-design.md` (§4 and §4.1 primarily; §9 for the security framing; §5 for the `repo` field; §11 for "public repos only")

## Global Constraints

- **Java 21.** JDK 21 is at `/opt/homebrew/opt/openjdk@21` and is NOT on `PATH`. `export JAVA_HOME=/opt/homebrew/opt/openjdk@21` before every `./gradlew` invocation.
- **The repo string must pass a strict `https://`-prefix allowlist before any git operation.** Not a denylist, not a substring scan — a prefix check, validated in `ClonedWorkspace`'s constructor, before `prepare()` ever touches JGit. This is load-bearing per spec §4.1's live-verified findings, not defensive overkill.
- **The repo is public.** No API key or secret in any tracked file. This phase adds no new secret handling — public repos need none (spec §11: "Public repos only | Auth adds no value to the thesis").
- **Every path-taking tool goes through `PathGuard`.** `ClonedWorkspace` follows the exact same `guard = new PathGuard(root)` pattern `FixtureWorkspace` already uses — no exception, no new carve-out.
- **Base package:** `ai.devflow`
- **`GitTools.commit`'s unconditional `git add .` is unaffected by this plan** — it already respects a repo's own `.gitignore` (empirically verified in `GitToolsTest`, corrected in STATUS.md 2026-09-05). This plan does not change `GitTools`.
- **Agents never know which `Workspace` they got.** `CoderAgent`/`ReviewerAgent`/`BuildTools` are untouched by this plan — they already operate purely against `workspace.root()`, generically.

---

## Design decisions this plan makes

**1. `RunState` gains `repoSlug` via an *overloaded* constructor, not a widened required one.** `RunState` is constructed directly in 9 places across the codebase (1 production, 8 tests — `OrchestratorTest` alone has ~15 individual call sites via its own inline construction). Widening the existing 3-arg constructor to require a 4th `repoSlug` argument would force mechanical edits across all of them for no behavioral gain, since none of those tests care about multi-repo behavior. Instead: the existing `RunState(runId, task, workspace)` constructor now delegates to a new `RunState(runId, task, workspace, repoSlug)` constructor with `repoSlug = "fixture"` — the exact value every existing test already implicitly assumed. Zero existing test files need to change. Only `RunRegistry` (which now knows the real answer) uses the 4-arg form.

**2. `Orchestrator` drops `repoSlug` from its constructor entirely and reads `state.repoSlug()` instead.** This *does* touch every `new Orchestrator(...)` call site (4 of them: `OrchestrationConfig`, `OrchestratorTest`'s shared helper, `RunRegistryTest`, `EndToEndLiveTest`) — the same shape Phase 5's Task 7 already handled once for a different parameter set, kept in one task here too so nothing sits half-wired between commits.

**3. The slugging regex used for skill filenames (Phase 5's `SkillDraft.slugify`) is extracted into a shared `ai.devflow.util.Slug` utility**, reused for repo-URL-to-repoSlug derivation. `SkillDraft.slugify` becomes a thin delegating wrapper — its 3 existing call sites (`FileSkillStore.readFull`, `FileSkillStore.write`, `Orchestrator.writeIntoWorkspace`) need no changes at all.

**4. `FixtureWorkspace` and `ClonedWorkspace` share a new package-private `AbstractGitWorkspace` base** for the ~70% of their logic that's now identical between the two (temp-dir root, branch naming, `PathGuard`, recursive cleanup, the self-contained git identity every workspace's repo needs). This is a refactor of existing, tested code, done at the exact moment a second implementation appears — not a speculative abstraction.

**5. Live tests reuse the existing `@Tag("live")` / `liveTest` mechanism as-is**, no build.gradle.kts changes needed — that mechanism already generically excludes/includes by tag across the whole test source set, and was never actually about "needs an API key" specifically, just "needs something the default environment doesn't guarantee" (this phase's live tests need network reachability, not a paid key).

**6. The one full-stack live test deliberately never reaches a real build.** Cloning devflowai's own repo (chosen because it's public, small, and fully within this project's control — no dependency on a third-party repo staying available) and then letting `BuildTools` actually run `./gradlew test` against that clone would recursively run this entire test suite inside itself. The test instead approves Gate 1 (proving the clone genuinely succeeded — reaching Gate 1 at all is only possible after `workspace.prepare()` succeeds) and rejects Gate 2 without a reason (the existing abort path), never invoking `BuildTools`.

---

## File Structure

```
src/main/java/ai/devflow/
  util/
    Slug.java                  NEW — of(String) -> filename/identifier-safe slug
  skill/
    SkillDraft.java             MODIFIED — slugify() delegates to Slug.of()
  workspace/
    AbstractGitWorkspace.java   NEW — package-private shared base
    FixtureWorkspace.java       MODIFIED — extends AbstractGitWorkspace, loses duplicated code
    ClonedWorkspace.java        NEW — the actual Phase 6 deliverable
  orchestrator/
    RunState.java               MODIFIED — repoSlug field + accessor + overloaded constructor
    Orchestrator.java           MODIFIED — repoSlug read from state, not a constructor field
    RunRegistry.java            MODIFIED — branches on repo; derives repoSlug; cloneTimeout
  config/
    OrchestrationConfig.java    MODIFIED — orchestrator bean loses repoSlug @Value; runRegistry bean gains cloneTimeout
  web/
    RunController.java          MODIFIED — a rejected repo URL becomes HTTP 400, not 500
src/main/resources/
  application.yml               MODIFIED — devflowai.skills.repo-slug removed; devflowai.clone.timeout-minutes added
  static/index.html              MODIFIED — a URL field overrides the repo dropdown

src/test/java/ai/devflow/...    mirrors main
```

---

# Task 1: `Slug` utility extraction

**Files:**
- Create: `src/main/java/ai/devflow/util/Slug.java`
- Modify: `src/main/java/ai/devflow/skill/SkillDraft.java`
- Test: `src/test/java/ai/devflow/util/SlugTest.java` (new)

**Interfaces:**
- Produces: `Slug.of(String raw) -> String` — lowercase, collapse any run of non-`[a-z0-9]` characters into a single dash, trim leading/trailing dashes.

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/ai/devflow/util/SlugTest.java`:

```java
package ai.devflow.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SlugTest {

    @Test
    void lowercasesAndTrims() {
        assertThat(Slug.of("  Add Validation  ")).isEqualTo("add-validation");
    }

    @Test
    void collapsesRunsOfNonAlphanumericCharactersIntoOneDash() {
        assertThat(Slug.of("Add   Validation!!!  To Controller")).isEqualTo("add-validation-to-controller");
    }

    @Test
    void stripsLeadingAndTrailingDashes() {
        assertThat(Slug.of("--@Valid--")).isEqualTo("valid");
    }

    @Test
    void slugifiesARealisticRepoUrl() {
        assertThat(Slug.of("https://github.com/zjimmm/devflowai.git"))
                .isEqualTo("https-github-com-zjimmm-devflowai-git");
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
./gradlew test --tests 'ai.devflow.util.SlugTest'
```

Expected: compile failure — `Slug` does not exist yet.

- [ ] **Step 3: Implement `Slug`**

Create `src/main/java/ai/devflow/util/Slug.java`:

```java
package ai.devflow.util;

/**
 * Filename/identifier-safe slugging: lowercase, collapse any run of
 * non-alphanumeric characters into a single dash, trim leading/trailing
 * dashes. Shared by skill-name-to-filename derivation
 * ({@code ai.devflow.skill.SkillDraft}) and repo-URL-to-repoSlug derivation
 * ({@code ai.devflow.orchestrator.RunRegistry}, Phase 6) -- both need the
 * same safe, deterministic rule, and duplicating the regex would risk them
 * drifting apart.
 */
public final class Slug {

    private Slug() {}

    public static String of(String raw) {
        String s = raw.toLowerCase().trim().replaceAll("[^a-z0-9]+", "-");
        return s.replaceAll("(^-+|-+$)", "");
    }
}
```

- [ ] **Step 4: Update `SkillDraft` to delegate**

Replace the body of `SkillDraft.slugify` in `src/main/java/ai/devflow/skill/SkillDraft.java`:

```java
package ai.devflow.skill;

import ai.devflow.util.Slug;

import java.util.List;

public record SkillDraft(String name, String description, List<String> triggers, String body) {

    /** Filename-safe slug, used to match "an existing skill by name" (spec §6.3). */
    public String slug() {
        return slugify(name);
    }

    /**
     * Filename-safe slug for an arbitrary name. Shared by {@link #slug()} and
     * by {@code FileSkillStore.readFull}, which must resolve the same file
     * that {@code FileSkillStore.write} created -- the name reported back by
     * {@code index()}/the picker is the raw frontmatter {@code name:} field,
     * not the slug the file was actually written under. Delegates to the
     * shared {@link Slug} utility (Phase 6 needs the identical rule for
     * repo-URL-to-repoSlug derivation).
     */
    public static String slugify(String name) {
        return Slug.of(name);
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
./gradlew test --tests 'ai.devflow.util.SlugTest' --tests 'ai.devflow.skill.*' --tests 'ai.devflow.orchestrator.*'
```

Expected: PASS. `SkillFileFormatTest`/`FileSkillStoreTest`/`Orchestrator`-related tests that exercise `SkillDraft.slug()`/`slugify()` indirectly must still pass unchanged — the regex is identical, just relocated.

- [ ] **Step 6: Run the full suite**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
./gradlew test
```

Expected: PASS, full suite (137 tests before this task).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/ai/devflow/util/Slug.java \
        src/main/java/ai/devflow/skill/SkillDraft.java \
        src/test/java/ai/devflow/util/SlugTest.java
git commit -m "$(cat <<'EOF'
refactor: extract Slug utility from SkillDraft.slugify

Phase 6 needs the identical filename/identifier-safe slugging rule for
repo-URL-to-repoSlug derivation. SkillDraft.slugify becomes a thin
delegating wrapper -- its 3 existing call sites need no changes.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_019frjxEdfkt5pkfAJJSixtZ
EOF
)"
```

---

# Task 2: `AbstractGitWorkspace` + `ClonedWorkspace`

**Files:**
- Create: `src/main/java/ai/devflow/workspace/AbstractGitWorkspace.java`
- Modify: `src/main/java/ai/devflow/workspace/FixtureWorkspace.java`
- Create: `src/main/java/ai/devflow/workspace/ClonedWorkspace.java`
- Test: `src/test/java/ai/devflow/workspace/ClonedWorkspaceTest.java` (new — no network)
- Test: `src/test/java/ai/devflow/workspace/ClonedWorkspaceLiveTest.java` (new — `@Tag("live")`, real network)
- Verify unchanged: `src/test/java/ai/devflow/workspace/FixtureWorkspaceTest.java` must still pass with ZERO edits — this is the regression proof that the refactor didn't change `FixtureWorkspace`'s behavior.

**Interfaces:**
- Consumes: nothing new
- Produces: `ClonedWorkspace(String repoUrl, String runId, Duration cloneTimeout)` implementing `Workspace`; a package-private `AbstractGitWorkspace` base both workspace classes extend.

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/ai/devflow/workspace/ClonedWorkspaceTest.java`:

```java
package ai.devflow.workspace;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClonedWorkspaceTest {

    @Test
    void rejectsTheExtProtocol() {
        assertThatThrownBy(() -> new ClonedWorkspace("ext::sh -c \"true\"", "t", Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("https://");
    }

    @Test
    void rejectsTheFileProtocol() {
        assertThatThrownBy(() -> new ClonedWorkspace("file:///etc", "t", Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsABareLocalPath() {
        assertThatThrownBy(() -> new ClonedWorkspace("/etc/passwd", "t", Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsPlainHttp() {
        assertThatThrownBy(() -> new ClonedWorkspace("http://example.com/repo.git", "t", Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsSsh() {
        assertThatThrownBy(() -> new ClonedWorkspace("ssh://git@example.com/repo.git", "t", Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> new ClonedWorkspace(null, "t", Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsAnHttpsUrlAtConstructionTimeWithoutTouchingTheNetwork() {
        // Construction alone must never make a network call -- only prepare() does.
        var workspace = new ClonedWorkspace("https://github.com/zjimmm/devflowai.git", "t", Duration.ofSeconds(5));
        assertThat(workspace.branchName()).isEqualTo("devflowai/t");
    }
}
```

Create `src/test/java/ai/devflow/workspace/ClonedWorkspaceLiveTest.java`:

```java
package ai.devflow.workspace;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("live")
class ClonedWorkspaceLiveTest {

    @Test
    void clonesARealPublicRepoAndCreatesTheRunBranch() throws Exception {
        var workspace = new ClonedWorkspace(
                "https://github.com/zjimmm/devflowai.git", "live-clone-test", Duration.ofMinutes(2));
        try {
            workspace.prepare();

            assertThat(Files.exists(workspace.root().resolve("build.gradle.kts"))).isTrue();
            try (Git git = Git.open(workspace.root().toFile())) {
                assertThat(git.getRepository().getBranch()).isEqualTo("devflowai/live-clone-test");
            }
        } finally {
            workspace.cleanup();
        }
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
./gradlew test --tests 'ai.devflow.workspace.ClonedWorkspaceTest'
```

Expected: compile failure — `ClonedWorkspace` does not exist yet.

- [ ] **Step 3: Create `AbstractGitWorkspace`**

Create `src/main/java/ai/devflow/workspace/AbstractGitWorkspace.java`:

```java
package ai.devflow.workspace;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.StoredConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Shared plumbing between workspace implementations that populate a
 * directory with a real, on-disk git repository ({@link FixtureWorkspace},
 * {@link ClonedWorkspace}, Phase 6): the temp-dir lifecycle, the branch
 * name, and the self-contained commit identity every such repo needs (see
 * {@link #configureIdentity} -- without it, JGit falls back to ambient host
 * git config, which is environment-dependent and undesirable for a
 * workspace whose commits should be reproducible).
 */
abstract class AbstractGitWorkspace implements Workspace {

    protected final String runId;
    protected Path root;
    protected PathGuard guard;

    protected AbstractGitWorkspace(String runId) {
        this.runId = runId;
    }

    @Override public Path root() { return root; }
    @Override public String branchName() { return "devflowai/" + runId; }
    @Override public PathGuard guard() { return guard; }

    @Override
    public void cleanup() throws IOException {
        if (root == null || !Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) { }
            });
        }
    }

    protected void configureIdentity(Git git) throws IOException {
        StoredConfig config = git.getRepository().getConfig();
        config.setString("user", null, "name", "devflowai");
        config.setString("user", null, "email", "devflowai@localhost");
        config.save();
    }
}
```

- [ ] **Step 4: Rewrite `FixtureWorkspace` to extend it**

Replace the whole file `src/main/java/ai/devflow/workspace/FixtureWorkspace.java`:

```java
package ai.devflow.workspace;

import org.eclipse.jgit.api.Git;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.Set;
import java.util.stream.Stream;

public class FixtureWorkspace extends AbstractGitWorkspace {

    private static final Set<String> SKIP_DIR_NAMES = Set.of(".git", ".gradle", "build");

    private final Path source;

    public FixtureWorkspace(Path source, String runId) {
        super(runId);
        this.source = source;
    }

    @Override
    public void prepare() throws IOException {
        root = Files.createTempDirectory("devflowai-" + runId + "-");
        copyRecursively(source, root);
        try (Git git = Git.init().setDirectory(root.toFile()).call()) {
            configureIdentity(git);
            git.add().addFilepattern(".").call();
            git.commit().setMessage("baseline").setSign(false).call();
            git.checkout().setCreateBranch(true).setName(branchName()).call();
        } catch (Exception e) {
            throw new IOException("Failed to initialise git in workspace", e);
        }
        guard = new PathGuard(root);
    }

    private void copyRecursively(Path from, Path to) throws IOException {
        // Skip build-artifact directories (.git, .gradle, build) wherever they
        // occur under the source tree. The fixture is a standalone Gradle
        // project (Task 4's brief itself instructs verifying it builds
        // standalone), so a `.gradle` cache and `build` output directory can
        // legitimately exist on disk next to it even though they're gitignored
        // and never committed -- copyRecursively walks the real filesystem, not
        // git's tracked state, so without this filter those directories (and
        // anything git-related) get copied into every temp workspace. Matched
        // by exact path-component name, not substring, so a real source file
        // like MyBuildHelper.java is never excluded.
        try (Stream<Path> walk = Files.walk(from)) {
            walk.filter(src -> !isUnderSkippedDir(from, src))
                .forEach(src -> {
                    try {
                        Path dest = to.resolve(from.relativize(src).toString());
                        if (Files.isDirectory(src)) Files.createDirectories(dest);
                        else Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
        }
    }

    private boolean isUnderSkippedDir(Path root, Path candidate) {
        for (Path component : root.relativize(candidate)) {
            if (SKIP_DIR_NAMES.contains(component.toString())) return true;
        }
        return false;
    }
}
```

- [ ] **Step 5: Implement `ClonedWorkspace`**

Create `src/main/java/ai/devflow/workspace/ClonedWorkspace.java`:

```java
package ai.devflow.workspace;

import org.eclipse.jgit.api.Git;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;

/**
 * Populates a workspace by shallow-cloning a real, public git repository
 * (spec §4, Phase 6) -- "public repos, shallow clone, run, delete."
 *
 * <p>The constructor validates the URL before any network call, not after:
 * spec §4.1 records the live-verified reason a bare https:// prefix
 * allowlist is load-bearing here rather than defensive overkill -- JGit's
 * {@code ext::} transport executes an arbitrary subprocess, and both
 * {@code file://} and a schemeless bare path silently clone from the
 * server's own filesystem.
 */
public class ClonedWorkspace extends AbstractGitWorkspace {

    private static final String ALLOWED_SCHEME = "https://";

    private final String repoUrl;
    private final Duration cloneTimeout;

    public ClonedWorkspace(String repoUrl, String runId, Duration cloneTimeout) {
        super(runId);
        if (repoUrl == null || !repoUrl.startsWith(ALLOWED_SCHEME)) {
            throw new IllegalArgumentException("Repo must be an https:// URL, got: " + repoUrl);
        }
        this.repoUrl = repoUrl;
        this.cloneTimeout = cloneTimeout;
    }

    @Override
    public void prepare() throws IOException {
        root = Files.createTempDirectory("devflowai-" + runId + "-");
        try (Git git = Git.cloneRepository()
                .setURI(repoUrl)
                .setDirectory(root.toFile())
                .setDepth(1)
                .setTimeout((int) cloneTimeout.toSeconds())
                .call()) {
            configureIdentity(git);
            git.checkout().setCreateBranch(true).setName(branchName()).call();
        } catch (Exception e) {
            throw new IOException("Failed to clone " + repoUrl, e);
        }
        guard = new PathGuard(root);
    }
}
```

- [ ] **Step 6: Run the tests to verify they pass**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
./gradlew test --tests 'ai.devflow.workspace.ClonedWorkspaceTest' --tests 'ai.devflow.workspace.FixtureWorkspaceTest'
```

Expected: PASS. `ClonedWorkspaceTest`'s 7 cases pass; `FixtureWorkspaceTest`'s existing 4 cases pass **unmodified** — this is the proof the refactor preserved `FixtureWorkspace`'s exact behavior.

- [ ] **Step 7: Run the live test manually (not part of the default suite)**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
./gradlew liveTest --tests 'ai.devflow.workspace.ClonedWorkspaceLiveTest'
```

Expected: PASS, given network access to github.com. This needs no `ANTHROPIC_API_KEY` and makes no LLM call — only a real git clone. Confirm this in your report.

- [ ] **Step 8: Run the full default suite**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
./gradlew test
```

Expected: PASS. The live test does NOT run here (excluded by tag) — confirm the total count only grew by `ClonedWorkspaceTest`'s 7 cases, not 8.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/ai/devflow/workspace/AbstractGitWorkspace.java \
        src/main/java/ai/devflow/workspace/FixtureWorkspace.java \
        src/main/java/ai/devflow/workspace/ClonedWorkspace.java \
        src/test/java/ai/devflow/workspace/ClonedWorkspaceTest.java \
        src/test/java/ai/devflow/workspace/ClonedWorkspaceLiveTest.java
git commit -m "$(cat <<'EOF'
feat: ClonedWorkspace shallow-clones a real public repo (spec §4, §4.1)

The constructor validates the repo string against a strict https://
prefix allowlist before any network call -- live-verified against the
resolved JGit 7.1.0 jar (spec §4.1) that ext:: executes an arbitrary
subprocess and both file:// and a bare schemeless path silently clone
from the server's own filesystem. All three are closed by the same
prefix check.

FixtureWorkspace now extends a new AbstractGitWorkspace base shared
with ClonedWorkspace (temp-dir lifecycle, branch naming, PathGuard,
recursive cleanup, the self-contained commit identity) -- refactored
at the exact moment a second implementation appears, not speculatively.
FixtureWorkspaceTest passes unmodified, proving no behavior changed.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_019frjxEdfkt5pkfAJJSixtZ
EOF
)"
```

---

# Task 3: `repoSlug` becomes per-run

**Files:**
- Modify: `src/main/java/ai/devflow/orchestrator/RunState.java`
- Modify: `src/main/java/ai/devflow/orchestrator/Orchestrator.java`
- Modify: `src/main/java/ai/devflow/config/OrchestrationConfig.java`
- Modify: `src/main/resources/application.yml`
- Modify: `src/test/java/ai/devflow/orchestrator/RunStateTest.java`
- Modify: `src/test/java/ai/devflow/orchestrator/OrchestratorTest.java`
- Modify: `src/test/java/ai/devflow/orchestrator/RunRegistryTest.java`
- Modify: `src/test/java/ai/devflow/EndToEndLiveTest.java`

**Interfaces:**
- Produces: `RunState.repoSlug()`; `RunState`'s existing 3-arg constructor now delegates to a new 4-arg one; `Orchestrator`'s constructor drops its `String repoSlug` parameter (10 params, down from 11).

- [ ] **Step 1: Write the failing tests**

Add to `src/test/java/ai/devflow/orchestrator/RunStateTest.java`:

```java
    @Test
    void repoSlugDefaultsToFixtureViaTheThreeArgConstructor() {
        var state = new RunState("r", "t", workspace);
        assertThat(state.repoSlug()).isEqualTo("fixture");
    }

    @Test
    void repoSlugIsExplicitViaTheFourArgConstructor() {
        var state = new RunState("r", "t", workspace, "github-com-owner-repo");
        assertThat(state.repoSlug()).isEqualTo("github-com-owner-repo");
    }
```

In `src/test/java/ai/devflow/orchestrator/OrchestratorTest.java`, find the shared `orchestrator(...)` helper's `new Orchestrator(...)` call:

```java
        return new Orchestrator(coder, reviewer, picker, scribe, skillStore, memoryStore, "fixture",
                events, 3, 5, Duration.ofMinutes(1));
```

Remove the `"fixture",` argument:

```java
        return new Orchestrator(coder, reviewer, picker, scribe, skillStore, memoryStore,
                events, 3, 5, Duration.ofMinutes(1));
```

In `src/test/java/ai/devflow/orchestrator/RunRegistryTest.java`, find (in `setUp()`):

```java
        var orchestrator = new Orchestrator(new StubAgent("coder"), new StubAgent("reviewer"),
                (task, index) -> List.of(), (state, findings, reason) -> ScribeDraft.EMPTY,
                new NoOpSkillStore(), new NoOpMemoryStore(), "fixture",
                events, 3, 5, Duration.ofMinutes(1));
```

Remove the `"fixture",` argument (same edit as above):

```java
        var orchestrator = new Orchestrator(new StubAgent("coder"), new StubAgent("reviewer"),
                (task, index) -> List.of(), (state, findings, reason) -> ScribeDraft.EMPTY,
                new NoOpSkillStore(), new NoOpMemoryStore(),
                events, 3, 5, Duration.ofMinutes(1));
```

In `src/test/java/ai/devflow/EndToEndLiveTest.java`, find (inside the `new Orchestrator(...)` call):

```java
                    "fixture",
                    new ai.devflow.event.RunEventPublisher(),
```

Remove that line entirely:

```java
                    new ai.devflow.event.RunEventPublisher(),
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
./gradlew compileTestJava
```

Expected: compile failure — `RunState.repoSlug()` doesn't exist yet, and the `Orchestrator` constructor still has 11 params (so the edited call sites above, now with 10 positional args, don't match either — every touched file fails to compile until Step 3 lands). This is expected; resolve in the next step.

- [ ] **Step 3: Update `RunState`**

In `src/main/java/ai/devflow/orchestrator/RunState.java`, add a field:

```java
    private final String repoSlug;
```

(alongside the existing `runId`/`task`/`workspace`/`gitTools` fields)

Replace the existing constructor with two:

```java
    public RunState(String runId, String task, Workspace workspace) {
        this(runId, task, workspace, "fixture");
    }

    public RunState(String runId, String task, Workspace workspace, String repoSlug) {
        this.runId = runId;
        this.task = task;
        this.workspace = workspace;
        this.repoSlug = repoSlug;
        // Bound to THIS run's workspace, created once. Agents read tools from
        // here rather than holding their own, so agents stay stateless and are
        // safe to register as singleton beans (Phase 3 finding I3).
        this.gitTools = new GitTools(workspace);
    }
```

Add an accessor alongside the other unsynchronized ones (`repoSlug` is final, set once at construction, same as `runId`/`task`):

```java
    public String repoSlug() { return repoSlug; }
```

- [ ] **Step 4: Update `Orchestrator`**

In `src/main/java/ai/devflow/orchestrator/Orchestrator.java`, remove the field:

```java
    private final String repoSlug;
```

Remove `String repoSlug,` from the constructor's parameter list (between `MemoryStore memoryStore,` and `RunEventPublisher events,`) and remove the corresponding `this.repoSlug = repoSlug;` line from the constructor body.

Replace every remaining use of the (now-removed) `repoSlug` field with `state.repoSlug()`:

In `loadKnowledge(RunState state)`:
```java
        String memory = memoryStore.read(state.repoSlug());
```
```java
        List<SkillIndexEntry> index = skillStore.index(state.repoSlug());
```
```java
            String full = skillStore.readFull(state.repoSlug(), name);
```

In the Gate 3 persistence block:
```java
                    skillStore.write(state.repoSlug(), state.runId(), draft.skill());
```
```java
                    String updated = memoryStore.append(state.repoSlug(), draft.memoryFact());
```

- [ ] **Step 5: Update `OrchestrationConfig`**

In `src/main/java/ai/devflow/config/OrchestrationConfig.java`, in the `orchestrator(...)` bean method, remove the `@Value("${devflowai.skills.repo-slug:fixture}") String repoSlug,` parameter and the `repoSlug,` argument passed to `new Orchestrator(...)`:

```java
    @Bean
    Orchestrator orchestrator(Agent coderAgent, Agent reviewerAgent, SkillPicker skillPicker, Scribe scribe,
                              SkillStore skillStore, MemoryStore memoryStore, RunEventPublisher events,
                              @Value("${devflowai.review.max-iterations:3}") int maxReviewIterations,
                              @Value("${devflowai.review.max-human-iterations:5}") int maxHumanIterations,
                              @Value("${devflowai.build.timeout-minutes:5}") long buildTimeoutMinutes) {
        return new Orchestrator(coderAgent, reviewerAgent, skillPicker, scribe, skillStore, memoryStore,
                events, maxReviewIterations, maxHumanIterations, Duration.ofMinutes(buildTimeoutMinutes));
    }
```

- [ ] **Step 6: Update `application.yml`**

Remove:

```yaml
  skills:
    repo-slug: fixture
```

- [ ] **Step 7: Run the tests to verify they pass**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
./gradlew test
```

Expected: PASS, full suite. Every test that previously relied on the implicit `repoSlug = "fixture"` behavior continues to get exactly that via the new 3-arg `RunState` constructor's default — confirm no test needed any change beyond the `new Orchestrator(...)` call-site edits above.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/ai/devflow/orchestrator/RunState.java \
        src/main/java/ai/devflow/orchestrator/Orchestrator.java \
        src/main/java/ai/devflow/config/OrchestrationConfig.java \
        src/main/resources/application.yml \
        src/test/java/ai/devflow/orchestrator/RunStateTest.java \
        src/test/java/ai/devflow/orchestrator/OrchestratorTest.java \
        src/test/java/ai/devflow/orchestrator/RunRegistryTest.java \
        src/test/java/ai/devflow/EndToEndLiveTest.java
git commit -m "$(cat <<'EOF'
refactor: repoSlug becomes per-run, not a static Orchestrator constructor value

Phase 5 hardcoded devflowai.skills.repo-slug=fixture as a single value
applied to every run regardless of which repo it targets -- fine when
only the fixture existed, wrong the moment ClonedWorkspace lets two
different runs target two different repos (a skill learned on repo A
must not apply to repo B).

RunState's existing 3-arg constructor now delegates to a new 4-arg one
defaulting repoSlug to "fixture" -- the value every existing test
already implicitly assumed, so none of the 8 test files that construct
RunState directly needed to change. Orchestrator drops repoSlug from
its constructor entirely and reads state.repoSlug() at each of its 5
call sites instead; the 4 call sites of new Orchestrator(...) (down
to 10 params) were updated to match.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_019frjxEdfkt5pkfAJJSixtZ
EOF
)"
```

---

# Task 4: `RunRegistry` routes by repo; a rejected URL becomes HTTP 400

**Files:**
- Modify: `src/main/java/ai/devflow/orchestrator/RunRegistry.java`
- Modify: `src/main/java/ai/devflow/web/RunController.java`
- Modify: `src/main/java/ai/devflow/config/OrchestrationConfig.java`
- Modify: `src/main/resources/application.yml`
- Modify: `src/test/java/ai/devflow/orchestrator/RunRegistryTest.java`
- Modify: `src/test/java/ai/devflow/web/RunControllerTest.java`

**Interfaces:**
- Consumes: `ClonedWorkspace` (Task 2), `RunState`'s 4-arg constructor (Task 3), `Slug.of` (Task 1)
- Produces: `RunRegistry`'s constructor gains a `Duration cloneTimeout` parameter; `RunRegistry.start(task, repo)` now genuinely dispatches on `repo`.

- [ ] **Step 1: Write the failing tests**

Add to `src/test/java/ai/devflow/orchestrator/RunRegistryTest.java` — first the import:

```java
import static org.assertj.core.api.Assertions.assertThatThrownBy;
```

(alongside the existing `import static org.assertj.core.api.Assertions.assertThat;`)

Update the `RunRegistry` construction inside `setUp()` to add the new 6th argument:

```java
        registry = new RunRegistry(orchestrator, events, Executors.newCachedThreadPool(),
                java.nio.file.Path.of("src/test/resources/fixture"), Duration.ofSeconds(2),
                Duration.ofSeconds(2));
```

Add a new test:

```java
    @Test
    void aNonHttpsRepoIsRejectedSynchronouslyWithoutStartingAnyWork() {
        assertThatThrownBy(() -> registry.start("task", "ext::sh -c \"true\""))
                .isInstanceOf(IllegalArgumentException.class);
    }
```

Add to `src/test/java/ai/devflow/web/RunControllerTest.java`:

```java
    @Test
    void startingARunRejectsARepoStringThatIsNotAnHttpsUrl() throws Exception {
        when(registry.start(any(), any()))
                .thenThrow(new IllegalArgumentException("Repo must be an https:// URL, got: ext::sh -c \"true\""));

        mvc.perform(post("/api/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new StartRunRequest("do a thing", "ext::sh -c \"true\""))))
                .andExpect(status().isBadRequest());
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
./gradlew compileTestJava
```

Expected: compile failure — `RunRegistry`'s constructor still takes 5 params, not 6.

- [ ] **Step 3: Rewrite `RunRegistry`**

Replace the whole file `src/main/java/ai/devflow/orchestrator/RunRegistry.java`:

```java
package ai.devflow.orchestrator;

import ai.devflow.event.RunEvent;
import ai.devflow.event.RunEventPublisher;
import ai.devflow.util.Slug;
import ai.devflow.workspace.ClonedWorkspace;
import ai.devflow.workspace.FixtureWorkspace;
import ai.devflow.workspace.Workspace;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/**
 * Starts runs and keeps the in-flight ones addressable by id.
 *
 * <p>In-memory and single-node by design (spec §11): an interrupted run is
 * lost, and that is an accepted limitation for a single-user local tool.
 */
public class RunRegistry {

    private final Orchestrator orchestrator;
    private final RunEventPublisher events;
    private final ExecutorService executor;
    private final Path fixtureSource;
    private final Duration gateTimeout;
    private final Duration cloneTimeout;

    private final Map<String, RunHandle> runs = new ConcurrentHashMap<>();

    public RunRegistry(Orchestrator orchestrator, RunEventPublisher events,
                       ExecutorService executor, Path fixtureSource, Duration gateTimeout,
                       Duration cloneTimeout) {
        this.orchestrator = orchestrator;
        this.events = events;
        this.executor = executor;
        this.fixtureSource = fixtureSource;
        this.gateTimeout = gateTimeout;
        this.cloneTimeout = cloneTimeout;
    }

    /**
     * Prepares a workspace and starts the orchestrator on the executor.
     *
     * @param repo {@code "fixture"} for the bundled fixture, or an
     *             {@code https://} git URL to clone (spec §4.1). A rejected
     *             URL throws {@link IllegalArgumentException} synchronously
     *             from this method -- {@link ClonedWorkspace}'s constructor
     *             validates before any network call, so the caller (
     *             {@code RunController}) gets an immediate, clean error
     *             rather than an async failure event after the run has
     *             already been reported started.
     */
    public RunHandle start(String task, String repo) {
        String runId = UUID.randomUUID().toString().substring(0, 8);
        Workspace workspace;
        String repoSlug;
        if (repo.equals("fixture")) {
            workspace = new FixtureWorkspace(fixtureSource, runId);
            repoSlug = "fixture";
        } else {
            workspace = new ClonedWorkspace(repo, runId, cloneTimeout);
            repoSlug = Slug.of(repo);
        }
        RunState state = new RunState(runId, task, workspace, repoSlug);
        ApprovalGate gate = new ApprovalGate(gateTimeout);

        var future = executor.submit(() -> {
            try {
                workspace.prepare();
                orchestrator.run(state, gate);
            } catch (Exception e) {
                state.setPhase(RunPhase.FAILED);
                events.publish(runId, RunEvent.of("error",
                        "Could not prepare the workspace: " + e.getMessage(), Map.of()));
                // workspace.prepare() can throw partway through (e.g. after
                // copying files but before git init completes), leaking a temp
                // directory on disk. Orchestrator.run's own cleanup is never
                // reached here because orchestrator.run was never called.
                try {
                    workspace.cleanup();
                } catch (Exception cleanupFailure) {
                    events.publish(runId, RunEvent.of("warn",
                            "Workspace cleanup also failed: " + cleanupFailure.getMessage(), Map.of()));
                }
                events.complete(runId);
            } finally {
                runs.remove(runId);
            }
        });

        RunHandle handle = new RunHandle(runId, state, gate, future);
        runs.put(runId, handle);
        return handle;
    }

    public RunHandle find(String runId) {
        return runs.get(runId);
    }
}
```

- [ ] **Step 4: Update `RunController`**

In `src/main/java/ai/devflow/web/RunController.java`, replace the `start` method:

```java
    @PostMapping
    public ResponseEntity<Map<String, String>> start(@RequestBody StartRunRequest request) {
        if (request.task() == null || request.task().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "task must not be blank"));
        }
        String repo = (request.repo() == null || request.repo().isBlank()) ? "fixture" : request.repo();
        RunHandle handle;
        try {
            handle = registry.start(request.task().trim(), repo);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
        return ResponseEntity.ok(Map.of("runId", handle.runId()));
    }
```

- [ ] **Step 5: Update `OrchestrationConfig`**

In `src/main/java/ai/devflow/config/OrchestrationConfig.java`, replace the `runRegistry(...)` bean method:

```java
    @Bean
    RunRegistry runRegistry(Orchestrator orchestrator, RunEventPublisher events, ExecutorService runExecutor,
                            @Value("${devflowai.fixture.path:src/test/resources/fixture}") String fixturePath,
                            @Value("${devflowai.gate.timeout-minutes:10}") long gateTimeoutMinutes,
                            @Value("${devflowai.clone.timeout-minutes:2}") long cloneTimeoutMinutes) {
        return new RunRegistry(orchestrator, events, runExecutor,
                Path.of(fixturePath), Duration.ofMinutes(gateTimeoutMinutes),
                Duration.ofMinutes(cloneTimeoutMinutes));
    }
```

- [ ] **Step 6: Update `application.yml`**

Add under the existing `devflowai:` key:

```yaml
  clone:
    timeout-minutes: 2
```

- [ ] **Step 7: Run the tests to verify they pass**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
./gradlew test
```

Expected: PASS, full suite.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/ai/devflow/orchestrator/RunRegistry.java \
        src/main/java/ai/devflow/web/RunController.java \
        src/main/java/ai/devflow/config/OrchestrationConfig.java \
        src/main/resources/application.yml \
        src/test/java/ai/devflow/orchestrator/RunRegistryTest.java \
        src/test/java/ai/devflow/web/RunControllerTest.java
git commit -m "$(cat <<'EOF'
feat: RunRegistry routes fixture vs a real repo URL; bad URL -> HTTP 400

RunRegistry.start(task, repo) previously ignored repo entirely and
always built a FixtureWorkspace. It now branches: "fixture" keeps the
existing path; anything else constructs a ClonedWorkspace, whose
constructor validates the URL (spec §4.1) before any network call --
synchronously, from inside this method, so a rejected URL throws
before the run is ever reported started. RunController converts that
IllegalArgumentException into a clean 400 rather than letting it
surface as a raw 500.

repoSlug is now derived per-run via Slug.of(repo) for a real URL, or
"fixture" unchanged.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_019frjxEdfkt5pkfAJJSixtZ
EOF
)"
```

---

# Task 5: Operator page — a URL field overrides the repo dropdown

**Files:**
- Modify: `src/main/resources/static/index.html`

**Interfaces:**
- Consumes: the existing `POST /api/runs {task, repo}` contract (unchanged)

- [ ] **Step 1: Add the URL field**

In `src/main/resources/static/index.html`, replace the Repo row:

```html
    <div class="row">
      <label for="repo">Repo</label>
      <select id="repo"><option value="fixture">bundled fixture</option></select>
      <input id="repo-url" placeholder="or paste a public https:// git URL instead" style="margin-top:0.4rem">
    </div>
```

- [ ] **Step 2: Use it when present, with an inline client-side check**

Replace the start of the `$('run').onclick` handler:

```javascript
  $('run').onclick = async () => {
    const task = $('task').value.trim();
    if (!task) return;
    const repoUrl = $('repo-url').value.trim();
    if (repoUrl && !repoUrl.startsWith('https://')) {
      log('error', 'Repo URL must start with https://');
      return;
    }
    const repo = repoUrl || $('repo').value;
    $('run').disabled = true;
    $('log').innerHTML = '';
    hideGate();

    const res = await fetch('/api/runs', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ task, repo })
    });
```

(Everything after this line is unchanged — only the `body: JSON.stringify({ task, repo: $('repo').value })` line becomes `body: JSON.stringify({ task, repo })` using the new local variable.)

- [ ] **Step 3: Verify manually in the browser**

No JS test harness exists in this project (matches Phase 5 Task 9's precedent). Verification is manual:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
./gradlew bootRun
```

Open `http://localhost:8080`. Confirm: leaving the URL field blank and clicking Run still sends `repo: "fixture"` (check the Network tab or the existing behavior); typing a non-`https://` string into the URL field and clicking Run shows the inline "must start with https://" error without a network call; typing a real `https://` URL and clicking Run sends that URL as `repo` in the POST body (the actual run will fail without a live API key, exactly as already observed for the fixture path this session — that's expected and unrelated to this task).

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/static/index.html
git commit -m "$(cat <<'EOF'
feat: operator page can target a real repo URL instead of the fixture

A URL field next to the repo dropdown overrides it when filled --
matches spec §5.3's original mockup ("Repo [ fixture ] or [
https://github.com/... ]"). A client-side https:// prefix check gives
immediate feedback; the server (ClonedWorkspace's constructor, spec
§4.1) remains the authoritative check.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_019frjxEdfkt5pkfAJJSixtZ
EOF
)"
```

---

# Task 6: Full-stack live test — a real clone flows through HTTP end to end

**Files:**
- Modify: `src/test/java/ai/devflow/web/RunFlowIntegrationTest.java`

**Interfaces:**
- Consumes: the existing `@TestBean` overrides and MockMvc setup already in this file (Phase 5, Task 10)

This is deliberately added as one more `@Tag("live")`-annotated *method* in the existing `RunFlowIntegrationTest` class rather than a new class: this class already declares all 5 `@TestBean` overrides (`coderAgent`, `reviewerAgent`, `scribeAgent`, `skillStore`, `memoryStore`) and the MockMvc/SpringBootTest setup this test also needs — duplicating that into a separate class would be ~60 lines of pure repetition for one test. Per-method `@Tag` is standard JUnit 5 behavior the project's own `build.gradle.kts` tag-based include/exclude already operates on at method granularity, not class granularity.

- [ ] **Step 1: Write the test**

Add the import to `src/test/java/ai/devflow/web/RunFlowIntegrationTest.java`:

```java
import org.junit.jupiter.api.Tag;
```

Add the test method:

```java
    @Test
    @Tag("live")
    void aClonedRepoFlowsThroughGate1AndAbortsCleanlyAtGate2() throws Exception {
        String body = mvc.perform(post("/api/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                new StartRunRequest("add a class", "https://github.com/zjimmm/devflowai.git"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String runId = json.readTree(body).get("runId").asText();
        RunHandle handle = registry.find(runId);
        assertThat(handle).isNotNull();

        // Approve Gate 1 (pre-flight) so the coder/reviewer stubs run against
        // the REAL cloned repo, then reject Gate 2 without a reason. Reaching
        // Gate 1 at all is itself the proof the clone succeeded -- if it
        // hadn't, RunRegistry's own catch block would have published an
        // error and completed the run before Orchestrator.run (and so Gate 1)
        // was ever reached. This deliberately never lets BuildTools run a
        // real build against devflowai's own test suite inside its own clone.
        long gate1Deadline = System.currentTimeMillis() + 120_000; // a real network clone can be slow
        while (handle.gate().pending() != Gate.PRE_FLIGHT) {
            if (System.currentTimeMillis() > gate1Deadline) {
                throw new AssertionError("clone never reached Gate 1 (pending=" + handle.gate().pending() + ")");
            }
            Thread.sleep(5);
        }
        mvc.perform(post("/api/runs/" + runId + "/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new ApproveRequest(true, null))))
                .andExpect(status().isOk());

        long gate2Deadline = System.currentTimeMillis() + 30_000;
        while (handle.gate().pending() != Gate.BEFORE_BUILD) {
            if (System.currentTimeMillis() > gate2Deadline) {
                throw new AssertionError("run never reached Gate 2 (pending=" + handle.gate().pending() + ")");
            }
            Thread.sleep(5);
        }
        mvc.perform(post("/api/runs/" + runId + "/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new ApproveRequest(false, null))))
                .andExpect(status().isOk());

        long deadline = System.currentTimeMillis() + 30_000;
        while (!handle.task().isDone()) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("run never ended");
            Thread.sleep(10);
        }

        assertThat(handle.state().phase()).isEqualTo(RunPhase.FAILED);
    }
```

- [ ] **Step 2: Run it manually (not part of the default suite)**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
./gradlew liveTest --tests 'ai.devflow.web.RunFlowIntegrationTest'
```

Expected: PASS, given network access to github.com. No `ANTHROPIC_API_KEY` needed (all LLM-calling beans are stubbed in this class, as they already are for its other 3 tests).

- [ ] **Step 3: Run the default suite to confirm this test is excluded from it**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
./gradlew test
```

Expected: PASS, full suite, same count as after Task 5 — this new test must not appear in the default run.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/ai/devflow/web/RunFlowIntegrationTest.java
git commit -m "$(cat <<'EOF'
test: prove a real cloned repo flows through HTTP end to end (live)

One more @Tag("live") method on the existing RunFlowIntegrationTest --
reuses its 5 existing @TestBean overrides rather than duplicating ~60
lines of setup in a new class. Clones devflowai's own public repo
(fully within this project's control, unlike a third-party demo repo),
approves Gate 1 (reaching it at all is the proof the clone succeeded),
then rejects Gate 2 without a reason -- deliberately never lets
BuildTools run a real (recursively nested) build against devflowai's
own test suite inside its own clone.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_019frjxEdfkt5pkfAJJSixtZ
EOF
)"
```

---

## Spec coverage check

- §4 (`Workspace` interface, `ClonedWorkspace` populated by `git clone --depth 1`) → Task 2
- §4.1 (strict `https://` allowlist, live-verified JGit findings) → Task 2
- §5 (the `repo` field, corrected from the stale `repoUrl?` spelling — that correction landed in the spec doc itself during brainstorming, not in this plan) → Task 4 implements the behavior the corrected line describes
- §9 (repo-URL scheme confinement alongside path confinement) → Task 2
- §11 ("Public repos only") → Task 2's validation enforces exactly this
- §12 ("An interviewer can hand it a URL") → Task 5 (the UI surface that makes this true for an actual person, not just the API)

Not in scope for this plan, by design: private-repo authentication, branch push-back, container isolation (all explicitly out of scope per spec §4/§9); a curated list of "known good" demo repos beyond free-text URL entry (YAGNI — the spec's own mockup shows free text).
