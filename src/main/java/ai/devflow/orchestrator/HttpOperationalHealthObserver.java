package ai.devflow.orchestrator;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class HttpOperationalHealthObserver implements OperationalHealthObserver {

    private final URI endpoint;
    private final HttpClient client;
    private final Duration timeout;

    public HttpOperationalHealthObserver(String endpoint, Duration timeout) {
        this.timeout = requirePositive(timeout);
        this.endpoint = endpoint == null || endpoint.isBlank() ? null : validateEndpoint(endpoint.trim());
        this.client = HttpClient.newBuilder()
                .connectTimeout(this.timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public boolean isConfigured() {
        return endpoint != null;
    }

    @Override
    public OperationalHealthObservation observe() {
        if (!isConfigured()) throw new IllegalStateException("Operational health checks are not configured");
        try {
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .GET()
                    .timeout(timeout)
                    .header("Accept", "application/json")
                    .build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            int statusCode = response.statusCode();
            OperationalHealthStatus status = statusCode >= 200 && statusCode < 300
                    ? OperationalHealthStatus.PASSED : OperationalHealthStatus.FAILED;
            return new OperationalHealthObservation(status, endpoint.toString(), statusCode);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Operational health check was interrupted", e);
        } catch (Exception e) {
            throw new IllegalStateException("Operational health check failed: " + e.getMessage(), e);
        }
    }

    private static URI validateEndpoint(String value) {
        URI endpoint;
        try {
            endpoint = URI.create(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("devflowai.health.url must be a valid https:// URL", e);
        }
        if (!"https".equals(endpoint.getScheme()) || endpoint.getHost() == null || endpoint.getUserInfo() != null
                || endpoint.getRawQuery() != null || endpoint.getFragment() != null) {
            throw new IllegalArgumentException("devflowai.health.url must be an absolute https:// URL without credentials, a query, or a fragment");
        }
        return endpoint;
    }

    private static Duration requirePositive(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("health timeout must be positive");
        }
        return timeout;
    }
}
