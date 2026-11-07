package io.jenkins.plugins.dockersandbox;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.Extension;
import hudson.model.ManagementLink;
import io.jenkins.plugins.dockersandbox.account.AccountApiFactory;
import io.jenkins.plugins.dockersandbox.account.AccountState;
import io.jenkins.plugins.dockersandbox.account.SandboxAccount;
import java.io.IOException;
import java.time.Duration;
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
    private volatile transient String persistentMessage = "";

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

    public String getDefaultKit() {
        return DockerSandboxGlobalConfiguration.get().getDefaultKit();
    }

    public String getDefaultImage() {
        return DockerSandboxGlobalConfiguration.get().getDefaultImage();
    }

    public String getStrategyName() {
        return DockerSandboxGlobalConfiguration.get().getStrategy().getDisplayName();
    }

    /** Persistent sandboxes with the accounts that could not be listed. */
    public static final class PersistentView {
        private final List<PersistentSandboxes.Row> rows;
        private final List<String> errors;

        PersistentView(List<PersistentSandboxes.Row> rows, List<String> errors) {
            this.rows = rows;
            this.errors = errors;
        }

        public List<PersistentSandboxes.Row> getRows() {
            return rows;
        }

        public List<String> getErrors() {
            return errors;
        }
    }

    /** For Jelly: persistent sandboxes of this Jenkins on all accounts (queries the API on every page view). */
    public PersistentView getPersistent() {
        List<String> errors = new ArrayList<>();
        List<PersistentSandboxes.Row> rows =
                PersistentSandboxes.list(DockerSandboxGlobalConfiguration.get(), SandboxBackend.DEFAULT, errors);
        return new PersistentView(rows, errors);
    }

    public String getPersistentMessage() {
        return persistentMessage;
    }

    /** Stops a persistent sandbox (data kept). */
    @POST
    public HttpResponse doStopPersistent(
            @QueryParameter("accountId") @Nullable String accountId, @QueryParameter("name") @Nullable String name)
            throws IOException {
        return managePersistent(PersistentSandboxes.Action.STOP, accountId, name);
    }

    /** Deletes a persistent sandbox and its data. */
    @POST
    public HttpResponse doDeletePersistent(
            @QueryParameter("accountId") @Nullable String accountId, @QueryParameter("name") @Nullable String name)
            throws IOException {
        return managePersistent(PersistentSandboxes.Action.DELETE, accountId, name);
    }

    private HttpResponse managePersistent(PersistentSandboxes.Action action, String accountId, String name)
            throws IOException {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        DockerSandboxGlobalConfiguration config = DockerSandboxGlobalConfiguration.get();
        String verb = action.name().toLowerCase(java.util.Locale.ROOT);
        try {
            // fail at once if a build is inside the sandbox (zero lock wait); never blocks the request thread
            String result = PersistentSandboxes.manage(
                    config, SandboxBackend.DEFAULT, name, action, accountId, "Manage Jenkins", Duration.ZERO, m -> {});
            persistentMessage = "Persistent sandbox '" + name + "': " + verb + " -> " + result;
        } catch (IOException e) {
            persistentMessage = "Persistent sandbox '" + name + "': " + verb + " failed: "
                    + SandboxJobRunner.mask(e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            persistentMessage = "Persistent sandbox '" + name + "': " + verb + " interrupted";
        }
        return new HttpRedirect(".");
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
