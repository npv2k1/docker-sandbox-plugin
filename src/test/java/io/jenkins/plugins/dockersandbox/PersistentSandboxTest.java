package io.jenkins.plugins.dockersandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import io.jenkins.plugins.dockersandbox.api.DockerSandboxApi;
import io.jenkins.plugins.dockersandbox.api.model.CreateSandboxRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Future;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/** Persistent (long-lived) sandboxes against {@link FakeDockerCloud}: reuse, stop/start, locking, cleanup of state. */
@WithJenkins
class PersistentSandboxTest {

    private static final String PAT = "dckr_pat_TOPSECRET123";
    /** Appends to a counter file that lives in the sandbox work directory. */
    private static final String COUNT = "n=$(cat counter 2>/dev/null || echo 0); n=$((n+1)); echo $n > counter; echo counter=$n";

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

    /** A pipeline that runs {@code script} in persistent sandbox {@code name} with extra step arguments. */
    private WorkflowJob persistentJob(String name, String script, String extraArgs) throws Exception {
        return pipeline("node { def r = sandboxRun(persistent: '" + name + "', script: '" + script
                + "', uploadWorkspace: false" + (extraArgs.isEmpty() ? "" : ", " + extraArgs)
                + ")\n  echo \"result persistent=${r.persistent} account=${r.accountId} sandbox=${r.sandboxId}\" }");
    }

    private void assertLeasesAndLockReleased(String name) {
        for (SandboxAccount a : config.getAccounts()) {
            assertEquals(0, config.getPool().stateOf(a.getId()).getActive(), "lease leaked for " + a.getId());
        }
        assertNull(
                PersistentSandboxLocks.holderOf(PersistentSandboxLocks.key(config.getInstanceId(), name)),
                "lock leaked for " + name);
    }

    private Path workDir(String sandboxId) {
        return cloud.sandbox(sandboxId).root.resolve("home/agent/workspace");
    }

    private static String file(Path p) throws Exception {
        return Files.readString(p).trim();
    }

    /** Waits until the fake reports the sandbox as stopped (a stop is accepted as stopping first). */
    private void settle(String id) throws Exception {
        long end = System.currentTimeMillis() + 10_000;
        while (!"stopped".equals(cloud.sandbox(id).state) && System.currentTimeMillis() < end) {
            SandboxBackend.DEFAULT.management(config.getAccounts().get(0)).getSandbox(id);
            Thread.sleep(10);
        }
    }

    @Test
    void secondBuildReusesTheStoppedSandboxAndSeesItsState() throws Exception {
        accounts(1, 10, LoadBalancingStrategy.LEAST_LOADED);
        WorkflowJob job = persistentJob("p1", COUNT, "");

        WorkflowRun first = r.buildAndAssertSuccess(job);
        r.assertLogContains("counter=1", first);
        r.assertLogContains("Created persistent sandbox jkp-p1 (sb1) on account 'acc-1'", first);
        r.assertLogContains("result persistent=p1 account=acc-1 sandbox=sb1", first);
        r.assertLogContains("Stopping persistent sandbox jkp-p1 (sb1) (data kept)", first);
        // stopped, not deleted
        assertEquals(List.of("sb1"), cloud.stoppedIds());
        assertTrue(cloud.deletedIds().isEmpty());
        assertEquals(1, cloud.liveSandboxes());
        assertTrue(List.of("stopping", "stopped").contains(cloud.sandbox("sb1").state));
        FakeDockerCloud.Sandbox sb = cloud.sandbox("sb1");
        assertEquals("jkp-p1", sb.displayName);
        assertEquals("p1", sb.labels.get("jenkins-persistent"));
        assertEquals(config.getInstanceId(), sb.labels.get("jenkins-instance"));
        assertEquals("acc-1", sb.labels.get("jenkins-account"));
        assertEquals("stop", sb.createRequest.path("features").path("timeouts").path("onTimeout").asText());
        assertEquals("3600s", sb.createRequest.path("features").path("timeouts").path("timeout").asText());
        assertFalse(sb.labels.containsKey(SandboxJobRunner.KEEP_LABEL));
        assertLeasesAndLockReleased("p1");

        WorkflowRun second = r.buildAndAssertSuccess(job);
        r.assertLogContains("Reusing persistent sandbox jkp-p1 (sb1) on account acc-1, status stopped -> starting", second);
        r.assertLogContains("counter=2", second);
        r.assertLogContains("result persistent=p1 account=acc-1 sandbox=sb1", second);
        assertEquals(List.of("sb1"), cloud.startedIds());
        assertEquals(1, cloud.account("user1").createAttempts.get(), "the sandbox is created only once");
        assertEquals("2", file(workDir("sb1").resolve("counter")));
        r.assertLogNotContains(PAT, second);
        r.assertLogNotContains("tok-user1", second);
        assertEquals(1, cloud.liveSandboxes());
        assertEquals(List.of("sb1", "sb1"), cloud.stoppedIds());
        assertLeasesAndLockReleased("p1");
    }

