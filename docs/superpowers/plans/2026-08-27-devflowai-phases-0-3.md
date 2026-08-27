# devflowai Implementation Plan — Phases 0–3

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a working AI agent crew that takes a plain-language development task and returns a git branch — orchestrator, coder, reviewer, and a bounded review→fix loop, on a deterministic test fixture.

**Architecture:** Central orchestration. An LLM router picks the agent sequence; Java code executes it. Agents are pure `RunState → AgentResult` functions that never call each other and never see each other's context. All filesystem access goes through a path-confined guard rooted at a temp workspace.

**Tech Stack:** Java 21 · Spring Boot 4.1.1 · Spring AI 2.0.1 (Anthropic) · Gradle 9.5.1 · JUnit 5 + AssertJ · JGit

**Spec:** `docs/superpowers/specs/2026-08-27-devflowai-design.md`

## Global Constraints

- **Java 21.** JDK 21 is at `/opt/homebrew/opt/openjdk@21` but is NOT on `PATH`. Gradle toolchain must pin it explicitly.
- **Spring Boot 4.1.1 + Spring AI 2.0.1.** Matched pair — `spring-ai-starter-model-anthropic:2.0.1` depends on `spring-boot-starter:4.1.1`. Do not upgrade one alone.
- **Pin every model explicitly.** Spring AI's Anthropic starter defaults to `claude-sonnet-4-20250514` (stale). Router/scribe = `claude-haiku-4-5`. Coder/reviewer = `claude-opus-5`.
- **Never send `temperature`.** Spring AI defaults it to `1.0`; Opus 5 rejects sampling parameters with HTTP 400.
- **The repo is public.** No API key in any tracked file. `ANTHROPIC_API_KEY` from environment only.
- **Base package:** `ai.devflow`
- **Orchestrator never holds file contents or diffs.** Only `AgentResult` records. This is a review criterion, not a suggestion.
- **Every path-taking tool goes through `PathGuard`.** No exceptions.
- **Review loop cap: 3.** Human steering cap: 5. Separate counters.

---

## File Structure

```
src/main/java/ai/devflow/
  DevflowaiApplication.java        Spring Boot entry point
  workspace/
    Workspace.java                 interface: root(), branchName(), prepare(), cleanup()
    FixtureWorkspace.java          copies bundled fixture to a temp dir
    PathGuard.java                 canonicalises + rejects escapes. Security-critical.
  tools/
    FileTools.java                 @Tool readFile/writeFile/listFiles/searchFiles
    GitTools.java                  @Tool createBranch/diff/status/commit (JGit)
    BuildTools.java                @Tool runBuild/runTests (target repo's wrapper)
  agent/
    AgentResult.java               record + Status enum
    Finding.java                   record + Origin, Severity enums
    TokenUsage.java                record
    Agent.java                     interface: AgentResult run(RunState)
    CoderAgent.java
    ReviewerAgent.java
    Router.java                    returns RoutingDecision
    RoutingDecision.java           record: agents + skillNames
  orchestrator/
    Orchestrator.java              sequences agents, owns the bounded loop
    RunState.java                  mutable run context
  config/
    ChatClientConfig.java          model pinning; the temperature fix lives here

src/test/java/ai/devflow/...       mirrors main
src/test/resources/fixture/        the target repo: a flawed UserController

.claude/
  settings.json                    hooks + permissions
  skills/add-agent/SKILL.md
  skills/add-tool/SKILL.md
CLAUDE.md                          project context for every future session
```

**Why this split:** `workspace/` and `tools/` are pure deterministic Java with no LLM involvement — they carry the whole test suite's weight and must be solid before any agent exists. `agent/` classes are thin: a prompt, a `ChatClient` call, and a mapping to `AgentResult`. `orchestrator/` is the only place control flow lives.

---

# PHASE 0 — Foundations

