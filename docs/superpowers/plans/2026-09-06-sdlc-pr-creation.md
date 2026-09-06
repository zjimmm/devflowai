# PR Creation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** After a successful, approved commit on a `ClonedWorkspace` run, let an operator who opted in get a real pushed branch and a real GitHub pull request, closing the gap where `AbstractGitWorkspace.cleanup()` currently destroys every run's work the moment it ends.

**Architecture:** A new `GitHubClient` seam (mirroring `CodingWorker`'s provider-neutral isolation of the LLM) with one production implementation (`GitHubApiClient`, JGit for push + Spring's `RestClient` for PR creation) and a new `PullRequestContent` pure-function assembler that builds PR title/body from data already on `RunState`. Both `Orchestrator` and `DirectExecutor` gain one optional step after their existing commit step: push, then attempt a PR, with push failure treated as a real loss (loud, explicit) and PR failure as a graceful degradation (branch is already safe).

**Tech Stack:** Spring Boot 4.1.1, Java 21, JGit (already a dependency), Spring's `RestClient` (ships with `spring-web`, already a dependency, no new library), JUnit 5, AssertJ, Mockito.

**Spec:** `docs/superpowers/specs/2026-09-06-sdlc-pr-creation-design.md`

## Global Constraints

- JDK 21 at `/opt/homebrew/opt/openjdk@21`, not on PATH — `export JAVA_HOME=/opt/homebrew/opt/openjdk@21` before every `./gradlew` invocation.
- GitHub only. A non-`github.com` `https://` URL combined with `openPr: true` is rejected with a 400 by `RunController`, never attempted.
- The token is read once from the `DEVFLOWAI_GITHUB_TOKEN` environment variable — never a tracked file (this repo is public), never logged, never included in any SSE event payload.
- Push failure is a real, irreversible loss of already-committed work (the workspace is about to be deleted) — its `"error"` event message must say so explicitly. PR-creation failure, once the push already succeeded, must still end the run `DONE` (nothing was lost) with a `"warn"` event instead.
- No new LLM call for PR content — `PullRequestContent` is a pure function over data already produced during the run.
- One-shot only: no retry loop and no new gate for the push/PR step.
- Every history-table/log cell that renders a `prUrl` value must set `.textContent` for the link label and only the `href` attribute directly — never `innerHTML` with interpolated data (the established XSS-safety precedent in `index.html`).

---

## File Structure

**New:**
- `src/main/java/ai/devflow/tools/GitHubClient.java` — interface
- `src/main/java/ai/devflow/tools/PullRequestResult.java` — record
- `src/main/java/ai/devflow/tools/GitHubClientException.java` — checked exception
- `src/main/java/ai/devflow/tools/GitHubApiClient.java` — production implementation
- `src/test/java/ai/devflow/tools/GitHubApiClientTest.java`
- `src/main/java/ai/devflow/orchestrator/PullRequestContent.java` — PR title/body assembly
- `src/test/java/ai/devflow/orchestrator/PullRequestContentTest.java`
- `src/test/java/ai/devflow/GitHubIntegrationLiveTest.java` — gated live test (will not run until `DEVFLOWAI_LIVETEST_REPO` is configured outside this plan)

**Modified:**
- `RunPhase.java` — gains `OPENING_PR`
- `RunState.java` — gains `openPr` field + accessor, new 5-arg constructor
- `Workspace.java` — gains `String repoUrl()`
- `AbstractGitWorkspace.java` — default `repoUrl()` returning `null`
- `ClonedWorkspace.java` — overrides `repoUrl()`
- `Orchestrator.java` — constructor gains `GitHubClient`; push/PR step wired in after commit
- `DirectExecutor.java` — constructor gains `GitHubClient`; identical wiring
- `StartRunRequest.java` — gains `openPr` field
- `RunController.java` — validates `openPr` (fixture + non-GitHub rejection)
- `RunRegistry.java` — `start(...)` gains `openPr` param
- `OrchestrationConfig.java` — new `gitHubClient` bean; `orchestrator`/`directExecutor` beans updated
- `SdlcRun.java` — gains `prUrl` column
- `SdlcRunRecorder.java` — gains `maybeRecordPrUrl`
- `RunSummary.java` — surfaces `prUrl`
- `src/main/resources/static/index.html` — "Open a pull request" checkbox, PR link rendering
- Test files: `OrchestratorTest`, `DirectExecutorTest`, `RunControllerTest`, `RunRegistryTest`, `RunFlowIntegrationTest`, `StaticPageTest` — see individual tasks

---

# Task 1: `GitHubClient` seam + `GitHubApiClient`

**Files:**
- Create: `src/main/java/ai/devflow/tools/GitHubClient.java`
- Create: `src/main/java/ai/devflow/tools/PullRequestResult.java`
- Create: `src/main/java/ai/devflow/tools/GitHubClientException.java`
- Create: `src/main/java/ai/devflow/tools/GitHubApiClient.java`
- Test: `src/test/java/ai/devflow/tools/GitHubApiClientTest.java`

**Interfaces:**
- Produces: `GitHubClient { void push(Workspace, String branchName) throws GitHubClientException; PullRequestResult openPullRequest(String repoUrl, String branchName, String title, String body) throws GitHubClientException; }`; `PullRequestResult(String url, int number)`; `GitHubClientException extends Exception`; `GitHubApiClient(String token, RestClient restClient) implements GitHubClient`; package-private `GitHubApiClient.parseOwnerRepo(String repoUrl)` returning a record with `owner()`/`repo()` accessors, used only by later tasks' understanding of the class — not called from outside this file.

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/ai/devflow/tools/GitHubApiClientTest.java`:

```java
package ai.devflow.tools;

