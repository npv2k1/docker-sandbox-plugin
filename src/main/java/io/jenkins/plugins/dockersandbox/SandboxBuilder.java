package io.jenkins.plugins.dockersandbox;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractProject;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import jenkins.tasks.SimpleBuildStep;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

/** Freestyle build step "Run script in Docker Cloud Sandbox" (same options as the {@code sandboxRun} step). */
public class SandboxBuilder extends Builder implements SimpleBuildStep {

    private final String script;
    private String account = "";
    private String accountLabel = "";
    private String kit = "";
    private String size = "";
    private int ttlMinutes = 0;
    private boolean uploadWorkspace = true;
    private String includes = "**/*";
    private String excludes = "";
    private String artifacts = "";
    private int timeoutMinutes = 60;
    private boolean keepSandbox = false;
    private boolean returnStatus = false;
    private boolean allowEmptyArtifacts = true;
    private String workDir = SandboxRunSpec.DEFAULT_WORK_DIR;
    private int maxUploadMb = 200;
    private String envText = "";

    @DataBoundConstructor
    public SandboxBuilder(@NonNull String script) {
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

    /** One {@code NAME=value} per line. */
    public String getEnvText() {
        return envText;
    }

    @DataBoundSetter
    public void setEnvText(String envText) {
        this.envText = envText == null ? "" : envText;
    }

    SandboxRunSpec toSpec() {
        List<String> env = new ArrayList<>();
        for (String line : envText.split("\\R")) {
            if (!line.isBlank()) {
                env.add(line.trim());
            }
        }
        return new SandboxRunSpec(script)
                .account(account)
                .accountLabel(accountLabel)
                .kit(kit)
                .size(size)
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
                .env(env);
    }

    @Override
    public void perform(
            @NonNull Run<?, ?> run,
            @NonNull FilePath workspace,
            @NonNull EnvVars env,
            @NonNull Launcher launcher,
            @NonNull TaskListener listener)
            throws InterruptedException, IOException {
        SandboxRunResult result = new SandboxJobRunner().run(run, workspace, listener, toSpec());
        if (result.getExitCode() != 0) {
            // only reachable with returnStatus=true: keep going but flag the build
            listener.getLogger().println("[docker-sandbox] script exited with code " + result.getExitCode()
                    + " (returnStatus=true): marking the build UNSTABLE");
            run.setResult(Result.UNSTABLE);
        }
    }

    @Extension
    @Symbol("sandboxBuild")
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }

        @NonNull
        @Override
        public String getDisplayName() {
            return "Run script in Docker Cloud Sandbox";
        }

        public ListBoxModel doFillSizeItems() {
            ListBoxModel items = new ListBoxModel();
            items.add("(global default)", "");
            for (SandboxSize s : SandboxSize.values()) {
                items.add(s.name().toLowerCase(java.util.Locale.ROOT));
            }
            return items;
        }

        public FormValidation doCheckScript(@QueryParameter String value) {
            return value == null || value.isBlank() ? FormValidation.error("Script is required") : FormValidation.ok();
        }

        public FormValidation doCheckTimeoutMinutes(@QueryParameter String value) {
            return FormValidation.validateNonNegativeInteger(value);
        }

        public FormValidation doCheckMaxUploadMb(@QueryParameter String value) {
            return FormValidation.validatePositiveInteger(value);
        }
    }
}