## Task 1: Buildable Spring Boot skeleton

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle/wrapper/*`
- Create: `src/main/java/ai/devflow/DevflowaiApplication.java`
- Create: `src/main/resources/application.yml`
- Test: `src/test/java/ai/devflow/DevflowaiApplicationTests.java`

**Interfaces:**
- Consumes: nothing
- Produces: a Spring context that boots; `./gradlew test` as the project's verification command

- [ ] **Step 1: Generate the Gradle wrapper**

```bash
cd /Users/jim/Projects/devflowai
gradle wrapper --gradle-version 9.5.1
```

- [ ] **Step 2: Write `settings.gradle.kts`**

```kotlin
rootProject.name = "devflowai"
```

- [ ] **Step 3: Write `build.gradle.kts`**

JDK 21 is not on `PATH`, so the toolchain block is what makes this build at all.

```kotlin
plugins {
    java
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "ai.devflow"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories { mavenCentral() }

dependencyManagement {
    imports { mavenBom("org.springframework.ai:spring-ai-bom:2.0.1") }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.ai:spring-ai-starter-model-anthropic")
    implementation("org.eclipse.jgit:org.eclipse.jgit:7.1.0.202411261347-r")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> { useJUnitPlatform() }
```

- [ ] **Step 4: Write the application class**

```java
package ai.devflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class DevflowaiApplication {
    public static void main(String[] args) {
        SpringApplication.run(DevflowaiApplication.class, args);
    }
}
```

- [ ] **Step 5: Write `application.yml`**

The API key comes from the environment. Never hardcode it — this repo is public.

```yaml
spring:
  application:
    name: devflowai
  ai:
    anthropic:
      api-key: ${ANTHROPIC_API_KEY}

devflowai:
  review:
    max-iterations: 3
    max-human-iterations: 5
  gate:
    timeout-minutes: 10
```

- [ ] **Step 6: Write the context-loads test**

```java
package ai.devflow;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "spring.ai.anthropic.api-key=test-key-not-used")
class DevflowaiApplicationTests {
    @Test
    void contextLoads() {}
}
```

- [ ] **Step 7: Run the test**

Run: `./gradlew test`
Expected: PASS. If it fails on toolchain resolution, add to `gradle.properties`:
`org.gradle.java.installations.paths=/opt/homebrew/opt/openjdk@21`

- [ ] **Step 8: Commit**

```bash
git add settings.gradle.kts build.gradle.kts gradle/ gradlew gradlew.bat src/ gradle.properties
git commit -m "feat: Spring Boot 4.1.1 skeleton with Spring AI 2.0.1 and JDK 21 toolchain"
```

---

## Task 2: Claude Code project scaffolding

**Files:**
- Create: `CLAUDE.md`
- Create: `.claude/settings.json`
- Create: `.claude/hooks/secret-scan.sh`
- Create: `.claude/skills/add-tool/SKILL.md`
- Create: `.claude/skills/add-agent/SKILL.md`

**Interfaces:**
- Consumes: nothing
- Produces: project context + guardrails for every subsequent task in this plan

This lands before the code because every later task benefits from it, and because the secret-scan hook protects a public repo from the first commit that could leak.

- [ ] **Step 1: Write `CLAUDE.md`**

```markdown
# devflowai

An AI agent crew that takes a plain-language development task and returns a git
branch. Orchestrator routes → coder implements → reviewer critiques → bounded
loop → commit. Learns by writing skill docs when the reviewer bounces the coder.

Design spec: `docs/superpowers/specs/2026-08-27-devflowai-design.md`
Plan: `docs/superpowers/plans/2026-08-27-devflowai-phases-0-3.md`

## Build and test

    ./gradlew test          # full suite; most tests stub ChatClient and cost nothing
    ./gradlew bootRun       # needs ANTHROPIC_API_KEY in the environment

JDK 21 is at /opt/homebrew/opt/openjdk@21 and is NOT on PATH. The Gradle
toolchain block pins it — do not remove it.

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
```

- [ ] **Step 2: Write the secret-scan hook**

```bash
#!/usr/bin/env bash
# Blocks `git commit` when staged content contains key-shaped strings.
# devflowai is a public repo; a leaked key is unrecoverable.
set -uo pipefail

payload=$(cat)
command=$(printf '%s' "$payload" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("tool_input",{}).get("command",""))' 2>/dev/null || true)

case "$command" in
  *"git commit"*) ;;
  *) exit 0 ;;
esac

if git diff --cached 2>/dev/null | grep -qiE 'sk-ant-[A-Za-z0-9_-]{16,}|ANTHROPIC_API_KEY[[:space:]]*=[[:space:]]*[A-Za-z0-9]'; then
  echo "BLOCKED: staged content contains a key-shaped string. devflowai is a public repo." >&2
  exit 2
fi
exit 0
```

- [ ] **Step 3: Make it executable**

```bash
chmod +x .claude/hooks/secret-scan.sh
```

- [ ] **Step 4: Write `.claude/settings.json`**

Permissions cut prompt noise for the commands this plan runs constantly.

```json
{
  "permissions": {
    "allow": [
      "Bash(./gradlew test)",
      "Bash(./gradlew build)",
      "Bash(./gradlew compileJava)",
      "Bash(./gradlew bootRun)",
      "Bash(git status)",
      "Bash(git diff:*)",
      "Bash(git log:*)",
      "Bash(git add:*)"
    ],
    "deny": [
      "Bash(git push --force:*)",
      "Bash(git push -f:*)"
    ]
  },
  "hooks": {
    "PreToolUse": [
      {
        "matcher": "Bash",
        "hooks": [
          { "type": "command", "command": "$CLAUDE_PROJECT_DIR/.claude/hooks/secret-scan.sh" }
        ]
      }
    ]
  }
}
```

- [ ] **Step 5: Verify the hook actually blocks**

```bash
echo 'ANTHROPIC_API_KEY=sk-ant-abcdefghijklmnop1234' > /tmp/leak-test.txt
git add -f /tmp/leak-test.txt 2>/dev/null || cp /tmp/leak-test.txt ./leak-test.txt && git add leak-test.txt
printf '{"tool_input":{"command":"git commit -m x"}}' | ./.claude/hooks/secret-scan.sh; echo "exit=$?"
```

Expected: `BLOCKED: ...` on stderr and `exit=2`.

Then clean up: `git reset leak-test.txt && rm -f leak-test.txt /tmp/leak-test.txt`

- [ ] **Step 6: Write `.claude/skills/add-tool/SKILL.md`**

```markdown
---
name: add-tool
description: Use when adding a new @Tool method that any devflowai agent can call
---

# Adding a tool to devflowai

Every tool that accepts a path MUST resolve it through `PathGuard`. A tool calling
`Paths.get()` or `File` directly is a security bug — it lets an agent read
`../../.ssh/id_rsa`.

## Steps

1. Add the method to the relevant class in `ai.devflow.tools`
   (`FileTools`, `GitTools`, `BuildTools`) — create a new class only for a genuinely
   new capability area.
2. Annotate with `@Tool(description = "...")`. The description is a prompt: the
   model chooses the tool from it, so say what it does and when to use it.
3. Resolve every path argument via the injected `PathGuard`:

   ```java
   Path target = pathGuard.resolve(relativePath);
   ```

4. Return a `String`. Tool returns are fed back to the model as text — return an
   error message rather than throwing, so the model can recover.
5. Register on the agent that needs it in `ChatClientConfig`. Give it to the
   fewest agents possible: the reviewer gets read-only file access, the scribe
   gets nothing.

## Test it

Write the escape test first. It is the one that matters:

```java
@Test
void rejectsPathEscapingWorkspace() {
    assertThatThrownBy(() -> fileTools.readFile("../../../etc/passwd"))
        .isInstanceOf(SecurityException.class);
}
```
```

- [ ] **Step 7: Write `.claude/skills/add-agent/SKILL.md`**

```markdown
---
name: add-agent
description: Use when adding a new agent (planner, test-writer, doc-writer) to the devflowai crew
---

# Adding an agent to devflowai

Agents are pure functions: `RunState in, AgentResult out`. They never call each
other and never see another agent's context. The orchestrator decides what runs.

## Steps

1. Implement `ai.devflow.agent.Agent`:

   ```java
   public interface Agent {
       AgentResult run(RunState state);
   }
   ```

2. Give it a dedicated `ChatClient` bean in `ChatClientConfig` — its own system
   prompt, its own model, its own tool subset. Pin the model explicitly and do
   not set `temperature`.
3. Add a constant to the router's agent enum so the router can select it.
4. Add the `case` to the orchestrator's `switch`.
5. Return `AgentResult` with a real `summary` — the orchestrator shows this to the
   operator and it is the ONLY thing downstream agents learn about this step.

## Do not

- Pass file contents or diffs back through `AgentResult`. Put the path in
  `filesTouched` and let the next agent read it off disk. The flat-orchestrator
  guarantee depends on this.
- Let the agent call another agent. If it needs work done first, that is a routing
  decision, not the agent's decision.

## Test it

Stub the `ChatClient` so the test costs nothing:

```java
ChatClient stub = ChatClientStubs.returning("{\"status\":\"OK\",\"summary\":\"...\"}");
```
```

- [ ] **Step 8: Commit**

```bash
git add CLAUDE.md .claude/
git commit -m "chore: Claude Code project scaffolding — context, hooks, skills"
```

---

## Task 3: Spring AI spike — resolve the two integration landmines

**Files:**
- Create: `src/test/java/ai/devflow/config/AnthropicSpikeTest.java`
- Modify: `CLAUDE.md` (record the findings)

**Interfaces:**
- Consumes: the Spring Boot skeleton from Task 1
- Produces: the verified exact builder syntax that `ChatClientConfig` (Task 10) will use

**This is the highest-value hour in the plan.** Two documented facts need converting into working code before anything is built on top:

1. Spring AI's Anthropic starter defaults to a stale model.
2. Spring AI defaults `temperature` to `1.0`; Opus 5 rejects sampling params with a 400.

If (2) cannot be cleanly unset through the options builder, that reshapes Task 10 — and you want to know now, not in Phase 3.

**Method note:** do not research the SDK's type names first. Write the code from the shapes below, run the compiler, and let `cannot find symbol` point at the right member. That is faster than reading source.

- [ ] **Step 1: Write the spike test, tagged so it does not run in CI**

```java
package ai.devflow.config;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Tag("live")
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
class AnthropicSpikeTest {

    @Autowired
    ChatClient.Builder builder;

    @Test
    void opus5RespondsWithoutTemperature() {
        var options = AnthropicChatOptions.builder()
                .model("claude-opus-5")
                .build();

        String reply = builder.build()
                .prompt("Reply with exactly the word: OK")
                .options(options)
                .call()
                .content();

        assertThat(reply).contains("OK");
    }
}
```

- [ ] **Step 2: Exclude live tests from the default run**

Add to `build.gradle.kts`:

```kotlin
tasks.withType<Test> {
    useJUnitPlatform {
        excludeTags("live")
    }
}

tasks.register<Test>("liveTest") {
    useJUnitPlatform { includeTags("live") }
    group = "verification"
}
```

- [ ] **Step 3: Run the spike**

Run: `ANTHROPIC_API_KEY=$ANTHROPIC_API_KEY ./gradlew liveTest --info`

Three possible outcomes — record which one you got:

| Outcome | Meaning | Action |
|---|---|---|
| PASS | Builder omits `temperature` when unset | Task 10 uses this shape as-is |
| HTTP 400 mentioning `temperature` | Spring AI is sending its default | Go to Step 4 |
| Compile error | Guessed a member name wrong | Fix from the compiler message, re-run |

- [ ] **Step 4: If and only if Step 3 returned a 400 on `temperature`**

Try, in this order, stopping at the first that works:

```java
// (a) explicit null
AnthropicChatOptions.builder().model("claude-opus-5").temperature(null).build();
```

```yaml
# (b) null the property so autoconfiguration contributes no default
spring.ai.anthropic.chat.options.temperature: ~
```

```java
// (c) last resort: post-process the options object before the call,
//     or drop to the Anthropic Java SDK for the agent calls only.
```

- [ ] **Step 5: Verify adaptive thinking and effort compile and run**

```java
var options = AnthropicChatOptions.builder()
        .model("claude-opus-5")
        .thinkingAdaptive()
        .effort(AnthropicChatOptions.OutputConfig.Effort.HIGH)
        .build();
```

If either method name is wrong, the compiler names the correct one. Record the
working signature — Task 10 depends on it.

- [ ] **Step 6: Record the findings in `CLAUDE.md`**

Append a `## Verified Spring AI 2.0.1 syntax` section containing the exact builder
code that worked. Every future session reads this instead of rediscovering it.

- [ ] **Step 7: Commit**

```bash
git add src/test/java/ai/devflow/config/AnthropicSpikeTest.java build.gradle.kts CLAUDE.md
git commit -m "test: verify Opus 5 integration through Spring AI 2.0.1"
```

---

# PHASE 1 — Deterministic core (no LLM)

Everything in this phase is plain Java with no model calls. It carries the test
suite's weight and must be solid before an agent exists.

## Task 4: The fixture — a target repo with a known flaw

**Files:**
- Create: `src/test/resources/fixture/build.gradle.kts`
- Create: `src/test/resources/fixture/settings.gradle.kts`
- Create: `src/test/resources/fixture/src/main/java/com/example/User.java`
- Create: `src/test/resources/fixture/src/main/java/com/example/UserController.java`
- Create: `src/test/resources/fixture/src/test/java/com/example/UserControllerTest.java`

**Interfaces:**
- Consumes: nothing
- Produces: a buildable project at a known path, containing a flaw the reviewer must catch

The flaw is deliberate and specific: `UserController` accepts a `User` with no
validation. The correct fix is a DTO plus `@Valid`. This is what makes
"the reviewer caught it" an assertable fact rather than a hope.

- [ ] **Step 1: Write the fixture build files**

```kotlin
// settings.gradle.kts
rootProject.name = "fixture"
```

```kotlin
// build.gradle.kts
plugins {
    java
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
}
group = "com.example"
version = "0.0.1-SNAPSHOT"
java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
repositories { mavenCentral() }
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
tasks.withType<Test> { useJUnitPlatform() }
```

- [ ] **Step 2: Write the flawed source**

```java
package com.example;

public class User {
    private String email;
    private String name;

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
}
```

```java
package com.example;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/users")
public class UserController {

    // FLAW (deliberate): no validation. A null or malformed email is accepted.
    @PostMapping
    public ResponseEntity<User> create(@RequestBody User user) {
        return ResponseEntity.ok(user);
    }
}
```

- [ ] **Step 3: Write a passing baseline test**

```java
package com.example;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class UserControllerTest {
    @Test
    void controllerExists() {
        assertThat(new UserController()).isNotNull();
    }
}
```

- [ ] **Step 4: Verify the fixture builds standalone**

```bash
cd src/test/resources/fixture && gradle test && cd -
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add src/test/resources/fixture/
git commit -m "test: add fixture project with a deliberate missing-validation flaw"
```

---

## Task 5: PathGuard

**Files:**
- Create: `src/main/java/ai/devflow/workspace/PathGuard.java`
- Test: `src/test/java/ai/devflow/workspace/PathGuardTest.java`

**Interfaces:**
- Consumes: nothing
- Produces: `PathGuard(Path root)` with `Path resolve(String relative)` — throws `SecurityException` on escape. Every tool in Task 7–9 uses this.

Security-critical. Write the escape tests first.

- [ ] **Step 1: Write the failing tests**

```java
package ai.devflow.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;

import static org.assertj.core.api.Assertions.*;

class PathGuardTest {

    @Test
    void resolvesPathInsideRoot(@TempDir Path root) {
        var guard = new PathGuard(root);
        assertThat(guard.resolve("src/Main.java")).startsWith(root);
    }

    @Test
    void rejectsParentTraversal(@TempDir Path root) {
        var guard = new PathGuard(root);
        assertThatThrownBy(() -> guard.resolve("../../../etc/passwd"))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void rejectsAbsolutePathOutsideRoot(@TempDir Path root) {
        var guard = new PathGuard(root);
        assertThatThrownBy(() -> guard.resolve("/etc/passwd"))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void rejectsSymlinkEscape(@TempDir Path root) throws IOException {
        Path outside = Files.createTempDirectory("outside");
        Files.createSymbolicLink(root.resolve("escape"), outside);
        var guard = new PathGuard(root);
        assertThatThrownBy(() -> guard.resolve("escape/secret.txt"))
                .isInstanceOf(SecurityException.class);
    }
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew test --tests '*PathGuardTest*'`
Expected: FAIL — `PathGuard` does not exist.

- [ ] **Step 3: Implement**

`normalize()` alone does not defeat symlinks — that is what the fourth test proves.
For an existing path use `toRealPath()`; for a not-yet-created file, resolve the
nearest existing ancestor.

```java
package ai.devflow.workspace;

import java.io.IOException;
import java.nio.file.*;

public final class PathGuard {

    private final Path root;

    public PathGuard(Path root) {
        try {
            this.root = root.toRealPath();
        } catch (IOException e) {
            throw new IllegalArgumentException("Workspace root unreadable: " + root, e);
        }
    }

    public Path root() { return root; }

    public Path resolve(String relative) {
        Path candidate = root.resolve(relative).normalize();
        Path real = realPathOfNearestExistingAncestor(candidate);
        if (!real.startsWith(root)) {
            throw new SecurityException("Path escapes workspace root: " + relative);
        }
        return candidate;
    }

    private Path realPathOfNearestExistingAncestor(Path candidate) {
        Path p = candidate;
        while (p != null && !Files.exists(p, LinkOption.NOFOLLOW_LINKS)) {
            p = p.getParent();
        }
        if (p == null) return candidate;
        try {
            Path real = p.toRealPath();
            Path remainder = p.relativize(candidate);
            return real.resolve(remainder).normalize();
        } catch (IOException e) {
            throw new SecurityException("Cannot verify path: " + candidate, e);
        }
    }
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew test --tests '*PathGuardTest*'`
Expected: all 4 PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/ai/devflow/workspace/PathGuard.java src/test/java/ai/devflow/workspace/PathGuardTest.java
git commit -m "feat: PathGuard confines all tool filesystem access to the workspace root"
```

---

## Task 6: Workspace interface and FixtureWorkspace

**Files:**
- Create: `src/main/java/ai/devflow/workspace/Workspace.java`
- Create: `src/main/java/ai/devflow/workspace/FixtureWorkspace.java`
- Test: `src/test/java/ai/devflow/workspace/FixtureWorkspaceTest.java`

**Interfaces:**
- Consumes: `PathGuard` (Task 5), the fixture (Task 4)
- Produces: `Workspace` with `Path root()`, `String branchName()`, `void prepare()`, `void cleanup()`, `PathGuard guard()`

- [ ] **Step 1: Write the failing test**

```java
package ai.devflow.workspace;

import org.junit.jupiter.api.*;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

class FixtureWorkspaceTest {

    Workspace workspace;

    @AfterEach
    void tearDown() throws Exception {
        if (workspace != null) workspace.cleanup();
    }

    @Test
    void prepareCopiesFixtureAndInitialisesGitBranch() throws Exception {
        workspace = new FixtureWorkspace(Path.of("src/test/resources/fixture"), "run-abc123");
        workspace.prepare();

        assertThat(workspace.root()).isDirectory();
        assertThat(workspace.root().resolve("src/main/java/com/example/UserController.java")).exists();
        assertThat(workspace.root().resolve(".git")).exists();
        assertThat(workspace.branchName()).isEqualTo("devflowai/run-abc123");
    }

    @Test
    void cleanupRemovesTheTempDirectory() throws Exception {
        var ws = new FixtureWorkspace(Path.of("src/test/resources/fixture"), "run-xyz");
        ws.prepare();
        Path root = ws.root();
        ws.cleanup();
        assertThat(root).doesNotExist();
    }

    @Test
    void doesNotMutateTheSourceFixture() throws Exception {
        Path source = Path.of("src/test/resources/fixture");
        long before = Files.walk(source).count();
        workspace = new FixtureWorkspace(source, "run-1");
        workspace.prepare();
        Files.writeString(workspace.root().resolve("scratch.txt"), "written by a test");
        assertThat(Files.walk(source).count()).isEqualTo(before);
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew test --tests '*FixtureWorkspaceTest*'`
Expected: FAIL — classes do not exist.

- [ ] **Step 3: Write the interface**

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

- [ ] **Step 4: Implement FixtureWorkspace**

```java
package ai.devflow.workspace;

import org.eclipse.jgit.api.Git;

import java.io.IOException;
import java.nio.file.*;
import java.util.Comparator;
import java.util.stream.Stream;

public class FixtureWorkspace implements Workspace {

    private final Path source;
    private final String runId;
    private Path root;
    private PathGuard guard;

    public FixtureWorkspace(Path source, String runId) {
        this.source = source;
        this.runId = runId;
    }

    @Override public Path root() { return root; }
    @Override public String branchName() { return "devflowai/" + runId; }
    @Override public PathGuard guard() { return guard; }

    @Override
    public void prepare() throws IOException {
        root = Files.createTempDirectory("devflowai-" + runId + "-");
        copyRecursively(source, root);
        try (Git git = Git.init().setDirectory(root.toFile()).call()) {
            git.add().addFilepattern(".").call();
            git.commit().setMessage("baseline").setSign(false).call();
            git.checkout().setCreateBranch(true).setName(branchName()).call();
        } catch (Exception e) {
            throw new IOException("Failed to initialise git in workspace", e);
        }
        guard = new PathGuard(root);
    }

    @Override
    public void cleanup() throws IOException {
        if (root == null || !Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) { }
            });
        }
    }

    private void copyRecursively(Path from, Path to) throws IOException {
        try (Stream<Path> walk = Files.walk(from)) {
            walk.forEach(src -> {
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
}
```

- [ ] **Step 5: Run tests**

Run: `./gradlew test --tests '*FixtureWorkspaceTest*'`
Expected: all 3 PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/ai/devflow/workspace/ src/test/java/ai/devflow/workspace/
git commit -m "feat: Workspace interface and FixtureWorkspace with git init and cleanup"
```

---

## Task 7: FileTools

**Files:**
- Create: `src/main/java/ai/devflow/tools/FileTools.java`
- Test: `src/test/java/ai/devflow/tools/FileToolsTest.java`

**Interfaces:**
- Consumes: `PathGuard` (Task 5)
- Produces: `FileTools(PathGuard)` with `@Tool` methods `readFile(String)`, `writeFile(String, String)`, `listFiles(String)`, `searchFiles(String)` — all return `String`

Tool returns feed back to the model as text, so errors are returned as strings, not
thrown — except security violations, which must be loud.

- [ ] **Step 1: Write the failing tests**

```java
package ai.devflow.tools;

import ai.devflow.workspace.PathGuard;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

class FileToolsTest {

    FileTools tools;
    Path root;

    @BeforeEach
    void setUp(@TempDir Path dir) throws Exception {
        root = dir;
        Files.writeString(dir.resolve("Hello.java"), "class Hello {}");
        Files.createDirectories(dir.resolve("src"));
        tools = new FileTools(new PathGuard(dir));
    }

    @Test
    void readsAFile() {
        assertThat(tools.readFile("Hello.java")).isEqualTo("class Hello {}");
    }

    @Test
    void writesAFileCreatingParents() {
        tools.writeFile("src/main/java/New.java", "class New {}");
        assertThat(root.resolve("src/main/java/New.java")).exists();
    }

    @Test
    void listsFiles() {
        assertThat(tools.listFiles(".")).contains("Hello.java");
    }

    @Test
    void searchFindsMatchingFiles() {
        assertThat(tools.searchFiles("class Hello")).contains("Hello.java");
    }

    @Test
    void returnsMessageRatherThanThrowingOnMissingFile() {
        assertThat(tools.readFile("Nope.java")).containsIgnoringCase("not found");
    }

    @Test
    void refusesToEscapeTheWorkspace() {
        assertThatThrownBy(() -> tools.readFile("../../../etc/passwd"))
                .isInstanceOf(SecurityException.class);
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew test --tests '*FileToolsTest*'`
Expected: FAIL — `FileTools` does not exist.

- [ ] **Step 3: Implement**

```java
package ai.devflow.tools;

import ai.devflow.workspace.PathGuard;
import org.springframework.ai.tool.annotation.Tool;

import java.io.IOException;
import java.nio.file.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class FileTools {

    private final PathGuard guard;

    public FileTools(PathGuard guard) { this.guard = guard; }

    @Tool(description = "Read a file from the workspace. Path is relative to the repository root.")
    public String readFile(String path) {
        Path target = guard.resolve(path);
        try {
            return Files.readString(target);
        } catch (NoSuchFileException e) {
            return "File not found: " + path;
        } catch (IOException e) {
            return "Could not read " + path + ": " + e.getMessage();
        }
    }

    @Tool(description = "Write a file in the workspace, creating parent directories. Overwrites if it exists.")
    public String writeFile(String path, String content) {
        Path target = guard.resolve(path);
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, content);
            return "Wrote " + path + " (" + content.length() + " chars)";
        } catch (IOException e) {
            return "Could not write " + path + ": " + e.getMessage();
        }
    }

    @Tool(description = "List files under a directory in the workspace, recursively.")
    public String listFiles(String path) {
        Path target = guard.resolve(path);
        try (Stream<Path> walk = Files.walk(target)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> !p.toString().contains("/.git/"))
                    .map(p -> guard.root().relativize(p).toString())
                    .collect(Collectors.joining("\n"));
        } catch (IOException e) {
            return "Could not list " + path + ": " + e.getMessage();
        }
    }

    @Tool(description = "Find files whose contents contain the given text. Returns matching paths.")
    public String searchFiles(String text) {
        try (Stream<Path> walk = Files.walk(guard.root())) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> !p.toString().contains("/.git/"))
                    .filter(p -> {
                        try { return Files.readString(p).contains(text); }
                        catch (IOException e) { return false; }
                    })
                    .map(p -> guard.root().relativize(p).toString())
                    .collect(Collectors.joining("\n"));
        } catch (IOException e) {
            return "Search failed: " + e.getMessage();
        }
    }
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew test --tests '*FileToolsTest*'`
Expected: all 6 PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/ai/devflow/tools/FileTools.java src/test/java/ai/devflow/tools/FileToolsTest.java
git commit -m "feat: FileTools with path-confined read, write, list and search"
```

---

## Task 8: GitTools

**Files:**
- Create: `src/main/java/ai/devflow/tools/GitTools.java`
- Test: `src/test/java/ai/devflow/tools/GitToolsTest.java`

**Interfaces:**
- Consumes: `Workspace` (Task 6)
- Produces: `GitTools(Workspace)` with `@Tool` methods `status()`, `diff()`, `commit(String message)`, and plain method `List<String> changedFiles()`

`changedFiles()` is deliberately NOT a `@Tool` — the orchestrator calls it to populate
`AgentResult.filesTouched` without the model being involved.

- [ ] **Step 1: Write the failing tests**

```java
package ai.devflow.tools;

import ai.devflow.workspace.*;
import org.junit.jupiter.api.*;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

class GitToolsTest {

    Workspace workspace;
    GitTools git;

    @BeforeEach
    void setUp() throws Exception {
        workspace = new FixtureWorkspace(Path.of("src/test/resources/fixture"), "git-test");
        workspace.prepare();
        git = new GitTools(workspace);
    }

    @AfterEach
    void tearDown() throws Exception { workspace.cleanup(); }

    @Test
    void statusIsCleanOnAFreshWorkspace() {
        assertThat(git.status()).containsIgnoringCase("clean");
    }

    @Test
    void diffShowsAnEditedFile() throws Exception {
        Files.writeString(workspace.root().resolve("src/main/java/com/example/User.java"),
                "package com.example;\npublic class User { }\n");
        assertThat(git.diff()).contains("User.java");
    }

    @Test
    void changedFilesListsModifiedPaths() throws Exception {
        Files.writeString(workspace.root().resolve("newfile.txt"), "hello");
        assertThat(git.changedFiles()).contains("newfile.txt");
    }

    @Test
    void commitRecordsTheChange() throws Exception {
        Files.writeString(workspace.root().resolve("newfile.txt"), "hello");
        assertThat(git.commit("add newfile")).containsIgnoringCase("committed");
        assertThat(git.status()).containsIgnoringCase("clean");
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew test --tests '*GitToolsTest*'`
Expected: FAIL.

- [ ] **Step 3: Implement**

```java
package ai.devflow.tools;

import ai.devflow.workspace.Workspace;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.springframework.ai.tool.annotation.Tool;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

public class GitTools {

    private final Workspace workspace;

    public GitTools(Workspace workspace) { this.workspace = workspace; }

    @Tool(description = "Show which files in the workspace have been added, modified or deleted.")
    public String status() {
        try (Git git = Git.open(workspace.root().toFile())) {
            Status s = git.status().call();
            if (s.isClean()) return "Working tree clean.";
            List<String> lines = new ArrayList<>();
            s.getAdded().forEach(f -> lines.add("added:    " + f));
            s.getChanged().forEach(f -> lines.add("changed:  " + f));
            s.getModified().forEach(f -> lines.add("modified: " + f));
            s.getRemoved().forEach(f -> lines.add("removed:  " + f));
            s.getUntracked().forEach(f -> lines.add("new:      " + f));
            return String.join("\n", lines);
        } catch (Exception e) {
            return "git status failed: " + e.getMessage();
        }
    }

    @Tool(description = "Show the unified diff of all uncommitted changes in the workspace.")
    public String diff() {
        try (Git git = Git.open(workspace.root().toFile())) {
            var out = new ByteArrayOutputStream();
            git.diff().setOutputStream(out).call();
            String d = out.toString();
            return d.isBlank() ? "No changes." : d;
        } catch (Exception e) {
            return "git diff failed: " + e.getMessage();
        }
    }

    @Tool(description = "Stage all changes and commit them with the given message.")
    public String commit(String message) {
        try (Git git = Git.open(workspace.root().toFile())) {
            git.add().addFilepattern(".").call();
            git.add().addFilepattern(".").setUpdate(true).call();
            var rev = git.commit().setMessage(message).setSign(false).call();
            return "Committed " + rev.getName().substring(0, 7) + ": " + message;
        } catch (Exception e) {
            return "git commit failed: " + e.getMessage();
        }
    }

    /** Not a @Tool — the orchestrator uses this to populate AgentResult.filesTouched. */
    public List<String> changedFiles() {
        try (Git git = Git.open(workspace.root().toFile())) {
            Status s = git.status().call();
            var all = new TreeSet<String>();
            all.addAll(s.getAdded());
            all.addAll(s.getChanged());
            all.addAll(s.getModified());
            all.addAll(s.getRemoved());
            all.addAll(s.getUntracked());
            return new ArrayList<>(all);
        } catch (Exception e) {
            return List.of();
        }
    }
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew test --tests '*GitToolsTest*'`
Expected: all 4 PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/ai/devflow/tools/GitTools.java src/test/java/ai/devflow/tools/GitToolsTest.java
git commit -m "feat: GitTools for status, diff, commit and changed-file listing"
```

