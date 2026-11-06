package io.jenkins.plugins.dockersandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.CredentialsStore;
import com.cloudbees.plugins.credentials.domains.Domain;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import io.jenkins.plugins.dockersandbox.account.LoadBalancingStrategy;
import io.jenkins.plugins.dockersandbox.account.SandboxAccount;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.CreateFileBuilder;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/** End-to-end plugin behavior against {@link FakeDockerCloud}, which really runs the scripts with local bash. */
@WithJenkins
class SandboxRunTest {

    private static final String PAT = "dckr_pat_TOPSECRET123";

    private JenkinsRule r;
    private FakeDockerCloud cloud;
    private DockerSandboxGlobalConfiguration config;

    @BeforeEach
    void setUp(JenkinsRule rule) throws Exception {
        this.r = rule;
        cloud = new FakeDockerCloud();
        config = DockerSandboxGlobalConfiguration.get();
        SandboxJobRunner.setTiming(new SandboxJobRunner.Timing(
                Duration.ofMillis(20),
                Duration.ofSeconds(30),
                Duration.ofMillis(20),
                Duration.ofMillis(100),
                Duration.ofMillis(100),
                Duration.ofMillis(50)));
        r.jenkins.setNumExecutors(6);
    }

    @AfterEach
    void tearDown() throws Exception {
        SandboxJobRunner.setTiming(null);
        cloud.close();
    }

