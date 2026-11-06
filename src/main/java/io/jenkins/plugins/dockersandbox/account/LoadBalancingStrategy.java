package io.jenkins.plugins.dockersandbox.account;

/** How {@link AccountPool} picks one account among those that currently have free capacity. */
public enum LoadBalancingStrategy {
    /** Lowest {@code active / maxConcurrent}; ties go to the higher weight, then configuration order. */
    LEAST_LOADED("Least loaded"),
    /** Cycles through the accounts in configuration order, skipping those without capacity. */
    ROUND_ROBIN("Round robin"),
    /** Random pick, probability proportional to weight. */
    WEIGHTED_RANDOM("Weighted random"),
    /** First account (configuration order = priority) that has capacity. */
    FIRST_AVAILABLE("First available (priority order)");

    private final String displayName;

    LoadBalancingStrategy(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
