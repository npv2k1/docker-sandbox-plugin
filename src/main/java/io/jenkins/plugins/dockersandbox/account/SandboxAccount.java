package io.jenkins.plugins.dockersandbox.account;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder;
import hudson.Extension;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.interceptor.RequirePOST;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

/**
 * One Docker account (Docker ID + personal access token stored as Jenkins credentials) that sandboxes can be
 * created on. Immutable apart from the UI-bound optional settings.
 */
public class SandboxAccount extends AbstractDescribableImpl<SandboxAccount> {

    public static final String DEFAULT_API_URL = "https://connect.docker.com/sandboxes";
    public static final String DEFAULT_AUTH_URL = "https://hub.docker.com";
    public static final int DEFAULT_MAX_CONCURRENT = 10;

    private final String id;
    private String displayName = "";
    private String credentialsId = "";
    private String apiUrl = DEFAULT_API_URL;
    private String authUrl = DEFAULT_AUTH_URL;
    private int maxConcurrent = DEFAULT_MAX_CONCURRENT;
    private int weight = 1;
    private String labels = "";
    private boolean enabled = true;

    @DataBoundConstructor
    public SandboxAccount(@NonNull String id) {
        this.id = id == null ? "" : id.trim();
    }

    @NonNull
    public String getId() {
        return id;
    }

    @NonNull
    public String getDisplayName() {
        return displayName.isEmpty() ? id : displayName;
    }

    @DataBoundSetter
    public void setDisplayName(@Nullable String displayName) {
        this.displayName = displayName == null ? "" : displayName.trim();
    }

    @NonNull
    public String getCredentialsId() {
        return credentialsId;
    }

    @DataBoundSetter
    public void setCredentialsId(@Nullable String credentialsId) {
        this.credentialsId = credentialsId == null ? "" : credentialsId.trim();
    }

    @NonNull
    public String getApiUrl() {
        return apiUrl;
    }

    @DataBoundSetter
    public void setApiUrl(@Nullable String apiUrl) {
        this.apiUrl = apiUrl == null || apiUrl.isBlank() ? DEFAULT_API_URL : apiUrl.trim();
    }

    @NonNull
    public String getAuthUrl() {
        return authUrl;
    }

    @DataBoundSetter
    public void setAuthUrl(@Nullable String authUrl) {
        this.authUrl = authUrl == null || authUrl.isBlank() ? DEFAULT_AUTH_URL : authUrl.trim();
    }

    public int getMaxConcurrent() {
        return maxConcurrent;
    }

    @DataBoundSetter
    public void setMaxConcurrent(int maxConcurrent) {
        this.maxConcurrent = Math.max(1, maxConcurrent);
    }

    public int getWeight() {
        return weight;
    }

    @DataBoundSetter
    public void setWeight(int weight) {
        this.weight = Math.max(1, weight);
    }

    @NonNull
    public String getLabels() {
        return labels;
    }

    @DataBoundSetter
    public void setLabels(@Nullable String labels) {
        this.labels = labels == null ? "" : labels.trim();
    }

    public boolean isEnabled() {
        return enabled;
    }

    @DataBoundSetter
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** Lower-cased labels. */
    @NonNull
    public Set<String> getLabelSet() {
        if (labels.isEmpty()) {
            return Collections.emptySet();
        }
        return Arrays.stream(labels.split("\\s+"))
                .filter(s -> !s.isEmpty())
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public String toString() {
        return "SandboxAccount[" + id + "]";
    }

    @Extension
    @Symbol("dockerSandboxAccount")
    public static class DescriptorImpl extends Descriptor<SandboxAccount> {

        @NonNull
        @Override
        public String getDisplayName() {
            return "Docker account";
        }

        public ListBoxModel doFillCredentialsIdItems(@QueryParameter String credentialsId, @QueryParameter String authUrl) {
            Jenkins jenkins = Jenkins.get();
            StandardListBoxModel result = new StandardListBoxModel();
            if (!jenkins.hasPermission(Jenkins.ADMINISTER)) {
                return result.includeCurrentValue(credentialsId);
            }
            String uri = authUrl == null || authUrl.isBlank() ? DEFAULT_AUTH_URL : authUrl;
            return result.includeEmptyValue()
                    .includeMatchingAs(
                            ACL.SYSTEM2,
                            jenkins,
                            StandardUsernamePasswordCredentials.class,
                            URIRequirementBuilder.fromUri(uri).build(),
                            CredentialsMatchers.always())
                    .includeCurrentValue(credentialsId);
        }

        public FormValidation doCheckCredentialsId(@QueryParameter String value) {
            if (value == null || value.isBlank()) {
                return FormValidation.error("Select a username/password credential (Docker ID + personal access token)");
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckId(@QueryParameter String value) {
            if (value == null || value.isBlank()) {
                return FormValidation.error("Account ID is required");
            }
            if (!value.trim().matches("[A-Za-z0-9_.-]+")) {
                return FormValidation.error("Use only letters, digits, '.', '_' and '-'");
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckMaxConcurrent(@QueryParameter String value) {
            return FormValidation.validatePositiveInteger(value);
        }

        public FormValidation doCheckWeight(@QueryParameter String value) {
            return FormValidation.validatePositiveInteger(value);
        }

        public FormValidation doCheckApiUrl(@QueryParameter String value) {
            return checkUrl(value);
        }

        public FormValidation doCheckAuthUrl(@QueryParameter String value) {
            return checkUrl(value);
        }

        private static FormValidation checkUrl(String value) {
            if (value == null || value.isBlank()) {
                return FormValidation.ok();
            }
            try {
                java.net.URI uri = java.net.URI.create(value.trim());
                if (uri.getScheme() == null || uri.getHost() == null) {
                    return FormValidation.error("Enter an absolute http(s) URL");
                }
            } catch (IllegalArgumentException e) {
                return FormValidation.error("Not a valid URL");
            }
            return FormValidation.ok();
        }

        /** Token exchange + list sandboxes, so a wrong PAT or missing "Cloud Sandboxes" scope shows up immediately. */
        @RequirePOST
        public FormValidation doTestConnection(
                @QueryParameter String credentialsId, @QueryParameter String authUrl, @QueryParameter String apiUrl) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            SandboxAccount probe = new SandboxAccount("test");
            probe.setCredentialsId(credentialsId);
            probe.setAuthUrl(authUrl);
            probe.setApiUrl(apiUrl);
            try {
                return FormValidation.ok(AccountApiFactory.testConnection(probe));
            } catch (IOException e) {
                return FormValidation.error(e.getMessage());
            }
        }

    }
}
