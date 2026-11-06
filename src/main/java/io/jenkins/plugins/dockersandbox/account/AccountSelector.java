package io.jenkins.plugins.dockersandbox.account;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Restricts which accounts a build may use: a pinned account id and/or required labels, minus excluded ids. */
public final class AccountSelector {

    private final String accountId;
    private final Set<String> requiredLabels;
    private final Set<String> excludedIds;

    private AccountSelector(String accountId, Set<String> requiredLabels, Set<String> excludedIds) {
        this.accountId = accountId;
        this.requiredLabels = requiredLabels;
        this.excludedIds = excludedIds;
    }

    /** @param accountId pinned account id or blank; @param labels space-separated labels, all required, or blank */
    public static AccountSelector of(String accountId, String labels) {
        Set<String> required = new HashSet<>();
        if (labels != null) {
            Arrays.stream(labels.trim().split("\\s+"))
                    .filter(s -> !s.isEmpty())
                    .map(s -> s.toLowerCase(Locale.ROOT))
                    .forEach(required::add);
        }
        String id = accountId == null || accountId.isBlank() ? null : accountId.trim();
        return new AccountSelector(id, required, Set.of());
    }

    public static AccountSelector any() {
        return of(null, null);
    }

    /** Same selector, additionally refusing the given account ids (used for failover). */
    public AccountSelector excluding(Collection<String> ids) {
        Set<String> all = new HashSet<>(excludedIds);
        all.addAll(ids);
        return new AccountSelector(accountId, requiredLabels, Set.copyOf(all));
    }

    public boolean matches(SandboxAccount account) {
        if (excludedIds.contains(account.getId())) {
            return false;
        }
        if (accountId != null && !accountId.equals(account.getId())) {
            return false;
        }
        return account.getLabelSet().containsAll(requiredLabels);
    }

    @Override
    public String toString() {
        return "AccountSelector[account=" + (accountId == null ? "*" : accountId) + ", labels=" + requiredLabels + "]";
    }
}
