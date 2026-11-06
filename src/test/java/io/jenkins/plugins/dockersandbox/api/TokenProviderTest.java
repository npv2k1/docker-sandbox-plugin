package io.jenkins.plugins.dockersandbox.api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TokenProviderTest {

    private WireMockServer server;
    private MutableClock clock;

    @BeforeEach
    void start() {
        server = new WireMockServer(options().dynamicPort());
        server.start();
        clock = new MutableClock(Instant.parse("2030-01-01T00:00:00Z"));
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    private TokenProvider provider() {
        return new TokenProvider(server.baseUrl(), "alice", "dckr_pat_secret", HttpCaller.newClient(), clock);
    }

    @Test
    void exchangesPatAndCachesToken() throws Exception {
        server.stubFor(post(urlEqualTo("/v2/auth/token"))
                .withRequestBody(equalToJson("{\"identifier\":\"alice\",\"secret\":\"dckr_pat_secret\"}"))
                .willReturn(aResponse().withStatus(200).withBody("{\"access_token\":\"tok-1\"}")));
        TokenProvider p = provider();
        assertEquals("tok-1", p.getToken());
        assertEquals("tok-1", p.getToken());
        server.verify(1, postRequestedFor(urlEqualTo("/v2/auth/token")));
    }

    @Test
    void refreshesWhenNearExpiryOrInvalidated() throws Exception {
        server.stubFor(post(urlEqualTo("/v2/auth/token"))
                .willReturn(aResponse().withStatus(200).withBody("{\"access_token\":\"opaque\"}")));
        TokenProvider p = provider();
        p.getToken();
        clock.advance(Duration.ofMinutes(2));
        p.getToken();
        server.verify(1, postRequestedFor(urlEqualTo("/v2/auth/token")));
        clock.advance(Duration.ofMinutes(2)); // past the 4 minute fallback minus skew
        p.getToken();
        server.verify(2, postRequestedFor(urlEqualTo("/v2/auth/token")));
        p.invalidate();
        p.getToken();
        server.verify(3, postRequestedFor(urlEqualTo("/v2/auth/token")));
    }

    @Test
    void honorsJwtExpiry() throws Exception {
        long exp = clock.instant().plusSeconds(3600).getEpochSecond();
        String payload = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"exp\":" + exp + "}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String jwt = "h." + payload + ".s";
        server.stubFor(post(urlEqualTo("/v2/auth/token"))
                .willReturn(aResponse().withStatus(200).withBody("{\"access_token\":\"" + jwt + "\"}")));
        TokenProvider p = provider();
        p.getToken();
        clock.advance(Duration.ofMinutes(30));
        p.getToken();
        server.verify(1, postRequestedFor(urlEqualTo("/v2/auth/token")));
    }

    @Test
    void badCredentialsAreAnAuthError() {
        server.stubFor(post(urlEqualTo("/v2/auth/token"))
                .willReturn(aResponse().withStatus(401).withBody("{\"message\":\"incorrect authentication credentials\"}")));
        SandboxApiException e = assertThrows(SandboxApiException.class, () -> provider().getToken());
        assertTrue(e.isAuthError());
        assertFalse(e.getMessage().contains("dckr_pat_secret"));
    }

    @Test
    void secretIsMaskedInToString() {
        assertFalse(provider().toString().contains("dckr_pat_secret"));
    }
}