    @Test
    void waitsForASandboxThatIsStillStoppingBeforeStartingIt() throws Exception {
        accounts(1, 10, LoadBalancingStrategy.LEAST_LOADED);
        cloud.setStopPolls(4);
        cloud.setStartPolls(2);
        WorkflowJob job = persistentJob("slow", COUNT, "");
        r.buildAndAssertSuccess(job);
        assertEquals("stopping", cloud.sandbox("sb1").state);
        // the first lookup of the next build may see the sandbox stopping
        WorkflowRun second = r.buildAndAssertSuccess(job);
        r.assertLogContains("counter=2", second);
        assertEquals(List.of("sb1"), cloud.startedIds());
        assertLeasesAndLockReleased("slow");
    }

    @Test
    void uploadedWorkspaceIsExtractedOverExistingFilesWithoutRemovingOthers() throws Exception {
        accounts(1, 10, LoadBalancingStrategy.LEAST_LOADED);
        WorkflowJob job = pipeline("node {\n"
                + "  writeFile file: 'in.txt', text: \"build-${env.BUILD_NUMBER}\"\n"
                + "  sandboxRun(persistent: 'up', script: 'cat in.txt; echo keep-me-${BUILD_NUMBER} > extra-${BUILD_NUMBER}.txt',"
                + " env: ['BUILD_NUMBER=' + env.BUILD_NUMBER], includes: 'in.txt')\n}");
        r.buildAndAssertSuccess(job);
        r.buildAndAssertSuccess(job);
        Path dir = workDir("sb1");
        assertEquals("build-2", file(dir.resolve("in.txt")), "same-named file is replaced");
        assertTrue(Files.exists(dir.resolve("extra-1.txt")), "files of earlier builds stay");
        assertTrue(Files.exists(dir.resolve("extra-2.txt")));
    }

    @Test
    void afterRunDeleteRemovesThePersistentSandbox() throws Exception {
        accounts(1, 10, LoadBalancingStrategy.LEAST_LOADED);
        WorkflowRun b = r.buildAndAssertSuccess(persistentJob("gone", COUNT, "afterRun: 'delete'"));
        r.assertLogContains("Deleted sandbox sb1", b);
        assertEquals(List.of("sb1"), cloud.deletedIds());
        assertTrue(cloud.stoppedIds().isEmpty());
        assertEquals(0, cloud.liveSandboxes());
        assertLeasesAndLockReleased("gone");
        // the name is free again: the next build creates a fresh sandbox
        WorkflowRun again = r.buildAndAssertSuccess(persistentJob("gone", COUNT, "afterRun: 'delete'"));
        r.assertLogContains("Created persistent sandbox jkp-gone (sb2)", again);
        r.assertLogContains("counter=1", again);
    }

    @Test
    void afterRunKeepLeavesThePersistentSandboxRunning() throws Exception {
        accounts(1, 10, LoadBalancingStrategy.LEAST_LOADED);
        WorkflowRun b = r.buildAndAssertSuccess(persistentJob("run", COUNT, "afterRun: 'keep'"));
        r.assertLogContains("Leaving persistent sandbox jkp-run (sb1) running", b);
        assertEquals("running", cloud.sandbox("sb1").state);
        assertTrue(cloud.stoppedIds().isEmpty());
        assertTrue(cloud.deletedIds().isEmpty());
        assertLeasesAndLockReleased("run");
        // a running sandbox is simply reused
        WorkflowRun second = r.buildAndAssertSuccess(persistentJob("run", COUNT, "afterRun: 'keep'"));
        r.assertLogContains("status running -> using it", second);
        r.assertLogContains("counter=2", second);
        assertTrue(cloud.startedIds().isEmpty());
    }

