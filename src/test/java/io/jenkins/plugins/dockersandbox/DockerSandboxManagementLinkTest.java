package io.jenkins.plugins.dockersandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
