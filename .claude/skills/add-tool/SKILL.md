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