    @Test
    void keepSandboxTrueStillKeepsAOneShotSandboxRunning() throws Exception {
        accounts(1, 10, LoadBalancingStrategy.LEAST_LOADED);
        WorkflowRun b = r.buildAndAssertSuccess(pipeline(
                "node { sandboxRun(script: 'echo hi', uploadWorkspace: false, workDir: '/work', keepSandbox: true) }"));
        r.assertLogContains("Keeping sandbox sb1 (keepSandbox=true)", b);
        assertTrue(cloud.deletedIds().isEmpty());
        assertEquals("true", cloud.sandbox("sb1").labels.get(SandboxJobRunner.KEEP_LABEL));
        assertFalse(cloud.sandbox("sb1").labels.containsKey("jenkins-persistent"));
        assertEquals("delete", cloud.sandbox("sb1").createRequest.path("features").path("timeouts").path("onTimeout").asText());
    }

    @Test
    void keepSandboxTrueWithPersistentMeansKeepRunning() throws Exception {
        accounts(1, 10, LoadBalancingStrategy.LEAST_LOADED);
        r.buildAndAssertSuccess(persistentJob("kp", COUNT, "keepSandbox: true"));
        assertEquals("running", cloud.sandbox("sb1").state);
        assertTrue(cloud.stoppedIds().isEmpty());
    }

    @Test
    void failingScriptStillStopsInsteadOfDeletingAndReleasesEverything() throws Exception {
        accounts(1, 10, LoadBalancingStrategy.LEAST_LOADED);
        WorkflowRun b = r.assertBuildStatus(
                Result.FAILURE, persistentJob("bad", "echo before-failure; exit 3", "").scheduleBuild2(0));
        r.assertLogContains("Sandbox script exited with code 3", b);
        assertEquals(List.of("sb1"), cloud.stoppedIds());
        assertTrue(cloud.deletedIds().isEmpty());
        assertEquals(1, cloud.liveSandboxes());
        assertLeasesAndLockReleased("bad");
    }

    @Test
    void abortingTheBuildStopsThePersistentSandboxAndReleasesLeaseAndLock() throws Exception {
        accounts(1, 10, LoadBalancingStrategy.LEAST_LOADED);
        WorkflowJob job = persistentJob("ab", "echo started; sleep 120", "workDir: '/work'");
        WorkflowRun b = job.scheduleBuild2(0).waitForStart();
        long end = System.currentTimeMillis() + 60_000;
        while (!cloud.anyProcessRunning() && System.currentTimeMillis() < end) {
            Thread.sleep(50);
        }
        assertTrue(cloud.anyProcessRunning(), "script never started");
        assertEquals(
                b.getExternalizableId(),
                PersistentSandboxLocks.holderOf(PersistentSandboxLocks.key(config.getInstanceId(), "ab")));
        b.doStop();
        r.assertBuildStatus(Result.ABORTED, r.waitForCompletion(b));
        assertEquals(List.of("sb1"), cloud.stoppedIds());
        assertTrue(cloud.deletedIds().isEmpty());
        assertFalse(cloud.anyProcessRunning());
        assertLeasesAndLockReleased("ab");
    }

    @Test
    void parallelBuildsOnTheSameNameRunOneAfterTheOther() throws Exception {
        accounts(1, 10, LoadBalancingStrategy.LEAST_LOADED);
        String script = "echo enter >> trace; sleep 1; echo exit >> trace";
        WorkflowJob a = persistentJob("lock", script, "");
        WorkflowJob b = persistentJob("lock", script, "");
        Future<WorkflowRun> fa = a.scheduleBuild2(0);
        Future<WorkflowRun> fb = b.scheduleBuild2(0);
        WorkflowRun ra = r.assertBuildStatusSuccess(fa);
        WorkflowRun rb = r.assertBuildStatusSuccess(fb);
        assertEquals(List.of("enter", "exit", "enter", "exit"), Files.readAllLines(workDir("sb1").resolve("trace")));
        String logs = JenkinsRule.getLog(ra) + JenkinsRule.getLog(rb);
        assertTrue(logs.contains("Waiting for persistent sandbox 'lock' (in use by "), logs);
        assertEquals(1, cloud.account("user1").createAttempts.get(), "one sandbox serves both builds");
        assertEquals(1, cloud.liveSandboxes());
        assertLeasesAndLockReleased("lock");
    }

