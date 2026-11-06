package io.jenkins.plugins.dockersandbox;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.CredentialsStore;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.domains.Domain;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import com.github.tomakehurst.wiremock.WireMockServer;
import hudson.util.FormValidation;
import io.jenkins.plugins.dockersandbox.account.LoadBalancingStrategy;
import io.jenkins.plugins.dockersandbox.account.SandboxAccount;
import java.util.List;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class DockerSandboxGlobalConfigurationTest {

    private static void addCredentials(String id, String user, String pat) throws Exception {
        CredentialsStore store = CredentialsProvider.lookupStores(Jenkins.get()).iterator().next();
        store.addCredentials(
                Domain.global(), new UsernamePasswordCredentialsImpl(CredentialsScope.GLOBAL, id, "docker " + id, user, pat));
    }

    private static SandboxAccount account(String id, String creds, int max, int weight, String labels) {
        SandboxAccount a = new SandboxAccount(id);
        a.setDisplayName("Account " + id);
        a.setCredentialsId(creds);
        a.setMaxConcurrent(max);
        a.setWeight(weight);
        a.setLabels(labels);
        a.setApiUrl("https://sandboxes.example.test/api");
        a.setAuthUrl("https://hub.example.test");
        return a;
    }

    @Test
    void configRoundTripKeepsEverything(JenkinsRule r) throws Exception {
        addCredentials("cred-1", "alice", "pat-1");
        addCredentials("cred-2", "bob", "pat-2");
        DockerSandboxGlobalConfiguration cfg = DockerSandboxGlobalConfiguration.get();
        SandboxAccount a1 = account("acc-1", "cred-1", 10, 1, "eu large");
        SandboxAccount a2 = account("acc-2", "cred-2", 4, 3, "us");
        a2.setEnabled(false);
        cfg.setAccounts(List.of(a1, a2));
        cfg.setStrategy(LoadBalancingStrategy.ROUND_ROBIN);
        cfg.setDefaultKit("shell");
        cfg.setDefaultSize("medium");
        cfg.setDefaultTtlMinutes(90);
        cfg.setLeaseTimeoutMinutes(12);
        cfg.setCooldownMinutes(7);
        cfg.setMaxFailoverAttempts(2);
        cfg.setOrphanCleanupEnabled(false);
        String instanceId = cfg.getInstanceId();

        r.configRoundtrip();

        DockerSandboxGlobalConfiguration after = DockerSandboxGlobalConfiguration.get();
        r.assertEqualDataBoundBeans(List.of(a1, a2), after.getAccounts());
        assertEquals(LoadBalancingStrategy.ROUND_ROBIN, after.getStrategy());
        assertEquals("medium", after.getDefaultSize());
        assertEquals(90, after.getDefaultTtlMinutes());
        assertEquals(12, after.getLeaseTimeoutMinutes());
        assertEquals(7, after.getCooldownMinutes());
        assertEquals(2, after.getMaxFailoverAttempts());
        assertFalse(after.isOrphanCleanupEnabled());
        assertEquals(instanceId, after.getInstanceId());
        assertFalse(after.getAccounts().get(1).isEnabled());
        assertEquals("https://sandboxes.example.test/api", after.getAccounts().get(0).getApiUrl());
    }

    @Test
    void removingAllAccountsIsPersisted(JenkinsRule r) throws Exception {
        DockerSandboxGlobalConfiguration cfg = DockerSandboxGlobalConfiguration.get();
        cfg.setAccounts(List.of(account("only", "", 1, 1, "")));
        r.configRoundtrip();
        assertEquals(1, DockerSandboxGlobalConfiguration.get().getAccounts().size());
        DockerSandboxGlobalConfiguration.get().setAccounts(List.of());
        r.configRoundtrip();
        assertTrue(DockerSandboxGlobalConfiguration.get().getAccounts().isEmpty());
    }

    @Test
    void duplicateAndBlankAccountIdsAreRejected(JenkinsRule r) {
        DockerSandboxGlobalConfiguration cfg = DockerSandboxGlobalConfiguration.get();
        assertThrows(
                IllegalArgumentException.class,
                () -> cfg.setAccounts(List.of(account("x", "", 1, 1, ""), account("x", "", 1, 1, ""))));
        assertThrows(IllegalArgumentException.class, () -> cfg.setAccounts(List.of(new SandboxAccount(" "))));
    }

    @Test
    void instanceIdIsGeneratedAndStable(JenkinsRule r) {
        String id = DockerSandboxGlobalConfiguration.get().getInstanceId();
        assertNotNull(id);
        assertFalse(id.isEmpty());
        assertEquals(id, DockerSandboxGlobalConfiguration.get().getInstanceId());
    }

    @Test
    void fieldValidation(JenkinsRule r) {
        SandboxAccount.DescriptorImpl d = r.jenkins.getDescriptorByType(SandboxAccount.DescriptorImpl.class);
        assertEquals(FormValidation.Kind.ERROR, d.doCheckId("").kind);
        assertEquals(FormValidation.Kind.ERROR, d.doCheckId("bad id!").kind);
        assertEquals(FormValidation.Kind.OK, d.doCheckId("acc-1_eu.2").kind);
        assertEquals(FormValidation.Kind.ERROR, d.doCheckMaxConcurrent("0").kind);
        assertEquals(FormValidation.Kind.ERROR, d.doCheckApiUrl("not a url").kind);
        assertEquals(FormValidation.Kind.OK, d.doCheckApiUrl("https://connect.docker.com/sandboxes").kind);
        assertEquals(FormValidation.Kind.ERROR, d.doCheckCredentialsId("").kind);
    }

    @Test
    void testConnectionExchangesPatAndListsSandboxes(JenkinsRule r) throws Exception {
        WireMockServer server = new WireMockServer(options().dynamicPort());
        server.start();
        try {
            addCredentials("cred-1", "alice", "pat-1");
            server.stubFor(post(urlEqualTo("/v2/auth/token"))
                    .willReturn(aResponse().withBody("{\"access_token\":\"tok\"}")));
            server.stubFor(get(urlPathEqualTo("/sandboxes/v1/sandboxes"))
                    .willReturn(aResponse().withBody("{\"sandboxes\":[{\"name\":\"sandboxes/a\"}]}")));
            SandboxAccount.DescriptorImpl d = r.jenkins.getDescriptorByType(SandboxAccount.DescriptorImpl.class);
            FormValidation ok = d.doTestConnection("cred-1", server.baseUrl(), server.baseUrl() + "/sandboxes");
            assertEquals(FormValidation.Kind.OK, ok.kind, ok.getMessage());
            assertTrue(ok.getMessage().contains("1 sandbox"));

            server.stubFor(post(urlEqualTo("/v2/auth/token"))
                    .willReturn(aResponse().withStatus(401).withBody("{\"message\":\"bad credentials\"}")));
            // a fresh credential id forces a new token provider
            addCredentials("cred-bad", "alice", "wrong");
            FormValidation bad = d.doTestConnection("cred-bad", server.baseUrl(), server.baseUrl() + "/sandboxes");
            assertEquals(FormValidation.Kind.ERROR, bad.kind);
            assertFalse(bad.getMessage().contains("wrong"));

            FormValidation missing = d.doTestConnection("nope", server.baseUrl(), server.baseUrl());
            assertEquals(FormValidation.Kind.ERROR, missing.kind);
        } finally {
            server.stop();
        }
    }
}
