package io.jenkins.plugins.dockersandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.domains.Domain;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import hudson.model.ManagementLink;
import io.jenkins.plugins.dockersandbox.account.SandboxAccount;
import java.util.List;
import jenkins.model.Jenkins;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class DockerSandboxManagementLinkTest {

    private JenkinsRule r;
    private FakeDockerCloud cloud;
    private DockerSandboxGlobalConfiguration config;

    @BeforeEach
    void setUp(JenkinsRule rule) throws Exception {
        r = rule;
        cloud = new FakeDockerCloud();
        cloud.account("user1");
        config = DockerSandboxGlobalConfiguration.get();
        CredentialsProvider.lookupStores(Jenkins.get()).iterator().next().addCredentials(
                Domain.global(), new UsernamePasswordCredentialsImpl(CredentialsScope.GLOBAL, "cred-1", "d", "user1", "pat"));
    }

    @AfterEach
    void tearDown() throws Exception {
        cloud.close();
    }

    private SandboxAccount account(String id, int max) {
        SandboxAccount a = new SandboxAccount(id);
        a.setCredentialsId("cred-1");
        a.setApiUrl(cloud.apiUrl());
        a.setAuthUrl(cloud.authUrl());
        a.setMaxConcurrent(max);
        return a;
    }

    @Test
    void linkIsRegisteredAndShowsEmptyState() throws Exception {
        assertTrue(r.jenkins.getExtensionList(ManagementLink.class).stream()
                .anyMatch(l -> "docker-sandbox".equals(l.getUrlName())));
        HtmlPage page = r.createWebClient().goTo("manage/docker-sandbox");
        assertTrue(page.asNormalizedText().contains("No Docker accounts are configured yet"));
    }

    @Test
    void showsDefaultKitAndImage() throws Exception {
        config.setDefaultImage("node:22");
        String text = r.createWebClient().goTo("manage/docker-sandbox").asNormalizedText();
        assertTrue(text.contains("Default image: node:22"), text);
        config.setDefaultImage("");
    }

    @Test
    void showsLiveUsageAndRunsConnectionTest() throws Exception {
        SandboxAccount a1 = account("acc-1", 4);
        SandboxAccount a2 = account("acc-2", 7);
        config.setAccounts(List.of(a1, a2));

        // hold a lease on acc-1 and fail acc-2 so the table has something to show
        try (var lease = config.getPool().lease(
                io.jenkins.plugins.dockersandbox.account.AccountSelector.of("acc-1", ""),
                io.jenkins.plugins.dockersandbox.account.LoadBalancingStrategy.FIRST_AVAILABLE,
                java.time.Duration.ofSeconds(5),
                m -> {})) {
            config.getPool().markFailure(a2, new RuntimeException("quota exceeded"), java.time.Duration.ofMinutes(5));

            JenkinsRule.WebClient wc = r.createWebClient();
            HtmlPage page = wc.goTo("manage/docker-sandbox");
            String text = page.asNormalizedText();
            assertTrue(text.contains("acc-1"), text);
            assertTrue(text.contains("1 / 4"), text);
            assertTrue(text.contains("0 / 7"), text);
            assertTrue(text.contains("quota exceeded"), text);

            HtmlForm form = page.getFormByName("test-acc-1");
            r.submit(form);
            String after = wc.goTo("manage/docker-sandbox").asNormalizedText();
            assertTrue(after.contains("Connected."), after);
        }
        assertEquals(0, config.getPool().stateOf("acc-1").getActive());
    }

    @Test
    void listsPersistentSandboxesAndStopsAndDeletesThemWithPostOnly() throws Exception {
        SandboxAccount a1 = account("acc-1", 4);
        config.setAccounts(List.of(a1));
        io.jenkins.plugins.dockersandbox.api.DockerSandboxApi api = SandboxBackend.DEFAULT.management(a1);
        String ours = persistentSandbox(api, config.getInstanceId(), "jkp-web", "web");
        String foreign = persistentSandbox(api, "other-jenkins", "jkp-web", "web");
        String plain = api.createSandbox(new io.jenkins.plugins.dockersandbox.api.model.CreateSandboxRequest(
                "jk-job-1", null, null, null, java.util.Map.of("jenkins-instance", config.getInstanceId()), null), "k3").id();
        cloud.forceState(ours, "running");

        JenkinsRule.WebClient wc = r.createWebClient();
        HtmlPage page = wc.goTo("manage/docker-sandbox");
        String text = page.asNormalizedText();
        assertTrue(text.contains("Persistent sandboxes"), text);
        assertEquals(1, page.getByXPath("//tr[contains(@class,'persistent-row')]").size(), text);
        assertTrue(text.contains(ours), text);
        assertFalse(text.contains(foreign), "sandboxes of another Jenkins are not listed");
        assertFalse(text.contains(plain), "one-shot sandboxes are not listed");

        // the actions are POST only
        wc.getOptions().setThrowExceptionOnFailingStatusCode(false);
        for (String action : List.of("stopPersistent", "deletePersistent")) {
            org.htmlunit.WebRequest get = new org.htmlunit.WebRequest(
                    new java.net.URL(r.getURL() + "manage/docker-sandbox/" + action + "?accountId=acc-1&name=web"),
                    org.htmlunit.HttpMethod.GET);
            int status = wc.getPage(get).getWebResponse().getStatusCode();
            assertTrue(status == 404 || status == 405, "GET must not reach the action, got " + status);
        }
        assertEquals("running", cloud.sandbox(ours).state);
        wc.getOptions().setThrowExceptionOnFailingStatusCode(true);

        r.submit(page.getFormByName("stop-acc-1-web"));
        assertEquals("stopping", cloud.sandbox(ours).state);
        assertEquals(List.of(ours), cloud.stoppedIds());
        String afterStop = wc.goTo("manage/docker-sandbox").asNormalizedText();
        assertTrue(afterStop.contains("Persistent sandbox 'web': stop -> stopping"), afterStop);

        r.submit(wc.goTo("manage/docker-sandbox").getFormByName("delete-acc-1-web"));
        assertTrue(cloud.sandbox(ours).deleted);
        assertFalse(cloud.sandbox(foreign).deleted, "never touches sandboxes of another Jenkins");
        assertFalse(cloud.sandbox(plain).deleted);
        assertTrue(wc.goTo("manage/docker-sandbox").asNormalizedText().contains("No persistent sandboxes"));
    }

    @Test
    void persistentActionsNeedAdministerPermissionAndRefuseSandboxesInUse() throws Exception {
        SandboxAccount a1 = account("acc-1", 4);
        config.setAccounts(List.of(a1));
        String id = persistentSandbox(SandboxBackend.DEFAULT.management(a1), config.getInstanceId(), "jkp-busy", "busy");
        cloud.forceState(id, "running");
        DockerSandboxManagementLink link = r.jenkins.getExtensionList(ManagementLink.class).get(DockerSandboxManagementLink.class);

        try (PersistentSandboxLocks.Hold held = PersistentSandboxLocks.acquire(
                PersistentSandboxLocks.key(config.getInstanceId(), "busy"), "busy", "build #3", java.time.Duration.ofSeconds(1), m -> {})) {
            link.doStopPersistent("acc-1", "busy");
            assertTrue(link.getPersistentMessage().contains("in use by build #3"), link.getPersistentMessage());
        }
        assertEquals("running", cloud.sandbox(id).state);

        r.jenkins.setSecurityRealm(r.createDummySecurityRealm());
        r.jenkins.setAuthorizationStrategy(new org.jvnet.hudson.test.MockAuthorizationStrategy()
                .grant(Jenkins.READ).everywhere().to("alice")
                .grant(Jenkins.ADMINISTER).everywhere().to("admin"));
        try (hudson.security.ACLContext ignored = hudson.security.ACL.as2(
                hudson.model.User.getById("alice", true).impersonate2())) {
            org.junit.jupiter.api.Assertions.assertThrows(
                    org.springframework.security.access.AccessDeniedException.class,
                    () -> link.doDeletePersistent("acc-1", "busy"));
        }
        assertFalse(cloud.sandbox(id).deleted);
    }

    private String persistentSandbox(
            io.jenkins.plugins.dockersandbox.api.DockerSandboxApi api, String instance, String displayName, String name)
            throws Exception {
        java.util.Map<String, String> labels = new java.util.LinkedHashMap<>();
        labels.put("jenkins-instance", instance);
        labels.put("jenkins-persistent", name);
        return api.createSandbox(
                        new io.jenkins.plugins.dockersandbox.api.model.CreateSandboxRequest(displayName, null, null, null, labels, null),
                        java.util.UUID.randomUUID().toString())
                .id();
    }

    @Test
    void failedConnectionTestIsReportedWithoutSecrets() throws Exception {
        SandboxAccount broken = account("broken", 2);
        broken.setCredentialsId("missing");
        config.setAccounts(List.of(broken));
        DockerSandboxManagementLink link = r.jenkins.getExtensionList(ManagementLink.class).get(DockerSandboxManagementLink.class);
        link.doTestConnection("broken");
        String last = link.getRows().get(0).getLastTest();
        assertTrue(last.startsWith("FAILED"), last);
    }
}
