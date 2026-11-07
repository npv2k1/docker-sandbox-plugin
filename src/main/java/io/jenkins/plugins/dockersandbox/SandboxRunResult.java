package io.jenkins.plugins.dockersandbox;

import java.io.Serializable;
import org.jenkinsci.plugins.scriptsecurity.sandbox.whitelists.Whitelisted;

/** Value returned by {@code sandboxRun}. */
public final class SandboxRunResult implements Serializable {

    private static final long serialVersionUID = 1L;

    private final int exitCode;
    private final String accountId;
    private final String sandboxId;
    private final String persistent;

    public SandboxRunResult(int exitCode, String accountId, String sandboxId) {
        this(exitCode, accountId, sandboxId, "");
    }

    public SandboxRunResult(int exitCode, String accountId, String sandboxId, String persistent) {
        this.exitCode = exitCode;
        this.accountId = accountId;
        this.sandboxId = sandboxId;
        this.persistent = persistent == null ? "" : persistent;
    }

    @Whitelisted
    public int getExitCode() {
        return exitCode;
    }

    @Whitelisted
    public String getAccountId() {
        return accountId;
    }

    @Whitelisted
    public String getSandboxId() {
        return sandboxId;
    }

    /** Name of the persistent sandbox that ran the script, or an empty string for a one-shot sandbox. */
    @Whitelisted
    public String getPersistent() {
        return persistent == null ? "" : persistent;
    }

    @Override
    public String toString() {
        return "SandboxRunResult[exitCode=" + exitCode + ", accountId=" + accountId + ", sandboxId=" + sandboxId
                + (getPersistent().isEmpty() ? "" : ", persistent=" + persistent) + "]";
    }
}
