package ai.devflow.orchestrator;

public interface OperationalHealthObserver {

    boolean isConfigured();

    OperationalHealthObservation observe();

    static OperationalHealthObserver disabled() {
        return new OperationalHealthObserver() {
            @Override public boolean isConfigured() { return false; }
            @Override public OperationalHealthObservation observe() {
                throw new IllegalStateException("Operational health checks are not configured");
            }
        };
    }
}