    @Test
    void differentNamesDoNotBlockEachOther() throws Exception {
        accounts(1, 10, LoadBalancingStrategy.LEAST_LOADED);
        try (PersistentSandboxLocks.Hold held = PersistentSandboxLocks.acquire(
                PersistentSandboxLocks.key(config.getInstanceId(), "other"), "other", "test", Duration.ofSeconds(1), m -> {})) {
            r.buildAndAssertSuccess(persistentJob("free", COUNT, ""));
        }
        assertLeasesAndLockReleased("free");
    }

    @Test
    void sandboxIsLeasedOnTheAccountThatHoldsIt() throws Exception {
        accounts(2, 10, LoadBalancingStrategy.FIRST_AVAILABLE);
        r.buildAndAssertSuccess(persistentJob("two", COUNT, "account: 'acc-2'"));
        assertEquals(0, cloud.account("user1").createAttempts.get());
        assertEquals(1, cloud.account("user2").createAttempts.get());
        // no account given: FIRST_AVAILABLE would pick acc-1, but the sandbox lives on acc-2
        WorkflowRun b = r.buildAndAssertSuccess(persistentJob("two", COUNT, ""));
        r.assertLogContains("Reusing persistent sandbox jkp-two (sb1) on account acc-2", b);
        r.assertLogContains("result persistent=two account=acc-2 sandbox=sb1", b);
        assertEquals(0, cloud.account("user1").createAttempts.get());
        assertEquals(List.of("sb1"), cloud.startedIds());
        assertLeasesAndLockReleased("two");
    }

    @Test
    void reuseWaitsForCapacityOnTheHoldingAccount() throws Exception {
        accounts(2, 1, LoadBalancingStrategy.FIRST_AVAILABLE);
        r.buildAndAssertSuccess(persistentJob("cap", COUNT, "account: 'acc-2'"));
        // occupy the only slot of acc-2 from outside, release it a bit later
        var lease = config.getPool().lease(
                io.jenkins.plugins.dockersandbox.account.AccountSelector.of("acc-2", ""),
                LoadBalancingStrategy.FIRST_AVAILABLE,
                Duration.ofSeconds(5),
                m -> {});
        Future<WorkflowRun> f = persistentJob("cap", COUNT, "").scheduleBuild2(0);
        Thread.sleep(500);
        assertFalse(f.isDone(), "must wait for capacity on acc-2 instead of creating a second sandbox");
        lease.release();
        WorkflowRun b = r.assertBuildStatusSuccess(f);
        r.assertLogContains("counter=2", b);
        assertEquals(1, cloud.account("user2").createAttempts.get());
        assertLeasesAndLockReleased("cap");
    }

    private String foreignSandbox(String instance, String displayName) throws Exception {
        DockerSandboxApi api = SandboxBackend.DEFAULT.management(config.getAccounts().get(0));
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("jenkins-instance", instance);
        labels.put("jenkins-persistent", "x");
        return api.createSandbox(new CreateSandboxRequest(displayName, null, null, null, labels, null), UUID.randomUUID().toString())
                .id();
    }

    @Test
    void sandboxesOfAnotherJenkinsAreNeverUsedStoppedOrDeleted() throws Exception {
        accounts(1, 10, LoadBalancingStrategy.LEAST_LOADED);
        String foreign = foreignSandbox("other-jenkins", "jkp-x");
        cloud.forceState(foreign, "running");
        // a build with the same name creates its own sandbox
        r.buildAndAssertSuccess(persistentJob("x", COUNT, ""));
        assertEquals("jkp-x", cloud.sandbox("sb2").displayName);
        assertEquals(config.getInstanceId(), cloud.sandbox("sb2").labels.get("jenkins-instance"));
        // sandboxManage on that name only touches ours
        WorkflowRun b = r.buildAndAssertSuccess(pipeline(
                "node { echo 'status=' + sandboxManage(name: 'x', action: 'delete') }"));
        r.assertLogContains("status=deleted", b);
        assertTrue(cloud.sandbox("sb2").deleted);
        assertFalse(cloud.sandbox(foreign).deleted);
        assertEquals("running", cloud.sandbox(foreign).state);
        r.assertLogContains("status=absent", r.buildAndAssertSuccess(pipeline(
                "node { echo 'status=' + sandboxManage(name: 'x', action: 'delete') }")));
        assertFalse(cloud.sandbox(foreign).deleted);
        assertTrue(cloud.sandbox(foreign).stopCalls.get() == 0);
    }