---

## Task 9: BuildTools

**Files:**
- Create: `src/main/java/ai/devflow/tools/BuildTools.java`
- Test: `src/test/java/ai/devflow/tools/BuildToolsTest.java`

**Interfaces:**
- Consumes: `Workspace` (Task 6)
- Produces: `BuildTools(Workspace, Duration timeout)` with `@Tool String runTests()` and `record BuildResult(boolean success, String output)` via `BuildResult build(String task)`

This executes the *target repo's* build wrapper — arbitrary code execution by design
(spec §9). It is gated behind Gate 2 at runtime. Here it needs a hard timeout and
output truncation, because unbounded output would blow the model's context.

- [ ] **Step 1: Write the failing tests**

```java
package ai.devflow.tools;

import ai.devflow.workspace.*;
import org.junit.jupiter.api.*;
import java.nio.file.Path;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;

class BuildToolsTest {

    Workspace workspace;
    BuildTools build;

    @BeforeEach
    void setUp() throws Exception {
        workspace = new FixtureWorkspace(Path.of("src/test/resources/fixture"), "build-test");
        workspace.prepare();
        build = new BuildTools(workspace, Duration.ofMinutes(5));
    }

    @AfterEach
    void tearDown() throws Exception { workspace.cleanup(); }

    @Test
    void reportsMissingWrapperClearly() {
        // The fixture has no gradlew wrapper committed; BuildTools must say so
        // rather than hanging or throwing.
        var result = build.build("test");
        assertThat(result.output()).isNotBlank();
    }

    @Test
    void truncatesVeryLongOutput() {
        String huge = "x".repeat(50_000);
        assertThat(BuildTools.truncate(huge, 4_000)).hasSizeLessThan(4_200);
        assertThat(BuildTools.truncate(huge, 4_000)).contains("truncated");
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew test --tests '*BuildToolsTest*'`
Expected: FAIL.

