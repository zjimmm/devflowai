package ai.devflow.orchestrator;

import java.net.URI;
import java.time.Duration;

public class HttpOperationalHealthObserver implements OperationalHealthObserver {

    private final URI endpoint;
    private final HttpsEndpointProbe probe;

    public HttpOperationalHealthObserver(String endpoint, Duration timeout) {
        this.probe = new HttpsEndpointProbe(timeout);
        this.endpoint = endpoint == null || endpoint.isBlank() ? null
                : HttpsEndpointProbe.validate(endpoint.trim(), "devflowai.health.url");
    }

    @Override
    public boolean isConfigured() {
        return endpoint != null;
    }

    @Override
    public OperationalHealthObservation observe() {
        if (!isConfigured()) throw new IllegalStateException("Operational health checks are not configured");
        return probe.probe(endpoint);
    }
}
