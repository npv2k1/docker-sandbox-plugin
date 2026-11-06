package io.jenkins.plugins.dockersandbox;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.Extension;
import hudson.model.ManagementLink;
import io.jenkins.plugins.dockersandbox.account.AccountApiFactory;
import io.jenkins.plugins.dockersandbox.account.AccountState;
import io.jenkins.plugins.dockersandbox.account.SandboxAccount;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.HttpRedirect;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

/** Manage Jenkins > Docker Cloud Sandboxes: live usage per account and a connection test. */
@Extension
public class DockerSandboxManagementLink extends ManagementLink {

    private final transient Map<String, String> lastTest = new ConcurrentHashMap<>();

    /** One table row. */
    public static final class Row {
        private final SandboxAccount account;
        private final AccountState state;
        private final String lastTest;

        Row(SandboxAccount account, AccountState state, String lastTest) {
            this.account = account;
            this.state = state;
            this.lastTest = lastTest;
        }

        public String getId() {
            return account.getId();
        }

        public String getDisplayName() {
            return account.getDisplayName();
        }

        public boolean isEnabled() {
            return account.isEnabled();
        }

        public int getActive() {
            return state.getActive();
        }

        public int getMax() {
            return account.getMaxConcurrent();
        }

        public String getLabels() {
            return account.getLabels();
        }

        public long getTotalRuns() {
            return state.getTotalRuns();
        }

        public long getTotalFailures() {
            return state.getTotalFailures();
        }

        public String getLastError() {
            return state.getLastError();
        }

        /** Seconds left in the cooldown, 0 if the account is usable. */
        public long getCooldownSeconds() {
            return Math.max(0, (state.getCooldownUntilMillis() - System.currentTimeMillis()) / 1000);
        }

        public String getLastTest() {
            return lastTest;
        }
    }

    @Override
    public String getIconFileName() {
        return "symbol-settings";
    }

    @Override
    public String getUrlName() {
        return "docker-sandbox";
    }

    @Override
    public String getDisplayName() {
        return "Docker Cloud Sandboxes";
    }

    @Override
    public String getDescription() {
        return "Usage and health of the Docker accounts used for cloud sandbox builds";
    }

    @NonNull
    @Override
    public Category getCategory() {
        return Category.STATUS;
    }

    /** For Jelly: one row per configured account. */
    public List<Row> getRows() {
        DockerSandboxGlobalConfiguration config = DockerSandboxGlobalConfiguration.get();
        List<Row> rows = new ArrayList<>();
        for (SandboxAccount a : config.getAccounts()) {
            rows.add(new Row(a, config.getPool().stateOf(a.getId()), lastTest.get(a.getId())));
        }
        return rows;
    }

    public String getStrategyName() {
        return DockerSandboxGlobalConfiguration.get().getStrategy().getDisplayName();
    }

    /** Token exchange + list sandboxes for one account; the outcome is shown in the table afterwards. */
    @POST
    public HttpResponse doTestConnection(@QueryParameter("accountId") @Nullable String accountId) throws IOException {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        SandboxAccount account = DockerSandboxGlobalConfiguration.get().findAccount(accountId);
        if (account != null) {
            String outcome;
            try {
                outcome = AccountApiFactory.testConnection(account);
            } catch (IOException e) {
                outcome = "FAILED: " + SandboxJobRunner.mask(e.getMessage());
            }
            lastTest.put(account.getId(), outcome);
        }
        return new HttpRedirect(".");
    }
}
