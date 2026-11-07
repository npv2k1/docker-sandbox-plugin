package io.jenkins.plugins.dockersandbox.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import hudson.ProxyConfiguration;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.Function;
import jenkins.model.Jenkins;

/**
 * Shared HTTP plumbing: bearer auth with one refresh on 401, bounded retries on 429/502/503/504 honoring
 * {@code Retry-After}, and error mapping to {@link SandboxApiException}.
 */
final class HttpCaller {

    static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private static final long MAX_RETRY_WAIT_MILLIS = 30_000;
    private static final int MAX_ERROR_BYTES = 64 * 1024;

    /** Supplies and invalidates the bearer token. */
    interface Auth {
        String token() throws IOException;

        void invalidate();
    }

    /** A successful response; the body stream must be closed by the caller. */
    static final class Response implements AutoCloseable {
        final int status;
        final HttpHeaders headers;
        private final InputStream body;

        Response(int status, HttpHeaders headers, InputStream body) {
            this.status = status;
            this.headers = headers;
            this.body = body;
        }

        InputStream body() {
            return body;
        }

        byte[] bytes() throws IOException {
            return body.readAllBytes();
        }

        @Override
        public void close() throws IOException {
            body.close();
        }
    }

    /**
     * Proxy-aware client. Uses the proxy configured in Jenkins (Manage Jenkins > Plugins > Advanced, including
     * no-proxy hosts and proxy authentication) when there is one, otherwise the JVM default {@link ProxySelector}
     * ({@code -Dhttps.proxyHost} etc.). HTTP/1.1 only: avoids h2c upgrade attempts on plain-http endpoints.
     */
    static HttpClient newClient() {
        return proxiedBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    private static HttpClient.Builder proxiedBuilder() {
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        ProxyConfiguration proxy = jenkins == null ? null : jenkins.getProxy();
        if (proxy != null && proxy.getName() != null && !proxy.getName().isBlank()) {
            return ProxyConfiguration.newHttpClientBuilder();
        }
        HttpClient.Builder builder = HttpClient.newBuilder();
        ProxySelector selector = ProxySelector.getDefault();
        return selector == null ? builder : builder.proxy(selector);
    }

    private final HttpClient client;
    private final int maxRetries;
    private final long baseBackoffMillis;

    HttpCaller(HttpClient client, int maxRetries, Duration baseBackoff) {
        this.client = client;
        this.maxRetries = maxRetries;
        this.baseBackoffMillis = baseBackoff.toMillis();
    }

    Response send(Auth auth, Function<String, HttpRequest> requestForToken) throws IOException {
        int attempt = 0;
        boolean refreshed = false;
        while (true) {
            HttpResponse<InputStream> response;
            try {
                response = client.send(requestForToken.apply(auth.token()), HttpResponse.BodyHandlers.ofInputStream());
            } catch (ConnectException | HttpConnectTimeoutException e) {
                if (attempt++ < maxRetries) {
                    sleep(backoff(attempt));
                    continue;
                }
                throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Interrupted while calling the Docker Sandboxes API");
            }
            int status = response.statusCode();
            if (status / 100 == 2) {
                return new Response(status, response.headers(), response.body());
            }
            byte[] errorBody = readLimited(response.body());
            SandboxApiException ex = SandboxApiException.fromResponse(
                    status, errorBody, response.headers().firstValue("Retry-After").orElse(null));
            if (status == 401 && !refreshed) {
                refreshed = true;
                auth.invalidate();
                continue;
            }
            if (ex.retryable() && attempt < maxRetries) {
                attempt++;
                long wait = ex.getRetryAfter() != null ? ex.getRetryAfter().toMillis() : backoff(attempt);
                sleep(Math.min(wait, MAX_RETRY_WAIT_MILLIS));
                continue;
            }
            throw ex;
        }
    }

    private long backoff(int attempt) {
        return baseBackoffMillis * (1L << Math.min(attempt - 1, 6));
    }

    private static byte[] readLimited(InputStream in) throws IOException {
        try (in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0 && out.size() < MAX_ERROR_BYTES) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    private static void sleep(long millis) throws IOException {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted while waiting to retry");
        }
    }
}