- [ ] **Step 3: Implement**

```java
package ai.devflow.tools;

import ai.devflow.workspace.Workspace;
import org.springframework.ai.tool.annotation.Tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

public class BuildTools {

    public record BuildResult(boolean success, String output) {}

    private static final int MAX_OUTPUT_CHARS = 4_000;

    private final Workspace workspace;
    private final Duration timeout;

    public BuildTools(Workspace workspace, Duration timeout) {
        this.workspace = workspace;
        this.timeout = timeout;
    }

    @Tool(description = "Run the target repository's test suite. Returns pass/fail and the tail of the build output.")
    public String runTests() {
        BuildResult r = build("test");
        return (r.success() ? "BUILD PASSED\n" : "BUILD FAILED\n") + r.output();
    }

    public BuildResult build(String task) {
        Path root = workspace.root();
        String wrapper = resolveWrapper(root);
        if (wrapper == null) {
            return new BuildResult(false, "No build wrapper found (looked for gradlew and mvnw).");
        }
        try {
            Process p = new ProcessBuilder(wrapper, task, "--console=plain")
                    .directory(root.toFile())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(p.getInputStream().readAllBytes());
            boolean finished = p.waitFor(timeout.toSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return new BuildResult(false, "Build timed out after " + timeout.toMinutes() + " minutes.");
            }
            return new BuildResult(p.exitValue() == 0, truncate(output, MAX_OUTPUT_CHARS));
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            return new BuildResult(false, "Build could not run: " + e.getMessage());
        }
    }

    private String resolveWrapper(Path root) {
        for (String name : new String[]{"gradlew", "mvnw"}) {
            Path candidate = root.resolve(name);
            if (Files.isRegularFile(candidate)) {
                candidate.toFile().setExecutable(true);
                return candidate.toAbsolutePath().toString();
            }
        }
        return null;
    }

    /** Keeps the tail — build failures put the useful information at the end. */
    static String truncate(String s, int max) {
        if (s.length() <= max) return s;
        return "[... " + (s.length() - max) + " chars truncated ...]\n" + s.substring(s.length() - max);
    }
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew test --tests '*BuildToolsTest*'`
Expected: both PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/ai/devflow/tools/BuildTools.java src/test/java/ai/devflow/tools/BuildToolsTest.java
git commit -m "feat: BuildTools runs the target repo's wrapper with timeout and output truncation"
```

---

# PHASE 2 — The coder agent

## Task 10: Domain types

**Files:**
- Create: `src/main/java/ai/devflow/agent/{AgentResult,Finding,TokenUsage,Agent}.java`
- Create: `src/main/java/ai/devflow/orchestrator/RunState.java`
- Test: `src/test/java/ai/devflow/agent/AgentResultTest.java`

**Interfaces:**
- Consumes: nothing
- Produces: the types every later task uses. **Exact names matter** — Tasks 11–14 reference these verbatim.

- [ ] **Step 1: Write the types**

```java
package ai.devflow.agent;

