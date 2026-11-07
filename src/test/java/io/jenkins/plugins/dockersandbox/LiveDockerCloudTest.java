package io.jenkins.plugins.dockersandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.CredentialsStore;
import com.cloudbees.plugins.credentials.domains.Domain;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import hudson.model.BooleanParameterValue;
import hudson.model.ParametersAction;
import hudson.model.PasswordParameterValue;
import hudson.model.StringParameterValue;
import io.jenkins.plugins.dockersandbox.account.SandboxAccount;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Opt-in tests against the real Docker Cloud Sandboxes service (they create and delete real sandboxes and, for the
 * OpenCode test, call a real model). Skipped unless the environment provides credentials, see {@code .env.example}:
 *
 * <pre>set -a; . ./.env; set +a; mvn -B -ntp test -Dtest=LiveDockerCloudTest</pre>
 */
@WithJenkins
class LiveDockerCloudTest {

    private JenkinsRule r;

    @BeforeEach
    void setUp(JenkinsRule rule) throws Exception {
        this.r = rule;
        String id = System.getenv("DOCKER_ID");
        String pat = System.getenv("DOCKER_PAT");
        assumeTrue(id != null && !id.isBlank() && pat != null && !pat.isBlank(), "DOCKER_ID / DOCKER_PAT not set");
        CredentialsStore store = CredentialsProvider.lookupStores(Jenkins.get()).iterator().next();
        store.addCredentials(Domain.global(),
                new UsernamePasswordCredentialsImpl(CredentialsScope.GLOBAL, "docker-live", "live test", id, pat));
        SandboxAccount account = new SandboxAccount("acc-live");
        account.setCredentialsId("docker-live");
        account.setMaxConcurrent(2);
        DockerSandboxGlobalConfiguration.get().setAccounts(List.of(account));
        r.jenkins.setNumExecutors(2);
    }

    @Test
    void roundTripExampleOnTheLiveService() throws Exception {
        WorkflowRun b = run(job("examples/round-trip/Jenkinsfile"));
        r.assertLogContains("greet.sh says: Xin chao, round-trip!", b);
        r.assertLogContains("status=PASS", b);
        assertReleased();
    }

    /**
     * Two builds share state in one persistent sandbox on the real service, then the sandbox is deleted. Needs a few
     * minutes: a real stop takes about a minute and a start about 15 seconds. Always cleans up the sandbox it made.
     */
    @Test
    void persistentSandboxKeepsStateAcrossBuildsOnTheLiveService() throws Exception {
        String name = "live-" + Long.toString(System.currentTimeMillis(), 36);
        String displayName = "jkp-" + name;
        WorkflowJob job = job("examples/persistent-sandbox/Jenkinsfile");
        // define the parameters up front so that even build 1 gets the unique name (the example would otherwise
        // fall back to its default name 'demo-project' on a job's very first build)
        job.addProperty(new hudson.model.ParametersDefinitionProperty(
                new hudson.model.StringParameterDefinition("SANDBOX_NAME", "demo-project"),
                new hudson.model.BooleanParameterDefinition("DELETE_SANDBOX", false, "")));
        try {
            WorkflowRun first = r.assertBuildStatusSuccess(job.scheduleBuild2(0, new ParametersAction(
                    new StringParameterValue("SANDBOX_NAME", name), new BooleanParameterValue("DELETE_SANDBOX", false))));
            System.out.println("=== LIVE PERSISTENT BUILD 1\n" + JenkinsRule.getLog(first));
            r.assertLogContains("counter=1", first);
            r.assertLogContains("Created persistent sandbox " + displayName, first);
            WorkflowRun second = r.assertBuildStatusSuccess(job.scheduleBuild2(0, new ParametersAction(
                    new StringParameterValue("SANDBOX_NAME", name), new BooleanParameterValue("DELETE_SANDBOX", false))));
            System.out.println("=== LIVE PERSISTENT BUILD 2\n" + JenkinsRule.getLog(second));
            r.assertLogContains("Reusing persistent sandbox " + displayName, second);
            r.assertLogContains("reusing the sandbox that was set up in build 1", second);
            r.assertLogContains("counter=2", second);
            r.assertLogNotContains(System.getenv("DOCKER_PAT"), second);

            WorkflowRun third = r.assertBuildStatusSuccess(job.scheduleBuild2(0, new ParametersAction(
                    new StringParameterValue("SANDBOX_NAME", name), new BooleanParameterValue("DELETE_SANDBOX", true))));
            System.out.println("=== LIVE PERSISTENT BUILD 3\n" + JenkinsRule.getLog(third));
            r.assertLogContains("counter=3", third);
            r.assertLogContains("delete: deleted; status now: absent", third);
        } finally {
            // never leave a stored sandbox behind, even when an assertion above failed
            SandboxAccount account = DockerSandboxGlobalConfiguration.get().getAccounts().get(0);
            var api = SandboxBackend.DEFAULT.management(account);
            for (var sandbox : api.listAllSandboxes()) {
                if (displayName.equals(sandbox.displayName())) {
                    api.deleteSandbox(sandbox.id(), true);
                }
            }
        }
        for (var sandbox : SandboxBackend.DEFAULT.management(DockerSandboxGlobalConfiguration.get().getAccounts().get(0))
                .listAllSandboxes()) {
            org.junit.jupiter.api.Assertions.assertNotEquals(displayName, sandbox.displayName(), "sandbox leaked");
        }
        assertReleased();
    }

    @Test
    void opencodeWithARealModel() throws Exception {
        String key = System.getenv("LLM_API_KEY");
        String model = System.getenv("LLM_MODEL");
        assumeTrue(key != null && !key.isBlank() && model != null && !model.isBlank(), "LLM_API_KEY / LLM_MODEL not set");
        String provider = envOr("LLM_PROVIDER", "anthropic-compatible");
        WorkflowJob job = job("examples/opencode-byo-model/Jenkinsfile");
        r.buildAndAssertSuccess(job); // first build defines the parameters (dry-run)

        WorkflowRun b = r.assertBuildStatusSuccess(job.scheduleBuild2(0, new ParametersAction(
                new StringParameterValue("PROVIDER", provider),
                new StringParameterValue("MODEL", model),
                new StringParameterValue("BASE_URL", envOr("LLM_BASE_URL", "")),
                new PasswordParameterValue("API_KEY", key))));
        System.out.println("=== LIVE OPENCODE LOG\n" + JenkinsRule.getLog(b));
        r.assertLogContains("API key provided: yes", b);
        r.assertLogContains("PASS: add 2 3 = 5", b);
        r.assertLogNotContains(key, b);
        r.assertLogNotContains(System.getenv("DOCKER_PAT"), b);
        assertReleased();
    }

    private WorkflowJob job(String path) throws Exception {
        WorkflowJob job = r.createProject(WorkflowJob.class);
        job.setDefinition(new CpsFlowDefinition(Files.readString(Path.of(path)), true));
        return job;
    }

    private WorkflowRun run(WorkflowJob job) throws Exception {
        WorkflowRun b = r.buildAndAssertSuccess(job);
        System.out.println("=== LIVE LOG " + job.getName() + "\n" + JenkinsRule.getLog(b));
        return b;
    }

    private void assertReleased() {
        assertEquals(0, DockerSandboxGlobalConfiguration.get().getPool().stateOf("acc-live").getActive());
    }

    private static String envOr(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }
}
