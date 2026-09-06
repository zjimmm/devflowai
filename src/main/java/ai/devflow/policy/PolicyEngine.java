package ai.devflow.policy;

/**
 * Decides whether a run may proceed past its automated checks. An interface
 * (not just the concrete {@code ConfigurablePolicyEngine}) so tests can
 * supply a trivial lambda double, matching this codebase's existing
 * {@code SkillPicker}/{@code Scribe} pattern.
 */
public interface PolicyEngine {
    PolicyResult evaluate(PolicyContext context);
}