import java.util.List;

public record AgentResult(
        String agent,
        Status status,
        String summary,
        List<String> filesTouched,
        List<Finding> findings,
        TokenUsage tokens) {

    public enum Status { OK, NEEDS_WORK, FAILED }

    public static AgentResult ok(String agent, String summary, List<String> filesTouched, TokenUsage tokens) {
        return new AgentResult(agent, Status.OK, summary, filesTouched, List.of(), tokens);
    }

    public static AgentResult needsWork(String agent, String summary, List<Finding> findings, TokenUsage tokens) {
        return new AgentResult(agent, Status.NEEDS_WORK, summary, List.of(), findings, tokens);
    }
}
```

```java
package ai.devflow.agent;

public record Finding(
        Origin origin,
        Severity severity,
        String file,      // null for HUMAN findings
        Integer line,     // null for HUMAN findings
        String message) {

    public enum Origin { REVIEWER, HUMAN }
    public enum Severity { LOW, MEDIUM, HIGH }

    public static Finding fromHuman(String message) {
        return new Finding(Origin.HUMAN, Severity.HIGH, null, null, message);
    }
}
```

```java
package ai.devflow.agent;

public record TokenUsage(long input, long output) {
    public static final TokenUsage NONE = new TokenUsage(0, 0);
    public TokenUsage plus(TokenUsage other) {
        return new TokenUsage(input + other.input(), output + other.output());
    }
}
```

```java
package ai.devflow.agent;

import ai.devflow.orchestrator.RunState;

public interface Agent {
    String name();
    AgentResult run(RunState state);
}
```

- [ ] **Step 2: Write RunState**

The `findings` list is how corrections reach the coder on the next iteration —
from the reviewer *and* from a human rejection (spec §5.2).

```java
package ai.devflow.orchestrator;

import ai.devflow.agent.AgentResult;
import ai.devflow.agent.Finding;
import ai.devflow.agent.TokenUsage;
import ai.devflow.workspace.Workspace;

import java.util.ArrayList;
import java.util.List;

public class RunState {

    private final String runId;
    private final String task;
    private final Workspace workspace;
    private final List<AgentResult> history = new ArrayList<>();
    private final List<Finding> openFindings = new ArrayList<>();
    private final List<String> loadedSkills = new ArrayList<>();

    private int reviewIterations = 0;
    private int humanIterations = 0;
    private TokenUsage totalTokens = TokenUsage.NONE;

    public RunState(String runId, String task, Workspace workspace) {
        this.runId = runId;
        this.task = task;
        this.workspace = workspace;
    }

    public String runId() { return runId; }
    public String task() { return task; }
    public Workspace workspace() { return workspace; }
    public List<AgentResult> history() { return List.copyOf(history); }
    public List<Finding> openFindings() { return List.copyOf(openFindings); }
    public List<String> loadedSkills() { return List.copyOf(loadedSkills); }
    public int reviewIterations() { return reviewIterations; }
    public int humanIterations() { return humanIterations; }
    public TokenUsage totalTokens() { return totalTokens; }

    public void record(AgentResult result) {
        history.add(result);
        totalTokens = totalTokens.plus(result.tokens());
    }

