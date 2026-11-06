package io.jenkins.plugins.dockersandbox;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.model.Descriptor;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import io.jenkins.plugins.dockersandbox.account.AccountPool;
import io.jenkins.plugins.dockersandbox.account.LoadBalancingStrategy;
import io.jenkins.plugins.dockersandbox.account.SandboxAccount;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import jenkins.model.GlobalConfiguration;
import net.sf.json.JSONObject;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest2;

/** Manage Jenkins > System > "Docker Cloud Sandboxes": accounts, balancing strategy and defaults. */
@Extension
@Symbol("dockerSandbox")
public class DockerSandboxGlobalConfiguration extends GlobalConfiguration {

    private List<SandboxAccount> accounts = new ArrayList<>();
    private LoadBalancingStrategy strategy = LoadBalancingStrategy.LEAST_LOADED;
    private String defaultKit = "shell";
    private String defaultSize = "small";
    private int defaultTtlMinutes = 60;
    private int leaseTimeoutMinutes = 30;
    private int cooldownMinutes = 5;
    private int maxFailoverAttempts = 3;
    private boolean orphanCleanupEnabled = true;
    private String instanceId = "";

    private transient volatile AccountPool pool;

    public DockerSandboxGlobalConfiguration() {
        load();
        if (instanceId == null || instanceId.isEmpty()) {
            instanceId = UUID.randomUUID().toString();
            save();
        }
    }

    @NonNull
    public static DockerSandboxGlobalConfiguration get() {
        return ExtensionList.lookupSingleton(DockerSandboxGlobalConfiguration.class);
    }

    /** The shared in-memory capacity tracker; survives configuration saves (state is keyed by account id). */
    @NonNull
    public AccountPool getPool() {
        AccountPool p = pool;
        if (p == null) {
            synchronized (this) {
                p = pool;
                if (p == null) {
                    p = new AccountPool(this::getAccounts);
                    pool = p;
                }
            }
        }
        return p;
    }

    @NonNull
    public synchronized List<SandboxAccount> getAccounts() {
        return accounts == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(accounts));
    }

    @DataBoundSetter
    public synchronized void setAccounts(List<SandboxAccount> accounts) {
        List<SandboxAccount> copy = accounts == null ? new ArrayList<>() : new ArrayList<>(accounts);
        Set<String> seen = new HashSet<>();
        for (SandboxAccount a : copy) {
            if (a.getId().isEmpty()) {
                throw new IllegalArgumentException("Every Docker sandbox account needs an ID");
            }
            if (!seen.add(a.getId())) {
                throw new IllegalArgumentException("Duplicate Docker sandbox account ID: " + a.getId());
            }
        }
        this.accounts = copy;
    }

    public SandboxAccount findAccount(String id) {
        for (SandboxAccount a : getAccounts()) {
            if (a.getId().equals(id)) {
                return a;
            }
        }
        return null;
    }

    @NonNull
    public LoadBalancingStrategy getStrategy() {
        return strategy == null ? LoadBalancingStrategy.LEAST_LOADED : strategy;
    }

    @DataBoundSetter
    public void setStrategy(LoadBalancingStrategy strategy) {
        this.strategy = strategy == null ? LoadBalancingStrategy.LEAST_LOADED : strategy;
    }

    @NonNull
    public String getDefaultKit() {
        return defaultKit == null ? "" : defaultKit;
    }

    @DataBoundSetter
    public void setDefaultKit(String defaultKit) {
        this.defaultKit = defaultKit == null ? "" : defaultKit.trim();
    }

    @NonNull
    public String getDefaultSize() {
        return defaultSize == null || defaultSize.isBlank() ? "small" : defaultSize;
    }

    @DataBoundSetter
    public void setDefaultSize(String defaultSize) {
        this.defaultSize = defaultSize == null ? "small" : defaultSize.trim();
    }

    public int getDefaultTtlMinutes() {
        return defaultTtlMinutes;
    }

    @DataBoundSetter
    public void setDefaultTtlMinutes(int defaultTtlMinutes) {
        this.defaultTtlMinutes = Math.max(1, defaultTtlMinutes);
    }

    public int getLeaseTimeoutMinutes() {
        return leaseTimeoutMinutes;
    }

    @DataBoundSetter
    public void setLeaseTimeoutMinutes(int leaseTimeoutMinutes) {
        this.leaseTimeoutMinutes = Math.max(1, leaseTimeoutMinutes);
    }

    public int getCooldownMinutes() {
        return cooldownMinutes;
    }

    @DataBoundSetter
    public void setCooldownMinutes(int cooldownMinutes) {
        this.cooldownMinutes = Math.max(0, cooldownMinutes);
    }

    public int getMaxFailoverAttempts() {
        return maxFailoverAttempts;
    }

    @DataBoundSetter
    public void setMaxFailoverAttempts(int maxFailoverAttempts) {
        this.maxFailoverAttempts = Math.max(1, maxFailoverAttempts);
    }

    public boolean isOrphanCleanupEnabled() {
        return orphanCleanupEnabled;
    }

    @DataBoundSetter
    public void setOrphanCleanupEnabled(boolean orphanCleanupEnabled) {
        this.orphanCleanupEnabled = orphanCleanupEnabled;
    }

    /** Stable id of this Jenkins instance; written into sandbox labels so orphan cleanup only touches our own. */
    @NonNull
    public String getInstanceId() {
        return instanceId == null ? "" : instanceId;
    }

    @Override
    public boolean configure(StaplerRequest2 req, JSONObject json) throws Descriptor.FormException {
        synchronized (this) {
            this.accounts = new ArrayList<>(); // an absent "accounts" key means the user removed them all
        }
        try {
            req.bindJSON(this, json);
        } catch (IllegalArgumentException e) {
            throw new Descriptor.FormException(e.getMessage(), "accounts");
        }
        save();
        return true;
    }

    public ListBoxModel doFillStrategyItems() {
        ListBoxModel items = new ListBoxModel();
        for (LoadBalancingStrategy s : LoadBalancingStrategy.values()) {
            items.add(s.getDisplayName(), s.name());
        }
        return items;
    }

    public ListBoxModel doFillDefaultSizeItems() {
        ListBoxModel items = new ListBoxModel();
        for (SandboxSize s : SandboxSize.values()) {
            items.add(s.name().toLowerCase(java.util.Locale.ROOT));
        }
        return items;
    }

    public FormValidation doCheckDefaultTtlMinutes(@QueryParameter String value) {
        return FormValidation.validatePositiveInteger(value);
    }

    public FormValidation doCheckLeaseTimeoutMinutes(@QueryParameter String value) {
        return FormValidation.validatePositiveInteger(value);
    }

    public FormValidation doCheckCooldownMinutes(@QueryParameter String value) {
        return FormValidation.validateNonNegativeInteger(value);
    }

    public FormValidation doCheckMaxFailoverAttempts(@QueryParameter String value) {
        return FormValidation.validatePositiveInteger(value);
    }
}
