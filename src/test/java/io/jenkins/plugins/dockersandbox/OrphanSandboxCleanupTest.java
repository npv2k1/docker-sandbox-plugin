package io.jenkins.plugins.dockersandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.domains.Domain;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import io.jenkins.plugins.dockersandbox.account.SandboxAccount;
import io.jenkins.plugins.dockersandbox.api.DockerSandboxApi;
import io.jenkins.plugins.dockersandbox.api.model.CreateSandboxRequest;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Future;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.SleepBuilder;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class OrphanSandboxCleanupTest {

    private JenkinsRule r;
    private FakeDockerCloud cloud;
    private DockerSandboxGlobalConfiguration config;
    private DockerSandboxApi api;

    @BeforeEach
    void setUp(JenkinsRule rule) throws Exception {
        r = rule;
        cloud = new FakeDockerCloud();
        cloud.account("user1");
        config = DockerSandboxGlobalConfiguration.get();
        CredentialsProvider.lookupStores(Jenkins.get()).iterator().next().addCredentials(
                Domain.global(), new UsernamePasswordCredentialsImpl(CredentialsScope.GLOBAL, "cred-1", "d", "user1", "pat"));
        SandboxAccount a = new SandboxAccount("acc-1");
        a.setCredentialsId("cred-1");
        a.setApiUrl(cloud.apiUrl());
        a.setAuthUrl(cloud.authUrl());
        config.setAccounts(List.of(a));
        api = SandboxBackend.DEFAULT.management(a);
    }

    @AfterEach
    void tearDown() throws Exception {
        cloud.close();
    }

    private String create(String instance, hudson.model.Run<?, ?> run, boolean old, boolean keep) throws Exception {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("jenkins-instance", instance);
        if (run != null) {
            labels.put("jenkins-run", SandboxJobRunner.encodeRunId(run.getExternalizableId()));
        }
        if (keep) {
            labels.put(SandboxJobRunner.KEEP_LABEL, "true");
        }
        String id = api.createSandbox(new CreateSandboxRequest("o", null, null, null, labels, null), UUID.randomUUID().toString()).id();
        if (old) {
            cloud.sandbox(id).createdAt = Instant.now().minusSeconds(3600);
        }
        return id;
    }

    private int cleanup() {
        return OrphanSandboxCleanup.cleanup(config, SandboxBackend.DEFAULT, Clock.systemUTC(), new PrintStream(new ByteArrayOutputStream()));
    }

    @Test
    void deletesOnlyOldSandboxesOfFinishedBuildsOfThisInstance() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject();
        FreeStyleBuild done = r.buildAndAssertSuccess(p);
        String instance = config.getInstanceId();

        String orphan = create(instance, done, true, false);
        String young = create(instance, done, false, false);
        String foreign = create("other-jenkins", done, true, false);
        String kept = create(instance, done, true, true);
        String noRun = create(instance, null, true, false);

        assertEquals(1, cleanup());
        assertTrue(cloud.sandbox(orphan).deleted);
        assertFalse(cloud.sandbox(young).deleted);
        assertFalse(cloud.sandbox(foreign).deleted);
        assertFalse(cloud.sandbox(kept).deleted);
        assertFalse(cloud.sandbox(noRun).deleted);
        assertEquals(0, cleanup(), "second pass finds nothing more");
    }

    @Test
    void sandboxOfRunningBuildIsKeptUntilBuildEnds() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject();
        p.getBuildersList().add(new SleepBuilder(4000));
        Future<FreeStyleBuild> future = p.scheduleBuild2(0).getStartCondition();
        FreeStyleBuild running = (FreeStyleBuild) future.get();
        long end = System.currentTimeMillis() + 10_000;
        while (!running.isBuilding() && System.currentTimeMillis() < end) {
            Thread.sleep(20);
        }
        assertTrue(running.isBuilding());
        String id = create(config.getInstanceId(), running, true, false);
        assertEquals(0, cleanup());
        assertFalse(cloud.sandbox(id).deleted);
        r.waitForCompletion(running);
        assertEquals(1, cleanup());
        assertTrue(cloud.sandbox(id).deleted);
    }

    @Test
    void sandboxOfDeletedJobIsAnOrphan() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject();
        FreeStyleBuild done = r.buildAndAssertSuccess(p);
        String id = create(config.getInstanceId(), done, true, false);
        p.delete();
        assertEquals(1, cleanup());
        assertTrue(cloud.sandbox(id).deleted);
    }

    @Test
    void periodicWorkHonorsTheEnableSwitch() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject();
        FreeStyleBuild done = r.buildAndAssertSuccess(p);
        String id = create(config.getInstanceId(), done, true, false);
        config.setOrphanCleanupEnabled(false);
        r.jenkins.getExtensionList(hudson.model.AsyncPeriodicWork.class).get(OrphanSandboxCleanup.class).run();
        assertFalse(cloud.sandbox(id).deleted);
        config.setOrphanCleanupEnabled(true);
        r.jenkins.getExtensionList(hudson.model.AsyncPeriodicWork.class).get(OrphanSandboxCleanup.class).run();
        long end = System.currentTimeMillis() + 15_000;
        while (!cloud.sandbox(id).deleted && System.currentTimeMillis() < end) {
            Thread.sleep(50);
        }
        assertTrue(cloud.sandbox(id).deleted);
    }
}