    public void addFindings(List<Finding> findings) { openFindings.addAll(findings); }
    public void clearFindings() { openFindings.clear(); }
    public void addLoadedSkill(String name) { loadedSkills.add(name); }
    public void incrementReviewIterations() { reviewIterations++; }
    public void incrementHumanIterations() { humanIterations++; }
}
```

- [ ] **Step 3: Write a test that pins the invariants**

```java
package ai.devflow.agent;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class AgentResultTest {

    @Test
    void okResultCarriesNoFindings() {
        var r = AgentResult.ok("coder", "did the thing", List.of("A.java"), TokenUsage.NONE);
        assertThat(r.status()).isEqualTo(AgentResult.Status.OK);
        assertThat(r.findings()).isEmpty();
    }

    @Test
    void humanFindingHasNoFileOrLine() {
        var f = Finding.fromHuman("use a DTO, don't annotate the entity");
        assertThat(f.origin()).isEqualTo(Finding.Origin.HUMAN);
        assertThat(f.file()).isNull();
        assertThat(f.line()).isNull();
        assertThat(f.severity()).isEqualTo(Finding.Severity.HIGH);
    }

    @Test
    void tokenUsageAccumulates() {
        assertThat(new TokenUsage(10, 5).plus(new TokenUsage(1, 2)))
                .isEqualTo(new TokenUsage(11, 7));
    }
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew test --tests '*AgentResultTest*'`
Expected: all 3 PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/ai/devflow/agent/ src/main/java/ai/devflow/orchestrator/ src/test/java/ai/devflow/agent/
git commit -m "feat: core domain types — AgentResult, Finding, TokenUsage, RunState"
```

---

## Task 11: ChatClientConfig with pinned models

**Files:**
- Create: `src/main/java/ai/devflow/config/ChatClientConfig.java`
- Create: `src/main/java/ai/devflow/config/ModelNames.java`
- Test: `src/test/java/ai/devflow/config/ChatClientConfigTest.java`

**Interfaces:**
- Consumes: the verified builder syntax recorded in `CLAUDE.md` by Task 3
- Produces: `@Qualifier("coder")`, `@Qualifier("reviewer")`, `@Qualifier("router")` `ChatClient` beans

**Use the exact builder shape Task 3 verified.** If Task 3 found `temperature` had to
be nulled a particular way, that is what goes here.

- [ ] **Step 1: Write the model-name constants**

```java
package ai.devflow.config;

public final class ModelNames {
    /** Cheap: routing, skill selection, scribe. */
    public static final String HAIKU = "claude-haiku-4-5";
    /** Strong: code generation and review. */
    public static final String OPUS  = "claude-opus-5";
    private ModelNames() {}
}
```

- [ ] **Step 2: Write the failing test**

```java
package ai.devflow.config;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ChatClientConfigTest {

    @Test
    void modelsArePinnedAndNotTheSpringAiDefault() {
        assertThat(ModelNames.OPUS).isEqualTo("claude-opus-5");
        assertThat(ModelNames.HAIKU).isEqualTo("claude-haiku-4-5");
        // Guards against silently inheriting Spring AI's stale default.
        assertThat(ModelNames.OPUS).doesNotContain("sonnet-4-2025");
    }
}
```

- [ ] **Step 3: Write the config**

```java
package ai.devflow.config;

import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatClientConfig {

    /**
     * NOTE: temperature is deliberately never set. Spring AI defaults it to 1.0 and
     * Opus 5 rejects sampling parameters with HTTP 400. See CLAUDE.md.
     */
    private AnthropicChatOptions options(String model) {
        return AnthropicChatOptions.builder()
                .model(model)
                .build();
    }

    @Bean @Qualifier("coder")
    ChatClient coderChatClient(ChatClient.Builder builder) {
        return builder.defaultOptions(options(ModelNames.OPUS))
                .defaultSystem("""
                    You are the Coder in an automated development crew.
                    Implement the requested change in the workspace using your tools.
                    Make the smallest change that fully satisfies the task.
                    Follow the conventions already present in the code you are editing.
                    When you are done, stop. Do not explain at length.
                    """)
                .build();
    }

    @Bean @Qualifier("reviewer")
    ChatClient reviewerChatClient(ChatClient.Builder builder) {
        return builder.defaultOptions(options(ModelNames.OPUS))
                .defaultSystem("""
                    You are the Reviewer in an automated development crew.
                    Read the changed files with your tools and judge correctness and security.
                    Report only real defects. Do not report style preferences.
                    If the change is correct, say so plainly.
                    """)
                .build();
    }

    @Bean @Qualifier("router")
    ChatClient routerChatClient(ChatClient.Builder builder) {
        return builder.defaultOptions(options(ModelNames.HAIKU))
                .defaultSystem("""
                    You classify development tasks and select which agents should run.
                    Answer only in the requested JSON shape. No prose.
                    """)
                .build();
    }
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew test`
Expected: PASS, including `contextLoads` — which proves the beans wire.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/ai/devflow/config/ src/test/java/ai/devflow/config/ChatClientConfigTest.java
git commit -m "feat: pinned-model ChatClient beans for coder, reviewer and router"
```

---

## Task 12: CoderAgent

**Files:**
- Create: `src/main/java/ai/devflow/agent/CoderAgent.java`
- Test: `src/test/java/ai/devflow/agent/CoderAgentTest.java`
- Test: `src/test/java/ai/devflow/agent/CoderAgentLiveTest.java`

**Interfaces:**
- Consumes: `RunState` (Task 10), `FileTools`/`GitTools` (Tasks 7–8), `@Qualifier("coder") ChatClient` (Task 11)
- Produces: `CoderAgent implements Agent` — `run(RunState)` returns `AgentResult` whose `filesTouched` comes from `GitTools.changedFiles()`, never from the model

**Critical:** `filesTouched` is derived from git, not from what the model claims it did.
Models misreport. Git does not.

- [ ] **Step 1: Write the failing unit test**

```java
package ai.devflow.agent;

import ai.devflow.orchestrator.RunState;
import ai.devflow.tools.GitTools;
import ai.devflow.workspace.*;
import org.junit.jupiter.api.*;
import org.springframework.ai.chat.client.ChatClient;

import java.nio.file.*;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CoderAgentTest {

    Workspace workspace;

    @BeforeEach
    void setUp() throws Exception {
        workspace = new FixtureWorkspace(Path.of("src/test/resources/fixture"), "coder-test");
        workspace.prepare();
    }

    @AfterEach
    void tearDown() throws Exception { workspace.cleanup(); }

    @Test
    void filesTouchedComesFromGitNotFromTheModel() throws Exception {
        // The model claims nothing; git sees a real edit.
        Files.writeString(workspace.root().resolve("Touched.java"), "class Touched {}");

        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(client.prompt().user(any(String.class)).tools(any()).call().content())
                .thenReturn("I made no changes.");

        var agent = new CoderAgent(client, new GitTools(workspace));
        var state = new RunState("coder-test", "add a class", workspace);

        AgentResult result = agent.run(state);

        assertThat(result.filesTouched()).contains("Touched.java");
        assertThat(result.agent()).isEqualTo("coder");
    }

    @Test
    void openFindingsAreIncludedInThePrompt() throws Exception {
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(client.prompt().user(any(String.class)).tools(any()).call().content()).thenReturn("done");

        var agent = new CoderAgent(client, new GitTools(workspace));
        var state = new RunState("coder-test", "fix it", workspace);
        state.addFindings(List.of(Finding.fromHuman("use a DTO, don't annotate the entity")));

        agent.run(state);

        assertThat(agent.lastPrompt()).contains("use a DTO");
    }
}
```

- [ ] **Step 2: Add Mockito to the build**

`spring-boot-starter-test` already includes Mockito — no change needed. Verify with:
`./gradlew dependencies --configuration testRuntimeClasspath | grep mockito`

- [ ] **Step 3: Run to verify failure**

Run: `./gradlew test --tests '*CoderAgentTest*'`
Expected: FAIL — `CoderAgent` does not exist.

- [ ] **Step 4: Implement**

```java
package ai.devflow.agent;

import ai.devflow.orchestrator.RunState;
import ai.devflow.tools.FileTools;
import ai.devflow.tools.GitTools;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.stream.Collectors;

public class CoderAgent implements Agent {

    private final ChatClient chatClient;
    private final GitTools gitTools;
    private String lastPrompt = "";

    public CoderAgent(ChatClient chatClient, GitTools gitTools) {
        this.chatClient = chatClient;
        this.gitTools = gitTools;
    }

    @Override public String name() { return "coder"; }

    /** Exposed for tests — asserts that findings actually reach the model. */
    String lastPrompt() { return lastPrompt; }

    @Override
    public AgentResult run(RunState state) {
        lastPrompt = buildPrompt(state);

        String summary = chatClient.prompt()
                .user(lastPrompt)
                .tools(new FileTools(state.workspace().guard()), gitTools)
                .call()
                .content();

        // Derived from git, never from what the model claims.
        List<String> touched = gitTools.changedFiles();

        return AgentResult.ok(name(), summary, touched, TokenUsage.NONE);
    }

    private String buildPrompt(RunState state) {
        StringBuilder sb = new StringBuilder();
        sb.append("Task: ").append(state.task()).append("\n\n");

        if (!state.loadedSkills().isEmpty()) {
            sb.append("Relevant knowledge from previous runs on this repository:\n")
              .append(String.join("\n\n", state.loadedSkills()))
              .append("\n\n");
        }

        if (!state.openFindings().isEmpty()) {
            sb.append("Your previous attempt was rejected. Fix these findings:\n")
              .append(state.openFindings().stream()
                      .map(f -> "- [" + f.origin() + "/" + f.severity() + "] "
                              + (f.file() != null ? f.file() + ": " : "") + f.message())
                      .collect(Collectors.joining("\n")))
              .append("\n\n");
        }

        sb.append("Use your tools to read and modify files. Then stop.");
        return sb.toString();
    }
}
```

- [ ] **Step 5: Run tests**

Run: `./gradlew test --tests '*CoderAgentTest*'`
Expected: both PASS.

- [ ] **Step 6: Write the live end-to-end test**

```java
package ai.devflow.agent;

import ai.devflow.orchestrator.RunState;
import ai.devflow.tools.GitTools;
import ai.devflow.workspace.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Tag("live")
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
class CoderAgentLiveTest {

    @Autowired @Qualifier("coder") ChatClient coderClient;

    @Test
    void producesRealChangesOnTheFixture() throws Exception {
        Workspace ws = new FixtureWorkspace(Path.of("src/test/resources/fixture"), "live-coder");
        ws.prepare();
        try {
            var agent = new CoderAgent(coderClient, new GitTools(ws));
            var state = new RunState("live-coder", 
                "Add bean validation to UserController so a null or blank email is rejected.", ws);

            AgentResult result = agent.run(state);

            assertThat(result.filesTouched()).isNotEmpty();
        } finally {
            ws.cleanup();
        }
    }
}
```

- [ ] **Step 7: Run the live test**

Run: `ANTHROPIC_API_KEY=$ANTHROPIC_API_KEY ./gradlew liveTest --tests '*CoderAgentLiveTest*'`
Expected: PASS, and `git diff` in the temp workspace shows real edits.

**This is the first moment devflowai does its actual job.**

- [ ] **Step 8: Commit**

```bash
git add src/main/java/ai/devflow/agent/CoderAgent.java src/test/java/ai/devflow/agent/
git commit -m "feat: CoderAgent implements changes via tools, files derived from git"
```

---

# PHASE 3 — Reviewer and the bounded loop

## Task 13: ReviewerAgent

**Files:**
- Create: `src/main/java/ai/devflow/agent/ReviewerAgent.java`
- Test: `src/test/java/ai/devflow/agent/ReviewerAgentTest.java`

**Interfaces:**
- Consumes: `RunState`, `FileTools` (read-only subset), `@Qualifier("reviewer") ChatClient`
- Produces: `ReviewerAgent implements Agent` returning `Status.OK` or `Status.NEEDS_WORK` with `List<Finding>` where `origin == REVIEWER`

The reviewer receives **file paths, not contents** — it reads them itself. That is what
keeps the orchestrator flat (spec §3.2).

- [ ] **Step 1: Write the failing test**

```java
package ai.devflow.agent;

import ai.devflow.orchestrator.RunState;
import ai.devflow.workspace.*;
import org.junit.jupiter.api.*;
import org.springframework.ai.chat.client.ChatClient;

import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ReviewerAgentTest {

    Workspace workspace;

    @BeforeEach
    void setUp() throws Exception {
        workspace = new FixtureWorkspace(Path.of("src/test/resources/fixture"), "reviewer-test");
        workspace.prepare();
    }

    @AfterEach
    void tearDown() throws Exception { workspace.cleanup(); }

    @Test
    void parsesFindingsIntoNeedsWork() {
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(client.prompt().user(any(String.class)).tools(any()).call().content())
                .thenReturn("""
                    {"status":"NEEDS_WORK","summary":"validation missing",
                     "findings":[{"severity":"HIGH","file":"UserController.java","line":14,
                                  "message":"@Valid missing on the controller parameter"}]}
                    """);

        var agent = new ReviewerAgent(client);
        var state = new RunState("reviewer-test", "add validation", workspace);
        AgentResult result = agent.run(state);

        assertThat(result.status()).isEqualTo(AgentResult.Status.NEEDS_WORK);
        assertThat(result.findings()).hasSize(1);
        assertThat(result.findings().get(0).origin()).isEqualTo(Finding.Origin.REVIEWER);
        assertThat(result.findings().get(0).message()).contains("@Valid");
    }

    @Test
    void cleanReviewReturnsOk() {
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(client.prompt().user(any(String.class)).tools(any()).call().content())
                .thenReturn("""
                    {"status":"OK","summary":"looks correct","findings":[]}
                    """);

        var agent = new ReviewerAgent(client);
        AgentResult result = agent.run(new RunState("r", "t", workspace));

        assertThat(result.status()).isEqualTo(AgentResult.Status.OK);
        assertThat(result.findings()).isEmpty();
    }

    @Test
    void malformedModelOutputFailsClosed() {
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(client.prompt().user(any(String.class)).tools(any()).call().content())
                .thenReturn("not json at all");

        var agent = new ReviewerAgent(client);
        AgentResult result = agent.run(new RunState("r", "t", workspace));

        // A reviewer that cannot be parsed must NOT be read as approval.
        assertThat(result.status()).isEqualTo(AgentResult.Status.FAILED);
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew test --tests '*ReviewerAgentTest*'`
Expected: FAIL.

- [ ] **Step 3: Implement**

```java
package ai.devflow.agent;

import ai.devflow.orchestrator.RunState;
import ai.devflow.tools.FileTools;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;

import java.util.ArrayList;
import java.util.List;

public class ReviewerAgent implements Agent {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ChatClient chatClient;

    public ReviewerAgent(ChatClient chatClient) { this.chatClient = chatClient; }

    @Override public String name() { return "reviewer"; }

    @Override
    public AgentResult run(RunState state) {
        String changed = state.history().stream()
                .filter(r -> r.agent().equals("coder"))
                .reduce((a, b) -> b)
                .map(r -> String.join("\n", r.filesTouched()))
                .orElse("(none reported)");

        String prompt = """
            Task the coder was given: %s

            Files changed (read them yourself with your tools — they are not included here):
            %s

            Reply with ONLY this JSON:
            {"status":"OK"|"NEEDS_WORK","summary":"...",
             "findings":[{"severity":"LOW"|"MEDIUM"|"HIGH","file":"...","line":0,"message":"..."}]}
            """.formatted(state.task(), changed);

        String raw = chatClient.prompt()
                .user(prompt)
                .tools(new FileTools(state.workspace().guard()))
                .call()
                .content();

        return parse(raw);
    }

    private AgentResult parse(String raw) {
        try {
            String json = raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1);
            JsonNode node = MAPPER.readTree(json);
            String summary = node.path("summary").asText("");

            List<Finding> findings = new ArrayList<>();
            for (JsonNode f : node.path("findings")) {
                findings.add(new Finding(
                        Finding.Origin.REVIEWER,
                        Finding.Severity.valueOf(f.path("severity").asText("MEDIUM")),
                        f.path("file").asText(null),
                        f.has("line") ? f.path("line").asInt() : null,
                        f.path("message").asText("")));
            }

            boolean needsWork = "NEEDS_WORK".equals(node.path("status").asText())
                    || !findings.isEmpty();

            return needsWork
                    ? AgentResult.needsWork(name(), summary, findings, TokenUsage.NONE)
                    : AgentResult.ok(name(), summary, List.of(), TokenUsage.NONE);

        } catch (Exception e) {
            // Fail closed: an unparseable review is never an approval.
            return new AgentResult(name(), AgentResult.Status.FAILED,
                    "Could not parse reviewer output: " + e.getMessage(),
                    List.of(), List.of(), TokenUsage.NONE);
        }
    }
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew test --tests '*ReviewerAgentTest*'`
Expected: all 3 PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/ai/devflow/agent/ReviewerAgent.java src/test/java/ai/devflow/agent/ReviewerAgentTest.java
git commit -m "feat: ReviewerAgent reads files itself and fails closed on unparseable output"
```

---

## Task 14: Orchestrator with the bounded review loop

**Files:**
- Create: `src/main/java/ai/devflow/orchestrator/Orchestrator.java`
- Test: `src/test/java/ai/devflow/orchestrator/OrchestratorTest.java`

**Interfaces:**
- Consumes: `CoderAgent`, `ReviewerAgent`, `RunState`
- Produces: `Orchestrator.run(RunState)` returning `RunOutcome` — the bounded loop lives here and nowhere else

**The whole design converges here.** The review cap (3) and human cap (5) are separate
counters; the orchestrator holds only `AgentResult` records.

- [ ] **Step 1: Write the failing tests**

```java
package ai.devflow.orchestrator;

import ai.devflow.agent.*;
import ai.devflow.workspace.*;
import org.junit.jupiter.api.*;
import java.nio.file.Path;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class OrchestratorTest {

    Workspace workspace;

    @BeforeEach
    void setUp() throws Exception {
        workspace = new FixtureWorkspace(Path.of("src/test/resources/fixture"), "orch-test");
        workspace.prepare();
    }

    @AfterEach
    void tearDown() throws Exception { workspace.cleanup(); }

    /** A stub agent that returns a scripted sequence of results. */
    static class ScriptedAgent implements Agent {
        private final String name;
        private final List<AgentResult> script;
        private int i = 0;
        int calls = 0;
        ScriptedAgent(String name, List<AgentResult> script) { this.name = name; this.script = script; }
        @Override public String name() { return name; }
        @Override public AgentResult run(RunState s) {
            calls++;
            return script.get(Math.min(i++, script.size() - 1));
        }
    }

    @Test
    void stopsWhenTheReviewerApproves() {
        var coder = new ScriptedAgent("coder",
                List.of(AgentResult.ok("coder", "done", List.of("A.java"), TokenUsage.NONE)));
        var reviewer = new ScriptedAgent("reviewer",
                List.of(AgentResult.ok("reviewer", "looks good", List.of(), TokenUsage.NONE)));

        var state = new RunState("orch-test", "do a thing", workspace);
        var outcome = new Orchestrator(coder, reviewer, 3, 5).run(state);

        assertThat(outcome.approved()).isTrue();
        assertThat(coder.calls).isEqualTo(1);
        assertThat(reviewer.calls).isEqualTo(1);
    }

    @Test
    void loopsBackToTheCoderThenStopsAtTheCap() {
        var bounce = AgentResult.needsWork("reviewer", "nope",
                List.of(new Finding(Finding.Origin.REVIEWER, Finding.Severity.HIGH,
                        "A.java", 1, "still wrong")), TokenUsage.NONE);

        var coder = new ScriptedAgent("coder",
                List.of(AgentResult.ok("coder", "attempt", List.of("A.java"), TokenUsage.NONE)));
        var reviewer = new ScriptedAgent("reviewer", List.of(bounce));

        var state = new RunState("orch-test", "do a thing", workspace);
        var outcome = new Orchestrator(coder, reviewer, 3, 5).run(state);

        assertThat(outcome.approved()).isFalse();
        assertThat(coder.calls).isEqualTo(3);       // exactly the cap, not 4
        assertThat(state.reviewIterations()).isEqualTo(3);
    }

    @Test
    void aFailedReviewIsNotTreatedAsApproval() {
        var coder = new ScriptedAgent("coder",
                List.of(AgentResult.ok("coder", "attempt", List.of("A.java"), TokenUsage.NONE)));
        var reviewer = new ScriptedAgent("reviewer",
                List.of(new AgentResult("reviewer", AgentResult.Status.FAILED,
                        "unparseable", List.of(), List.of(), TokenUsage.NONE)));

        var outcome = new Orchestrator(coder, reviewer, 3, 5)
                .run(new RunState("orch-test", "t", workspace));

        assertThat(outcome.approved()).isFalse();
    }

    @Test
    void orchestratorNeverHoldsFileContents() {
        var coder = new ScriptedAgent("coder",
                List.of(AgentResult.ok("coder", "done", List.of("A.java"), TokenUsage.NONE)));
        var reviewer = new ScriptedAgent("reviewer",
                List.of(AgentResult.ok("reviewer", "ok", List.of(), TokenUsage.NONE)));

        var state = new RunState("orch-test", "t", workspace);
        new Orchestrator(coder, reviewer, 3, 5).run(state);

        // Every recorded summary is short — no diff or file body smuggled through.
        assertThat(state.history()).allSatisfy(r ->
                assertThat(r.summary().length()).isLessThan(2_000));
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew test --tests '*OrchestratorTest*'`
Expected: FAIL.

- [ ] **Step 3: Implement**

```java
package ai.devflow.orchestrator;

import ai.devflow.agent.Agent;
import ai.devflow.agent.AgentResult;

public class Orchestrator {

    public record RunOutcome(boolean approved, String reason, RunState state) {}

    private final Agent coder;
    private final Agent reviewer;
    private final int maxReviewIterations;
    private final int maxHumanIterations;

    public Orchestrator(Agent coder, Agent reviewer,
                        int maxReviewIterations, int maxHumanIterations) {
        this.coder = coder;
        this.reviewer = reviewer;
        this.maxReviewIterations = maxReviewIterations;
        this.maxHumanIterations = maxHumanIterations;
    }

    public RunOutcome run(RunState state) {
        while (state.reviewIterations() < maxReviewIterations) {
            state.incrementReviewIterations();

            AgentResult coded = coder.run(state);
            state.record(coded);
            state.clearFindings();

            if (coded.status() == AgentResult.Status.FAILED) {
                return new RunOutcome(false, "Coder failed: " + coded.summary(), state);
            }

            AgentResult reviewed = reviewer.run(state);
            state.record(reviewed);

            switch (reviewed.status()) {
                case OK -> {
                    return new RunOutcome(true, "Approved by reviewer", state);
                }
                case FAILED -> {
                    // Fail closed — an unparseable review is not an approval.
                    return new RunOutcome(false, "Review failed: " + reviewed.summary(), state);
                }
                case NEEDS_WORK -> state.addFindings(reviewed.findings());
            }
        }
        return new RunOutcome(false,
                "Review loop hit the cap of " + maxReviewIterations + " iterations", state);
    }

    public int maxHumanIterations() { return maxHumanIterations; }
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew test --tests '*OrchestratorTest*'`
Expected: all 4 PASS.

- [ ] **Step 5: Run the full suite**

Run: `./gradlew test`
Expected: everything green.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/ai/devflow/orchestrator/Orchestrator.java src/test/java/ai/devflow/orchestrator/OrchestratorTest.java
git commit -m "feat: Orchestrator with bounded review loop that fails closed"
```

---

## Task 15: The Phase 3 proof — live end-to-end

**Files:**
- Create: `src/test/java/ai/devflow/EndToEndLiveTest.java`

**Interfaces:**
- Consumes: everything built so far
- Produces: proof that the core loop works against a real model on a real repo

- [ ] **Step 1: Write the test**

```java
package ai.devflow;

import ai.devflow.agent.*;
import ai.devflow.orchestrator.*;
import ai.devflow.tools.GitTools;
import ai.devflow.workspace.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Tag("live")
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
class EndToEndLiveTest {

    @Autowired @Qualifier("coder")    ChatClient coderClient;
    @Autowired @Qualifier("reviewer") ChatClient reviewerClient;

    @Test
    void addsValidationToTheFixtureAndPassesReview() throws Exception {
        Workspace ws = new FixtureWorkspace(Path.of("src/test/resources/fixture"), "e2e");
        ws.prepare();
        try {
            var orchestrator = new Orchestrator(
                    new CoderAgent(coderClient, new GitTools(ws)),
                    new ReviewerAgent(reviewerClient),
                    3, 5);

            var state = new RunState("e2e",
                    "Add input validation to UserController so a null or blank email is rejected "
                    + "with HTTP 400. Cover it with a test.", ws);

            var outcome = orchestrator.run(state);

            assertThat(outcome.approved())
                    .as("reason: %s", outcome.reason())
                    .isTrue();

            String controller = Files.readString(
                    ws.root().resolve("src/main/java/com/example/UserController.java"));
            assertThat(controller).contains("@Valid");
        } finally {
            ws.cleanup();
        }
    }
}
```

- [ ] **Step 2: Run it**

Run: `ANTHROPIC_API_KEY=$ANTHROPIC_API_KEY ./gradlew liveTest --tests '*EndToEndLiveTest*'`
Expected: PASS. Cost roughly $0.15.

If the reviewer bounces the coder once before approving, that is the design working —
and in Phase 5 that same bounce is what triggers a skill being written.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/ai/devflow/EndToEndLiveTest.java
git commit -m "test: live end-to-end proof of the coder-reviewer loop"
```

---

# PHASES 4–7 — Sketch

Deliberately not decomposed. These build on code that does not exist yet, so
detailed tasks written now would go stale. Re-plan each phase once the phase
before it is green.

## Phase 4 — SSE, the web page, and approval gates

**Spec:** §5.1, §5.2, §5.3

- `RunController` — `POST /api/runs`, `GET /api/runs/{id}/stream` (`SseEmitter`), `POST /api/runs/{id}/approve`
- `RunRegistry` — in-memory `Map<String, RunState>` plus the parked `CompletableFuture<ApprovalDecision>` per gate
- Move orchestration onto a task executor so the HTTP thread is not blocked
- `ApprovalDecision(boolean approved, String reason)`; a reason becomes `Finding.fromHuman(...)` and re-enters the loop against the *human* counter
- Gate semantics differ per gate — copy the table in spec §5.2 exactly
- Single server-rendered page plus vanilla JS `EventSource`; no framework
- Gate timeout (10 min default) aborts and calls `workspace.cleanup()`

**Watch for:** `SseEmitter` timeouts on long coder turns; set an explicit long timeout and send heartbeats.

## Phase 5 — Skills and memory

**Spec:** §6

- `SkillStore` interface; `FileSkillStore` reading and writing `.devflowai/skills/*.md`
- Frontmatter parse — `name`, `description`, `triggers`, `learned_from`
- Host-side source of truth at `~/.devflowai/skills/<repo-slug>/`, copied into the branch for the git-log demo
- `Router` returns `RoutingDecision(List<AgentName> agents, List<String> skillNames)` — one call selecting both
- `ScribeAgent` — tool-free, returns a `SkillDraft`; `SkillStore` writes it
- Trigger: `state.reviewIterations() >= 2`. Weight `HUMAN` findings above `REVIEWER` ones
- **The test that proves the thesis:** run the same task twice; assert run 1 writes a skill and run 2 loads it and needs no bounce

## Phase 6 — ClonedWorkspace

**Spec:** §4

- `ClonedWorkspace implements Workspace` via `git clone --depth 1`
- Public HTTPS URLs only; validate the URL before shelling out
- Disk quota and a hard cleanup guarantee on every exit path
- The `Workspace` interface should need no change — if it does, Task 6 got the boundary wrong

## Phase 7 — The rest of the crew

**Spec:** §1, §12

- `PlannerAgent`, `TestWriterAgent`, `DocWriterAgent` — follow `.claude/skills/add-agent/SKILL.md`
- Router's agent enum grows; orchestrator gains `switch` cases
- Routing eval harness: N labelled tasks, assert the selected sequence, report accuracy

---

## Self-Review

**Spec coverage:**

| Spec § | Covered by |
|---|---|
| §3 architecture, orchestration pattern | Tasks 10, 14 |
| §3.2 context isolation | Tasks 10, 13, 14 (`orchestratorNeverHoldsFileContents`) |
| §4 workspace model | Tasks 5, 6; Phase 6 sketch |
| §5 run lifecycle | Task 14; Phase 4 sketch |
| §5.1–5.2 gates, reject-with-reason | Task 10 (`Finding.fromHuman`); Phase 4 sketch |
| §5.3 user interface | Phase 4 sketch |
| §6 learning loop | Phase 5 sketch |
| §7 models and cost | Task 11 |
| §7.1 integration landmines | Task 3 |
| §8 tool surface | Tasks 7, 8, 9 |
| §9 security | Task 5 (PathGuard), Task 9 (timeout, truncation), Task 2 (secret hook) |
| §10 testing | Task 4 (fixture), throughout |
| §12 ship order | Phase structure |

**Deliberately deferred to their own phase, not gaps:** SSE and gates (§5.1–5.3) → Phase 4; skills and memory (§6) → Phase 5; clone (§4) → Phase 6.

**Type consistency:** `AgentResult`, `Finding`, `TokenUsage`, `RunState`, `Workspace`, `PathGuard`, `GitTools.changedFiles()` are defined once and referenced with identical signatures in Tasks 12–15 and the Phase 4–7 sketches.

**Known deviation:** `TokenUsage.NONE` is passed everywhere in Phases 2–3 rather than real usage. Spring AI surfaces usage on the chat response metadata; wiring it is a Phase 4 concern once per-run cost display exists. Recorded here so it is a decision, not an oversight.
