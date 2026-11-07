package io.jenkins.plugins.dockersandbox;

import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.extension.ResponseDefinitionTransformerV2;
import com.github.tomakehurst.wiremock.http.Request;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stateful fake of the Docker auth, management and sandbox endpoint APIs served by one WireMock server. Scripts are
 * really executed with local bash inside a per-sandbox directory that plays the role of the sandbox file system.
 */
final class FakeDockerCloud implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** An {@code imageRef} whose sandboxes fail the bash/tar preflight probe. */
    static final String NO_BASH_IMAGE = "fake/no-bash";

    /** Per Docker ID behavior and counters. */
    static final class Account {
        volatile int quota = Integer.MAX_VALUE;
        volatile boolean exhausted;
        final AtomicInteger createAttempts = new AtomicInteger();
        final AtomicInteger live = new AtomicInteger();
        final AtomicInteger peakLive = new AtomicInteger();
    }

    static final class Sandbox {
        final String id;
        final String owner;
        final String displayName;
        final Map<String, String> labels = new LinkedHashMap<>();
        final Path root;
        final AtomicInteger polls = new AtomicInteger();
        final AtomicInteger credentials = new AtomicInteger();
        final Map<String, FakeProcess> processes = new ConcurrentHashMap<>();
        volatile boolean deleted;
        volatile Instant createdAt = Instant.now();
        JsonNode createRequest;

        Sandbox(String id, String owner, String displayName, Path root) {
            this.id = id;
            this.owner = owner;
            this.displayName = displayName;
            this.root = root;
        }
    }

    static final class FakeProcess {
        final Process process;
        final List<byte[]> chunks = Collections.synchronizedList(new ArrayList<>());
        volatile boolean drained;

        FakeProcess(Process process) {
            this.process = process;
        }
    }

    private final WireMockServer server;
    private final Path dir;
    private final Map<String, Account> accounts = new ConcurrentHashMap<>();
    private final Map<String, Sandbox> sandboxes = new ConcurrentHashMap<>();
    private final List<String> deletedIds = new CopyOnWriteArrayList<>();
    private final AtomicInteger sandboxCounter = new AtomicInteger();
    private volatile int startupPolls;
    private volatile boolean failStartup;

    FakeDockerCloud() throws IOException {
        dir = Files.createTempDirectory("fake-docker-cloud");
        server = new WireMockServer(options().dynamicPort().extensions(new Transformer()));
        server.start();
        server.stubFor(any(anyUrl()).willReturn(aResponse()));
    }

    String authUrl() {
        return server.baseUrl();
    }

    String apiUrl() {
        return server.baseUrl() + "/sandboxes";
    }

    Account account(String dockerId) {
        return accounts.computeIfAbsent(dockerId, k -> new Account());
    }

    /** Number of GETs a new sandbox answers with "creating" before it is running. */
    void setStartupPolls(int polls) {
        this.startupPolls = polls;
    }

    /** New sandboxes report status "failed" instead of running. */
    void setFailStartup(boolean fail) {
        this.failStartup = fail;
    }

    List<String> deletedIds() {
        return deletedIds;
    }

    Sandbox sandbox(String id) {
        return sandboxes.get(id);
    }

    List<Sandbox> allSandboxes() {
        return new ArrayList<>(sandboxes.values());
    }

    long liveSandboxes() {
        return sandboxes.values().stream().filter(s -> !s.deleted).count();
    }

    boolean anyProcessRunning() {
        return sandboxes.values().stream()
                .filter(s -> !s.deleted)
                .flatMap(s -> s.processes.values().stream())
                .anyMatch(p -> p.process.isAlive());
    }

    @Override
    public void close() throws IOException {
        for (Sandbox s : sandboxes.values()) {
            killProcesses(s);
        }
        server.stop();
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    private static void killProcesses(Sandbox s) {
        for (FakeProcess p : s.processes.values()) {
            kill(p.process);
        }
    }

    private static void kill(Process p) {
        p.descendants().forEach(ProcessHandle::destroyForcibly);
        p.destroyForcibly();
    }

    // ---- request handling ----------------------------------------------------------------------------------

    private static final Pattern SANDBOX_PATH = Pattern.compile("^/sandboxes/v1/sandboxes(?:/([^/]+)(?:/(endpoint-credentials))?)?$");
    private static final Pattern ENDPOINT_PATH = Pattern.compile("^/ep/([^/]+)(/v1/.*)$");

    private final class Transformer implements ResponseDefinitionTransformerV2 {
        @Override
        public String getName() {
            return "fake-docker-cloud";
        }

        @Override
        public boolean applyGlobally() {
            return true;
        }

        @Override
        public ResponseDefinition transform(ServeEvent serveEvent) {
            try {
                return handle(serveEvent.getRequest());
            } catch (RuntimeException | IOException e) {
                return json(500, "{\"code\":\"internal\",\"message\":\"fake failure: " + e + "\"}");
            }
        }
    }

    private static ResponseDefinition json(int status, String body) {
        return new com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder()
                .withStatus(status)
                .withHeader("Content-Type", "application/json")
                .withBody(body)
                .build();
    }

    private static ResponseDefinition error(int status, String code, String message) {
        return json(status, "{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }

    private ResponseDefinition handle(Request request) throws IOException {
        URI uri = URI.create(request.getUrl());
        String path = uri.getPath();
        String method = request.getMethod().getName();
        if (path.equals("/v2/auth/token") && method.equals("POST")) {
            String identifier = JSON.readTree(request.getBody()).path("identifier").asText();
            if (!accounts.containsKey(identifier)) {
                return error(401, "unauthenticated", "unknown docker id");
            }
            return json(200, "{\"access_token\":\"tok-" + identifier + "\"}");
        }
        Matcher ep = ENDPOINT_PATH.matcher(path);
        if (ep.matches()) {
            return handleEndpoint(request, uri, method, ep.group(1), ep.group(2));
        }
        Matcher m = SANDBOX_PATH.matcher(path);
        if (m.matches()) {
            return handleManagement(request, uri, method, m.group(1), m.group(2));
        }
        return error(404, "notFound", "no route " + path);
    }

    private String owner(Request request) {
        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer tok-")) {
            return null;
        }
        String id = auth.substring("Bearer tok-".length());
        return accounts.containsKey(id) ? id : null;
    }

    private ResponseDefinition handleManagement(Request request, URI uri, String method, String id, String sub) throws IOException {
        String owner = owner(request);
        if (owner == null) {
            return error(401, "unauthenticated", "bad management token");
        }
        Account account = account(owner);
        if (id == null) {
            if (method.equals("POST")) {
                return createSandbox(request, owner, account);
            }
            if (method.equals("GET")) {
                ArrayNode list = JSON.createArrayNode();
                sandboxes.values().stream()
                        .filter(s -> s.owner.equals(owner) && !s.deleted)
                        .forEach(s -> list.add(sandboxJson(s, false)));
                ObjectNode body = JSON.createObjectNode();
                body.set("sandboxes", list);
                return json(200, body.toString());
            }
            return error(405, "unimplemented", "method");
        }
        Sandbox sb = sandboxes.get(id);
        if (sb == null || sb.deleted || !sb.owner.equals(owner)) {
            return error(404, "notFound", "sandbox not found");
        }
        if (sub != null) {
            ObjectNode cred = JSON.createObjectNode();
            cred.put("token", "ep-" + id + "-" + sb.credentials.incrementAndGet());
            cred.put("expireTime", Instant.now().plusSeconds(300).toString());
            cred.put("sandbox", "sandboxes/" + id);
            cred.put("audience", "fake");
            return json(200, cred.toString());
        }
        if (method.equals("GET")) {
            boolean running = sb.polls.incrementAndGet() > startupPolls;
            return new com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withHeader("ETag", etag(sb))
                    .withBody(sandboxJson(sb, running).toString())
                    .build();
        }
        if (method.equals("DELETE")) {
            String ifMatch = request.getHeader("If-Match");
            if (ifMatch == null) {
                return error(428, "failedPrecondition", "If-Match required");
            }
            if (!ifMatch.equals(etag(sb))) {
                return error(412, "failedPrecondition", "stale etag");
            }
            synchronized (sb) {
                if (!sb.deleted) {
                    sb.deleted = true;
                    killProcesses(sb);
                    account.live.decrementAndGet();
                    deletedIds.add(sb.id);
                }
            }
            return new com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder().withStatus(204).build();
        }
        return error(405, "unimplemented", "method");
    }

    private static String etag(Sandbox sb) {
        return "\"" + sb.id + "-v1\"";
    }

    private ResponseDefinition createSandbox(Request request, String owner, Account account) throws IOException {
        account.createAttempts.incrementAndGet();
        if (account.exhausted) {
            return error(429, "RESOURCE_EXHAUSTED", "quota of concurrent sandboxes exceeded");
        }
        JsonNode body = JSON.readTree(request.getBody());
        synchronized (account) {
            if (account.live.get() >= account.quota) {
                return error(429, "RESOURCE_EXHAUSTED", "quota of concurrent sandboxes exceeded");
            }
            String id = "sb" + sandboxCounter.incrementAndGet();
            Sandbox sb = new Sandbox(id, owner, body.path("displayName").asText(), Files.createDirectories(dir.resolve(id)));
            sb.createRequest = body;
            body.path("labels").fields().forEachRemaining(e -> sb.labels.put(e.getKey(), e.getValue().asText()));
            sandboxes.put(id, sb);
            int live = account.live.incrementAndGet();
            account.peakLive.accumulateAndGet(live, Math::max);
            return new com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withHeader("ETag", etag(sb))
                    .withBody(sandboxJson(sb, false).toString())
                    .build();
        }
    }

    private ObjectNode sandboxJson(Sandbox sb, boolean running) {
        ObjectNode n = JSON.createObjectNode();
        n.put("name", "sandboxes/" + sb.id);
        n.put("uid", sb.id);
        n.put("displayName", sb.displayName);
        ObjectNode core = n.putObject("core");
        core.put("status", failStartup ? "failed" : running ? "running" : "creating");
        if (failStartup) {
            n.putObject("failure").put("code", "internal").put("message", "image pull failed");
        }
        core.put("etag", etag(sb));
        core.put("createdAt", sb.createdAt.toString());
        ObjectNode labels = core.putObject("labels");
        sb.labels.forEach(labels::put);
        if (running && !failStartup) {
            core.putObject("endpoint").put("uri", server.baseUrl() + "/ep/" + sb.id);
        }
        return n;
    }

    // ---- sandbox endpoint ----------------------------------------------------------------------------------

    private ResponseDefinition handleEndpoint(Request request, URI uri, String method, String id, String route) throws IOException {
        Sandbox sb = sandboxes.get(id);
        if (sb == null || sb.deleted) {
            return error(404, "notFound", "sandbox gone");
        }
        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ep-" + id + "-")) {
            return error(401, "unauthenticated", "bad endpoint credential");
        }
        if (route.equals("/v1/processes/exec") && method.equals("POST")) {
            JsonNode req = JSON.readTree(request.getBody());
            List<String> cmd = new ArrayList<>();
            req.path("cmd").forEach(c -> cmd.add(rewrite(c.asText(), sb)));
            if (NO_BASH_IMAGE.equals(sb.createRequest.path("imageRef").asText())
                    && String.join(" ", cmd).contains("command -v bash")) {
                // simulates an image without bash/tar: the plugin's preflight probe fails
                ObjectNode resp = JSON.createObjectNode();
                resp.put("exitCode", 1);
                resp.put("stdout", "");
                resp.put("stderr", Base64.getEncoder().encodeToString("sh: bash: not found".getBytes(StandardCharsets.UTF_8)));
                resp.put("incomplete", false);
                return json(200, resp.toString());
            }
            Process p = new ProcessBuilder(cmd).directory(sb.root.toFile()).start();
            p.getOutputStream().close();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            Thread t = new Thread(() -> {
                try (InputStream e = p.getErrorStream()) {
                    e.transferTo(err);
                } catch (IOException ignored) {
                    // test fake
                }
            });
            t.start();
            byte[] out = p.getInputStream().readAllBytes();
            try {
                int code = p.waitFor();
                t.join();
                ObjectNode resp = JSON.createObjectNode();
                resp.put("exitCode", code);
                resp.put("stdout", Base64.getEncoder().encodeToString(out));
                resp.put("stderr", Base64.getEncoder().encodeToString(err.toByteArray()));
                resp.put("incomplete", false);
                return json(200, resp.toString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return error(500, "internal", "interrupted");
            }
        }
        if (route.equals("/v1/processes") && method.equals("POST")) {
            JsonNode req = JSON.readTree(request.getBody());
            List<String> cmd = new ArrayList<>();
            req.path("cmd").forEach(c -> cmd.add(c.asText()));
            ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
            pb.directory(resolve(sb, req.path("workingDir").asText("/")).toFile());
            req.path("env").fields().forEachRemaining(e -> pb.environment().put(e.getKey(), e.getValue().asText()));
            Process process = pb.start();
            process.getOutputStream().close();
            FakeProcess fp = new FakeProcess(process);
            String pid = "p" + (sb.processes.size() + 1);
            sb.processes.put(pid, fp);
            Thread reader = new Thread(() -> {
                try (InputStream in = process.getInputStream()) {
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        fp.chunks.add(java.util.Arrays.copyOf(buf, n));
                    }
                } catch (IOException ignored) {
                    // process killed
                } finally {
                    fp.drained = true;
                }
            });
            reader.setDaemon(true);
            reader.start();
            return json(201, "{\"name\":\"processes/" + pid + "\",\"label\":\"" + pid + "\",\"state\":\"running\"}");
        }
        Matcher pm = Pattern.compile("^/v1/processes/([^/]+)/(output|signal)$").matcher(route);
        if (pm.matches()) {
            FakeProcess fp = sb.processes.get(pm.group(1));
            if (fp == null) {
                return error(404, "notFound", "process");
            }
            if (pm.group(2).equals("signal")) {
                kill(fp.process);
                return json(200, "{}");
            }
            long resume = Long.parseLong(query(uri, "resumeFrom", "0"));
            ObjectNode resp = JSON.createObjectNode();
            ArrayNode chunks = resp.putArray("chunks");
            List<byte[]> snapshot;
            synchronized (fp.chunks) {
                snapshot = new ArrayList<>(fp.chunks);
            }
            long next = resume;
            for (int i = (int) resume; i < snapshot.size(); i++) {
                ObjectNode c = chunks.addObject();
                c.put("stream", "stdout");
                c.put("data", Base64.getEncoder().encodeToString(snapshot.get(i)));
                c.put("streamSequence", String.valueOf(i + 1));
                next = i + 1;
            }
            resp.put("nextResumeFrom", String.valueOf(next));
            if (fp.drained && next >= fp.chunks.size()) {
                try {
                    if (fp.process.waitFor(2, TimeUnit.SECONDS)) {
                        resp.putObject("exited").put("exitCode", fp.process.exitValue());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return json(200, resp.toString());
        }
        if (route.startsWith("/v1/files/content")) {
            Path target = resolve(sb, query(uri, "path", ""));
            if (method.equals("PUT")) {
                Files.createDirectories(target.getParent());
                Files.write(target, rawBody(request));
                return json(200, "{}");
            }
            if (method.equals("GET")) {
                if (!Files.isRegularFile(target)) {
                    return error(404, "notFound", "no such file");
                }
                return new com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/octet-stream")
                        .withBody(Files.readAllBytes(target))
                        .build();
            }
        }
        return error(404, "notFound", "no route " + route);
    }

    /**
     * WireMock transparently gunzips request bodies that look like gzip, so an uploaded workspace archive arrives as
     * a plain tar. A real sandbox receives the bytes untouched; recompress here so tar -xzf works the same.
     */
    private static byte[] rawBody(Request request) throws IOException {
        byte[] body = request.getBody();
        String declared = request.getHeader("Content-Length");
        boolean gunzipped = declared != null
                && Long.parseLong(declared) != body.length
                && !(body.length > 1 && (body[0] & 0xff) == 0x1f && (body[1] & 0xff) == 0x8b);
        if (!gunzipped) {
            return body;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (java.util.zip.GZIPOutputStream gz = new java.util.zip.GZIPOutputStream(out)) {
            gz.write(body);
        }
        return out.toByteArray();
    }

    private static Path resolve(Sandbox sb, String sandboxPath) {
        Path p = sb.root.resolve(sandboxPath.startsWith("/") ? sandboxPath.substring(1) : sandboxPath).normalize();
        if (!p.startsWith(sb.root)) {
            throw new IllegalArgumentException("path escapes sandbox");
        }
        return p;
    }

    /** Maps the absolute sandbox paths the plugin uses ({@code /tmp}, {@code /work}) into the sandbox directory. */
    private static String rewrite(String s, Sandbox sb) {
        return s.replaceAll("(?<![\\w.])/(tmp|work)(?=[/'\"\\s]|$)", Matcher.quoteReplacement(sb.root.toString()) + "/$1");
    }

    private static String query(URI uri, String name, String fallback) {
        String q = uri.getRawQuery();
        if (q == null) {
            return fallback;
        }
        for (String pair : q.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return fallback;
    }
}
