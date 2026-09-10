package ai.devflow.orchestrator;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

public class HttpSmokeTestRunner implements SmokeTestRunner {

    private final List<URI> endpoints;
    private final HttpsEndpointProbe probe;

    public HttpSmokeTestRunner(String endpoints, Duration timeout) {
        this.probe = new HttpsEndpointProbe(timeout);
        this.endpoints = parseEndpoints(endpoints);
    }

    @Override
    public boolean isConfigured() {
        return !endpoints.isEmpty();
    }

    @Override
    public SmokeTestObservation run() {
        if (!isConfigured()) throw new IllegalStateException("Smoke tests are not configured");
        List<OperationalHealthObservation> results = endpoints.stream().map(endpoint -> {
            try {
                return probe.probe(endpoint);
            } catch (RuntimeException e) {
                return new OperationalHealthObservation(OperationalHealthStatus.UNAVAILABLE,
                        endpoint.toString(), null);
            }
        }).toList();
        SmokeTestStatus status = results.stream().anyMatch(result -> result.status() == OperationalHealthStatus.FAILED)
                ? SmokeTestStatus.FAILED
                : results.stream().anyMatch(result -> result.status() == OperationalHealthStatus.UNAVAILABLE)
                    ? SmokeTestStatus.UNAVAILABLE : SmokeTestStatus.PASSED;
        return new SmokeTestObservation(status, results);
    }

    private List<URI> parseEndpoints(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(endpoint -> !endpoint.isBlank())
                .map(endpoint -> HttpsEndpointProbe.validate(endpoint, "devflowai.smoke.urls"))
                .distinct()
                .toList();
    }
}
