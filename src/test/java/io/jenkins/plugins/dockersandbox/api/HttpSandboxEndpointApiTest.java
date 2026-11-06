package io.jenkins.plugins.dockersandbox.api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.binaryEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.jenkins.plugins.dockersandbox.api.model.CreateProcessRequest;
import io.jenkins.plugins.dockersandbox.api.model.ExecRequest;
import io.jenkins.plugins.dockersandbox.api.model.ExecResponse;
import io.jenkins.plugins.dockersandbox.api.model.Process;
import io.jenkins.plugins.dockersandbox.api.model.ReadOutputResponse;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HttpSandboxEndpointApiTest {

    private WireMockServer docker;
    private WireMockServer auth;
    private WireMockServer endpoint;
    private MutableClock clock;
    private HttpSandboxEndpointApi api;

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private void credentialStub(String token, String expires) {
        docker.stubFor(post(urlEqualTo("/sandboxes/v1/sandboxes/sb1/endpoint-credentials")).willReturn(aResponse().withBody(
                "{\"token\":\"" + token + "\",\"expireTime\":\"" + expires + "\"}")));
    }

    @BeforeEach
    void start() {
        docker = new WireMockServer(options().dynamicPort());
        auth = new WireMockServer(options().dynamicPort());
        endpoint = new WireMockServer(options().dynamicPort());
        docker.start();
        auth.start();
        endpoint.start();
        clock = new MutableClock(Instant.parse("2030-01-01T00:00:00Z"));
        auth.stubFor(post(urlEqualTo("/v2/auth/token")).willReturn(aResponse().withBody("{\"access_token\":\"mgmt\"}")));
        TokenProvider tokens = new TokenProvider(auth.baseUrl(), "a", "p", HttpCaller.newClient(), clock);
        HttpDockerSandboxApi mgmt = new HttpDockerSandboxApi(
                docker.baseUrl() + "/sandboxes", tokens, HttpCaller.newClient(), 1, Duration.ofMillis(5));
        credentialStub("ep-1", "2030-01-01T00:05:00Z");
        // endpoint URI deliberately has a path prefix that must be preserved
        api = new HttpSandboxEndpointApi(
                mgmt, "sb1", endpoint.baseUrl() + "/prefix/sb1", HttpCaller.newClient(), clock, 1, Duration.ofMillis(5));
    }

    @AfterEach
    void stop() {
        docker.stop();
        auth.stop();
        endpoint.stop();
    }

    @Test
    void execDecodesBase64AndKeepsPathPrefix() throws Exception {
        endpoint.stubFor(post(urlEqualTo("/prefix/sb1/v1/processes/exec")).willReturn(aResponse().withBody(
                "{\"exitCode\":3,\"stdout\":\"" + b64("hello\n") + "\",\"stderr\":\"" + b64("oops") + "\",\"incomplete\":false}")));
        ExecResponse r = api.exec(new ExecRequest(List.of("bash", "-c", "echo hello"), Map.of("K", "V"), "/work", null));
        assertEquals(3, r.exitCode());
        assertEquals("hello\n", r.stdoutText());
        assertEquals("oops", r.stderrText());
        endpoint.verify(postRequestedFor(urlEqualTo("/prefix/sb1/v1/processes/exec"))
                .withHeader("Authorization", equalTo("Bearer ep-1"))
                .withRequestBody(equalToJson("{\"cmd\":[\"bash\",\"-c\",\"echo hello\"],\"env\":{\"K\":\"V\"},\"workingDir\":\"/work\"}")));
    }

    @Test
    void startProcessAndReadOutputAndSignal() throws Exception {
        endpoint.stubFor(post(urlEqualTo("/prefix/sb1/v1/processes")).willReturn(aResponse().withStatus(201)
                .withBody("{\"name\":\"processes/p-9\",\"label\":\"l\",\"state\":\"running\"}")));
        Process p = api.startProcess(new CreateProcessRequest(List.of("bash", "x.sh"), null, "/w", null, null));
        assertEquals("p-9", p.id());

        endpoint.stubFor(get(urlEqualTo("/prefix/sb1/v1/processes/p-9/output?resumeFrom=4&maxBytes=65536"))
                .willReturn(aResponse().withBody("{\"chunks\":[{\"stream\":\"stdout\",\"data\":\"" + b64("line\n")
                        + "\",\"streamSequence\":\"5\"}],\"nextResumeFrom\":\"5\",\"exited\":{\"exitCode\":0}}")));
        ReadOutputResponse out = api.readOutput("p-9", 4, 65536);
        assertEquals(5L, out.nextResumeFrom());
        assertEquals("line\n", new String(out.chunks().get(0).data(), StandardCharsets.UTF_8));
        assertEquals(0, out.exited().exitCode());

        endpoint.stubFor(post(urlEqualTo("/prefix/sb1/v1/processes/p-9/signal")).willReturn(aResponse().withBody("{}")));
        api.signal("p-9", "term");
        endpoint.verify(postRequestedFor(urlEqualTo("/prefix/sb1/v1/processes/p-9/signal"))
                .withRequestBody(equalToJson("{\"signal\":\"term\"}")));
    }

    @Test
    void stillRunningHasNoExitedField() throws Exception {
        endpoint.stubFor(get(urlEqualTo("/prefix/sb1/v1/processes/p/output?resumeFrom=0"))
                .willReturn(aResponse().withBody("{\"chunks\":[],\"nextResumeFrom\":\"0\"}")));
        ReadOutputResponse out = api.readOutput("p", 0, 0);
        assertNull(out.exited());
        assertNotNull(out.chunks());
    }

    @Test
    void putAndGetFileRoundTrip(@TempDir Path tmp) throws Exception {
        byte[] data = {1, 2, 3, 0, (byte) 255};
        Path src = tmp.resolve("in.bin");
        Files.write(src, data);
        endpoint.stubFor(put(urlEqualTo("/prefix/sb1/v1/files/content?path=%2Ftmp%2Fa.tgz&mode=420")).willReturn(aResponse().withBody("{}")));
        api.putFile("/tmp/a.tgz", 420, src);
        endpoint.verify(putRequestedFor(urlEqualTo("/prefix/sb1/v1/files/content?path=%2Ftmp%2Fa.tgz&mode=420"))
                .withRequestBody(binaryEqualTo(data)));

        endpoint.stubFor(get(urlEqualTo("/prefix/sb1/v1/files/content?path=%2Ftmp%2Fout.tgz")).willReturn(aResponse().withBody(data)));
        Path dst = tmp.resolve("out.bin");
        assertEquals(data.length, api.getFile("/tmp/out.tgz", dst));
        assertArrayEquals(data, Files.readAllBytes(dst));
    }

    @Test
    void credentialRefreshedBeforeExpiry() throws Exception {
        endpoint.stubFor(post(urlEqualTo("/prefix/sb1/v1/processes/exec")).willReturn(aResponse().withBody("{\"exitCode\":0}")));
        api.exec(ExecRequest.of("true"));
        api.exec(ExecRequest.of("true"));
        docker.verify(1, postRequestedFor(urlEqualTo("/sandboxes/v1/sandboxes/sb1/endpoint-credentials")));
        clock.advance(Duration.ofSeconds(280)); // within 30s of the 300s expiry
        credentialStub("ep-2", "2030-01-01T00:10:00Z");
        api.exec(ExecRequest.of("true"));
        docker.verify(2, postRequestedFor(urlEqualTo("/sandboxes/v1/sandboxes/sb1/endpoint-credentials")));
        endpoint.verify(postRequestedFor(urlEqualTo("/prefix/sb1/v1/processes/exec")).withHeader("Authorization", equalTo("Bearer ep-2")));
    }

    @Test
    void credentialRefreshedAfter401() throws Exception {
        docker.stubFor(post(urlEqualTo("/sandboxes/v1/sandboxes/sb1/endpoint-credentials")).inScenario("c")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withBody("{\"token\":\"stale\",\"expireTime\":\"2030-01-01T00:05:00Z\"}"))
                .willSetStateTo("n"));
        docker.stubFor(post(urlEqualTo("/sandboxes/v1/sandboxes/sb1/endpoint-credentials")).inScenario("c")
                .whenScenarioStateIs("n")
                .willReturn(aResponse().withBody("{\"token\":\"fresh\",\"expireTime\":\"2030-01-01T00:05:00Z\"}")));
        endpoint.stubFor(post(urlEqualTo("/prefix/sb1/v1/processes/exec")).withHeader("Authorization", equalTo("Bearer stale"))
                .willReturn(aResponse().withStatus(401).withBody("{\"code\":\"unauthenticated\"}")));
        endpoint.stubFor(post(urlEqualTo("/prefix/sb1/v1/processes/exec")).withHeader("Authorization", equalTo("Bearer fresh"))
                .willReturn(aResponse().withBody("{\"exitCode\":0}")));
        assertEquals(0, api.exec(ExecRequest.of("true")).exitCode());
    }

    @Test
    void errorsMapToApiException() {
        endpoint.stubFor(post(urlEqualTo("/prefix/sb1/v1/processes/exec")).willReturn(aResponse().withStatus(400)
                .withBody("{\"code\":\"invalidArgument\",\"message\":\"cmd required\"}")));
        SandboxApiException e = org.junit.jupiter.api.Assertions.assertThrows(
                SandboxApiException.class, () -> api.exec(ExecRequest.of("x")));
        assertEquals(400, e.getHttpStatus());
        assertEquals("invalidargument", e.getCode());
    }
}
