package io.jenkins.plugins.dockersandbox.api;

import io.jenkins.plugins.dockersandbox.api.model.CreateEndpointCredentialRequest;
import io.jenkins.plugins.dockersandbox.api.model.CreateSandboxRequest;
import io.jenkins.plugins.dockersandbox.api.model.EndpointCredential;
import io.jenkins.plugins.dockersandbox.api.model.ListSandboxesResponse;
import io.jenkins.plugins.dockersandbox.api.model.Sandbox;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/** {@link DockerSandboxApi} over {@code java.net.http}. */
public class HttpDockerSandboxApi implements DockerSandboxApi {

    public static final String DEFAULT_API_URL = "https://connect.docker.com/sandboxes";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private final String baseUrl;
    private final HttpCaller caller;
    private final HttpCaller.Auth auth;

    public HttpDockerSandboxApi(String apiUrl, TokenProvider tokens) {
        this(apiUrl, tokens, HttpCaller.newClient(), 3, Duration.ofMillis(500));
    }

    public HttpDockerSandboxApi(
            String apiUrl, TokenProvider tokens, HttpClient client, int maxRetries, Duration baseBackoff) {
        String url = apiUrl == null || apiUrl.isBlank() ? DEFAULT_API_URL : apiUrl.trim();
        this.baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        this.caller = new HttpCaller(client, maxRetries, baseBackoff);
        Objects.requireNonNull(tokens);
        this.auth = new HttpCaller.Auth() {
            @Override
            public String token() throws IOException {
                return tokens.getToken();
            }

            @Override
            public void invalidate() {
                tokens.invalidate();
            }
        };
    }

    private HttpRequest.Builder request(String token, String path) {
        return HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json");
    }

    @Override
    public Sandbox createSandbox(CreateSandboxRequest body, String idempotencyKey) throws IOException {
        byte[] json = HttpCaller.MAPPER.writeValueAsBytes(body);
        try (HttpCaller.Response r = caller.send(auth, t -> {
            HttpRequest.Builder b = request(t, "/v1/sandboxes").header("Content-Type", "application/json");
            if (idempotencyKey != null) {
                b.header("Idempotency-Key", idempotencyKey);
            }
            return b.POST(HttpRequest.BodyPublishers.ofByteArray(json)).build();
        })) {
            return HttpCaller.MAPPER.readValue(r.bytes(), Sandbox.class);
        }
    }

    @Override
    public Sandbox getSandbox(String sandboxId) throws IOException {
        try (HttpCaller.Response r = getSandboxResponse(sandboxId)) {
            return HttpCaller.MAPPER.readValue(r.bytes(), Sandbox.class);
        }
    }

    private HttpCaller.Response getSandboxResponse(String sandboxId) throws IOException {
        return caller.send(auth, t -> request(t, "/v1/sandboxes/" + enc(sandboxId)).GET().build());
    }

    @Override
    public ListSandboxesResponse listSandboxes(int pageSize, String pageToken) throws IOException {
        StringBuilder path = new StringBuilder("/v1/sandboxes?pageSize=").append(pageSize);
        if (pageToken != null && !pageToken.isEmpty()) {
            path.append("&pageToken=").append(enc(pageToken));
        }
        try (HttpCaller.Response r = caller.send(auth, t -> request(t, path.toString()).GET().build())) {
            return HttpCaller.MAPPER.readValue(r.bytes(), ListSandboxesResponse.class);
        }
    }

    @Override
    public void deleteSandbox(String sandboxId, boolean force) throws IOException {
        for (int attempt = 0; ; attempt++) {
            String etag;
            try (HttpCaller.Response r = getSandboxResponse(sandboxId)) {
                Sandbox sandbox = HttpCaller.MAPPER.readValue(r.bytes(), Sandbox.class);
                etag = r.headers.firstValue("ETag").orElse(sandbox.core() == null ? null : sandbox.core().etag());
            } catch (SandboxApiException e) {
                if (e.isNotFound()) {
                    return;
                }
                throw e;
            }
            String path = "/v1/sandboxes/" + enc(sandboxId) + (force ? "?force=true" : "");
            try (HttpCaller.Response ignored = caller.send(auth, t -> {
                HttpRequest.Builder b = request(t, path);
                if (etag != null) {
                    b.header("If-Match", etag);
                }
                return b.DELETE().build();
            })) {
                return;
            } catch (SandboxApiException e) {
                if (e.isNotFound()) {
                    return;
                }
                boolean stale = e.getHttpStatus() == 412 || e.getHttpStatus() == 428;
                if (stale && attempt == 0) {
                    continue;
                }
                throw e;
            }
        }
    }

    @Override
    public Sandbox startSandbox(String sandboxId) throws IOException {
        return transition(sandboxId, "start");
    }

    @Override
    public Sandbox stopSandbox(String sandboxId) throws IOException {
        return transition(sandboxId, "stop");
    }

    /** POST {@code /v1/sandboxes/{id}/<action>} with the current etag as {@code If-Match}; one retry on 412/428. */
    private Sandbox transition(String sandboxId, String action) throws IOException {
        byte[] json = "{}".getBytes(StandardCharsets.UTF_8);
        for (int attempt = 0; ; attempt++) {
            String etag;
            try (HttpCaller.Response r = getSandboxResponse(sandboxId)) {
                Sandbox sandbox = HttpCaller.MAPPER.readValue(r.bytes(), Sandbox.class);
                etag = sandbox.core() != null && sandbox.core().etag() != null
                        ? sandbox.core().etag()
                        : r.headers.firstValue("ETag").orElse(null);
            }
            String path = "/v1/sandboxes/" + enc(sandboxId) + "/" + action;
            try (HttpCaller.Response r = caller.send(auth, t -> {
                HttpRequest.Builder b = request(t, path).header("Content-Type", "application/json");
                if (etag != null) {
                    b.header("If-Match", etag);
                }
                return b.POST(HttpRequest.BodyPublishers.ofByteArray(json)).build();
            })) {
                byte[] body = r.bytes();
                return body.length == 0 ? null : HttpCaller.MAPPER.readValue(body, Sandbox.class);
            } catch (SandboxApiException e) {
                boolean stale = e.getHttpStatus() == 412 || e.getHttpStatus() == 428;
                if (stale && attempt == 0) {
                    continue;
                }
                throw e;
            }
        }
    }

    @Override
    public EndpointCredential createEndpointCredential(String sandboxId, List<String> permissions, int ttlSeconds)
            throws IOException {
        byte[] json = HttpCaller.MAPPER.writeValueAsBytes(
                new CreateEndpointCredentialRequest(permissions, ttlSeconds + "s"));
        try (HttpCaller.Response r = caller.send(auth, t -> request(t, "/v1/sandboxes/" + enc(sandboxId) + "/endpoint-credentials")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(json))
                .build())) {
            return HttpCaller.MAPPER.readValue(r.bytes(), EndpointCredential.class);
        }
    }

    static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
