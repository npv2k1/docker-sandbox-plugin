package io.jenkins.plugins.dockersandbox.api;

import io.jenkins.plugins.dockersandbox.api.model.CreateProcessRequest;
import io.jenkins.plugins.dockersandbox.api.model.EndpointCredential;
import io.jenkins.plugins.dockersandbox.api.model.ExecRequest;
import io.jenkins.plugins.dockersandbox.api.model.ExecResponse;
import io.jenkins.plugins.dockersandbox.api.model.Process;
import io.jenkins.plugins.dockersandbox.api.model.ReadOutputResponse;
import io.jenkins.plugins.dockersandbox.api.model.SignalRequest;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * {@link SandboxEndpointApi} over {@code java.net.http}. The endpoint credential (valid at most 5 minutes) is
 * issued through the management API and renewed 30 seconds before it expires, or after a 401.
 */
public class HttpSandboxEndpointApi implements SandboxEndpointApi {

    /** Permissions needed by the plugin: run processes and read/write files. */
    public static final List<String> PERMISSIONS =
            List.of("sandboxesExec", "sandboxesFilesRead", "sandboxesFilesWrite");

    private static final Duration SKEW = Duration.ofSeconds(30);
    private static final Duration FALLBACK_LIFETIME = Duration.ofMinutes(4);
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration TRANSFER_TIMEOUT = Duration.ofMinutes(30);

    private final DockerSandboxApi management;
    private final String sandboxId;
    private final String baseUrl;
    private final HttpCaller caller;
    private final Clock clock;
    private final HttpCaller.Auth auth;

    private String token;
    private Instant expiry = Instant.MIN;

    public HttpSandboxEndpointApi(DockerSandboxApi management, String sandboxId, String endpointUri) {
        this(management, sandboxId, endpointUri, HttpCaller.newClient(), Clock.systemUTC(), 3, Duration.ofMillis(500));
    }

    public HttpSandboxEndpointApi(
            DockerSandboxApi management,
            String sandboxId,
            String endpointUri,
            HttpClient client,
            Clock clock,
            int maxRetries,
            Duration baseBackoff) {
        this.management = management;
        this.sandboxId = sandboxId;
        this.baseUrl = endpointUri.endsWith("/") ? endpointUri.substring(0, endpointUri.length() - 1) : endpointUri;
        this.caller = new HttpCaller(client, maxRetries, baseBackoff);
        this.clock = clock;
        this.auth = new HttpCaller.Auth() {
            @Override
            public String token() throws IOException {
                return currentToken();
            }

            @Override
            public void invalidate() {
                invalidateToken();
            }
        };
    }

    private synchronized String currentToken() throws IOException {
        if (token == null || !clock.instant().isBefore(expiry.minus(SKEW))) {
            EndpointCredential credential = management.createEndpointCredential(sandboxId, PERMISSIONS, 300);
            if (credential.token() == null || credential.token().isEmpty()) {
                throw new IOException("Endpoint credential response contained no token");
            }
            token = credential.token();
            expiry = parseExpiry(credential.expireTime());
        }
        return token;
    }

    private synchronized void invalidateToken() {
        token = null;
        expiry = Instant.MIN;
    }

    private Instant parseExpiry(String expireTime) {
        if (expireTime != null) {
            try {
                return Instant.parse(expireTime);
            } catch (DateTimeParseException e) {
                // fall through to the conservative default
            }
        }
        return clock.instant().plus(FALLBACK_LIFETIME);
    }

    private HttpRequest.Builder request(String token, String pathAndQuery, Duration timeout) {
        return HttpRequest.newBuilder(URI.create(baseUrl + pathAndQuery))
                .timeout(timeout)
                .header("Authorization", "Bearer " + token);
    }

    @Override
    public ExecResponse exec(ExecRequest request) throws IOException {
        byte[] json = HttpCaller.MAPPER.writeValueAsBytes(request);
        try (HttpCaller.Response r = caller.send(auth, t -> request(t, "/v1/processes/exec", TRANSFER_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(json))
                .build())) {
            return HttpCaller.MAPPER.readValue(r.bytes(), ExecResponse.class);
        }
    }

    @Override
    public Process startProcess(CreateProcessRequest request) throws IOException {
        byte[] json = HttpCaller.MAPPER.writeValueAsBytes(request);
        try (HttpCaller.Response r = caller.send(auth, t -> request(t, "/v1/processes", REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(json))
                .build())) {
            return HttpCaller.MAPPER.readValue(r.bytes(), Process.class);
        }
    }

    @Override
    public ReadOutputResponse readOutput(String processId, long resumeFrom, int maxBytes) throws IOException {
        StringBuilder path = new StringBuilder("/v1/processes/")
                .append(HttpDockerSandboxApi.enc(processId))
                .append("/output?resumeFrom=")
                .append(resumeFrom);
        if (maxBytes > 0) {
            path.append("&maxBytes=").append(maxBytes);
        }
        try (HttpCaller.Response r = caller.send(
                auth, t -> request(t, path.toString(), REQUEST_TIMEOUT).header("Accept", "application/json").GET().build())) {
            return HttpCaller.MAPPER.readValue(r.bytes(), ReadOutputResponse.class);
        }
    }

    @Override
    public void signal(String processId, String signal) throws IOException {
        byte[] json = HttpCaller.MAPPER.writeValueAsBytes(new SignalRequest(signal));
        try (HttpCaller.Response ignored = caller.send(
                auth, t -> request(t, "/v1/processes/" + HttpDockerSandboxApi.enc(processId) + "/signal", REQUEST_TIMEOUT)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(json))
                        .build())) {
            // no body of interest
        }
    }

    @Override
    public void putFile(String remotePath, int mode, Path localFile) throws IOException {
        String path = "/v1/files/content?path=" + HttpDockerSandboxApi.enc(remotePath) + "&mode=" + mode;
        try (HttpCaller.Response ignored = caller.send(auth, t -> {
            try {
                return request(t, path, TRANSFER_TIMEOUT)
                        .header("Content-Type", "application/octet-stream")
                        .PUT(HttpRequest.BodyPublishers.ofFile(localFile))
                        .build();
            } catch (java.io.FileNotFoundException e) {
                throw new java.io.UncheckedIOException(e);
            }
        })) {
            // no body of interest
        } catch (java.io.UncheckedIOException e) {
            throw e.getCause();
        }
    }

    @Override
    public long getFile(String remotePath, Path target) throws IOException {
        String path = "/v1/files/content?path=" + HttpDockerSandboxApi.enc(remotePath);
        try (HttpCaller.Response r = caller.send(
                auth, t -> request(t, path, TRANSFER_TIMEOUT).GET().build());
                InputStream in = r.body()) {
            return Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
