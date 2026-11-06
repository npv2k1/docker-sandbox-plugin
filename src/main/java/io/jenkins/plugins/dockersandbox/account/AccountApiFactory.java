package io.jenkins.plugins.dockersandbox.account;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder;
import hudson.AbortException;
import hudson.security.ACL;
import io.jenkins.plugins.dockersandbox.api.DockerSandboxApi;
import io.jenkins.plugins.dockersandbox.api.HttpDockerSandboxApi;
import io.jenkins.plugins.dockersandbox.api.TokenProvider;
import io.jenkins.plugins.dockersandbox.api.model.ListSandboxesResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import jenkins.model.Jenkins;

/** Builds API clients for accounts; token providers are cached so builds share one access token per account. */
public final class AccountApiFactory {

    private static final class CachedProvider {
        final String identifier;
        final String secret;
        final String authUrl;
        final TokenProvider provider;

        CachedProvider(String identifier, String secret, String authUrl) {
            this.identifier = identifier;
            this.secret = secret;
            this.authUrl = authUrl;
            this.provider = new TokenProvider(authUrl, identifier, secret);
        }

        boolean matches(String identifier, String secret, String authUrl) {
            return this.identifier.equals(identifier) && this.secret.equals(secret) && this.authUrl.equals(authUrl);
        }
    }

    private static final Map<String, CachedProvider> PROVIDERS = new ConcurrentHashMap<>();

    private AccountApiFactory() {}

    /** Management API for the account, authenticated with its Jenkins credentials. */
    public static DockerSandboxApi apiFor(SandboxAccount account) throws IOException {
        return new HttpDockerSandboxApi(account.getApiUrl(), tokenProviderFor(account));
    }

    static TokenProvider tokenProviderFor(SandboxAccount account) throws AbortException {
        StandardUsernamePasswordCredentials credentials = lookup(account);
        String identifier = credentials.getUsername();
        String secret = credentials.getPassword().getPlainText();
        return PROVIDERS
                .compute(account.getId(), (k, existing) -> existing != null
                                && existing.matches(identifier, secret, account.getAuthUrl())
                        ? existing
                        : new CachedProvider(identifier, secret, account.getAuthUrl()))
                .provider;
    }

    private static StandardUsernamePasswordCredentials lookup(SandboxAccount account) throws AbortException {
        String credentialsId = account.getCredentialsId();
        if (credentialsId.isEmpty()) {
            throw new AbortException("Docker sandbox account '" + account.getId() + "' has no credentials configured");
        }
        List<StandardUsernamePasswordCredentials> all = CredentialsProvider.lookupCredentialsInItemGroup(
                StandardUsernamePasswordCredentials.class,
                Jenkins.get(),
                ACL.SYSTEM2,
                URIRequirementBuilder.fromUri(account.getAuthUrl()).build());
        StandardUsernamePasswordCredentials found =
                CredentialsMatchers.firstOrNull(all, CredentialsMatchers.withId(credentialsId));
        if (found == null) {
            throw new AbortException("Credentials '" + credentialsId + "' of Docker sandbox account '"
                    + account.getId() + "' were not found (username/password credential expected)");
        }
        return found;
    }

    /** Exchanges the PAT and lists one page of sandboxes; returns a short human-readable summary. */
    public static String testConnection(SandboxAccount account) throws IOException {
        DockerSandboxApi api = apiFor(account);
        ListSandboxesResponse page = api.listSandboxes(1, null);
        boolean more = page.nextPageToken() != null && !page.nextPageToken().isEmpty();
        int n = page.sandboxes() == null ? 0 : page.sandboxes().size();
        return "Connected. " + (more ? "At least " + n + " sandbox(es)" : n + " sandbox(es)") + " visible on this account.";
    }
}
