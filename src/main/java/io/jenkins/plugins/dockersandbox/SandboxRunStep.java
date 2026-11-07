package io.jenkins.plugins.dockersandbox;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.FilePath;
import hudson.model.Run;
import hudson.model.TaskListener;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.steps.SynchronousNonBlockingStepExecution;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

/** Pipeline step {@code sandboxRun}: runs a script in a Docker Cloud Sandbox and returns a {@link SandboxRunResult}. */
public class SandboxRunStep extends Step {

    private final String script;
    private String account = "";
    private String accountLabel = "";
    private String kit = "";
    private String size = "";
    private String image = "";
    private String imageCommand = "";
    private int ttlMinutes = 0;
    private boolean uploadWorkspace = true;
    private String includes = "**/*";
    private String excludes = "";
    private String artifacts = "";
    private int timeoutMinutes = 60;
    private boolean keepSandbox = false;
    private boolean returnStatus = false;
    private boolean allowEmptyArtifacts = true;
    private String workDir = "";
    private int maxUploadMb = 200;
    private String persistent = "";
    private String afterRun = "";
    private List<String> env = new ArrayList<>();

    @DataBoundConstructor
    public SandboxRunStep(@NonNull String script) {
        this.script = script;
    }

    public String getScript() {
        return script;
    }

    public String getAccount() {
        return account;
    }

    @DataBoundSetter
    public void setAccount(String account) {
        this.account = account;
    }

    public String getAccountLabel() {
        return accountLabel;
    }

    @DataBoundSetter
    public void setAccountLabel(String accountLabel) {
        this.accountLabel = accountLabel;
    }

    public String getKit() {
        return kit;
    }

    @DataBoundSetter
    public void setKit(String kit) {
        this.kit = kit;
    }

    public String getImage() {
        return image;
    }

    @DataBoundSetter
    public void setImage(String image) {
        this.image = image;
    }

    public String getImageCommand() {
        return imageCommand;
    }

    @DataBoundSetter
    public void setImageCommand(String imageCommand) {
        this.imageCommand = imageCommand;
    }

    public String getSize() {
        return size;
    }

    @DataBoundSetter
    public void setSize(String size) {
        this.size = size;
    }

    public int getTtlMinutes() {
        return ttlMinutes;
    }

    @DataBoundSetter
    public void setTtlMinutes(int ttlMinutes) {
        this.ttlMinutes = ttlMinutes;
    }

    public boolean isUploadWorkspace() {
        return uploadWorkspace;
    }

    @DataBoundSetter
    public void setUploadWorkspace(boolean uploadWorkspace) {
        this.uploadWorkspace = uploadWorkspace;
    }

    public String getIncludes() {
        return includes;
    }

    @DataBoundSetter
    public void setIncludes(String includes) {
        this.includes = includes;
    }

    public String getExcludes() {
        return excludes;
    }

    @DataBoundSetter
    public void setExcludes(String excludes) {
        this.excludes = excludes;
    }

    public String getArtifacts() {
        return artifacts;
    }

    @DataBoundSetter
    public void setArtifacts(String artifacts) {
        this.artifacts = artifacts;
    }

    public int getTimeoutMinutes() {
        return timeoutMinutes;
    }

    @DataBoundSetter
    public void setTimeoutMinutes(int timeoutMinutes) {
        this.timeoutMinutes = timeoutMinutes;
    }

    public boolean isKeepSandbox() {
        return keepSandbox;
    }

    @DataBoundSetter
    public void setKeepSandbox(boolean keepSandbox) {
        this.keepSandbox = keepSandbox;
    }

    public boolean isReturnStatus() {
        return returnStatus;
    }

    @DataBoundSetter
    public void setReturnStatus(boolean returnStatus) {
        this.returnStatus = returnStatus;
    }

    public boolean isAllowEmptyArtifacts() {
        return allowEmptyArtifacts;
    }

    @DataBoundSetter
    public void setAllowEmptyArtifacts(boolean allowEmptyArtifacts) {
        this.allowEmptyArtifacts = allowEmptyArtifacts;
    }

    public String getWorkDir() {
        return workDir;
    }

    @DataBoundSetter
    public void setWorkDir(String workDir) {
        this.workDir = workDir;
    }

    public int getMaxUploadMb() {
        return maxUploadMb;
    }

    @DataBoundSetter
    public void setMaxUploadMb(int maxUploadMb) {
        this.maxUploadMb = maxUploadMb;
    }

    public List<String> getEnv() {
        return env;
    }

    @DataBoundSetter
    public void setEnv(List<String> env) {
        this.env = env == null ? new ArrayList<>() : new ArrayList<>(env);
    }

    public String getPersistent() {
        return persistent;
    }

    @DataBoundSetter
    public void setPersistent(String persistent) {
        this.persistent = persistent == null ? "" : persistent;
    }

    public String getAfterRun() {
        return afterRun;
    }

    @DataBoundSetter
    public void setAfterRun(String afterRun) {
        this.afterRun = afterRun == null ? "" : afterRun;
    }

    SandboxRunSpec toSpec() {
        return new SandboxRunSpec(script)
                .account(account)
                .accountLabel(accountLabel)
                .kit(kit)
                .size(size)
                .image(image)
                .imageCommand(imageCommand)
                .ttlMinutes(ttlMinutes)
                .uploadWorkspace(uploadWorkspace)
                .includes(includes)
                .excludes(excludes)
                .artifacts(artifacts)
                .timeoutMinutes(timeoutMinutes)
                .keepSandbox(keepSandbox)
                .returnStatus(returnStatus)
                .allowEmptyArtifacts(allowEmptyArtifacts)
                .workDir(workDir)
                .maxUploadMb(maxUploadMb)
                .persistent(persistent)
                .afterRun(afterRun)
                .env(env);
    }

    @Override
    public StepExecution start(StepContext context) throws Exception {
        return new Execution(context, toSpec());
    }

    private static final class Execution extends SynchronousNonBlockingStepExecution<SandboxRunResult> {

        private static final long serialVersionUID = 1L;
        private final transient SandboxRunSpec spec;

        Execution(StepContext context, SandboxRunSpec spec) {
            super(context);
            this.spec = spec;
        }

        @Override
        protected SandboxRunResult run() throws Exception {
            StepContext context = getContext();
            Run<?, ?> run = context.get(Run.class);
            TaskListener listener = context.get(TaskListener.class);
            FilePath workspace = context.get(FilePath.class);
            if (spec == null) {
                throw new IOException("sandboxRun cannot be resumed after a Jenkins restart");
            }
            return new SandboxJobRunner().run(run, workspace, listener, spec);
        }
    }

    @Extension
    public static final class DescriptorImpl extends StepDescriptor {

        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return Set.of(Run.class, TaskListener.class, FilePath.class);
        }

        @Override
        public String getFunctionName() {
            return "sandboxRun";
        }

        @NonNull
        @Override
        public String getDisplayName() {
            return "Run a script in a Docker Cloud Sandbox";
        }
    }
}
