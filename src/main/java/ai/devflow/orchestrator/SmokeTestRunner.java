package ai.devflow.orchestrator;

public interface SmokeTestRunner {

    boolean isConfigured();

    SmokeTestObservation run();

    static SmokeTestRunner disabled() {
        return new SmokeTestRunner() {
            @Override public boolean isConfigured() { return false; }
            @Override public SmokeTestObservation run() {
                throw new IllegalStateException("Smoke tests are not configured");
            }
        };
    }
}
