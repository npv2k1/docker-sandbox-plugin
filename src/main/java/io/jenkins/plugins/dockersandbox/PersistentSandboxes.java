package io.jenkins.plugins.dockersandbox;

import hudson.AbortException;
import io.jenkins.plugins.dockersandbox.account.AccountSelector;
import io.jenkins.plugins.dockersandbox.account.SandboxAccount;
import io.jenkins.plugins.dockersandbox.api.DockerSandboxApi;
import io.jenkins.plugins.dockersandbox.api.SandboxApiException;
import io.jenkins.plugins.dockersandbox.api.model.Sandbox;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Lookup and management of long-lived ("persistent") sandboxes. A persistent sandbox is recognized by its display name
 * {@code jkp-<name>} and, as a safety net, only sandboxes carrying this Jenkins' {@code jenkins-instance} label are ever
 * returned, so nothing that belongs to somebody else can be stopped or deleted through here.
 */
public final class PersistentSandboxes {

    static final String DISPLAY_PREFIX = "jkp-";
    /** Label whose value is the persistent name; sandboxes that have it are never touched by the orphan cleanup. */
    static final String LABEL = "jenkins-persistent";
    static final String INSTANCE_LABEL = "jenkins-instance";
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,40}");

    private PersistentSandboxes() {}

    /** A found sandbox together with the account it lives on. */
    record Entry(SandboxAccount account, Sandbox sandbox) {}

    /** What {@code sandboxManage} can do. */
    enum Action {
        STATUS,
        START,
        STOP,
        DELETE;

        static Action parse(String value) throws AbortException {
            String v = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
            for (Action a : values()) {
                if (a.name().toLowerCase(Locale.ROOT).equals(v)) {
                    return a;
                }
            }
            throw new AbortException("Unknown action '" + (value == null ? "" : SandboxJobRunner.mask(value))
                    + "': expected status, start, stop or delete");
        }
    }

    static void validateName(String name) throws AbortException {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new AbortException("Invalid persistent sandbox name: use 1-40 letters, digits, '_' or '-'");
        }
    }

    static String displayName(String name) {
        return DISPLAY_PREFIX + name;
    }

    /** True for a sandbox of this Jenkins that is marked as persistent (used for the management page). */
    static boolean isPersistent(Sandbox sandbox, String instanceId) {
        Map<String, String> labels = sandbox.core() == null ? null : sandbox.core().labels();
        return labels != null && instanceId.equals(labels.get(INSTANCE_LABEL)) && labels.get(LABEL) != null;
    }

    /**
     * The sandbox named {@code jkp-<name>} of this Jenkins among {@code all}, ignoring ones that are being deleted.
     * A failed one is only returned when nothing healthy exists, so the caller can report it instead of creating a
     * second sandbox with the same name.
     */
    static Sandbox pick(List<Sandbox> all, String instanceId, String name) {
        Sandbox failed = null;
        for (Sandbox s : all) {
            Map<String, String> labels = s.core() == null ? null : s.core().labels();
            if (!displayName(name).equals(s.displayName())
                    || labels == null
                    || !instanceId.equals(labels.get(INSTANCE_LABEL))) {
                continue;
            }
            String status = s.status();
            if ("deleting".equals(status)) {
                continue;
            }
            if (Sandbox.FAILED.equals(status)) {
                if (failed == null) {
                    failed = s;
                }
                continue;
            }
            return s;
        }
        return failed;
    }

    /**
     * Looks the persistent sandbox up on every enabled account matching {@code selector}.
     *
     * @return the sandbox, or null if there is none
     * @throws AbortException if an account could not be checked and nothing was found elsewhere: creating a new
     *     sandbox then could duplicate one that exists on the unreachable account
     */
    static Entry find(
            DockerSandboxGlobalConfiguration config,
            SandboxBackend backend,
            AccountSelector selector,
            String name,
            Consumer<String> log)
            throws AbortException {
        String instance = config.getInstanceId();
        List<String> failures = new ArrayList<>();
        Entry failedEntry = null;
        int matching = 0;
        for (SandboxAccount account : config.getAccounts()) {
            if (!account.isEnabled() || !selector.matches(account)) {
                continue;
            }
            matching++;
            try {
                DockerSandboxApi api = backend.management(account);
                Sandbox found = pick(api.listAllSandboxes(), instance, name);
                if (found == null) {
                    continue;
                }
                if (!Sandbox.FAILED.equals(found.status())) {
                    return new Entry(account, found);
                }
                if (failedEntry == null) {
                    failedEntry = new Entry(account, found);
                }
            } catch (IOException | RuntimeException e) {
                failures.add(account.getId());
                log.accept("WARNING: could not list sandboxes of account '" + account.getId() + "': "
                        + SandboxJobRunner.mask(e.getMessage()));
            }
        }
        if (matching == 0) {
            throw new AbortException("No enabled Docker sandbox account matches " + selector
                    + ". Check Manage Jenkins > System > Docker Cloud Sandboxes.");
        }
        if (failedEntry != null) {
            return failedEntry;
        }
        if (!failures.isEmpty()) {
            throw new AbortException("Cannot tell whether persistent sandbox '" + name + "' exists: account(s) "
                    + failures + " could not be checked. Not creating a possible duplicate; fix the account or "
                    + "pin another one with 'account'.");
        }
        return null;
    }

    /** One row of the management page. */
    public record Row(String accountId, String name, String sandboxId, String status, String createdAt) {
        // Jelly reads bean getters, not record accessors
        public String getAccountId() {
            return accountId;
        }

        public String getName() {
            return name;
        }

        public String getSandboxId() {
            return sandboxId;
        }

        public String getStatus() {
            return status;
        }

        public String getCreatedAt() {
            return createdAt;
        }
    }

    /** All persistent sandboxes of this Jenkins on all enabled accounts; per-account errors are added to {@code errors}. */
    static List<Row> list(DockerSandboxGlobalConfiguration config, SandboxBackend backend, List<String> errors) {
        List<Row> rows = new ArrayList<>();
        String instance = config.getInstanceId();
        for (SandboxAccount account : config.getAccounts()) {
            if (!account.isEnabled()) {
                continue;
            }
            try {
                for (Sandbox s : backend.management(account).listAllSandboxes()) {
                    if (isPersistent(s, instance) && !"deleting".equals(s.status())) {
                        rows.add(new Row(
                                account.getId(),
                                s.core().labels().get(LABEL),
                                s.id(),
                                s.status(),
                                s.core().createdAt() == null ? "" : s.core().createdAt()));
                    }
                }
            } catch (IOException | RuntimeException e) {
                errors.add("Account " + account.getId() + ": " + SandboxJobRunner.mask(e.getMessage()));
            }
        }
        return rows;
    }

    /**
     * Runs a management action on the persistent sandbox {@code name}. Everything except {@code status} takes the
     * per-name lock first, so it cannot pull a sandbox away from a running build.
     *
     * @param accountId restrict the lookup to this account, or blank for all
     * @param owner shown to builds that have to wait for the lock
     * @param lockTimeout how long to wait for the lock
     * @return the resulting status: {@code status} gives the current one or {@code absent}, {@code start} gives
     *     {@code running}, {@code stop} gives {@code stopping} (or {@code stopped}), {@code delete} gives
     *     {@code deleted} (or {@code absent} if there was nothing to delete)
     */
    static String manage(
            DockerSandboxGlobalConfiguration config,
            SandboxBackend backend,
            String name,
            Action action,
            String accountId,
            String owner,
            Duration lockTimeout,
            Consumer<String> log)
            throws IOException, InterruptedException {
        validateName(name);
        AccountSelector selector = AccountSelector.of(accountId, "");
        PersistentSandboxLocks.Hold hold = null;
        try {
            if (action != Action.STATUS) {
                hold = PersistentSandboxLocks.acquire(
                        PersistentSandboxLocks.key(config.getInstanceId(), name), name, owner, lockTimeout, log);
            }
            Entry entry = find(config, backend, selector, name, log);
            if (entry == null) {
                if (action == Action.START) {
                    throw new AbortException("Persistent sandbox '" + name + "' does not exist");
                }
                return "absent";
            }
            DockerSandboxApi api = backend.management(entry.account());
            String id = entry.sandbox().id();
            try {
                Sandbox current = api.getSandbox(id);
                switch (action) {
                    case STATUS:
                        return current.status();
                    case START:
                        log.accept("Starting persistent sandbox " + displayName(name) + " (" + id + ") on account "
                                + entry.account().getId());
                        SandboxJobRunner.ensureRunning(api, current, log, "");
                        return Sandbox.RUNNING;
                    case STOP:
                        if ("stopped".equals(current.status()) || "stopping".equals(current.status())) {
                            return current.status();
                        }
                        if (!Sandbox.RUNNING.equals(current.status())) {
                            throw new AbortException("Persistent sandbox '" + name + "' is " + current.status()
                                    + " and cannot be stopped now");
                        }
                        log.accept("Stopping persistent sandbox " + displayName(name) + " (" + id
                                + ") on account " + entry.account().getId() + " (data kept)");
                        Sandbox stopped = api.stopSandbox(id);
                        return stopped == null || stopped.status().isEmpty() ? "stopping" : stopped.status();
                    case DELETE:
                    default:
                        log.accept("Deleting persistent sandbox " + displayName(name) + " (" + id + ") on account "
                                + entry.account().getId());
                        api.deleteSandbox(id, true);
                        return "deleted";
                }
            } catch (SandboxApiException e) {
                if (e.isNotFound()) {
                    return "absent";
                }
                throw new AbortException("Persistent sandbox '" + name + "' " + action.name().toLowerCase(Locale.ROOT)
                        + " failed: " + SandboxJobRunner.mask(e.getMessage()));
            }
        } finally {
            if (hold != null) {
                hold.close();
            }
        }
    }
}
