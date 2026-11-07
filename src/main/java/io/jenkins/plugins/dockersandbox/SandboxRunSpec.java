package io.jenkins.plugins.dockersandbox;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Everything a single sandbox run needs; shared by the Pipeline step and the freestyle builder. */
public final class SandboxRunSpec {

    public static final String DEFAULT_WORK_DIR = "/home/agent/workspace";
    /** Work directory default in image mode: writable for non-root image users. */
    public static final String DEFAULT_IMAGE_WORK_DIR = "/tmp/workspace";
    public static final String DEFAULT_IMAGE_COMMAND = "sleep infinity";

    private final String script;
    private String account = "";
    private String accountLabel = "";
    private String kit;
    private String size;
    private String image;
    private String imageCommand = "";
    private int ttlMinutes;
    private boolean uploadWorkspace = true;
    private String includes = "**/*";
    private String excludes = "";
    private String artifacts = "";
    private List<String> env = new ArrayList<>();
    private int timeoutMinutes = 60;
    private boolean keepSandbox;
    private boolean returnStatus;
    private boolean allowEmptyArtifacts = true;
    private String workDir;
    private int maxUploadMb = 200;
    private String persistent = "";
    private String afterRun = "";

    public SandboxRunSpec(String script) {
        this.script = script == null ? "" : script;
    }

    public String getScript() {
        return script;
    }

    public String getAccount() {
        return account;
    }

    public SandboxRunSpec account(String v) {
        this.account = nz(v);
        return this;
    }

    public String getAccountLabel() {
        return accountLabel;
    }

    public SandboxRunSpec accountLabel(String v) {
        this.accountLabel = nz(v);
        return this;
    }

    /** Kit name, or null to use the global default. */
    public String getKit() {
        return kit;
    }

    public SandboxRunSpec kit(String v) {
        this.kit = v == null || v.isBlank() ? null : v.trim();
        return this;
    }

    /** Image reference ({@code node:22} or managed {@code images/<id>}), or null to fall back to kit/global defaults. */
    public String getImage() {
        return image;
    }

    public SandboxRunSpec image(String v) {
        this.image = v == null || v.isBlank() ? null : v.trim();
        return this;
    }

    /** Keep-alive command run as {@code sh -c}; empty means {@code sleep infinity}. */
    public String getImageCommand() {
        return imageCommand;
    }

    public SandboxRunSpec imageCommand(String v) {
        this.imageCommand = nz(v);
        return this;
    }

    /** Size name, or null to use the global default. */
    public String getSize() {
        return size;
    }

    public SandboxRunSpec size(String v) {
        this.size = v == null || v.isBlank() ? null : v.trim();
        return this;
    }

    /** Sandbox lifetime in minutes; 0 means the global default. */
    public int getTtlMinutes() {
        return ttlMinutes;
    }

    public SandboxRunSpec ttlMinutes(int v) {
        this.ttlMinutes = Math.max(0, v);
        return this;
    }

    public boolean isUploadWorkspace() {
        return uploadWorkspace;
    }

    public SandboxRunSpec uploadWorkspace(boolean v) {
        this.uploadWorkspace = v;
        return this;
    }

    public String getIncludes() {
        return includes;
    }

    public SandboxRunSpec includes(String v) {
        this.includes = v == null || v.isBlank() ? "**/*" : v.trim();
        return this;
    }

    public String getExcludes() {
        return excludes;
    }

    public SandboxRunSpec excludes(String v) {
        this.excludes = nz(v);
        return this;
    }

    public String getArtifacts() {
        return artifacts;
    }

    public SandboxRunSpec artifacts(String v) {
        this.artifacts = nz(v);
        return this;
    }

    public List<String> getEnv() {
        return Collections.unmodifiableList(env);
    }

    public SandboxRunSpec env(List<String> v) {
        this.env = v == null ? new ArrayList<>() : new ArrayList<>(v);
        return this;
    }

    /** Wall-clock limit for the script in minutes; 0 means unlimited. */
    public int getTimeoutMinutes() {
        return timeoutMinutes;
    }

    public SandboxRunSpec timeoutMinutes(int v) {
        this.timeoutMinutes = Math.max(0, v);
        return this;
    }

    public boolean isKeepSandbox() {
        return keepSandbox;
    }

    public SandboxRunSpec keepSandbox(boolean v) {
        this.keepSandbox = v;
        return this;
    }

    public boolean isReturnStatus() {
        return returnStatus;
    }

    public SandboxRunSpec returnStatus(boolean v) {
        this.returnStatus = v;
        return this;
    }

    public boolean isAllowEmptyArtifacts() {
        return allowEmptyArtifacts;
    }

    public SandboxRunSpec allowEmptyArtifacts(boolean v) {
        this.allowEmptyArtifacts = v;
        return this;
    }

    /** Explicitly chosen work directory, or null when the runner should pick the default for the mode. */
    public String getWorkDir() {
        return workDir;
    }

    public SandboxRunSpec workDir(String v) {
        this.workDir = v == null || v.isBlank() ? null : v.trim();
        return this;
    }

    public int getMaxUploadMb() {
        return maxUploadMb;
    }

    public SandboxRunSpec maxUploadMb(int v) {
        this.maxUploadMb = Math.max(1, v);
        return this;
    }

    /** Name of a long-lived sandbox reused across builds, or empty for a one-shot sandbox. */
    public String getPersistent() {
        return persistent;
    }

    public SandboxRunSpec persistent(String v) {
        this.persistent = nz(v);
        return this;
    }

    /** {@code stop}, {@code keep} or {@code delete}; empty means the default (see SandboxJobRunner#effectiveAfterRun). */
    public String getAfterRun() {
        return afterRun;
    }

    public SandboxRunSpec afterRun(String v) {
        this.afterRun = nz(v).toLowerCase(java.util.Locale.ROOT);
        return this;
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }
}