import ai.devflow.workspace.FixtureWorkspace;
import ai.devflow.workspace.Workspace;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.URIish;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitHubApiClientTest {

    Workspace workspace;
    Path bareRepo;

    @BeforeEach
    void setUp() throws Exception {
        workspace = new FixtureWorkspace(Path.of("src/test/resources/fixture"), "gh-push-test");
        workspace.prepare();

        bareRepo = Files.createTempDirectory("bare-remote-");
        Git.init().setDirectory(bareRepo.toFile()).setBare(true).call();

        try (Git git = Git.open(workspace.root().toFile())) {
            git.remoteAdd().setName("origin")
                    .setUri(new URIish(bareRepo.toUri().toString()))
                    .call();
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        workspace.cleanup();
        try (var walk = Files.walk(bareRepo)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    private GitHubApiClient client(String token) {
        return new GitHubApiClient(token, RestClient.builder().build());
    }

    @Test
    void pushSendsTheBranchToTheConfiguredRemote() throws Exception {
        client("unused-for-a-local-remote").push(workspace, workspace.branchName());

        try (Git bare = Git.open(bareRepo.toFile())) {
            assertThat(bare.getRepository().findRef(workspace.branchName())).isNotNull();
        }
    }

    @Test
    void pushWrapsATransportFailureAsAGitHubClientException() throws Exception {
        try (Git git = Git.open(workspace.root().toFile())) {
            git.remoteRemove().setRemoteName("origin").call();
            git.remoteAdd().setName("origin")
                    .setUri(new URIish("file:///no/such/path/at/all"))
                    .call();
        }

        assertThatThrownBy(() -> client("unused-for-a-local-remote").push(workspace, workspace.branchName()))
                .isInstanceOf(GitHubClientException.class);
    }

    @Test
    void pushFailsFastWhenTheTokenIsMissing() {
        assertThatThrownBy(() -> client(null).push(workspace, workspace.branchName()))
                .isInstanceOf(GitHubClientException.class)
                .hasMessageContaining("DEVFLOWAI_GITHUB_TOKEN");
    }

    @Test
    void pushFailsFastWhenTheTokenIsBlank() {
        assertThatThrownBy(() -> client("  ").push(workspace, workspace.branchName()))
                .isInstanceOf(GitHubClientException.class)
                .hasMessageContaining("DEVFLOWAI_GITHUB_TOKEN");
    }

    @Test
    void openPullRequestFailsFastWhenTheTokenIsMissing() {
        assertThatThrownBy(() -> client(null).openPullRequest(
                "https://github.com/o/r", "b", "title", "body"))
                .isInstanceOf(GitHubClientException.class)
                .hasMessageContaining("DEVFLOWAI_GITHUB_TOKEN");
    }

    @Test
    void parseOwnerRepoHandlesTrailingGitAndSlash() {
        assertThat(GitHubApiClient.parseOwnerRepo("https://github.com/o/r").owner()).isEqualTo("o");
        assertThat(GitHubApiClient.parseOwnerRepo("https://github.com/o/r").repo()).isEqualTo("r");
        assertThat(GitHubApiClient.parseOwnerRepo("https://github.com/o/r.git").repo()).isEqualTo("r");
        assertThat(GitHubApiClient.parseOwnerRepo("https://github.com/o/r/").repo()).isEqualTo("r");
    }

    @Test
    void parseOwnerRepoRejectsAUrlWithoutBothSegments() {
        assertThatThrownBy(() -> GitHubApiClient.parseOwnerRepo("https://github.com/onlyowner"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

Note: this task deliberately does not test `openPullRequest`'s success path (a real HTTP call to `https://api.github.com`) — that is exercised only by the gated live test in Task 5. `parseOwnerRepo`'s URL parsing and `push`'s JGit mechanics (success, transport failure, missing-token fast-fail) are fully covered here without any network call, following this codebase's existing precedent (`ClonedWorkspace`'s URL validation is unit-tested directly; the actual clone is only tested by `ClonedWorkspaceLiveTest`). The `PushResult`-per-ref-rejected-status branch inside `push` (a non-fast-forward rejection from the remote, as opposed to a transport-level failure) is exercised by code inspection rather than a dedicated test — deliberately, to avoid a contrived multi-repo git-history setup disproportionate to what it proves; note this in your self-review as a known, accepted gap, matching this codebase's existing "Known, deliberately unfixed gaps" documentation style (see `STATUS.md`).

- [ ] **Step 2: Run tests to verify they fail**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests '*GitHubApiClientTest*'`
Expected: compilation failure — none of `GitHubClient`/`GitHubApiClient`/`GitHubClientException` exist yet.

- [ ] **Step 3: Create `GitHubClient`, `PullRequestResult`, `GitHubClientException`**

`src/main/java/ai/devflow/tools/GitHubClient.java`:

```java
package ai.devflow.tools;

import ai.devflow.workspace.Workspace;

/**
 * Provider-neutral seam for the one place this codebase talks to a real
 * forge (mirrors how {@code CodingWorker} isolates the LLM provider). The
 * one production implementation is GitHub-only by design — a non-GitHub
 * repo URL is rejected before ever reaching this interface (see
 * {@code RunController}).
 */
public interface GitHubClient {

    /** Pushes {@code branchName} to the workspace's {@code origin} remote. */
    void push(Workspace workspace, String branchName) throws GitHubClientException;

    /** Opens a pull request from {@code branchName} against the repo's default branch. */
    PullRequestResult openPullRequest(String repoUrl, String branchName, String title, String body)
            throws GitHubClientException;
}
```

`src/main/java/ai/devflow/tools/PullRequestResult.java`:

```java
package ai.devflow.tools;

public record PullRequestResult(String url, int number) {}
```

`src/main/java/ai/devflow/tools/GitHubClientException.java`:

```java
package ai.devflow.tools;

/** Checked -- a push or PR failure is always a real, operator-visible condition, never swallowed. */
public class GitHubClientException extends Exception {
    public GitHubClientException(String message) {
        super(message);
    }

    public GitHubClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

- [ ] **Step 4: Create `GitHubApiClient`**

`src/main/java/ai/devflow/tools/GitHubApiClient.java`:

```java
package ai.devflow.tools;

import ai.devflow.workspace.Workspace;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * The one production {@link GitHubClient}: JGit for the push, a plain
 * {@link RestClient} call to GitHub's REST API for PR creation. No new
 * dependency -- both JGit and spring-web are already on this project's
 * classpath.
 */
public class GitHubApiClient implements GitHubClient {

    private static final String API_BASE = "https://api.github.com";

    private final String token;
    private final RestClient restClient;

    public GitHubApiClient(String token, RestClient restClient) {
        this.token = token;
        this.restClient = restClient;
    }

    @Override
    public void push(Workspace workspace, String branchName) throws GitHubClientException {
        requireToken();
        try (Git git = Git.open(workspace.root().toFile())) {
            Iterable<PushResult> results = git.push()
                    .setRemote("origin")
                    .setRefSpecs(new RefSpec(branchName + ":" + branchName))
                    .setCredentialsProvider(new UsernamePasswordCredentialsProvider("x-access-token", token))
                    .call();
            for (PushResult result : results) {
                for (RemoteRefUpdate update : result.getRemoteUpdates()) {
                    RemoteRefUpdate.Status status = update.getStatus();
                    if (status != RemoteRefUpdate.Status.OK && status != RemoteRefUpdate.Status.UP_TO_DATE) {
                        throw new GitHubClientException("Push rejected: " + status
                                + (update.getMessage() != null ? " (" + update.getMessage() + ")" : ""));
                    }
                }
            }
        } catch (GitHubClientException e) {
            throw e;
        } catch (Exception e) {
            throw new GitHubClientException("Push failed: " + e.getMessage(), e);
        }
    }

    @Override
    public PullRequestResult openPullRequest(String repoUrl, String branchName, String title, String body)
            throws GitHubClientException {
        requireToken();
        OwnerRepo or = parseOwnerRepo(repoUrl);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> repoInfo = restClient.get()
                    .uri(API_BASE + "/repos/{owner}/{repo}", or.owner(), or.repo())
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github+json")
                    .retrieve()
                    .body(Map.class);
            String defaultBranch = String.valueOf(repoInfo.get("default_branch"));

            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.post()
                    .uri(API_BASE + "/repos/{owner}/{repo}/pulls", or.owner(), or.repo())
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github+json")
                    .body(Map.of("title", title, "body", body, "head", branchName, "base", defaultBranch))
                    .retrieve()
                    .body(Map.class);

            return new PullRequestResult((String) response.get("html_url"), (Integer) response.get("number"));
        } catch (RuntimeException e) {
            throw new GitHubClientException("Opening the pull request failed: " + e.getMessage(), e);
        }
    }

    private void requireToken() throws GitHubClientException {
        if (token == null || token.isBlank()) {
            throw new GitHubClientException("DEVFLOWAI_GITHUB_TOKEN is not set");
        }
    }

    record OwnerRepo(String owner, String repo) {}

    static OwnerRepo parseOwnerRepo(String repoUrl) {
        String s = repoUrl.substring("https://".length());
        if (s.startsWith("github.com/")) s = s.substring("github.com/".length());
        if (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        if (s.endsWith(".git")) s = s.substring(0, s.length() - 4);
        String[] parts = s.split("/", 2);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new IllegalArgumentException("Not a valid GitHub repo URL: " + repoUrl);
        }
        return new OwnerRepo(parts[0], parts[1]);
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests '*GitHubApiClientTest*'`
Expected: PASS (8 tests).

- [ ] **Step 6: Run the full suite**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test`
Expected: PASS. This task adds only new files — nothing existing changes shape yet.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/ai/devflow/tools/GitHubClient.java src/main/java/ai/devflow/tools/PullRequestResult.java \
        src/main/java/ai/devflow/tools/GitHubClientException.java src/main/java/ai/devflow/tools/GitHubApiClient.java \
        src/test/java/ai/devflow/tools/GitHubApiClientTest.java
git commit -m "$(cat <<'EOF'
feat: add the GitHubClient seam and its GitHubApiClient implementation

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

# Task 2: `PullRequestContent` — PR title/body assembly

**Files:**
- Create: `src/main/java/ai/devflow/orchestrator/PullRequestContent.java`
- Test: `src/test/java/ai/devflow/orchestrator/PullRequestContentTest.java`

**Interfaces:**
- Consumes: `RunState` (existing), `RunStrategy` (existing), `Finding.Origin` (existing).
- Produces: `PullRequestContent.Content(String title, String body)`; `PullRequestContent.build(RunState state, RunStrategy strategy, boolean buildSucceeded, String buildOutput)` — a pure function, no side effects, no new LLM call.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/ai/devflow/orchestrator/PullRequestContentTest.java`:

```java
package ai.devflow.orchestrator;

import ai.devflow.agent.AgentResult;
import ai.devflow.agent.Finding;
import ai.devflow.agent.TokenUsage;
import ai.devflow.workspace.FixtureWorkspace;
import ai.devflow.workspace.Workspace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PullRequestContentTest {

    Workspace workspace;

    @BeforeEach
    void setUp() throws Exception {
        workspace = new FixtureWorkspace(Path.of("src/test/resources/fixture"), "pr-content-test");
        workspace.prepare();
    }

    @AfterEach
    void tearDown() throws Exception { workspace.cleanup(); }

    @Test
    void titleIsTheTasksFirstLineCappedAt72Characters() {
        var state = new RunState("r1", "line one\nline two", workspace);

        var content = PullRequestContent.build(state, RunStrategy.DIRECT, true, "");

        assertThat(content.title()).isEqualTo("line one");
    }

    @Test
    void titleLongerThan72CharactersIsTruncated() {
        String longTask = "x".repeat(100);
        var state = new RunState("r2", longTask, workspace);

        var content = PullRequestContent.build(state, RunStrategy.DIRECT, true, "");

        assertThat(content.title()).hasSize(72);
    }

    @Test
    void summaryComesFromTheLastCoderResult() {
        var state = new RunState("r3", "t", workspace);
        state.record(AgentResult.ok("coder", "first pass", List.of(), TokenUsage.NONE));
        state.record(AgentResult.ok("reviewer", "looks fine", List.of(), TokenUsage.NONE));
        state.record(AgentResult.ok("coder", "second pass, addressed feedback", List.of(), TokenUsage.NONE));

        var content = PullRequestContent.build(state, RunStrategy.ORCHESTRATED, true, "");

        assertThat(content.body()).contains("second pass, addressed feedback");
        assertThat(content.body()).doesNotContain("first pass");
    }

    @Test
    void testingSectionReportsAPassedBuild() {
        var state = new RunState("r4", "t", workspace);

        var content = PullRequestContent.build(state, RunStrategy.DIRECT, true, "");

        assertThat(content.body()).contains("Build passed.");
    }

    @Test
    void testingSectionReportsAFailedBuildWithItsOutput() {
        var state = new RunState("r5", "t", workspace);

        var content = PullRequestContent.build(state, RunStrategy.DIRECT, false, "compile error on line 4");

        assertThat(content.body()).contains("Build failed.");
        assertThat(content.body()).contains("compile error on line 4");
    }

    @Test
    void aiReviewSectionCountsReviewerFindingsForOrchestratedOnly() {
        var state = new RunState("r6", "t", workspace);
        state.addFindings(List.of(
                new Finding(Finding.Origin.REVIEWER, Finding.Severity.HIGH, "F.java", 1, "missing null check"),
                new Finding(Finding.Origin.REVIEWER, Finding.Severity.LOW, "F.java", 2, "naming")));

        var orchestrated = PullRequestContent.build(state, RunStrategy.ORCHESTRATED, true, "");
        var direct = PullRequestContent.build(state, RunStrategy.DIRECT, true, "");

        assertThat(orchestrated.body()).contains("2 finding(s) identified and resolved.");
        assertThat(direct.body()).doesNotContain("AI Review");
    }

    @Test
    void securitySectionReportsNoPolicyViolationsWhenThereAreNone() {
        var state = new RunState("r7", "t", workspace);

        var content = PullRequestContent.build(state, RunStrategy.DIRECT, true, "");

        assertThat(content.body()).contains("No policy violations.");
    }

    @Test
    void securitySectionCountsPolicyFindings() {
        var state = new RunState("r8", "t", workspace);
        state.addFindings(List.of(Finding.fromPolicy("the build did not pass")));

        var content = PullRequestContent.build(state, RunStrategy.DIRECT, true, "");

        assertThat(content.body()).contains("1 policy violation(s) flagged and resolved.");
    }

    @Test
    void bodyEndsWithARunIdReference() {
        var state = new RunState("r9", "t", workspace);

        var content = PullRequestContent.build(state, RunStrategy.DIRECT, true, "");

        assertThat(content.body()).contains("devflowai run: r9");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests '*PullRequestContentTest*'`
Expected: compilation failure — `PullRequestContent` does not exist yet.

- [ ] **Step 3: Create `PullRequestContent`**

`src/main/java/ai/devflow/orchestrator/PullRequestContent.java`:

```java
package ai.devflow.orchestrator;

import ai.devflow.agent.AgentResult;
import ai.devflow.agent.Finding;

/**
 * Assembles PR title/body from data a run already produced -- no new LLM
 * call (spec §4.4). One assembler, not two: the strategy is a parameter so
 * the two run kinds' PR bodies stay structurally identical except for the
 * one section that's genuinely strategy-dependent (there is no review to
 * report for a Direct run).
 */
public final class PullRequestContent {

    public record Content(String title, String body) {}

    private PullRequestContent() {}

    public static Content build(RunState state, RunStrategy strategy, boolean buildSucceeded, String buildOutput) {
        String title = title(state.task());

        StringBuilder body = new StringBuilder();
        body.append("Summary:\n").append(lastCoderSummary(state)).append("\n\n");

        body.append("Testing:\n").append(buildSucceeded ? "Build passed." : "Build failed.");
        if (!buildSucceeded && buildOutput != null && !buildOutput.isBlank()) {
            body.append("\n\n").append(buildOutput);
        }
        body.append("\n\n");

        if (strategy == RunStrategy.ORCHESTRATED) {
            long reviewFindings = state.allFindings().stream()
                    .filter(f -> f.origin() == Finding.Origin.REVIEWER)
                    .count();
            if (reviewFindings > 0) {
                body.append("AI Review:\n").append(reviewFindings)
                        .append(" finding(s) identified and resolved.\n\n");
            }
        }

        long policyFindings = state.allFindings().stream()
                .filter(f -> f.origin() == Finding.Origin.POLICY)
                .count();
        body.append("Security:\n");
        if (policyFindings == 0) {
            body.append("No policy violations.");
        } else {
            body.append(policyFindings).append(" policy violation(s) flagged and resolved.");
        }

        body.append("\n\ndevflowai run: ").append(state.runId());

        return new Content(title, body.toString());
    }

    private static String title(String task) {
        String firstLine = task.lines().findFirst().orElse(task).trim();
        return firstLine.length() > 72 ? firstLine.substring(0, 72) : firstLine;
    }

    private static String lastCoderSummary(RunState state) {
        String summary = "";
        for (AgentResult result : state.history()) {
            if ("coder".equals(result.agent())) summary = result.summary();
        }
        return summary;
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests '*PullRequestContentTest*'`
Expected: PASS (9 tests).

- [ ] **Step 5: Run the full suite**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/ai/devflow/orchestrator/PullRequestContent.java src/test/java/ai/devflow/orchestrator/PullRequestContentTest.java
git commit -m "$(cat <<'EOF'
feat: add PullRequestContent to assemble PR title/body from run data

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

# Task 3: `RunPhase.OPENING_PR` + `RunState`/`Workspace` + `Orchestrator`/`DirectExecutor` wiring

**Files:**
- Modify: `src/main/java/ai/devflow/orchestrator/RunPhase.java`
- Modify: `src/main/java/ai/devflow/orchestrator/RunState.java`
- Modify: `src/main/java/ai/devflow/workspace/Workspace.java`
- Modify: `src/main/java/ai/devflow/workspace/AbstractGitWorkspace.java`
- Modify: `src/main/java/ai/devflow/workspace/ClonedWorkspace.java`
- Modify: `src/main/java/ai/devflow/orchestrator/Orchestrator.java`
- Modify: `src/main/java/ai/devflow/orchestrator/DirectExecutor.java`
- Modify: `src/test/java/ai/devflow/orchestrator/OrchestratorTest.java`
- Modify: `src/test/java/ai/devflow/orchestrator/DirectExecutorTest.java`

**Interfaces:**
- Consumes: `GitHubClient`, `GitHubClientException` (Task 1); `PullRequestContent.build(...)` (Task 2).
- Produces: `RunState(String runId, String task, Workspace workspace, String repoSlug, boolean openPr)` (new 5-arg constructor; the existing 4-arg one now delegates to it with `openPr=false`) + `RunState.openPr()`; `Workspace.repoUrl()` (nullable — `null` for `FixtureWorkspace`, the real URL for `ClonedWorkspace`); `Orchestrator`'s constructor gains a 13th param, `GitHubClient gitHubClient`; `DirectExecutor`'s constructor gains a 4th param, `GitHubClient gitHubClient`. The `"done"` event's data map gains a `prUrl` key only when a PR was actually opened.

- [ ] **Step 1: Add `RunPhase.OPENING_PR`**

Read `src/main/java/ai/devflow/orchestrator/RunPhase.java` first (7 values today). Change it to:

```java
package ai.devflow.orchestrator;

/** Coarse lifecycle position of a run, surfaced to the UI via SSE. */
public enum RunPhase {
    PREPARING,
    CODING,
    REVIEWING,
    BUILDING,
    COMMITTING,
    OPENING_PR,
    DONE,
    FAILED
}
```

- [ ] **Step 2: `RunState` gains `openPr`**

Read `src/main/java/ai/devflow/orchestrator/RunState.java` first (shown in full below — the whole file before this task):

```java
package ai.devflow.orchestrator;

import ai.devflow.agent.AgentResult;
import ai.devflow.agent.Finding;
import ai.devflow.agent.TokenUsage;
import ai.devflow.skill.ScribeDraft;
import ai.devflow.tools.GitTools;
import ai.devflow.workspace.Workspace;

import java.util.ArrayList;
import java.util.List;

public class RunState {

    private final String runId;
    private final String task;
    private final Workspace workspace;
    private final String repoSlug;
    private final GitTools gitTools;

    private final List<AgentResult> history = new ArrayList<>();
    private final List<Finding> openFindings = new ArrayList<>();
    private final List<String> loadedSkills = new ArrayList<>();
    private final List<Finding> allFindings = new ArrayList<>();

    private int reviewIterations = 0;
    private int humanIterations = 0;
    private TokenUsage totalTokens = TokenUsage.NONE;
    private RunPhase phase = RunPhase.PREPARING;
    private String memory = "";
    private String plan = "";
    private ScribeDraft pendingScribeDraft = ScribeDraft.EMPTY;

    public RunState(String runId, String task, Workspace workspace) {
        this(runId, task, workspace, "fixture");
    }

    public RunState(String runId, String task, Workspace workspace, String repoSlug) {
        this.runId = runId;
        this.task = task;
        this.workspace = workspace;
        this.repoSlug = repoSlug;
        this.gitTools = new GitTools(workspace);
    }

    public String runId() { return runId; }
    public String task() { return task; }
    public Workspace workspace() { return workspace; }
    public String repoSlug() { return repoSlug; }
    public GitTools gitTools() { return gitTools; }

    // ... (accessors and mutators unchanged, omitted here for brevity -- see the live file)
}
```

Change the constructors and add the `openPr` field/accessor. Every other member of this class (accessors, `record(...)`, `addFindings(...)`, etc.) stays exactly as it is today — only the constructor chain and one new field/accessor are added:

```java
    private final String runId;
    private final String task;
    private final Workspace workspace;
    private final String repoSlug;
    private final boolean openPr;
    private final GitTools gitTools;

    // ... existing mutable fields unchanged ...

    public RunState(String runId, String task, Workspace workspace) {
        this(runId, task, workspace, "fixture");
    }

    public RunState(String runId, String task, Workspace workspace, String repoSlug) {
        this(runId, task, workspace, repoSlug, false);
    }

    public RunState(String runId, String task, Workspace workspace, String repoSlug, boolean openPr) {
        this.runId = runId;
        this.task = task;
        this.workspace = workspace;
        this.repoSlug = repoSlug;
        this.openPr = openPr;
        // Bound to THIS run's workspace, created once. Agents read tools from
        // here rather than holding their own, so agents stay stateless and are
        // safe to register as singleton beans (Phase 3 finding I3).
        this.gitTools = new GitTools(workspace);
    }

    public String runId() { return runId; }
    public String task() { return task; }
    public Workspace workspace() { return workspace; }
    public String repoSlug() { return repoSlug; }
    public boolean openPr() { return openPr; }
    public GitTools gitTools() { return gitTools; }
```

Every existing 3-arg and 4-arg `new RunState(...)` call site across the codebase keeps compiling unmodified — this mirrors the exact delegation pattern `StartRunRequest` already uses.

- [ ] **Step 3: `Workspace` gains `repoUrl()`**

Read `src/main/java/ai/devflow/workspace/Workspace.java` first (shown in full — the whole file):

```java
package ai.devflow.workspace;

import java.io.IOException;
import java.nio.file.Path;

public interface Workspace {
    Path root();
    String branchName();
    PathGuard guard();
    void prepare() throws IOException;
    void cleanup() throws IOException;
}
```

Change it to:

```java
package ai.devflow.workspace;

import java.io.IOException;
import java.nio.file.Path;

public interface Workspace {
    Path root();
    String branchName();
    PathGuard guard();
    /** The origin repo's https:// URL, or null for a workspace with no real remote (the fixture). */
    String repoUrl();
    void prepare() throws IOException;
    void cleanup() throws IOException;
}
```

Read `src/main/java/ai/devflow/workspace/AbstractGitWorkspace.java` first. Add a default implementation shared by both subclasses, right after the existing `branchName()`/`guard()` accessors:

```java
    @Override public Path root() { return root; }
    @Override public String branchName() { return "devflowai/" + runId; }
    @Override public PathGuard guard() { return guard; }
    @Override public String repoUrl() { return null; }
```

Read `src/main/java/ai/devflow/workspace/ClonedWorkspace.java` first. Add an override (anywhere among its existing methods — right after the constructor is a reasonable spot):

```java
    @Override
    public String repoUrl() {
        return repoUrl;
    }
```

`FixtureWorkspace` needs no change — it inherits `AbstractGitWorkspace`'s default `repoUrl()` returning `null`.

- [ ] **Step 4: `Orchestrator` wiring**

Read `src/main/java/ai/devflow/orchestrator/Orchestrator.java` first (its current full content — 397 lines, unchanged since Sub-project 3 except for the `"strategy"` key added to the first `emit(...)` call).

Add two imports:

```java
import ai.devflow.tools.GitHubClient;
import ai.devflow.tools.GitHubClientException;
```

Add a field and thread it through the constructor:

```java
    private final PolicyEngine policyEngine;
    private final GitHubClient gitHubClient;

    public Orchestrator(Agent coder, Agent reviewer, Agent planner, SkillPicker skillPicker, Scribe scribe,
                        SkillStore skillStore, MemoryStore memoryStore,
                        RunEventPublisher events, int maxReviewIterations, int maxHumanIterations,
                        Duration buildTimeout, PolicyEngine policyEngine, GitHubClient gitHubClient) {
        this.coder = coder;
        this.reviewer = reviewer;
        this.planner = planner;
        this.skillPicker = skillPicker;
        this.scribe = scribe;
        this.skillStore = skillStore;
        this.memoryStore = memoryStore;
        this.events = events;
        this.maxReviewIterations = maxReviewIterations;
        this.maxHumanIterations = maxHumanIterations;
        this.buildTimeout = buildTimeout;
        this.policyEngine = policyEngine;
        this.gitHubClient = gitHubClient;
    }
```

Find the commit-and-done block near the end of `execute(...)`:

```java
            state.setPhase(RunPhase.COMMITTING);
            String committed = state.gitTools().commit("devflowai: " + state.task());
            emit(state, "step", committed, Map.of());

            state.setPhase(RunPhase.DONE);
            emit(state, "done", "Approved and committed on " + state.workspace().branchName(),
                    Map.of("branch", state.workspace().branchName(),
                           "inputTokens", state.totalTokens().input(),
                           "outputTokens", state.totalTokens().output()));
            String outcomeReason = draftDeclinedByBareRejection
                    ? "Committed by operator; declined the proposed lesson"
                    : "Approved by reviewer and operator";
            return new RunOutcome(true, outcomeReason, state);
```

Change it to:

```java
            state.setPhase(RunPhase.COMMITTING);
            String committed = state.gitTools().commit("devflowai: " + state.task());
            emit(state, "step", committed, Map.of());

            String prUrl = null;
            if (state.openPr()) {
                state.setPhase(RunPhase.OPENING_PR);
                try {
                    gitHubClient.push(state.workspace(), state.workspace().branchName());
                } catch (GitHubClientException e) {
                    return failed(state, "Push failed: " + e.getMessage()
                            + ". The commit was made locally but never reached the remote — it is now lost.");
                }
                try {
                    var content = PullRequestContent.build(state, RunStrategy.ORCHESTRATED,
                            build.success(), build.output());
                    var result = gitHubClient.openPullRequest(
                            state.workspace().repoUrl(), state.workspace().branchName(),
                            content.title(), content.body());
                    prUrl = result.url();
                    emit(state, "step", "Pull request opened: " + prUrl, Map.of());
                } catch (GitHubClientException e) {
                    emit(state, "warn", "Branch pushed, but opening the PR failed: " + e.getMessage()
                            + ". Open it manually from " + state.workspace().branchName() + ".", Map.of());
                }
            }

            state.setPhase(RunPhase.DONE);
            Map<String, Object> doneData = new HashMap<>();
            doneData.put("branch", state.workspace().branchName());
            doneData.put("inputTokens", state.totalTokens().input());
            doneData.put("outputTokens", state.totalTokens().output());
            if (prUrl != null) doneData.put("prUrl", prUrl);
            emit(state, "done", "Approved and committed on " + state.workspace().branchName(), doneData);
            String outcomeReason = draftDeclinedByBareRejection
                    ? "Committed by operator; declined the proposed lesson"
                    : "Approved by reviewer and operator";
            return new RunOutcome(true, outcomeReason, state);
```

`build` (the `BuildTools.BuildResult` local variable) is already in scope at this point in `execute(...)` — it was computed earlier in this same loop iteration, right before Gate 3. No new import is needed for `PullRequestContent` since it lives in the same `ai.devflow.orchestrator` package as `Orchestrator`.

- [ ] **Step 5: `DirectExecutor` wiring**

Read `src/main/java/ai/devflow/orchestrator/DirectExecutor.java` first (its current full content, shown earlier in this plan's exploration — 112 lines).

Add two imports:

```java
import ai.devflow.tools.GitHubClient;
import ai.devflow.tools.GitHubClientException;
```

Add a field and thread it through the constructor:

```java
    private final Agent coder;
    private final RunEventPublisher events;
    private final Duration buildTimeout;
    private final GitHubClient gitHubClient;

    public DirectExecutor(Agent coder, RunEventPublisher events, Duration buildTimeout, GitHubClient gitHubClient) {
        this.coder = coder;
        this.events = events;
        this.buildTimeout = buildTimeout;
        this.gitHubClient = gitHubClient;
    }
```

Find the commit-and-done block at the end of `execute(...)`:

```java
        state.setPhase(RunPhase.COMMITTING);
        String committed = state.gitTools().commit("devflowai (direct): " + state.task());
        emit(state, "step", committed, Map.of());

        state.setPhase(RunPhase.DONE);
        emit(state, "done", "Direct run committed on " + state.workspace().branchName(),
                Map.of("branch", state.workspace().branchName(),
                       "inputTokens", state.totalTokens().input(),
                       "outputTokens", state.totalTokens().output()));
        return new Orchestrator.RunOutcome(true, "Direct run committed, no review", state);
```

Change it to:

```java
        state.setPhase(RunPhase.COMMITTING);
        String committed = state.gitTools().commit("devflowai (direct): " + state.task());
        emit(state, "step", committed, Map.of());

        String prUrl = null;
        if (state.openPr()) {
            state.setPhase(RunPhase.OPENING_PR);
            try {
                gitHubClient.push(state.workspace(), state.workspace().branchName());
            } catch (GitHubClientException e) {
                return failed(state, "Push failed: " + e.getMessage()
                        + ". The commit was made locally but never reached the remote — it is now lost.");
            }
            try {
                var content = PullRequestContent.build(state, RunStrategy.DIRECT, build.success(), build.output());
                var result = gitHubClient.openPullRequest(
                        state.workspace().repoUrl(), state.workspace().branchName(),
                        content.title(), content.body());
                prUrl = result.url();
                emit(state, "step", "Pull request opened: " + prUrl, Map.of());
            } catch (GitHubClientException e) {
                emit(state, "warn", "Branch pushed, but opening the PR failed: " + e.getMessage()
                        + ". Open it manually from " + state.workspace().branchName() + ".", Map.of());
            }
        }

        state.setPhase(RunPhase.DONE);
        Map<String, Object> doneData = new HashMap<>();
        doneData.put("branch", state.workspace().branchName());
        doneData.put("inputTokens", state.totalTokens().input());
        doneData.put("outputTokens", state.totalTokens().output());
        if (prUrl != null) doneData.put("prUrl", prUrl);
        emit(state, "done", "Direct run committed on " + state.workspace().branchName(), doneData);
        return new Orchestrator.RunOutcome(true, "Direct run committed, no review", state);
```

`build` is already in scope here too — it's the `BuildTools.BuildResult` local variable computed a few lines earlier in this same method, right before the commit step.

- [ ] **Step 6: Update `OrchestratorTest`**

Read `src/test/java/ai/devflow/orchestrator/OrchestratorTest.java` first. Add a field near the top of the class, alongside the existing `planner`/`policyEngine` fields:

```java
    Agent planner = defaultPlanner();
    PolicyEngine policyEngine = defaultPolicyEngine();
    GitHubClient gitHubClient = defaultGitHubClient();
```

Add the import:

```java
import ai.devflow.tools.GitHubClient;
import ai.devflow.tools.GitHubClientException;
import ai.devflow.tools.PullRequestResult;
```

Add a factory method alongside `defaultPlanner()`/`defaultPolicyEngine()`:

```java
    static GitHubClient defaultGitHubClient() {
        return new GitHubClient() {
            @Override public void push(ai.devflow.workspace.Workspace workspace, String branchName) {
                throw new UnsupportedOperationException("no test using this default expects a push");
            }
            @Override public PullRequestResult openPullRequest(String repoUrl, String branchName, String title, String body) {
                throw new UnsupportedOperationException("no test using this default expects a PR");
            }
        };
    }
```

Update the existing factory method that constructs `Orchestrator`:

```java
    private Orchestrator orchestrator(Agent coder, Agent reviewer, SkillPicker picker, Scribe scribe,
                                      SkillStore skillStore, MemoryStore memoryStore) {
        return new Orchestrator(coder, reviewer, planner, picker, scribe, skillStore, memoryStore,
                events, 3, 5, Duration.ofMinutes(1), policyEngine, gitHubClient);
    }
```

None of this file's existing tests set `openPr=true` on any `RunState`, so `defaultGitHubClient()`'s `UnsupportedOperationException` stubs are never actually invoked by them — they exist only to satisfy the constructor.

Add new tests proving the push/PR wiring, anywhere after the existing gate-related tests:

```java
    @Test
    void openPrPushesThenOpensAPrAndTheDoneEventCarriesItsUrl() throws Exception {
        var captured = new ArrayList<Object>();
        var recordingEvents = new RunEventPublisher(captured::add);
        var pushed = new AtomicInteger(0);
        GitHubClient fakeClient = new GitHubClient() {
            @Override public void push(ai.devflow.workspace.Workspace workspace, String branchName) {
                pushed.incrementAndGet();
            }
            @Override public PullRequestResult openPullRequest(String repoUrl, String branchName, String title, String body) {
                return new PullRequestResult("https://github.com/o/r/pull/7", 7);
            }
        };
        var orchestrator = new Orchestrator(writingCoder("done"), okReviewer(), planner,
                (task, index) -> List.of(), (state, findings, reason) -> ScribeDraft.EMPTY,
                new FakeSkillStore(), new FakeMemoryStore(),
                recordingEvents, 3, 5, Duration.ofMinutes(1), policyEngine, fakeClient);
        var state = new RunState("pr1", "t", workspace, "fixture", true);
        var gate = fullyApprovingGate();

        var outcome = orchestrator.run(state, gate);

        assertThat(outcome.approved()).isTrue();
        assertThat(pushed.get()).isEqualTo(1);
        var doneEvent = recordedEvents(captured).stream()
                .filter(e -> "done".equals(e.type()))
                .findFirst().orElseThrow();
        assertThat(doneEvent.data()).containsEntry("prUrl", "https://github.com/o/r/pull/7");
    }

    @Test
    void aPushFailureIsReportedAsALostCommitAndTheRunFails() throws Exception {
        GitHubClient failingClient = new GitHubClient() {
            @Override public void push(ai.devflow.workspace.Workspace workspace, String branchName) throws GitHubClientException {
                throw new GitHubClientException("network unreachable");
            }
            @Override public PullRequestResult openPullRequest(String repoUrl, String branchName, String title, String body) {
                throw new UnsupportedOperationException("push already failed");
            }
        };
        var orchestrator = new Orchestrator(writingCoder("done"), okReviewer(), planner,
                (task, index) -> List.of(), (state, findings, reason) -> ScribeDraft.EMPTY,
                new FakeSkillStore(), new FakeMemoryStore(),
                events, 3, 5, Duration.ofMinutes(1), policyEngine, failingClient);
        var state = new RunState("pr2", "t", workspace, "fixture", true);
        var gate = fullyApprovingGate();

        var outcome = orchestrator.run(state, gate);

        assertThat(outcome.approved()).isFalse();
        assertThat(outcome.reason()).contains("Push failed").contains("now lost");
    }

    @Test
    void aPrCreationFailureStillEndsTheRunDone() throws Exception {
        var captured = new ArrayList<Object>();
        var recordingEvents = new RunEventPublisher(captured::add);
        GitHubClient pushOnlyClient = new GitHubClient() {
            @Override public void push(ai.devflow.workspace.Workspace workspace, String branchName) { }
            @Override public PullRequestResult openPullRequest(String repoUrl, String branchName, String title, String body)
                    throws GitHubClientException {
                throw new GitHubClientException("insufficient permissions");
            }
        };
        var orchestrator = new Orchestrator(writingCoder("done"), okReviewer(), planner,
                (task, index) -> List.of(), (state, findings, reason) -> ScribeDraft.EMPTY,
                new FakeSkillStore(), new FakeMemoryStore(),
                recordingEvents, 3, 5, Duration.ofMinutes(1), policyEngine, pushOnlyClient);
        var state = new RunState("pr3", "t", workspace, "fixture", true);
        var gate = fullyApprovingGate();

        var outcome = orchestrator.run(state, gate);

        assertThat(outcome.approved()).isTrue();
        var events = recordedEvents(captured);
        var doneEvent = events.stream()
                .filter(e -> "done".equals(e.type()))
                .findFirst().orElseThrow();
        assertThat(doneEvent.data()).doesNotContainKey("prUrl");
        assertThat(events.stream().anyMatch(e ->
                "warn".equals(e.type()) && e.message().contains("Open it manually"))).isTrue();
    }
```

Add one more small helper alongside `okReviewer()`/`fullyApprovingGate()` below — `RunEventPublisher`'s constructor takes a plain `ApplicationEventPublisher` (`void publishEvent(Object event)`), so the capturing list above must be typed `List<Object>` (matching the exact pattern `RunControllerTest.publishedEvents` already uses) rather than `List<RunRecorded>` — a method reference cannot narrow `Object` to `RunRecorded`. This helper filters and unwraps the captured `Object`s back to `RunEvent`s:

```java
    private List<ai.devflow.event.RunEvent> recordedEvents(List<Object> captured) {
        return captured.stream()
                .filter(ai.devflow.event.RunRecorded.class::isInstance)
                .map(ai.devflow.event.RunRecorded.class::cast)
                .map(ai.devflow.event.RunRecorded::event)
                .toList();
    }
```

These three new tests need two small helper methods this file does not yet have — add them alongside the existing `writingCoder(...)` helper:

```java
    private Agent okReviewer() {
        return new Agent() {
            @Override public String name() { return "reviewer"; }
            @Override public AgentResult run(RunState s) {
                return AgentResult.ok("reviewer", "looks good", List.of(), TokenUsage.NONE);
            }
        };
    }

    private ApprovalGate fullyApprovingGate() {
        var gate = new ApprovalGate(Duration.ofSeconds(10));
        var approver = Executors.newSingleThreadExecutor();
        approver.submit(() -> {
            while (true) {
                if (gate.pending() != null) gate.decide(ApprovalDecision.approve());
                try { Thread.sleep(2); } catch (InterruptedException e) { return; }
            }
        });
        return gate;
    }
```

If this file already defines an equivalent "approve everything" gate helper or an "always-OK reviewer" helper under a different name, reuse the existing one instead of adding a duplicate — check the file for one before adding these two.

- [ ] **Step 7: Update `DirectExecutorTest`**

Read `src/test/java/ai/devflow/orchestrator/DirectExecutorTest.java` first (its current full content, shown earlier in this plan's exploration — 120 lines).

Add imports:

```java
import ai.devflow.tools.GitHubClient;
import ai.devflow.tools.GitHubClientException;
import ai.devflow.tools.PullRequestResult;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
```

Change the `executor(Agent coder)` helper to also accept a `GitHubClient`, keeping the existing single-arg version for tests that never open a PR:

```java
    private DirectExecutor executor(Agent coder) {
        return executor(coder, defaultGitHubClient());
    }

    private DirectExecutor executor(Agent coder, GitHubClient gitHubClient) {
        return new DirectExecutor(coder, events, Duration.ofMinutes(1), gitHubClient);
    }

    static GitHubClient defaultGitHubClient() {
        return new GitHubClient() {
            @Override public void push(ai.devflow.workspace.Workspace workspace, String branchName) {
                throw new UnsupportedOperationException("no test using this default expects a push");
            }
            @Override public PullRequestResult openPullRequest(String repoUrl, String branchName, String title, String body) {
                throw new UnsupportedOperationException("no test using this default expects a PR");
            }
        };
    }
```

None of this file's five existing tests construct a `RunState` with `openPr=true`, so they all keep passing unmodified with `executor(coder)`'s default stub.

Add new tests proving the same push/PR branching `DirectExecutor` now has, mirroring `OrchestratorTest`'s new tests:

```java
    @Test
    void openPrPushesThenOpensAPrAndTheDoneEventCarriesItsUrl() throws Exception {
        var coder = writingCoder("done");
        var captured = new ArrayList<Object>();
        var recordingEvents = new RunEventPublisher(captured::add);
        var pushed = new AtomicInteger(0);
        GitHubClient fakeClient = new GitHubClient() {
            @Override public void push(ai.devflow.workspace.Workspace workspace, String branchName) {
                pushed.incrementAndGet();
            }
            @Override public PullRequestResult openPullRequest(String repoUrl, String branchName, String title, String body) {
                return new PullRequestResult("https://github.com/o/r/pull/9", 9);
            }
        };
        var state = new RunState("dpr1", "t", workspace, "fixture", true);
        var gate = new ApprovalGate(Duration.ofSeconds(10));

        var outcome = new DirectExecutor(coder, recordingEvents, Duration.ofMinutes(1), fakeClient).run(state, gate);

        assertThat(outcome.approved()).isTrue();
        assertThat(pushed.get()).isEqualTo(1);
        var doneEvent = recordedEvents(captured).stream()
                .filter(e -> "done".equals(e.type()))
                .findFirst().orElseThrow();
        assertThat(doneEvent.data()).containsEntry("prUrl", "https://github.com/o/r/pull/9");
    }

    @Test
    void aPushFailureIsReportedAsALostCommitAndTheRunFails() throws Exception {
        var coder = writingCoder("done");
        GitHubClient failingClient = new GitHubClient() {
            @Override public void push(ai.devflow.workspace.Workspace workspace, String branchName) throws GitHubClientException {
                throw new GitHubClientException("network unreachable");
            }
            @Override public PullRequestResult openPullRequest(String repoUrl, String branchName, String title, String body) {
                throw new UnsupportedOperationException("push already failed");
            }
        };
        var state = new RunState("dpr2", "t", workspace, "fixture", true);
        var gate = new ApprovalGate(Duration.ofSeconds(10));

        var outcome = new DirectExecutor(coder, events, Duration.ofMinutes(1), failingClient).run(state, gate);

        assertThat(outcome.approved()).isFalse();
        assertThat(outcome.reason()).contains("Push failed").contains("now lost");
    }

    @Test
    void aPrCreationFailureStillEndsTheRunDone() throws Exception {
        var coder = writingCoder("done");
        var captured = new ArrayList<Object>();
        var recordingEvents = new RunEventPublisher(captured::add);
        GitHubClient pushOnlyClient = new GitHubClient() {
            @Override public void push(ai.devflow.workspace.Workspace workspace, String branchName) { }
            @Override public PullRequestResult openPullRequest(String repoUrl, String branchName, String title, String body)
                    throws GitHubClientException {
                throw new GitHubClientException("insufficient permissions");
            }
        };
        var state = new RunState("dpr3", "t", workspace, "fixture", true);
        var gate = new ApprovalGate(Duration.ofSeconds(10));

        var outcome = new DirectExecutor(coder, recordingEvents, Duration.ofMinutes(1), pushOnlyClient).run(state, gate);

        assertThat(outcome.approved()).isTrue();
        var doneEvent = recordedEvents(captured).stream()
                .filter(e -> "done".equals(e.type()))
                .findFirst().orElseThrow();
        assertThat(doneEvent.data()).doesNotContainKey("prUrl");
    }
```

Add one more small helper this file does not yet have, alongside the existing `writingCoder(...)` helper — for the same reason given in Task 3 Step 6: `RunEventPublisher`'s constructor takes a plain `ApplicationEventPublisher` (`void publishEvent(Object event)`), so the capturing list above must be typed `List<Object>` (a method reference cannot narrow `Object` to `RunRecorded`), and this helper filters/unwraps it back to `RunEvent`s:

```java
    private List<ai.devflow.event.RunEvent> recordedEvents(List<Object> captured) {
        return captured.stream()
                .filter(ai.devflow.event.RunRecorded.class::isInstance)
                .map(ai.devflow.event.RunRecorded.class::cast)
                .map(ai.devflow.event.RunRecorded::event)
                .toList();
    }
```

- [ ] **Step 8: Run the updated test files**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests '*OrchestratorTest*' --tests '*DirectExecutorTest*'`
Expected: PASS (all tests in both files, old and new).

- [ ] **Step 9: Run the full suite**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test`
Expected: compilation failures in `RunRegistryTest.java` and `OrchestrationConfig.java` — both construct `Orchestrator`/`DirectExecutor` directly and haven't been updated yet. This is expected; Task 4 fixes `RunRegistryTest` and `OrchestrationConfig`. Confirm the failures are ONLY in those two files (not, for example, a typo in this task's own changes) before moving on.

- [ ] **Step 10: Commit**

```bash
git add src/main/java/ai/devflow/orchestrator/RunPhase.java src/main/java/ai/devflow/orchestrator/RunState.java \
        src/main/java/ai/devflow/workspace/Workspace.java src/main/java/ai/devflow/workspace/AbstractGitWorkspace.java \
        src/main/java/ai/devflow/workspace/ClonedWorkspace.java src/main/java/ai/devflow/orchestrator/Orchestrator.java \
        src/main/java/ai/devflow/orchestrator/DirectExecutor.java \
        src/test/java/ai/devflow/orchestrator/OrchestratorTest.java src/test/java/ai/devflow/orchestrator/DirectExecutorTest.java
git commit -m "$(cat <<'EOF'
feat: wire the push/PR step into Orchestrator and DirectExecutor

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

Note: this commit deliberately leaves the full suite red (Task 3 Step 9 confirmed exactly two files fail to compile) — Task 4 fixes both in its own first steps, matching how Sub-project 3's own Task 3 threaded a widened constructor through multiple files across two tasks. If your process requires every commit to leave the suite green, fold Task 4's `RunRegistryTest`/`OrchestrationConfig` fixes into this commit instead; either way, do not skip verifying the full suite before Task 4 is considered started.

---

# Task 4: `StartRunRequest`/`RunController`/`RunRegistry`/`OrchestrationConfig` wiring

**Files:**
- Modify: `src/main/java/ai/devflow/web/StartRunRequest.java`
- Modify: `src/main/java/ai/devflow/web/RunController.java`
- Modify: `src/main/java/ai/devflow/orchestrator/RunRegistry.java`
- Modify: `src/main/java/ai/devflow/config/OrchestrationConfig.java`
- Modify: `src/test/java/ai/devflow/web/RunControllerTest.java`
- Modify: `src/test/java/ai/devflow/orchestrator/RunRegistryTest.java`
- Modify: `src/test/java/ai/devflow/web/RunFlowIntegrationTest.java`

**Interfaces:**
- Consumes: `GitHubClient`/`GitHubApiClient` (Task 1); `Orchestrator`'s and `DirectExecutor`'s widened constructors (Task 3).
- Produces: `StartRunRequest(String task, String repo, String strategy, Boolean openPr)` + delegating 3-arg and 2-arg constructors; `RunRegistry.start(String task, String repo, RunStrategy strategy, boolean openPr)`.

- [ ] **Step 1: Update `StartRunRequest`**

Read `src/main/java/ai/devflow/web/StartRunRequest.java` first (shown in full above in this plan's exploration — currently a 3-arg record with a delegating 2-arg constructor). Change it to:

```java
package ai.devflow.web;

/**
 * @param repo "fixture" today, or an https:// git URL (Phase 6).
 * @param strategy "orchestrated" (default) or "direct" (spec §22) — null or
 *                 blank defaults to orchestrated.
 * @param openPr when true, push the branch and open a GitHub pull request
 *               after a successful commit. Null or false is the default —
 *               never inferred. Rejected by RunController when combined
 *               with the fixture repo or a non-github.com URL. The
 *               delegating constructors below exist so every pre-existing
 *               caller (tests included) that constructs this directly with
 *               fewer arguments keeps compiling unmodified.
 */
public record StartRunRequest(String task, String repo, String strategy, Boolean openPr) {
    public StartRunRequest(String task, String repo, String strategy) {
        this(task, repo, strategy, null);
    }
    public StartRunRequest(String task, String repo) {
        this(task, repo, null, null);
    }
}
```

- [ ] **Step 2: Write the failing `RunControllerTest` cases**

Read `src/test/java/ai/devflow/web/RunControllerTest.java` first (its current full content, shown earlier in this plan's exploration). Update every existing `registry.start(...)` mock/verify call to add a 4th argument, and add new tests for the `openPr` validation and pass-through.

Change `startingARunReturnsItsId`:

```java
    @Test
    void startingARunReturnsItsId() throws Exception {
        when(registry.start(any(), any(), any(), anyBoolean())).thenReturn(handleFor("abc123"));

        mvc.perform(post("/api/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new StartRunRequest("do a thing", "fixture"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value("abc123"));

        verify(registry).start("do a thing", "fixture", RunStrategy.ORCHESTRATED, false);
    }
```

Change `startingARunRejectsARepoStringThatIsNotAnHttpsUrl`:

```java
    @Test
    void startingARunRejectsARepoStringThatIsNotAnHttpsUrl() throws Exception {
        when(registry.start(any(), any(), any(), anyBoolean()))
                .thenThrow(new IllegalArgumentException("Repo must be an https:// URL, got: ext::sh -c \"true\""));

        mvc.perform(post("/api/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new StartRunRequest("do a thing", "ext::sh -c \"true\""))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Repo must be an https:// URL, got: ext::sh -c \"true\""));
    }
```

Change `startingARunWithDirectStrategyPassesItThrough`:

```java
    @Test
    void startingARunWithDirectStrategyPassesItThrough() throws Exception {
        when(registry.start(any(), any(), any(), anyBoolean())).thenReturn(handleFor("direct1"));

        mvc.perform(post("/api/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new StartRunRequest("do a thing", "fixture", "direct"))))
                .andExpect(status().isOk());

        verify(registry).start("do a thing", "fixture", RunStrategy.DIRECT, false);
    }
```

Add the `anyBoolean` static import alongside the existing `any` one:

```java
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
```

Add three new tests, anywhere after `startingARunWithDirectStrategyPassesItThrough`:

```java
    @Test
    void startingARunWithOpenPrAgainstTheFixtureIsRejected() throws Exception {
        mvc.perform(post("/api/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new StartRunRequest("do a thing", "fixture", "direct", true))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Opening a PR requires a real repository, not the bundled fixture"));

        verifyNoInteractions(registry);
    }

    @Test
    void startingARunWithOpenPrAgainstANonGitHubUrlIsRejected() throws Exception {
        mvc.perform(post("/api/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                new StartRunRequest("do a thing", "https://gitlab.com/o/r", "direct", true))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        "Opening a PR is only supported for github.com repositories, got: https://gitlab.com/o/r"));

        verifyNoInteractions(registry);
    }

    @Test
    void startingARunWithOpenPrAgainstAGitHubUrlPassesItThrough() throws Exception {
        when(registry.start(any(), any(), any(), anyBoolean())).thenReturn(handleFor("pr1"));

        mvc.perform(post("/api/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                new StartRunRequest("do a thing", "https://github.com/o/r", "direct", true))))
                .andExpect(status().isOk());

        verify(registry).start("do a thing", "https://github.com/o/r", RunStrategy.DIRECT, true);
    }
```

- [ ] **Step 3: Run to verify it fails**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests '*RunControllerTest*'`
Expected: compilation failure — `RunRegistry.start(...)` does not take a 4th argument yet, and `RunController` doesn't validate `openPr`.

- [ ] **Step 4: Update `RunController`**

Read `src/main/java/ai/devflow/web/RunController.java` first (its current full content, shown earlier in this plan's exploration). Change `start`:

```java
    @PostMapping
    public ResponseEntity<Map<String, String>> start(@RequestBody StartRunRequest request) {
        if (request.task() == null || request.task().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "task must not be blank"));
        }
        String rawRepo = request.repo() == null ? "" : request.repo().trim();
        String repo = rawRepo.isBlank() ? "fixture" : rawRepo;
        String rawStrategy = request.strategy() == null ? "" : request.strategy().trim();
        RunStrategy strategy = "direct".equalsIgnoreCase(rawStrategy) ? RunStrategy.DIRECT : RunStrategy.ORCHESTRATED;
        boolean openPr = Boolean.TRUE.equals(request.openPr());
        if (openPr) {
            if ("fixture".equals(repo)) {
                return ResponseEntity.badRequest().body(
                        Map.of("error", "Opening a PR requires a real repository, not the bundled fixture"));
            }
            if (!repo.startsWith("https://github.com/")) {
                return ResponseEntity.badRequest().body(
                        Map.of("error", "Opening a PR is only supported for github.com repositories, got: " + repo));
            }
        }
        RunHandle handle;
        try {
            handle = registry.start(request.task().trim(), repo, strategy, openPr);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
        return ResponseEntity.ok(Map.of("runId", handle.runId()));
    }
```

- [ ] **Step 5: Update `RunRegistry`**

Read `src/main/java/ai/devflow/orchestrator/RunRegistry.java` first (its current full content, shown earlier in this plan's exploration). Change `start`:

```java
    public RunHandle start(String task, String repo, RunStrategy strategy, boolean openPr) {
        String runId = UUID.randomUUID().toString().substring(0, 8);
        Workspace workspace;
        String repoSlug;
        if ("fixture".equals(repo)) {
            workspace = new FixtureWorkspace(fixtureSource, runId);
            repoSlug = "fixture";
        } else {
            workspace = new ClonedWorkspace(repo, runId, cloneTimeout);
            repoSlug = Slug.of(normalizeRepoUrl(repo));
        }
        RunState state = new RunState(runId, task, workspace, repoSlug, openPr);
        ApprovalGate gate = new ApprovalGate(gateTimeout);
        RunExecutor selectedExecutor = strategy == RunStrategy.DIRECT ? directExecutor : orchestrator;

        var future = executor.submit(() -> {
            try {
                workspace.prepare();
                selectedExecutor.run(state, gate);
            } catch (Exception e) {
                state.setPhase(RunPhase.FAILED);
                events.publish(runId, RunEvent.of("error",
                        "Could not prepare the workspace: " + e.getMessage(),
                        Map.of("task", task, "repoSlug", repoSlug, "strategy", strategy.name())));
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
```

Only the method signature and the `new RunState(...)` call change — everything else in this method, and the rest of the file, stays exactly as it is today.

- [ ] **Step 6: Fix `RunRegistryTest`**

Read `src/test/java/ai/devflow/orchestrator/RunRegistryTest.java` first (its current full content, shown earlier in this plan's exploration — this is the file Task 3 Step 9 predicted would fail to compile). Add imports:

```java
import ai.devflow.tools.GitHubClient;
import ai.devflow.tools.PullRequestResult;
```

Add a shared stub field alongside the class's other test fixtures:

```java
    static GitHubClient stubGitHubClient() {
        return new GitHubClient() {
            @Override public void push(ai.devflow.workspace.Workspace workspace, String branchName) {
                throw new UnsupportedOperationException("no test in this file expects a push");
            }
            @Override public PullRequestResult openPullRequest(String repoUrl, String branchName, String title, String body) {
                throw new UnsupportedOperationException("no test in this file expects a PR");
            }
        };
    }
```

Update the two inline `new Orchestrator(...)` calls in this file (in `setUp()` and in `directStrategyDispatchesToTheDirectExecutorNotTheOrchestrator`) to pass `stubGitHubClient()` as the 13th argument:

```java
    @BeforeEach
    void setUp() {
        var events = new RunEventPublisher();
        var orchestrator = new Orchestrator(new StubAgent("coder"), new StubAgent("reviewer"), new StubAgent("planner"),
                (task, index) -> List.of(), (state, findings, reason) -> ScribeDraft.EMPTY,
                new NoOpSkillStore(), new NoOpMemoryStore(),
                events, 3, 5, Duration.ofMinutes(1), context -> PolicyResult.ok(), stubGitHubClient());
        RunExecutor stubDirectExecutor = (state, gate) -> new Orchestrator.RunOutcome(true, "stub", state);
        registry = new RunRegistry(orchestrator, stubDirectExecutor, events, Executors.newCachedThreadPool(),
                java.nio.file.Path.of("src/test/resources/fixture"), Duration.ofSeconds(2),
                Duration.ofSeconds(2));
    }
```

```java
    @Test
    void directStrategyDispatchesToTheDirectExecutorNotTheOrchestrator() {
        java.util.concurrent.atomic.AtomicBoolean directCalled = new java.util.concurrent.atomic.AtomicBoolean(false);
        var events = new RunEventPublisher();
        var orchestrator = new Orchestrator(new StubAgent("coder"), new StubAgent("reviewer"), new StubAgent("planner"),
                (task, index) -> List.of(), (state, findings, reason) -> ScribeDraft.EMPTY,
                new NoOpSkillStore(), new NoOpMemoryStore(),
                events, 3, 5, Duration.ofMinutes(1), context -> PolicyResult.ok(), stubGitHubClient());
        RunExecutor recordingDirectExecutor = (state, gate) -> {
            directCalled.set(true);
            return new Orchestrator.RunOutcome(true, "stub", state);
        };
        var directRegistry = new RunRegistry(orchestrator, recordingDirectExecutor, events, Executors.newCachedThreadPool(),
                java.nio.file.Path.of("src/test/resources/fixture"), Duration.ofSeconds(2),
                Duration.ofSeconds(2));

        RunHandle handle = directRegistry.start("a task", "fixture", RunStrategy.DIRECT, false);
        while (!handle.task().isDone()) {
            try { Thread.sleep(5); } catch (InterruptedException e) { throw new RuntimeException(e); }
        }

        assertThat(directCalled.get()).isTrue();
    }
```

Update every `.start(...)` call in this file's remaining tests to add a 4th `false` argument — `startingARunGivesItAUniqueIdAndRegistersIt` (two calls), `theHandleExposesTheRunsStateAndGate` (one call), `aNonHttpsRepoIsRejectedSynchronouslyWithoutStartingAnyWork` (one call):

```java
    @Test
    void startingARunGivesItAUniqueIdAndRegistersIt() {
        RunHandle a = registry.start("task one", "fixture", RunStrategy.ORCHESTRATED, false);
        RunHandle b = registry.start("task two", "fixture", RunStrategy.ORCHESTRATED, false);
        // ... assertions unchanged
    }

    @Test
    void theHandleExposesTheRunsStateAndGate() {
        RunHandle handle = registry.start("a task", "fixture", RunStrategy.ORCHESTRATED, false);
        // ... assertions unchanged
    }

    @Test
    void aNonHttpsRepoIsRejectedSynchronouslyWithoutStartingAnyWork() {
        assertThatThrownBy(() -> registry.start("task", "ext::sh -c \"true\"", RunStrategy.ORCHESTRATED, false))
                .isInstanceOf(IllegalArgumentException.class);
    }
```

`findingAnUnknownRunReturnsNull` and `normalizeRepoUrlStripsTrailingGitAndSlashSoBothFormsMatch` don't call `.start(...)` or construct an `Orchestrator` — leave them unchanged.

- [ ] **Step 7: Update `OrchestrationConfig`**

Read `src/main/java/ai/devflow/config/OrchestrationConfig.java` first (its current full content, shown earlier in this plan's exploration). Add imports:

```java
import ai.devflow.tools.GitHubApiClient;
import ai.devflow.tools.GitHubClient;
import org.springframework.web.client.RestClient;
```

Add a new bean (anywhere among the existing `@Bean` methods — right before `orchestrator(...)` is a reasonable spot):

```java
    /** Token read directly from the environment, matching ANTHROPIC_API_KEY's own pattern -- never a tracked file. */
    @Bean
    GitHubClient gitHubClient() {
        String token = System.getenv("DEVFLOWAI_GITHUB_TOKEN");
        return new GitHubApiClient(token, RestClient.builder().baseUrl("https://api.github.com").build());
    }
```

Update the `orchestrator` bean method to take and pass it:

```java
    @Bean
    Orchestrator orchestrator(Agent coderAgent, Agent reviewerAgent, Agent plannerAgent, SkillPicker skillPicker, Scribe scribe,
                              SkillStore skillStore, MemoryStore memoryStore, RunEventPublisher events,
                              @Value("${devflowai.review.max-iterations:3}") int maxReviewIterations,
                              @Value("${devflowai.review.max-human-iterations:5}") int maxHumanIterations,
                              @Value("${devflowai.build.timeout-minutes:5}") long buildTimeoutMinutes,
                              PolicyEngine policyEngine, GitHubClient gitHubClient) {
        return new Orchestrator(coderAgent, reviewerAgent, plannerAgent, skillPicker, scribe, skillStore, memoryStore,
                events, maxReviewIterations, maxHumanIterations, Duration.ofMinutes(buildTimeoutMinutes), policyEngine,
                gitHubClient);
    }
```

Update the `directExecutor` bean method:

```java
    @Bean
    RunExecutor directExecutor(Agent coderAgent, RunEventPublisher events,
                              @Value("${devflowai.build.timeout-minutes:5}") long buildTimeoutMinutes,
                              GitHubClient gitHubClient) {
        return new DirectExecutor(coderAgent, events, Duration.ofMinutes(buildTimeoutMinutes), gitHubClient);
    }
```

`Orchestrator` and `DirectExecutor` are the only two beans that need `GitHubClient` — Spring resolves it by type, since there's exactly one `GitHubClient` bean in the context. `runRegistry(...)`'s own signature is unaffected — it never touches `GitHubClient` directly.

- [ ] **Step 8: Run `RunControllerTest` and `RunRegistryTest` to verify they pass**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests '*RunControllerTest*' --tests '*RunRegistryTest*'`
Expected: PASS (all tests in both files, old and new).

- [ ] **Step 9: Update `RunFlowIntegrationTest`**

Read `src/test/java/ai/devflow/web/RunFlowIntegrationTest.java` first (its current full content, shown earlier in this plan's exploration). Add a `@TestBean` override for `gitHubClient`, alongside the file's existing overrides:

```java
    @TestBean(name = "gitHubClient", methodName = "stubGitHubClient")
    ai.devflow.tools.GitHubClient gitHubClientOverride;
```

Add the corresponding static factory method, alongside `stubSkillStore()`/`stubMemoryStore()`:

```java
    static ai.devflow.tools.GitHubClient stubGitHubClient() {
        return new ai.devflow.tools.GitHubClient() {
            @Override public void push(ai.devflow.workspace.Workspace workspace, String branchName) { }
            @Override public ai.devflow.tools.PullRequestResult openPullRequest(
                    String repoUrl, String branchName, String title, String body) {
                return new ai.devflow.tools.PullRequestResult("https://github.com/" + repoUrl + "/pull/1", 1);
            }
        };
    }
```

This override alone is enough for Task 4 — the new end-to-end test that actually exercises it (`anOpenPrRunPushesAndOpensAPrAgainstTheRealClone`) is added in Task 5 instead, once `SdlcRun.prUrl()` exists for it to assert on. Adding the override here now means Task 5 only needs to add the one test method, not any new Spring wiring.

- [ ] **Step 10: Run the full suite**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test`
Expected: PASS.

- [ ] **Step 11: Commit**

```bash
git add src/main/java/ai/devflow/web/StartRunRequest.java src/main/java/ai/devflow/web/RunController.java \
        src/main/java/ai/devflow/orchestrator/RunRegistry.java src/main/java/ai/devflow/config/OrchestrationConfig.java \
        src/test/java/ai/devflow/web/RunControllerTest.java src/test/java/ai/devflow/orchestrator/RunRegistryTest.java \
        src/test/java/ai/devflow/web/RunFlowIntegrationTest.java
git commit -m "$(cat <<'EOF'
feat: wire openPr end to end from the HTTP API to RunRegistry

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

# Task 5: Persistence, UI, and the gated live test

**Files:**
- Modify: `src/main/java/ai/devflow/history/SdlcRun.java`
- Modify: `src/main/java/ai/devflow/history/SdlcRunRecorder.java`
- Modify: `src/main/java/ai/devflow/web/RunSummary.java`
- Modify: `src/main/resources/static/index.html`
- Modify: `src/test/java/ai/devflow/web/StaticPageTest.java`
- Modify: `src/test/java/ai/devflow/web/RunFlowIntegrationTest.java`
- Create: `src/test/java/ai/devflow/GitHubIntegrationLiveTest.java`

**Interfaces:**
- Produces: `SdlcRun.prUrl()`; `RunSummary`'s `prUrl` field.

- [ ] **Step 1: Write the failing tests**

Read `src/main/java/ai/devflow/history/SdlcRun.java` first (its current full content, shown earlier in this plan's exploration). There is no dedicated `SdlcRun`-construction test to change here (its constructor shape does not change — only a new nullable field/setter/accessor is added), so this task's tests live in `SdlcRunRecorderTest` and `StaticPageTest`.

Read `src/test/java/ai/devflow/history/SdlcRunRecorderTest.java` first. Add one new test, anywhere after the existing `aBuildStepEventRecordsBuildSucceededAndTheLatestOneWins` test:

```java
    @Test
    void aDoneEventWithAPrUrlRecordsIt() {
        recorder.onRunRecorded(new RunRecorded("r13", new RunEvent("step", "start",
                Map.of("task", "t", "repoSlug", "fixture", "phase", "PREPARING", "strategy", "ORCHESTRATED"))));

        recorder.onRunRecorded(new RunRecorded("r13", new RunEvent("done", "done",
                Map.of("phase", "DONE", "prUrl", "https://github.com/o/r/pull/3"))));

        assertThat(runs.findById("r13").orElseThrow().prUrl()).isEqualTo("https://github.com/o/r/pull/3");
    }

    @Test
    void aDoneEventWithNoPrUrlLeavesItNull() {
        recorder.onRunRecorded(new RunRecorded("r14", new RunEvent("step", "start",
                Map.of("task", "t", "repoSlug", "fixture", "phase", "PREPARING", "strategy", "DIRECT"))));

        recorder.onRunRecorded(new RunRecorded("r14", new RunEvent("done", "done", Map.of("phase", "DONE"))));

        assertThat(runs.findById("r14").orElseThrow().prUrl()).isNull();
    }
```

Read `src/test/java/ai/devflow/web/StaticPageTest.java` first (its current full content, shown earlier in this plan's exploration). Add one more assertion:

```java
    @Test
    void servesTheOperatorPageAtRoot() throws Exception {
        mvc.perform(get("/")).andExpect(status().isOk());

        mvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("devflowai")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"task\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"run\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("EventSource")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"history\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/api/runs/history")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"strategy\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("value=\"direct\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"open-pr\"")));
    }
```

- [ ] **Step 2: Run to verify they fail**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests '*SdlcRunRecorderTest*' --tests '*StaticPageTest*'`
Expected: compilation failure (`SdlcRun.prUrl()` doesn't exist) and a missing-element assertion failure (no `id="open-pr"` in the page yet).

- [ ] **Step 3: Update `SdlcRun`**

Read `src/main/java/ai/devflow/history/SdlcRun.java` first. Add a field and accessor/setter, following the exact pattern `buildSucceeded`/`recordBuildResult` already established:

```java
    private Boolean buildSucceeded;
    private String prUrl;
```

```java
    public Boolean buildSucceeded() { return buildSucceeded; }
    public String prUrl() { return prUrl; }
```

```java
    public void recordBuildResult(boolean succeeded) {
        this.buildSucceeded = succeeded;
    }

    public void recordPrUrl(String prUrl) {
        this.prUrl = prUrl;
    }
```

No `@Column` annotation is needed — a plain `String` field maps to a nullable `VARCHAR` column by default, exactly like `reason` (though without its explicit length cap; a PR URL is always short, so the default column length is fine).

- [ ] **Step 4: Update `SdlcRunRecorder`**

Read `src/main/java/ai/devflow/history/SdlcRunRecorder.java` first. Add a new method, alongside `maybeRecordBuildResult`:

```java
    private void maybeRecordPrUrl(String runId, Map<String, Object> data) {
        if (!(data.get("prUrl") instanceof String prUrl)) return;
        runs.findById(runId).ifPresent(run -> {
            run.recordPrUrl(prUrl);
            runs.save(run);
        });
    }
```

Call it from `onRunRecorded`, alongside the other `maybeXxx` calls:

```java
            maybeCreateRun(runId, data);
            maybeRecordStage(runId, data);
            maybeRecordFindings(runId, data);
            maybeRecordBuildResult(runId, data);
            maybeRecordPrUrl(runId, data);
            maybeFinishRun(runId, recorded.event().type(), recorded.event().message(), data);
```

- [ ] **Step 5: Update `RunSummary`**

Read `src/main/java/ai/devflow/web/RunSummary.java` first (its current full content, shown earlier in this plan's exploration). Change it to:

```java
package ai.devflow.web;

import ai.devflow.history.SdlcRun;

import java.time.Instant;

public record RunSummary(String runId, String task, String repoSlug, String status, String reason,
                          Instant startedAt, Instant finishedAt, long inputTokens, long outputTokens,
                          String strategy, Boolean buildSucceeded, String prUrl) {

    public static RunSummary from(SdlcRun run) {
        return new RunSummary(run.id(), run.task(), run.repoSlug(), run.status().name(), run.reason(),
                run.startedAt(), run.finishedAt(), run.inputTokens(), run.outputTokens(),
                run.strategy() == null ? null : run.strategy().name(), run.buildSucceeded(), run.prUrl());
    }
}
```

- [ ] **Step 6: Run `SdlcRunRecorderTest` to verify it passes**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests '*SdlcRunRecorderTest*'`
Expected: PASS (all tests, old and new).

- [ ] **Step 7: Add the UI checkbox and wire it into the start request**

Read `src/main/resources/static/index.html` first (its current full content, shown earlier in this plan's exploration). Find the Strategy row and add a new row right after it, before the `<button id="run">` line:

```html
    <div class="row">
      <label for="strategy">Strategy</label>
      <select id="strategy">
        <option value="orchestrated">Orchestrated (planner, reviewer, gates)</option>
        <option value="direct">Direct (single coder pass, no review)</option>
      </select>
    </div>
    <div class="row">
      <label style="display:flex; align-items:center; gap:0.5rem;">
        <input id="open-pr" type="checkbox" style="width:auto;">
        Open a pull request when done (requires a real repo, not the bundled fixture)
      </label>
    </div>
    <button id="run" class="primary">Run</button>
```

Find the `$('run').onclick` handler's fetch call:

```javascript
    const res = await fetch('/api/runs', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ task, repo, strategy: $('strategy').value })
    });
```

Change it to:

```javascript
    const res = await fetch('/api/runs', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ task, repo, strategy: $('strategy').value, openPr: $('open-pr').checked })
    });
```

- [ ] **Step 8: Render the PR link in the live log and the past-runs table**

In the same file, find the `log(type, message, data)` function's token-cost block:

```javascript
    if (data && (data.inputTokens !== undefined || data.outputTokens !== undefined)) {
      const cost = document.createElement('div');
      cost.className = 'files'; // reuse the existing muted-text style
      cost.textContent = `${data.inputTokens || 0} input tokens · ${data.outputTokens || 0} output tokens`;
      el.appendChild(cost);
    }
    $('log').appendChild(el);
    el.scrollIntoView({ block: 'nearest' });
```

Add a PR-link block right after it, before `$('log').appendChild(el);`:

```javascript
    if (data && data.prUrl) {
      const pr = document.createElement('div');
      pr.className = 'files';
      const link = document.createElement('a');
      link.href = data.prUrl;
      link.target = '_blank';
      link.rel = 'noopener noreferrer';
      link.textContent = 'View pull request';
      pr.appendChild(link);
      el.appendChild(pr);
    }
    $('log').appendChild(el);
    el.scrollIntoView({ block: 'nearest' });
```

Find the history table's header:

```html
    <table style="width:100%; border-collapse:collapse; margin-top:0.6rem; font-size:0.85rem;">
      <thead>
        <tr style="text-align:left; color:var(--muted);">
          <th style="padding:0.3rem 0;">Task</th>
          <th>Strategy</th>
          <th>Status</th>
          <th>Build</th>
          <th>Started</th>
        </tr>
      </thead>
      <tbody id="history-body"></tbody>
    </table>
```

Change it to:

```html
    <table style="width:100%; border-collapse:collapse; margin-top:0.6rem; font-size:0.85rem;">
      <thead>
        <tr style="text-align:left; color:var(--muted);">
          <th style="padding:0.3rem 0;">Task</th>
          <th>Strategy</th>
          <th>Status</th>
          <th>Build</th>
          <th>PR</th>
          <th>Started</th>
        </tr>
      </thead>
      <tbody id="history-body"></tbody>
    </table>
```

Find the `renderHistory(rows)` function:

```javascript
  function renderHistory(rows) {
    const body = $('history-body');
    body.innerHTML = '';
    rows.forEach(r => {
      const tr = document.createElement('tr');
      tr.style.borderTop = '1px solid var(--line)';

      const task = r.task.length > 60 ? r.task.slice(0, 60) + '…' : r.task;
      const taskCell = document.createElement('td');
      taskCell.style.padding = '0.3rem 0';
      taskCell.textContent = task;

      const strategyCell = document.createElement('td');
      strategyCell.textContent = r.strategy || '';

      const statusCell = document.createElement('td');
      statusCell.textContent = r.status;

      const buildCell = document.createElement('td');
      buildCell.textContent = r.buildSucceeded === true ? 'passed' : r.buildSucceeded === false ? 'failed' : '';

      const startedCell = document.createElement('td');
      startedCell.textContent = new Date(r.startedAt).toLocaleString();

      tr.appendChild(taskCell);
      tr.appendChild(strategyCell);
      tr.appendChild(statusCell);
      tr.appendChild(buildCell);
      tr.appendChild(startedCell);
      body.appendChild(tr);
    });
  }
```

Change it to:

```javascript
  function renderHistory(rows) {
    const body = $('history-body');
    body.innerHTML = '';
    rows.forEach(r => {
      const tr = document.createElement('tr');
      tr.style.borderTop = '1px solid var(--line)';

      const task = r.task.length > 60 ? r.task.slice(0, 60) + '…' : r.task;
      const taskCell = document.createElement('td');
      taskCell.style.padding = '0.3rem 0';
      taskCell.textContent = task;

      const strategyCell = document.createElement('td');
      strategyCell.textContent = r.strategy || '';

      const statusCell = document.createElement('td');
      statusCell.textContent = r.status;

      const buildCell = document.createElement('td');
      buildCell.textContent = r.buildSucceeded === true ? 'passed' : r.buildSucceeded === false ? 'failed' : '';

      const prCell = document.createElement('td');
      if (r.prUrl) {
        const prLink = document.createElement('a');
        prLink.href = r.prUrl;
        prLink.target = '_blank';
        prLink.rel = 'noopener noreferrer';
        prLink.textContent = 'PR';
        prCell.appendChild(prLink);
      }

      const startedCell = document.createElement('td');
      startedCell.textContent = new Date(r.startedAt).toLocaleString();

      tr.appendChild(taskCell);
      tr.appendChild(strategyCell);
      tr.appendChild(statusCell);
      tr.appendChild(buildCell);
      tr.appendChild(prCell);
      tr.appendChild(startedCell);
      body.appendChild(tr);
    });
  }
```

Both PR-link blocks set `.textContent` on the link's label and only the `href`/`target`/`rel` attributes directly — never `innerHTML` with interpolated data, matching the established XSS-safety precedent, even though `prUrl` originates from GitHub's own API response rather than free-text input.

- [ ] **Step 9: Run `StaticPageTest` to verify it passes**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests '*StaticPageTest*'`
Expected: PASS.

- [ ] **Step 10: Add the end-to-end open-PR test to `RunFlowIntegrationTest`**

Read `src/test/java/ai/devflow/web/RunFlowIntegrationTest.java` first — Task 4 Step 9 already added a `@TestBean(name = "gitHubClient", methodName = "stubGitHubClient")` override and its `stubGitHubClient()` factory method to this file; this step only adds the one test that exercises them, now that `SdlcRun.prUrl()` exists for it to assert on.

Add one new test proving the whole `openPr` path works over real HTTP, end to end. Because `RunController`'s own validation (Task 4 Step 4) rejects `openPr: true` against `"fixture"` at the HTTP boundary, this test must start the run against a real GitHub URL so that validation passes and `RunRegistry` routes it to a real `ClonedWorkspace` — which means this test needs `@Tag("live")` like this file's pre-existing `aClonedRepoFlowsThroughGate1AndAbortsCleanlyAtGate2`, since it performs a real network clone even though the push/PR step itself is stubbed:

```java
    @Test
    @Tag("live")
    void anOpenPrRunPushesAndOpensAPrAgainstTheRealClone() throws Exception {
        String body = mvc.perform(post("/api/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new StartRunRequest(
                                "add a class", "https://github.com/zjimmm/devflowai.git", "direct", true))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String runId = json.readTree(body).get("runId").asText();
        RunHandle handle = registry.find(runId);
        assertThat(handle).isNotNull();

        long deadline = System.currentTimeMillis() + 120_000;
        while (!handle.task().isDone()) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("run never finished");
            Thread.sleep(10);
        }

        assertThat(handle.state().phase()).isEqualTo(RunPhase.DONE);
        assertThat(runsRepo.findById(runId).orElseThrow().prUrl()).isNotNull();
    }
```

This test is excluded from the default `./gradlew test` run by the project's existing tag configuration, the same way `aClonedRepoFlowsThroughGate1AndAbortsCleanlyAtGate2` already is — it only actually runs under `./gradlew liveTest`.

- [ ] **Step 11: Create the gated live test**

Create `src/test/java/ai/devflow/GitHubIntegrationLiveTest.java`. This test requires a real, disposable GitHub repository set up outside this plan — it will not run (and does not need to) until `DEVFLOWAI_LIVETEST_REPO` is configured in your own environment. Point it at that repo's `https://github.com/<owner>/<repo>` URL:

```java
package ai.devflow;

import ai.devflow.orchestrator.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import ai.devflow.web.StartRunRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The one place a real GitHub write happens. Requires a small, disposable
 * scratch repo you control -- never devflowai's own repo -- so its test
 * branches and PRs don't clutter a real project. See the PR Creation spec
 * §6 for why this repo must be separate.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Tag("live")
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "DEVFLOWAI_GITHUB_TOKEN", matches = ".+")
@EnabledIfEnvironmentVariable(named = "DEVFLOWAI_LIVETEST_REPO", matches = ".+")
class GitHubIntegrationLiveTest {

    @Autowired MockMvc mvc;
    @Autowired RunRegistry registry;
    ObjectMapper json = new ObjectMapper();

    @Test
    void aDirectStrategyRunOpensARealPullRequest() throws Exception {
        String repoUrl = System.getenv("DEVFLOWAI_LIVETEST_REPO");

        String body = mvc.perform(post("/api/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                new StartRunRequest("add a small class", repoUrl, "direct", true))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String runId = json.readTree(body).get("runId").asText();
        RunHandle handle = registry.find(runId);
        assertThat(handle).isNotNull();

        long deadline = System.currentTimeMillis() + 180_000;
        while (!handle.task().isDone()) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("run never finished");
            Thread.sleep(50);
        }

        assertThat(handle.state().phase()).isEqualTo(RunPhase.DONE);
    }
}
```

- [ ] **Step 12: Run the full suite**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test`
Expected: PASS. `GitHubIntegrationLiveTest` (and the `@Tag("live")` test from Task 4/this task exercising a real clone) are excluded from the default `test` task per the project's existing tag configuration — they will not run, and do not need `DEVFLOWAI_LIVETEST_REPO` to be set, until you deliberately run `./gradlew liveTest` with that variable (and `DEVFLOWAI_GITHUB_TOKEN`) configured.

- [ ] **Step 13: Manually verify in a browser**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && export ANTHROPIC_API_KEY=<key> && ./gradlew bootRun`

Open `http://localhost:8080`. Confirm the "Open a pull request when done" checkbox appears below the Strategy dropdown. Start a run against the bundled fixture with the checkbox checked and confirm it is rejected with a clear error (the fixture-rejection check). If you have a real `DEVFLOWAI_GITHUB_TOKEN` and a disposable scratch repo available, paste that repo's URL, check the box, and run a Direct-strategy task to completion — confirm a "View pull request" link appears in the log and in the past-runs table's new PR column after refreshing.

- [ ] **Step 14: Commit**

```bash
git add src/main/java/ai/devflow/history/SdlcRun.java src/main/java/ai/devflow/history/SdlcRunRecorder.java \
        src/main/java/ai/devflow/web/RunSummary.java src/main/resources/static/index.html \
        src/test/java/ai/devflow/history/SdlcRunRecorderTest.java src/test/java/ai/devflow/web/StaticPageTest.java \
        src/test/java/ai/devflow/web/RunFlowIntegrationTest.java src/test/java/ai/devflow/GitHubIntegrationLiveTest.java
git commit -m "$(cat <<'EOF'
feat: persist and surface the PR link; add the operator page checkbox

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Spec coverage check

- §2 (goals: opted-in push/PR after a successful commit, both strategies, link back in the UI) — Tasks 3-5.
- §3 non-goals (CI/CD polling, deployment/release, non-GitHub forges, automatic PR creation, a retry loop, GitHub App auth) — respected throughout; `RunController`'s validation (Task 4) is the single enforcement point for "GitHub only."
- §4.1 (request shape, fixture rejection) — Task 4 Steps 1, 4.
- §4.2 (`RunPhase.OPENING_PR` placement for both executors) — Task 3.
- §4.3 (`GitHubClient` seam, JGit push, `RestClient` PR creation, token from env var) — Task 1, wired via Task 4 Step 7.
- §4.4 (PR content assembly from existing run data, no new LLM call) — Task 2.
- §4.5 (push failure = loud loss; PR failure = graceful degradation) — Task 3 Steps 4-5, tested in Steps 6-7.
- §4.6 (UI checkbox + PR link rendering, textContent-only) — Task 5 Steps 7-8.
- §5 (`SdlcRun.prUrl`, `SdlcRunRecorder`, `RunSummary`, past-runs table column) — Task 5 Steps 3-8.
- §6 (testing: stubbed `GitHubClient` everywhere except one gated live test against a separate scratch repo) — Task 1 (unit tests), Task 3 (executor-level push/PR branching tests), Task 5 (the gated `GitHubIntegrationLiveTest`).
- §7 (design decisions: `GitHubClient` lives in `ai.devflow.tools`; one PR content assembler parameterized by strategy; no shared helper class between the two executors) — Task 1 (package placement), Task 2 (single assembler), Task 3 (independent wiring in each executor, matching Sub-project 3's own precedent).
- §8 (ship order) — followed task-for-task, with the one documented exception that the gated live test's *skeleton* (Ship-order item 5) is written in Task 5 rather than deferred indefinitely, per this plan's own dispatch instructions — it simply cannot execute successfully until `DEVFLOWAI_LIVETEST_REPO` is configured outside this codebase.
