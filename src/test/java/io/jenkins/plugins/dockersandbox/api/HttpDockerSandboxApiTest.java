package io.jenkins.plugins.dockersandbox.api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.jenkins.plugins.dockersandbox.api.model.CreateSandboxRequest;
import io.jenkins.plugins.dockersandbox.api.model.EndpointCredential;
import io.jenkins.plugins.dockersandbox.api.model.FeatureOptions;
import io.jenkins.plugins.dockersandbox.api.model.KitInput;
import io.jenkins.plugins.dockersandbox.api.model.Resources;
import io.jenkins.plugins.dockersandbox.api.model.Sandbox;
import io.jenkins.plugins.dockersandbox.api.model.TimeoutOptions;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HttpDockerSandboxApiTest {

    private static final String SANDBOX_JSON =
            "{\"name\":\"sandboxes/sb1\",\"uid\":\"sb1\",\"core\":{\"status\":\"running\",\"etag\":\"\\\"e1\\\"\","
                    + "\"resources\":{\"cpus\":2,\"memoryMib\":\"4096\"},"
                    + "\"endpoint\":{\"uri\":\"https://ep.example/sb1\"}}}";

    private WireMockServer docker;
    private WireMockServer auth;
    private HttpDockerSandboxApi api;

    @BeforeEach
    void start() {
        docker = new WireMockServer(options().dynamicPort());
        auth = new WireMockServer(options().dynamicPort());
        docker.start();
        auth.start();
        auth.stubFor(post(urlEqualTo("/v2/auth/token"))
                .willReturn(aResponse().withStatus(200).withBody("{\"access_token\":\"mgmt-token\"}")));
        TokenProvider tokens =
                new TokenProvider(auth.baseUrl(), "alice", "pat", HttpCaller.newClient(), Clock.systemUTC());
        api = new HttpDockerSandboxApi(
                docker.baseUrl() + "/sandboxes", tokens, HttpCaller.newClient(), 3, Duration.ofMillis(5));
    }

    @AfterEach
    void stop() {
        docker.stop();
        auth.stop();
    }

    @Test
    void createSandboxSendsSpecShapeAndHeaders() throws Exception {
        docker.stubFor(post(urlEqualTo("/sandboxes/v1/sandboxes")).willReturn(aResponse().withStatus(201).withHeader("ETag", "\"e1\"").withBody(SANDBOX_JSON)));
        CreateSandboxRequest req = new CreateSandboxRequest(
                "jk-job-1",
                new Resources(2, 4096L),
                List.of(KitInput.byRef("shell")),
                Map.of("A", "b"),
                Map.of("jenkins-build", "1"),
                new FeatureOptions(new TimeoutOptions("3600s", "delete")));
        Sandbox sb = api.createSandbox(req, "key-123");
        assertEquals("sb1", sb.id());
        assertEquals("running", sb.status());
        assertEquals("https://ep.example/sb1", sb.endpointUri());
        assertEquals(4096L, sb.core().resources().memoryMib());
        docker.verify(postRequestedFor(urlEqualTo("/sandboxes/v1/sandboxes"))
                .withHeader("Authorization", equalTo("Bearer mgmt-token"))
                .withHeader("Idempotency-Key", equalTo("key-123"))
                .withRequestBody(equalToJson("{\"displayName\":\"jk-job-1\",\"resources\":{\"cpus\":2,\"memoryMib\":4096},"
                        + "\"kits\":[{\"artifact\":{\"ref\":{\"ref\":\"shell\",\"kind\":\"\"}}}],"
                        + "\"environment\":{\"A\":\"b\"},\"labels\":{\"jenkins-build\":\"1\"},"
                        + "\"features\":{\"timeouts\":{\"timeout\":\"3600s\",\"onTimeout\":\"delete\"}}}")));
    }

    @Test
    void createSandboxWithRawImageSendsImageRefAndStartupExecution() throws Exception {
        docker.stubFor(post(urlEqualTo("/sandboxes/v1/sandboxes")).willReturn(aResponse().withStatus(201).withHeader("ETag", "\"e1\"").withBody(SANDBOX_JSON)));
        CreateSandboxRequest req = new CreateSandboxRequest(
                "jk-job-2",
                new Resources(2, 4096L),
                null,
                null,
                Map.of("jenkins-image", "node_22"),
                null,
                null,
                "node:22",
                io.jenkins.plugins.dockersandbox.api.model.StartupExecution.rawImageStart(List.of("sh", "-c", "sleep infinity")));
        api.createSandbox(req, "key-456");
        docker.verify(postRequestedFor(urlEqualTo("/sandboxes/v1/sandboxes"))
                .withRequestBody(equalToJson("{\"displayName\":\"jk-job-2\",\"resources\":{\"cpus\":2,\"memoryMib\":4096},"
                        + "\"labels\":{\"jenkins-image\":\"node_22\"},\"imageRef\":\"node:22\","
                        + "\"startupExecution\":{\"rawImage\":{\"start\":{\"argv\":[\"sh\",\"-c\",\"sleep infinity\"]}}}}")));
    }

    @Test
    void getSandboxParsesStatusAndFailure() throws Exception {
        docker.stubFor(get(urlEqualTo("/sandboxes/v1/sandboxes/sb1")).willReturn(aResponse().withBody(
                "{\"name\":\"sandboxes/sb1\",\"uid\":\"sb1\",\"core\":{\"status\":\"FAILED\"},\"failure\":{\"code\":\"internal\",\"message\":\"boom\"},\"extra\":1}")));
        Sandbox sb = api.getSandbox("sb1");
        assertEquals(Sandbox.FAILED, sb.status());
        assertEquals("boom", sb.failure().message());
    }

    @Test
    void listFollowsPageTokens() throws Exception {
        docker.stubFor(get(urlPathEqualTo("/sandboxes/v1/sandboxes"))
                .withQueryParam("pageToken", equalTo("p2"))
                .atPriority(1)
                .willReturn(aResponse().withBody("{\"sandboxes\":[{\"name\":\"sandboxes/b\",\"uid\":\"b\"}]}")));
        docker.stubFor(get(urlPathEqualTo("/sandboxes/v1/sandboxes"))
                .withQueryParam("pageSize", equalTo("100"))
                .atPriority(5)
                .willReturn(aResponse().withBody(
                        "{\"sandboxes\":[{\"name\":\"sandboxes/a\",\"uid\":\"a\"}],\"nextPageToken\":\"p2\"}")));
        List<Sandbox> all = api.listAllSandboxes();
        assertEquals(List.of("a", "b"), all.stream().map(Sandbox::id).toList());
    }

    @Test
    void deleteUsesCurrentEtagInIfMatch() throws Exception {
        docker.stubFor(get(urlEqualTo("/sandboxes/v1/sandboxes/sb1")).willReturn(aResponse().withHeader("ETag", "\"abc\"").withBody(SANDBOX_JSON)));
        docker.stubFor(delete(urlEqualTo("/sandboxes/v1/sandboxes/sb1?force=true")).willReturn(aResponse().withStatus(202).withBody(SANDBOX_JSON)));
        api.deleteSandbox("sb1", true);
        docker.verify(1, deleteRequestedFor(urlEqualTo("/sandboxes/v1/sandboxes/sb1?force=true"))
                .withHeader("If-Match", equalTo("\"abc\"")));
    }

    @Test
    void deleteRetriesOnceOnStaleEtag() throws Exception {
        docker.stubFor(get(urlEqualTo("/sandboxes/v1/sandboxes/sb1")).willReturn(aResponse().withBody(SANDBOX_JSON)));
        docker.stubFor(delete(urlEqualTo("/sandboxes/v1/sandboxes/sb1?force=true"))
                .inScenario("etag").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(412).withBody("{\"code\":\"failedPrecondition\",\"message\":\"stale\"}"))
                .willSetStateTo("fresh"));
        docker.stubFor(delete(urlEqualTo("/sandboxes/v1/sandboxes/sb1?force=true"))
                .inScenario("etag").whenScenarioStateIs("fresh")
                .willReturn(aResponse().withStatus(204)));
        api.deleteSandbox("sb1", true);
        docker.verify(2, deleteRequestedFor(urlEqualTo("/sandboxes/v1/sandboxes/sb1?force=true")));
    }

    @Test
    void stopAndStartSendEmptyJsonBodyWithCoreEtagInIfMatch() throws Exception {
        // the live service may omit the ETag header on GET: core.etag is used
        docker.stubFor(get(urlEqualTo("/sandboxes/v1/sandboxes/sb1")).willReturn(aResponse().withBody(SANDBOX_JSON)));
        docker.stubFor(post(urlEqualTo("/sandboxes/v1/sandboxes/sb1/stop")).willReturn(aResponse().withStatus(202)
                .withBody(SANDBOX_JSON.replace("running", "stopping"))));
        docker.stubFor(post(urlEqualTo("/sandboxes/v1/sandboxes/sb1/start")).willReturn(aResponse().withStatus(202)
                .withBody(SANDBOX_JSON.replace("running", "starting"))));
        assertEquals("stopping", api.stopSandbox("sb1").status());
        assertEquals("starting", api.startSandbox("sb1").status());
        docker.verify(1, postRequestedFor(urlEqualTo("/sandboxes/v1/sandboxes/sb1/stop"))
                .withHeader("If-Match", equalTo("\"e1\""))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(equalToJson("{}")));
        docker.verify(1, postRequestedFor(urlEqualTo("/sandboxes/v1/sandboxes/sb1/start"))
                .withHeader("If-Match", equalTo("\"e1\"")));
    }

    @Test
    void stopRetriesOnceWithFreshEtagOnStalePrecondition() throws Exception {
        docker.stubFor(get(urlEqualTo("/sandboxes/v1/sandboxes/sb1")).willReturn(aResponse().withBody(SANDBOX_JSON)));
        docker.stubFor(post(urlEqualTo("/sandboxes/v1/sandboxes/sb1/stop"))
                .inScenario("etag").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(412).withBody("{\"code\":\"failedPrecondition\",\"message\":\"stale\"}"))
                .willSetStateTo("fresh"));
        docker.stubFor(post(urlEqualTo("/sandboxes/v1/sandboxes/sb1/stop"))
                .inScenario("etag").whenScenarioStateIs("fresh")
                .willReturn(aResponse().withStatus(202).withBody(SANDBOX_JSON)));
        api.stopSandbox("sb1");
        docker.verify(2, postRequestedFor(urlEqualTo("/sandboxes/v1/sandboxes/sb1/stop")));
        docker.verify(2, getRequestedFor(urlEqualTo("/sandboxes/v1/sandboxes/sb1")));
    }

    @Test
    void startFailsAfterSecondStalePrecondition() throws Exception {
        docker.stubFor(get(urlEqualTo("/sandboxes/v1/sandboxes/sb1")).willReturn(aResponse().withBody(SANDBOX_JSON)));
        docker.stubFor(post(urlEqualTo("/sandboxes/v1/sandboxes/sb1/start"))
                .willReturn(aResponse().withStatus(412).withBody("{\"code\":\"failedPrecondition\",\"message\":\"stale\"}")));
        SandboxApiException e = assertThrows(SandboxApiException.class, () -> api.startSandbox("sb1"));
        assertEquals(412, e.getHttpStatus());
        docker.verify(2, postRequestedFor(urlEqualTo("/sandboxes/v1/sandboxes/sb1/start")));
    }

    @Test
    void deleteOfMissingSandboxIsNotAnError() throws Exception {
        docker.stubFor(get(urlEqualTo("/sandboxes/v1/sandboxes/gone")).willReturn(aResponse().withStatus(404)
                .withBody("{\"code\":\"notFound\",\"message\":\"sandbox not found\"}")));
        api.deleteSandbox("gone", true);
        docker.verify(0, deleteRequestedFor(urlEqualTo("/sandboxes/v1/sandboxes/gone?force=true")));
    }

    @Test
    void endpointCredentialRequestUsesDurationTtl() throws Exception {
        docker.stubFor(post(urlEqualTo("/sandboxes/v1/sandboxes/sb1/endpoint-credentials")).willReturn(aResponse().withBody(
                "{\"token\":\"ep-secret\",\"expireTime\":\"2030-01-01T00:05:00Z\",\"permissions\":[\"sandboxesExec\"],\"sandbox\":\"sandboxes/sb1\",\"audience\":\"x\"}")));
        EndpointCredential c = api.createEndpointCredential("sb1", HttpSandboxEndpointApi.PERMISSIONS, 300);
        assertEquals("ep-secret", c.token());
        assertFalse(c.toString().contains("ep-secret"));
        docker.verify(postRequestedFor(urlEqualTo("/sandboxes/v1/sandboxes/sb1/endpoint-credentials"))
                .withRequestBody(equalToJson(
                        "{\"permissions\":[\"sandboxesExec\",\"sandboxesFilesRead\",\"sandboxesFilesWrite\"],\"ttl\":\"300s\"}")));
    }

    @Test
    void retriesOn429WithRetryAfter() throws Exception {
        docker.stubFor(get(urlEqualTo("/sandboxes/v1/sandboxes/sb1")).inScenario("rl").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "0")
                        .withBody("{\"code\":\"RESOURCE_EXHAUSTED\",\"message\":\"slow down\"}"))
                .willSetStateTo("ok"));
        docker.stubFor(get(urlEqualTo("/sandboxes/v1/sandboxes/sb1")).inScenario("rl").whenScenarioStateIs("ok")
                .willReturn(aResponse().withBody(SANDBOX_JSON)));
        assertEquals("sb1", api.getSandbox("sb1").id());
        docker.verify(2, getRequestedFor(urlEqualTo("/sandboxes/v1/sandboxes/sb1")));
    }

    @Test
    void quotaExhaustedWithoutRetryAfterFailsFast() {
        docker.stubFor(post(urlEqualTo("/sandboxes/v1/sandboxes")).willReturn(aResponse().withStatus(429)
                .withBody("{\"code\":\"RESOURCE_EXHAUSTED\",\"message\":\"10 concurrent sandboxes\"}")));
        SandboxApiException e = assertThrows(
                SandboxApiException.class,
                () -> api.createSandbox(new CreateSandboxRequest("x", null, null, null, null, null), "k"));
        assertTrue(e.isQuotaExhausted());
        assertEquals("resourceexhausted", e.getCode());
        docker.verify(1, postRequestedFor(urlEqualTo("/sandboxes/v1/sandboxes")));
    }

    @Test
    void retriesTransientUnavailableThenGivesUp() {
        docker.stubFor(get(urlEqualTo("/sandboxes/v1/sandboxes/sb1")).willReturn(aResponse().withStatus(503)));
        SandboxApiException e = assertThrows(SandboxApiException.class, () -> api.getSandbox("sb1"));
        assertEquals(503, e.getHttpStatus());
        docker.verify(4, getRequestedFor(urlEqualTo("/sandboxes/v1/sandboxes/sb1"))); // 1 try + 3 retries
    }

    @Test
    void refreshesTokenOn401AndRetries() throws Exception {
        auth.stubFor(post(urlEqualTo("/v2/auth/token")).inScenario("t").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withBody("{\"access_token\":\"old\"}")).willSetStateTo("second"));
        auth.stubFor(post(urlEqualTo("/v2/auth/token")).inScenario("t").whenScenarioStateIs("second")
                .willReturn(aResponse().withBody("{\"access_token\":\"new\"}")));
        docker.stubFor(get(urlEqualTo("/sandboxes/v1/sandboxes/sb1")).withHeader("Authorization", equalTo("Bearer old"))
                .willReturn(aResponse().withStatus(401).withBody("{\"code\":\"unauthenticated\",\"message\":\"expired\"}")));
        docker.stubFor(get(urlEqualTo("/sandboxes/v1/sandboxes/sb1")).withHeader("Authorization", equalTo("Bearer new"))
                .willReturn(aResponse().withBody(SANDBOX_JSON)));
        assertEquals("sb1", api.getSandbox("sb1").id());
        auth.verify(2, postRequestedFor(urlEqualTo("/v2/auth/token")));
    }
}
