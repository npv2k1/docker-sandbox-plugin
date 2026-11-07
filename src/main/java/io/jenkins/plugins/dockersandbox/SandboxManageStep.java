package io.jenkins.plugins.dockersandbox;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Run;
import hudson.model.TaskListener;
import java.io.PrintStream;
import java.time.Duration;
import java.util.Set;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.steps.SynchronousNonBlockingStepExecution;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

/**
 * Pipeline step {@code sandboxManage}: {@code status} (returns the status or {@code absent}), {@code start},
 * {@code stop} or {@code delete} on a persistent sandbox created by {@code sandboxRun(persistent: ...)}.
 */
public class SandboxManageStep extends Step {

    private final String name;
    private final String action;
    private String account = "";

    @DataBoundConstructor
    public SandboxManageStep(@NonNull String name, @NonNull String action) {
        this.name = name;
        this.action = action;
    }

    public String getName() {
        return name;
    }

    public String getAction() {
        return action;
    }

    public String getAccount() {
        return account;
    }

    @DataBoundSetter
    public void setAccount(String account) {
        this.account = account == null ? "" : account;
    }

    @Override
    public StepExecution start(StepContext context) {
        return new Execution(context, name, action, account);
    }

    private static final class Execution extends SynchronousNonBlockingStepExecution<String> {

        private static final long serialVersionUID = 1L;
        private final String name;
        private final String action;
        private final String account;

        Execution(StepContext context, String name, String action, String account) {
            super(context);
            this.name = name;
            this.action = action;
            this.account = account;
        }

        @Override
        protected String run() throws Exception {
            StepContext context = getContext();
            Run<?, ?> run = context.get(Run.class);
            PrintStream logger = context.get(TaskListener.class).getLogger();
            DockerSandboxGlobalConfiguration config = DockerSandboxGlobalConfiguration.get();
            PersistentSandboxes.Action parsed = PersistentSandboxes.Action.parse(action);
            String status = PersistentSandboxes.manage(
                    config,
                    SandboxBackend.DEFAULT,
                    name,
                    parsed,
                    account,
                    run.getExternalizableId(),
                    Duration.ofMinutes(config.getLeaseTimeoutMinutes()),
                    m -> {
                        logger.println("[docker-sandbox] " + SandboxJobRunner.mask(m).replace("\r", ""));
                        logger.flush();
                    });
            logger.println("[docker-sandbox] persistent sandbox '" + name + "': " + status);
            return status;
        }
    }

    @Extension
    public static final class DescriptorImpl extends StepDescriptor {

        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return Set.of(Run.class, TaskListener.class);
        }

        @Override
        public String getFunctionName() {
            return "sandboxManage";
        }

        @NonNull
        @Override
        public String getDisplayName() {
            return "Manage a persistent Docker Cloud Sandbox (status, start, stop, delete)";
        }
    }
}
