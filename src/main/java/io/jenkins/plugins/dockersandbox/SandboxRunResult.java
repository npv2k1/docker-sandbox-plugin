package io.jenkins.plugins.dockersandbox;

import java.io.Serializable;
import org.jenkinsci.plugins.scriptsecurity.sandbox.whitelists.Whitelisted;

/** Value returned by {@code sandboxRun}. */
public final class SandboxRunResult implements Serializable {

    private static final long serialVersionUID = 1L;

    private final int exitCode;
    private final String accountId;
    private final String sandboxId;

    public SandboxRunResult(int exitCode, String accountId, String sandboxId) {
        this.exitCode = exitCode;
        this.accountId = accountId;
        this.sandboxId = sandboxId;
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

    @Override
    public String toString() {
        return "SandboxRunResult[exitCode=" + exitCode + ", accountId=" + accountId + ", sandboxId=" + sandboxId + "]";
    }
}
