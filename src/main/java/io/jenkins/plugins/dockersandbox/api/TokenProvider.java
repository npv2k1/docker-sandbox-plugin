package io.jenkins.plugins.dockersandbox.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

/**
 * Per-account cache of the short-lived Docker access token, obtained by exchanging a Docker ID and a personal
 * access token at {@code <authUrl>/v2/auth/token}. The PAT and the access token are never logged.
 */
public class TokenProvider {

    private static final Duration SKEW = Duration.ofSeconds(30);
    private static final Duration FALLBACK_LIFETIME = Duration.ofMinutes(4);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String authUrl;
    private final String identifier;
    private final String secret;
    private final HttpClient client;
    private final Clock clock;

    private String token;
    private Instant expiry = Instant.MIN;

    public TokenProvider(String authUrl, String identifier, String secret, HttpClient client, Clock clock) {
        this.authUrl = stripTrailingSlash(authUrl);
        this.identifier = identifier;
        this.secret = secret;
        this.client = client;
        this.clock = clock;
    }

    public TokenProvider(String authUrl, String identifier, String secret) {
        this(authUrl, identifier, secret, HttpCaller.newClient(), Clock.systemUTC());
    }

    /** Returns a valid access token, exchanging the PAT when none is cached or it is about to expire. */
    public synchronized String getToken() throws IOException {
        if (token == null || !clock.instant().isBefore(expiry.minus(SKEW))) {
            refresh();
        }
        return token;
    }

    /** Drops the cached token (e.g. after a 401) so the next call exchanges again. */
    public synchronized void invalidate() {
        token = null;
        expiry = Instant.MIN;
    }

    private void refresh() throws IOException {
        String body = MAPPER.writeValueAsString(Map.of("identifier", identifier, "secret", secret));
        HttpRequest request = HttpRequest.newBuilder(URI.create(authUrl + "/v2/auth/token"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<byte[]> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new java.io.InterruptedIOException("Interrupted while requesting a Docker access token");
        }
        if (response.statusCode() / 100 != 2) {
            // Never echo the response body verbatim beyond the sanitized error parsing.
            throw SandboxApiException.fromResponse(
                    response.statusCode(), response.body(), response.headers().firstValue("Retry-After").orElse(null));
        }
        JsonNode json = MAPPER.readTree(response.body());
        String accessToken = json.path("access_token").asText("");
        if (accessToken.isEmpty()) {
            accessToken = json.path("token").asText("");
        }
        if (accessToken.isEmpty()) {
            throw new IOException("Docker token endpoint returned no access_token");
        }
        this.token = accessToken;
        this.expiry = expiryOf(accessToken);
    }

    /** Reads the JWT {@code exp} claim when present, otherwise assumes a short fixed lifetime. */
    private Instant expiryOf(String jwt) {
        Instant fallback = clock.instant().plus(FALLBACK_LIFETIME);
        String[] parts = jwt.split("\\.");
        if (parts.length < 2) {
            return fallback;
        }
        try {
            byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
            long exp = MAPPER.readTree(new String(payload, StandardCharsets.UTF_8)).path("exp").asLong(0);
            return exp > 0 ? Instant.ofEpochSecond(exp) : fallback;
        } catch (IllegalArgumentException | IOException e) {
            return fallback;
        }
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    @Override
    public String toString() {
        return "TokenProvider[identifier=" + identifier + ", secret=****]";
    }
}
