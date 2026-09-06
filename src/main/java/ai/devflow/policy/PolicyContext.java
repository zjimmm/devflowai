package ai.devflow.policy;

/**
 * What a {@link PolicyEngine} evaluates. A record specifically so later
 * sub-projects can add fields (a coverage percentage, a scanner's finding
 * counts) without another signature change across every caller — the same
 * reasoning that shaped {@code WorkerRequest} in Sub-project 1.
 */
public record PolicyContext(boolean buildPassed) {}