    @Test
    void startFailureReleasesLeaseAndLockAndKeepsTheSandbox() throws Exception {
        accounts(1, 10, LoadBalancingStrategy.LEAST_LOADED);
        WorkflowJob job = persistentJob("sf", COUNT, "");
        r.buildAndAssertSuccess(job);
        settle("sb1");
        cloud.setFailStart(true);
        WorkflowRun b = r.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));
        r.assertLogContains("Starting sandbox sb1 failed", b);
        assertTrue(cloud.deletedIds().isEmpty());
        assertEquals(1, cloud.liveSandboxes());
        assertLeasesAndLockReleased("sf");
        cloud.setFailStart(false);
        r.assertLogContains("counter=2", r.buildAndAssertSuccess(job));
    }

    @Test
    void listingFailureWhileFindingReleasesTheLockAndDoesNotCreateADuplicate() throws Exception {
        accounts(1, 10, LoadBalancingStrategy.LEAST_LOADED);
        config.getAccounts().get(0).setCredentialsId("missing-credentials");
        WorkflowRun b = r.assertBuildStatus(Result.FAILURE, persistentJob("lf", COUNT, "").scheduleBuild2(0));
        r.assertLogContains("Cannot tell whether persistent sandbox 'lf' exists", b);
        assertEquals(0, cloud.allSandboxes().size());
        assertLeasesAndLockReleased("lf");
    }

    @Test
    void failedPersistentSandboxAbortsWithDeleteHintAndCanBeDeleted() throws Exception {
        accounts(1, 10, LoadBalancingStrategy.LEAST_LOADED);
        WorkflowJob job = persistentJob("broken", COUNT, "");
        r.buildAndAssertSuccess(job);
        cloud.forceState("sb1", "failed");
        WorkflowRun b = r.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));
        r.assertLogContains("is in failed state: image pull failed", b);
        r.assertLogContains("sandboxManage(name: 'broken', action: 'delete')", b);
        assertTrue(cloud.deletedIds().isEmpty(), "a reused sandbox that cannot start is left for the user to delete");
        assertEquals(1, cloud.account("user1").createAttempts.get(), "no duplicate is created next to it");
        assertLeasesAndLockReleased("broken");

        r.buildAndAssertSuccess(pipeline("node { echo 'out=' + sandboxManage(name: 'broken', action: 'delete') }"));
        assertEquals(List.of("sb1"), cloud.deletedIds());
        r.assertLogContains("Created persistent sandbox jkp-broken (sb2)", r.buildAndAssertSuccess(job));
    }

    @Test
    void newPersistentSandboxThatFailsToStartIsDeletedSoTheNameStaysUsable() throws Exception {
        accounts(1, 10, LoadBalancingStrategy.LEAST_LOADED);
        cloud.setFailStartup(true);
        WorkflowRun b = r.assertBuildStatus(Result.FAILURE, persistentJob("nf", COUNT, "").scheduleBuild2(0));
        r.assertLogContains("failed to start: image pull failed", b);
        assertEquals(List.of("sb1"), cloud.deletedIds());
        assertLeasesAndLockReleased("nf");
        cloud.setFailStartup(false);
        r.assertLogContains("counter=1", r.buildAndAssertSuccess(persistentJob("nf", COUNT, "")));
    }

    @Test
    void sandboxStoppedByItsTtlDuringTheBuildIsNotStoppedTwice() throws Exception {
        accounts(1, 10, LoadBalancingStrategy.LEAST_LOADED);
        WorkflowJob job = persistentJob("ttl", "echo working", "");
        r.buildAndAssertSuccess(job);
        settle("sb1");
        // build 2 starts it; simulate the TTL stopping it while the script runs by stopping it from outside afterwards
        WorkflowRun b = r.buildAndAssertSuccess(job);
        cloud.forceState("sb1", "stopped");
        assertEquals(1, cloud.startedIds().size());
        r.assertLogContains("Stopping persistent sandbox jkp-ttl (sb1)", b);
    }

    @Test
    void sandboxManageReportsAndChangesTheState() throws Exception {
        accounts(1, 10, LoadBalancingStrategy.LEAST_LOADED);
        WorkflowRun before = r.buildAndAssertSuccess(pipeline(
                "node { echo 'status=' + sandboxManage(name: 'mg', action: 'status') }"));
        r.assertLogContains("status=absent", before);

        r.buildAndAssertSuccess(persistentJob("mg", COUNT, ""));
        WorkflowRun b = r.buildAndAssertSuccess(pipeline("node {\n"
                + "  echo 'after-run=' + sandboxManage(name: 'mg', action: 'status')\n"
                + "  echo 'start=' + sandboxManage(name: 'mg', action: 'start')\n"
                + "  echo 'running=' + sandboxManage(name: 'mg', action: 'status', account: 'acc-1')\n"
                + "  echo 'stop=' + sandboxManage(name: 'mg', action: 'stop')\n"
                + "  echo 'again=' + sandboxManage(name: 'mg', action: 'stop')\n"
                + "  echo 'delete=' + sandboxManage(name: 'mg', action: 'delete')\n"
                + "  echo 'final=' + sandboxManage(name: 'mg', action: 'status')\n}"));
        r.assertLogContains("after-run=stopped", b);
        r.assertLogContains("start=running", b);
        r.assertLogContains("running=running", b);
        r.assertLogContains("stop=stopping", b);
        r.assertLogContains("again=stop", b); // stopping or stopped: nothing more to do
        r.assertLogContains("delete=deleted", b);
        r.assertLogContains("final=absent", b);
        assertEquals(List.of("sb1"), cloud.deletedIds());
        assertEquals(2, cloud.stoppedIds().size());
        assertLeasesAndLockReleased("mg");
        r.assertLogNotContains(PAT, b);
    }

    @Test
    void sandboxManageStatusOnAnUnknownAccountFailsAndBadInputIsRejected() throws Exception {
        accounts(1, 10, LoadBalancingStrategy.LEAST_LOADED);
        r.assertLogContains("Unknown action 'explode'", r.assertBuildStatus(Result.FAILURE, pipeline(
                "node { sandboxManage(name: 'a', action: 'explode') }").scheduleBuild2(0)));
        r.assertLogContains("Invalid persistent sandbox name", r.assertBuildStatus(Result.FAILURE, pipeline(
                "node { sandboxManage(name: 'a b', action: 'status') }").scheduleBuild2(0)));
        r.assertLogContains("does not exist", r.assertBuildStatus(Result.FAILURE, pipeline(
                "node { sandboxManage(name: 'nothing', action: 'start') }").scheduleBuild2(0)));
        r.assertLogContains("No enabled Docker sandbox account matches", r.assertBuildStatus(Result.FAILURE, pipeline(
                "node { sandboxManage(name: 'a', action: 'status', account: 'nope') }").scheduleBuild2(0)));
        assertEquals(0, cloud.allSandboxes().size());
    }

    @Test
    void manageStopWaitsForTheLockWhileABuildIsInsideTheSandbox() throws Exception {
        accounts(1, 10, LoadBalancingStrategy.LEAST_LOADED);
        r.buildAndAssertSuccess(persistentJob("busy", COUNT, "afterRun: 'keep'"));
        try (PersistentSandboxLocks.Hold held = PersistentSandboxLocks.acquire(
                PersistentSandboxLocks.key(config.getInstanceId(), "busy"), "busy", "build #7", Duration.ofSeconds(1), m -> {})) {
            org.junit.jupiter.api.Assertions.assertThrows(hudson.AbortException.class, () -> PersistentSandboxes.manage(
                    config, SandboxBackend.DEFAULT, "busy", PersistentSandboxes.Action.STOP, "", "t", Duration.ZERO, m -> {}));
            assertEquals("running", cloud.sandbox("sb1").state);
            // status needs no lock
            assertEquals("running", PersistentSandboxes.manage(
                    config, SandboxBackend.DEFAULT, "busy", PersistentSandboxes.Action.STATUS, "", "t", Duration.ZERO, m -> {}));
        }
    }

    @Test
    void invalidNameAndAfterRunAreRejectedBeforeAnySandboxIsCreated() throws Exception {
        accounts(1, 10, LoadBalancingStrategy.LEAST_LOADED);
        r.assertLogContains("Invalid persistent sandbox name", r.assertBuildStatus(
                Result.FAILURE, persistentJob("bad name!", "echo x", "").scheduleBuild2(0)));
        r.assertLogContains("Invalid persistent sandbox name", r.assertBuildStatus(
                Result.FAILURE, persistentJob("a".repeat(41), "echo x", "").scheduleBuild2(0)));
        r.assertLogContains("'afterRun' must be stop, keep or delete", r.assertBuildStatus(
                Result.FAILURE, persistentJob("ok", "echo x", "afterRun: 'explode'").scheduleBuild2(0)));
        r.assertLogContains("afterRun 'stop' needs 'persistent'", r.assertBuildStatus(Result.FAILURE, pipeline(
                "node { sandboxRun(script: 'echo x', uploadWorkspace: false, afterRun: 'stop') }").scheduleBuild2(0)));
        assertEquals(0, cloud.allSandboxes().size());
        assertLeasesAndLockReleased("ok");
    }

    @Test
    void freestyleBuilderSupportsPersistentSandboxes() throws Exception {
        accounts(1, 10, LoadBalancingStrategy.LEAST_LOADED);
        FreeStyleProject p = r.createFreeStyleProject();
        SandboxBuilder builder = new SandboxBuilder(COUNT);
        builder.setPersistent("fs");
        builder.setUploadWorkspace(false);
        p.getBuildersList().add(builder);
        r.buildAndAssertSuccess(p);
        FreeStyleBuild b = r.buildAndAssertSuccess(p);
        r.assertLogContains("counter=2", b);
        assertEquals(List.of("sb1", "sb1"), cloud.stoppedIds());
        assertTrue(cloud.deletedIds().isEmpty());
        assertLeasesAndLockReleased("fs");
    }

    @Test
    void fakeKeepsEverythingInsideItsTempRoot() throws Exception {
        accounts(1, 10, LoadBalancingStrategy.LEAST_LOADED);
        WorkflowRun b = r.buildAndAssertSuccess(persistentJob(
                "iso", "echo \"$HOME|$TMPDIR\"; echo data > \"$HOME/h.txt\"; echo data > \"$TMPDIR/t.txt\"", ""));
        Path root = cloud.sandbox("sb1").root;
        assertTrue(Files.exists(root.resolve("home/agent/h.txt")));
        assertTrue(Files.exists(root.resolve("tmp/t.txt")));
        r.assertLogContains(root.resolve("home/agent") + "|" + root.resolve("tmp"), b);
    }

    @Test
    void lockTimesOutAndIsReleasedOnClose() throws Exception {
        String key = PersistentSandboxLocks.key("inst", "n");
        PersistentSandboxLocks.Hold first = PersistentSandboxLocks.acquire(key, "n", "one", Duration.ofSeconds(1), m -> {});
        List<String> logs = new ArrayList<>();
        hudson.AbortException e = org.junit.jupiter.api.Assertions.assertThrows(hudson.AbortException.class,
                () -> PersistentSandboxLocks.acquire(key, "n", "two", Duration.ofMillis(100), logs::add));
        assertTrue(e.getMessage().contains("in use by one"), e.getMessage());
        assertEquals(List.of("Waiting for persistent sandbox 'n' (in use by one)"), logs);
        first.close();
        first.close(); // harmless
        try (PersistentSandboxLocks.Hold second = PersistentSandboxLocks.acquire(key, "n", "two", Duration.ZERO, m -> {})) {
            assertEquals("two", PersistentSandboxLocks.holderOf(key));
        }
        assertNull(PersistentSandboxLocks.holderOf(key));
    }

    @Test
    void waitingForTheLockIsInterruptibleAndLeavesNothingBehind() throws Exception {
        String key = PersistentSandboxLocks.key("inst", "intr");
        try (PersistentSandboxLocks.Hold first = PersistentSandboxLocks.acquire(key, "intr", "one", Duration.ofSeconds(1), m -> {})) {
            Throwable[] thrown = new Throwable[1];
            Thread t = new Thread(() -> {
                try {
                    PersistentSandboxLocks.acquire(key, "intr", "two", Duration.ofMinutes(5), m -> {});
                } catch (Throwable x) {
                    thrown[0] = x;
                }
            });
            t.start();
            Thread.sleep(200);
            t.interrupt();
            t.join(5000);
            assertTrue(thrown[0] instanceof InterruptedException, String.valueOf(thrown[0]));
            assertEquals("one", PersistentSandboxLocks.holderOf(key));
        }
        assertNull(PersistentSandboxLocks.holderOf(key));
    }
}
