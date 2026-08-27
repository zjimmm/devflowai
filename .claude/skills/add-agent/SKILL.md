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
       String name();
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

Stub the `ChatClient` so the test costs nothing. Use Mockito with deep stubs:

```java
// static imports: org.mockito.Mockito.mock, org.mockito.Mockito.RETURNS_DEEP_STUBS, org.mockito.Mockito.when, org.mockito.ArgumentMatchers.any
ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
when(client.prompt().user(any(String.class)).tools(any()).call().content())
        .thenReturn("{\"status\":\"OK\",\"summary\":\"...\"}");
```
