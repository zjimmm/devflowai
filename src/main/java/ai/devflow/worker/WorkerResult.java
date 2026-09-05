package ai.devflow.worker;

import ai.devflow.agent.TokenUsage;

public record WorkerResult(String text, TokenUsage tokens) {}