    /** Adds accounts acc-1..acc-n backed by Docker IDs user1..usern. */
    private void accounts(int count, int maxConcurrent, LoadBalancingStrategy strategy) throws Exception {
        CredentialsStore store = CredentialsProvider.lookupStores(Jenkins.get()).iterator().next();
        List<SandboxAccount> list = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            cloud.account("user" + i);
            store.addCredentials(
                    Domain.global(),
                    new UsernamePasswordCredentialsImpl(CredentialsScope.GLOBAL, "cred-" + i, "docker " + i, "user" + i, PAT));
            SandboxAccount a = new SandboxAccount("acc-" + i);
            a.setCredentialsId("cred-" + i);
            a.setApiUrl(cloud.apiUrl());
            a.setAuthUrl(cloud.authUrl());
            a.setMaxConcurrent(maxConcurrent);
            list.add(a);
        }
        config.setAccounts(list);
        config.setStrategy(strategy);
    }

    private WorkflowJob pipeline(String script) throws Exception {
        WorkflowJob job = r.createProject(WorkflowJob.class);
        job.setDefinition(new CpsFlowDefinition(script, true));
        return job;
    }

    private void assertAllReleased() {
        for (SandboxAccount a : config.getAccounts()) {
            assertEquals(0, config.getPool().stateOf(a.getId()).getActive(), "lease leaked for " + a.getId());
        }
        assertEquals(0, cloud.liveSandboxes(), "sandbox leaked");
    }

    @Test
    void pipelineRunsScriptStreamsLogAndCopiesArtifacts() throws Exception {
        accounts(1, 10, LoadBalancingStrategy.LEAST_LOADED);
        WorkflowJob job = pipeline("node {\n"
                + "  writeFile file: 'in.txt', text: 'payload-42'\n"
                + "  def r = sandboxRun(script: 'echo hello-from-sandbox; cat in.txt; mkdir -p dist; echo built > dist/out.txt',"
                + " workDir: '/work', artifacts: 'dist/**')\n"
                + "  echo \"exit=${r.exitCode} account=${r.accountId}\"\n"
                + "  echo 'artifact=' + readFile('dist/out.txt').trim()\n"
                + "}");
        WorkflowRun b = r.buildAndAssertSuccess(job);
        r.assertLogContains("hello-from-sandbox", b);
        r.assertLogContains("payload-42", b);
        r.assertLogContains("exit=0 account=acc-1", b);
        r.assertLogContains("artifact=built", b);
        r.assertLogContains("Sandbox sb1 on account acc-1 ready", b);
        r.assertLogNotContains(PAT, b);
        r.assertLogNotContains("tok-user1", b);
        assertEquals(List.of("sb1"), cloud.deletedIds());
        assertAllReleased();

        FakeDockerCloud.Sandbox sb = cloud.sandbox("sb1");
        assertEquals("jenkins-instance", sb.labels.keySet().iterator().next());
        assertEquals(config.getInstanceId(), sb.labels.get("jenkins-instance"));
        assertEquals("acc-1", sb.labels.get("jenkins-account"));
        assertEquals("1", sb.labels.get("jenkins-build"));
        assertEquals("3600s", sb.createRequest.path("features").path("timeouts").path("timeout").asText());
        assertEquals("shell", sb.createRequest.path("kits").get(0).path("artifact").path("ref").path("ref").asText());
        assertEquals(2, sb.createRequest.path("resources").path("cpus").asInt());
    }

    @Test
    void nonZeroExitFailsBuildAndStillDeletesSandbox() throws Exception {
        accounts(1, 10, LoadBalancingStrategy.LEAST_LOADED);
        WorkflowJob job = pipeline("node { sandboxRun(script: 'echo boom; exit 7', uploadWorkspace: false, workDir: '/work') }");
        WorkflowRun b = r.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));
        r.assertLogContains("boom", b);
        r.assertLogContains("Sandbox script exited with code 7", b);
        assertEquals(1, cloud.deletedIds().size());
        assertAllReleased();
    }

    @Test
    void returnStatusKeepsBuildGreen() throws Exception {
        accounts(1, 10, LoadBalancingStrategy.LEAST_LOADED);
        WorkflowJob job = pipeline("node {\n"
                + "  def r = sandboxRun(script: 'exit 7', returnStatus: true, uploadWorkspace: false, workDir: '/work')\n"
                + "  echo \"code=${r.exitCode}\"\n}");
        WorkflowRun b = r.buildAndAssertSuccess(job);
        r.assertLogContains("code=7", b);
        assertAllReleased();
    }

    @Test
    void quotaExhaustedAccountFailsOverToNextOne() throws Exception {
        accounts(2, 10, LoadBalancingStrategy.FIRST_AVAILABLE);
        cloud.account("user1").exhausted = true;
        WorkflowJob job = pipeline("node {\n"
                + "  def r = sandboxRun(script: 'echo ok', uploadWorkspace: false, workDir: '/work')\n"
                + "  echo \"ran on ${r.accountId}\"\n}");
        WorkflowRun b = r.buildAndAssertSuccess(job);
        r.assertLogContains("ran on acc-2", b);
        r.assertLogContains("Account 'acc-1' cannot create a sandbox", b);
        r.assertLogContains("trying another account", b);
        assertEquals(1, cloud.account("user1").createAttempts.get());
        assertEquals(1, cloud.account("user2").createAttempts.get());
        assertTrue(config.getPool().stateOf("acc-1").getCooldownUntilMillis() > System.currentTimeMillis());
        assertAllReleased();
    }

    @Test
    void failsClearlyWhenEveryAccountIsExhausted() throws Exception {
        accounts(2, 10, LoadBalancingStrategy.FIRST_AVAILABLE);
        cloud.account("user1").exhausted = true;
        cloud.account("user2").exhausted = true;
        WorkflowJob job = pipeline("node { sandboxRun(script: 'echo ok', uploadWorkspace: false, workDir: '/work') }");
        WorkflowRun b = r.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));
        r.assertLogContains("No Docker sandbox account left to fail over to", b);
        assertAllReleased();
    }

    @Test
    void pinnedAccountAndLabelsAreHonored() throws Exception {
        accounts(2, 10, LoadBalancingStrategy.FIRST_AVAILABLE);
        List<SandboxAccount> list = new ArrayList<>(config.getAccounts());
        list.get(1).setLabels("eu large");
        config.setAccounts(list);
        WorkflowJob pinned = pipeline("node { def r = sandboxRun(script: 'true', account: 'acc-2', uploadWorkspace: false, workDir: '/work')\n echo \"on ${r.accountId}\" }");
        r.assertLogContains("on acc-2", r.buildAndAssertSuccess(pinned));
        WorkflowJob labelled = pipeline("node { def r = sandboxRun(script: 'true', accountLabel: 'large', uploadWorkspace: false, workDir: '/work')\n echo \"on ${r.accountId}\" }");
        r.assertLogContains("on acc-2", r.buildAndAssertSuccess(labelled));
        WorkflowJob nobody = pipeline("node { sandboxRun(script: 'true', accountLabel: 'gpu', uploadWorkspace: false, workDir: '/work') }");
        r.assertLogContains("No enabled Docker sandbox account matches", r.assertBuildStatus(Result.FAILURE, nobody.scheduleBuild2(0)));
        assertAllReleased();
    }

    @Test
    void thirdBuildWaitsForCapacityOnTwoSingleSlotAccounts() throws Exception {
        accounts(2, 1, LoadBalancingStrategy.LEAST_LOADED);
        cloud.account("user1").quota = 1;
        cloud.account("user2").quota = 1;
        WorkflowJob job = pipeline("def branches = [:]\n"
                + "for (int i = 0; i < 3; i++) {\n"
                + "  def n = i\n"
                + "  branches['b' + n] = { node { sandboxRun(script: 'sleep 1; echo finished-' + n, uploadWorkspace: false, workDir: '/work') } }\n"
                + "}\n"
                + "parallel branches\n");
        WorkflowRun b = r.buildAndAssertSuccess(job);
        r.assertLogContains("Waiting for Docker sandbox capacity (all 2 accounts busy)", b);
        for (int i = 0; i < 3; i++) {
            r.assertLogContains("finished-" + i, b);
        }
        assertEquals(1, cloud.account("user1").peakLive.get());
        assertEquals(1, cloud.account("user2").peakLive.get());
        assertEquals(3, cloud.deletedIds().size());
        // the plugin never even tried to exceed an account: no creation was refused
        assertEquals(3, cloud.account("user1").createAttempts.get() + cloud.account("user2").createAttempts.get());
        assertAllReleased();
    }

    @Test
    void abortingBuildDeletesSandboxAndReleasesLease() throws Exception {
        accounts(1, 10, LoadBalancingStrategy.LEAST_LOADED);
        WorkflowJob job = pipeline("node { sandboxRun(script: 'echo started; sleep 120', uploadWorkspace: false, workDir: '/work') }");
        WorkflowRun b = job.scheduleBuild2(0).waitForStart();
        long end = System.currentTimeMillis() + 60_000;
        while (!cloud.anyProcessRunning() && System.currentTimeMillis() < end) {
            Thread.sleep(50);
        }
        assertTrue(cloud.anyProcessRunning(), "script never started");
        b.doStop();
        r.assertBuildStatus(Result.ABORTED, r.waitForCompletion(b));
        assertEquals(1, cloud.deletedIds().size());
        assertFalse(cloud.anyProcessRunning());
        assertAllReleased();
    }

    @Test
    void freestyleBuilderRunsScript() throws Exception {
        accounts(1, 10, LoadBalancingStrategy.LEAST_LOADED);
        FreeStyleProject p = r.createFreeStyleProject();
        p.getBuildersList().add(new CreateFileBuilder("in.txt", "from-freestyle"));
        SandboxBuilder builder = new SandboxBuilder("cat in.txt; echo \"v=$MY_VAR\"; echo result > out.txt");
        builder.setWorkDir("/work");
        builder.setArtifacts("out.txt");
        builder.setEnvText("MY_VAR=hello\n");
        p.getBuildersList().add(builder);
        FreeStyleBuild b = r.buildAndAssertSuccess(p);
        r.assertLogContains("from-freestyle", b);
        r.assertLogContains("v=hello", b);
        assertEquals("result", b.getWorkspace().child("out.txt").readToString().trim());
        assertEquals(1, cloud.deletedIds().size());
        assertAllReleased();
    }

    @Test
    void freestyleReturnStatusMarksUnstable() throws Exception {
        accounts(1, 10, LoadBalancingStrategy.LEAST_LOADED);
        FreeStyleProject p = r.createFreeStyleProject();
        SandboxBuilder builder = new SandboxBuilder("exit 3");
        builder.setWorkDir("/work");
        builder.setUploadWorkspace(false);
        builder.setReturnStatus(true);
        p.getBuildersList().add(builder);
        r.assertBuildStatus(Result.UNSTABLE, p.scheduleBuild2(0));
        assertAllReleased();
    }

    @Test
    void blankScriptIsRejectedBeforeCreatingSandbox() throws Exception {
        accounts(1, 10, LoadBalancingStrategy.LEAST_LOADED);
        WorkflowJob job = pipeline("node { sandboxRun(script: '   ') }");
        WorkflowRun b = r.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));
        r.assertLogContains("'script' must not be empty", b);
        assertEquals(0, cloud.allSandboxes().size());
        assertAllReleased();
    }

    @Test
    void sandboxThatFailsToStartIsDeletedAndReported() throws Exception {
        accounts(1, 10, LoadBalancingStrategy.LEAST_LOADED);
        cloud.setFailStartup(true);
        WorkflowJob job = pipeline("node { sandboxRun(script: 'echo never', uploadWorkspace: false, workDir: '/work') }");
        WorkflowRun b = r.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));
        r.assertLogContains("failed to start: image pull failed", b);
        assertEquals(List.of("sb1"), cloud.deletedIds());
        assertAllReleased();
    }
}
