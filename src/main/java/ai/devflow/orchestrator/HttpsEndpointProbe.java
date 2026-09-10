package ai.devflow.orchestrator;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

final class HttpsEndpointProbe {

    private final HttpClient client;
    private final Duration timeout;

    HttpsEndpointProbe(Duration timeout) {
        this.timeout = requirePositive(timeout);
        this.client = HttpClient.newBuilder()
                .connectTimeout(this.timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    OperationalHealthObservation probe(URI endpoint) {
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
            throw new IllegalStateException("HTTPS endpoint probe was interrupted", e);
        } catch (Exception e) {
            throw new IllegalStateException("HTTPS endpoint probe failed: " + e.getMessage(), e);
        }
    }

    static URI validate(String value, String propertyName) {
        URI endpoint;
        try {
            endpoint = URI.create(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(propertyName + " must contain valid https:// URLs", e);
        }
        if (!"https".equals(endpoint.getScheme()) || endpoint.getHost() == null || endpoint.getUserInfo() != null
                || endpoint.getRawQuery() != null || endpoint.getFragment() != null) {
            throw new IllegalArgumentException(propertyName
                    + " must contain absolute https:// URLs without credentials, queries, or fragments");
        }
        return endpoint;
    }

    private static Duration requirePositive(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("endpoint probe timeout must be positive");
        }
        return timeout;
    }
}
