package io.jenkins.plugins.dockersandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.CredentialsStore;
import com.cloudbees.plugins.credentials.domains.Domain;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import io.jenkins.plugins.dockersandbox.account.SandboxAccount;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/** Runs the Jenkinsfiles under {@code examples/} unchanged against the fake Docker cloud, so they stay correct. */
@WithJenkins
class ExamplePipelineTest {

    private FakeDockerCloud cloud;

    @AfterEach
    void tearDown() throws Exception {
        SandboxJobRunner.setTiming(null);
        if (cloud != null) {
            cloud.close();
        }
    }

    /** One fake account and fast polling, like a minimal real setup. */
    private void setUpCloud(JenkinsRule r) throws Exception {
        cloud = new FakeDockerCloud();
        SandboxJobRunner.setTiming(new SandboxJobRunner.Timing(
                Duration.ofMillis(20),
                Duration.ofSeconds(30),
                Duration.ofMillis(20),
                Duration.ofMillis(100),
                Duration.ofMillis(100),
                Duration.ofMillis(50)));
        cloud.account("user1");
        CredentialsStore store = CredentialsProvider.lookupStores(Jenkins.get()).iterator().next();
        store.addCredentials(Domain.global(),
                new UsernamePasswordCredentialsImpl(CredentialsScope.GLOBAL, "cred-1", "docker", "user1", "dckr_pat_EXAMPLE"));
        SandboxAccount account = new SandboxAccount("acc-1");
        account.setCredentialsId("cred-1");
        account.setApiUrl(cloud.apiUrl());
        account.setAuthUrl(cloud.authUrl());
        DockerSandboxGlobalConfiguration.get().setAccounts(List.of(account));

    }

    private WorkflowRun run(JenkinsRule r, String path) throws Exception {
        WorkflowJob job = r.createProject(WorkflowJob.class);
        job.setDefinition(new CpsFlowDefinition(Files.readString(Path.of(path)), true));
        return r.buildAndAssertSuccess(job);
    }

    @Test
    void roundTripExampleCopiesEditsTestsAndPullsFilesBack(JenkinsRule r) throws Exception {
        setUpCloud(r);
        WorkflowRun b = run(r, "examples/round-trip/Jenkinsfile");

        r.assertLogContains("greet.sh says: Xin chao, round-trip!", b);
        r.assertLogContains("status=PASS", b);
        r.assertLogContains("edited_by=sandbox", b);
        // only app/** was uploaded
        FakeDockerCloud.Sandbox sb = cloud.sandbox("sb1");
        assertFalse(Files.exists(sb.root.resolve("home/agent/workspace/notes")), "excluded files must not be uploaded");
        // the modified files came back and were archived
        assertTrue(b.getArtifactManager().root().child("app/config.properties").isFile());
        assertTrue(b.getArtifactManager().root().child("report/test-report.txt").isFile());
        assertEquals(0, cloud.liveSandboxes(), "sandbox must be deleted");
    }

    @Test
    void aiAgentExampleDryRunEditsInSandboxAndPullsChangesAndPatchBack(JenkinsRule r) throws Exception {
        setUpCloud(r);
        // first build: parameters are not defined yet, so the example falls back to the key-less 'dry-run' agent
        WorkflowRun b = run(r, "examples/ai-agent/Jenkinsfile");

        r.assertLogContains("dry-run: fixed src/math.sh", b);
        r.assertLogContains("PASS: add 2 3 = 5", b);
        r.assertLogContains("Changes made by dry-run", b);
        assertEquals("docker/sandbox-templates:shell", cloud.sandbox("sb1").createRequest.path("imageRef").asText());
        // the fixed file and the patch came back into the workspace and were archived
        String math = Files.readString(Path.of(r.jenkins.getWorkspaceFor(b.getParent()).child("src/math.sh").getRemote()));
        assertTrue(math.contains("a + b"), math);
        assertTrue(b.getArtifactManager().root().child("agent.patch").isFile());
        assertEquals(0, cloud.liveSandboxes(), "sandbox must be deleted");
    }
}
