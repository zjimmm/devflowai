package ai.devflow.policy;

/** The one policy rule this sub-project ships: the build must pass. */
public class ConfigurablePolicyEngine implements PolicyEngine {

    private final boolean requireBuildPass;

    public ConfigurablePolicyEngine(boolean requireBuildPass) {
        this.requireBuildPass = requireBuildPass;
    }

    @Override
    public PolicyResult evaluate(PolicyContext context) {
        if (requireBuildPass && context.buildRan() && !context.buildPassed()) {
            return new PolicyResult(false, "the build did not pass");
        }
        return PolicyResult.ok();
    }
}
