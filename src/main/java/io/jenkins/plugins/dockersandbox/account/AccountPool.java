package io.jenkins.plugins.dockersandbox.account;

import hudson.AbortException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Tracks how many sandboxes each account is running and hands out capacity ("leases"). All capacity decisions are
 * made under one lock, so the number of active leases per account never exceeds its {@code maxConcurrent}.
 * A lease must be released on every path; use try-with-resources.
 */
public class AccountPool {

    private final Supplier<List<SandboxAccount>> accounts;
    private final Clock clock;
    private final Random random;
    private final Object lock = new Object();
    private final Map<String, AccountState> states = new ConcurrentHashMap<>();
    private int roundRobinCursor; // guarded by lock

    public AccountPool(Supplier<List<SandboxAccount>> accounts, Clock clock, Random random) {
        this.accounts = accounts;
        this.clock = clock;
        this.random = random;
    }

    public AccountPool(Supplier<List<SandboxAccount>> accounts) {
        this(accounts, Clock.systemUTC(), new Random());
    }

    /** Runtime state of an account (created on demand). */
    public AccountState stateOf(String accountId) {
        return states.computeIfAbsent(accountId, k -> new AccountState());
    }

    /** A claimed slot on one account. Closing it more than once is harmless. */
    public final class Lease implements AutoCloseable {
        private final SandboxAccount account;
        private final AtomicBoolean released = new AtomicBoolean();

        private Lease(SandboxAccount account) {
            this.account = account;
        }

        public SandboxAccount getAccount() {
            return account;
        }

        public void release() {
            if (released.compareAndSet(false, true)) {
                synchronized (lock) {
                    stateOf(account.getId()).active.decrementAndGet();
                    lock.notifyAll();
                }
            }
        }

        @Override
        public void close() {
            release();
        }
    }

    /**
     * Claims a slot on an account chosen by {@code strategy}. Blocks while every matching account is busy or cooling
     * down, until {@code timeout} elapses.
     *
     * @throws AbortException if no account matches the selector or the wait timed out
     * @throws InterruptedException if the waiting thread is interrupted (build aborted)
     */
    public Lease lease(AccountSelector selector, LoadBalancingStrategy strategy, Duration timeout, Consumer<String> log)
            throws AbortException, InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        boolean logged = false;
        synchronized (lock) {
            while (true) {
                List<SandboxAccount> matching = new ArrayList<>();
                for (SandboxAccount a : accounts.get()) {
                    if (a.isEnabled() && selector.matches(a)) {
                        matching.add(a);
                    }
                }
                if (matching.isEmpty()) {
                    throw new AbortException("No enabled Docker sandbox account matches " + selector
                            + ". Check Manage Jenkins > System > Docker Cloud Sandboxes.");
                }
                long now = clock.millis();
                List<SandboxAccount> candidates = new ArrayList<>();
                long nextCooldownEnd = Long.MAX_VALUE;
                for (SandboxAccount a : matching) {
                    AccountState s = stateOf(a.getId());
                    if (s.isCoolingDown(now)) {
                        nextCooldownEnd = Math.min(nextCooldownEnd, s.getCooldownUntilMillis());
                    } else if (s.getActive() < a.getMaxConcurrent()) {
                        candidates.add(a);
                    }
                }
                if (!candidates.isEmpty()) {
                    SandboxAccount chosen = pick(strategy, matching, candidates);
                    AccountState s = stateOf(chosen.getId());
                    s.active.incrementAndGet();
                    s.totalRuns.incrementAndGet();
                    return new Lease(chosen);
                }
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0) {
                    throw new AbortException("Timed out after " + timeout.toMinutes()
                            + " minute(s) waiting for Docker sandbox capacity on " + matching.size() + " account(s)");
                }
                if (!logged) {
                    logged = true;
                    log.accept("Waiting for Docker sandbox capacity (all " + matching.size() + " accounts busy)");
                }
                long waitMillis = Math.max(1, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
                if (nextCooldownEnd != Long.MAX_VALUE) {
                    waitMillis = Math.max(1, Math.min(waitMillis, nextCooldownEnd - now));
                }
                lock.wait(waitMillis);
            }
        }
    }

    /** Puts the account into cooldown (quota exhausted / authentication failure) so others are preferred. */
    public void markFailure(SandboxAccount account, Throwable cause, Duration cooldown) {
        String message = cause == null ? "" : String.valueOf(cause.getMessage());
        synchronized (lock) {
            stateOf(account.getId()).startCooldown(clock.millis() + cooldown.toMillis(), message);
            lock.notifyAll();
        }
    }

    private SandboxAccount pick(
            LoadBalancingStrategy strategy, List<SandboxAccount> matching, List<SandboxAccount> candidates) {
        switch (strategy) {
            case ROUND_ROBIN:
                for (int i = 0; i < matching.size(); i++) {
                    int index = (roundRobinCursor + i) % matching.size();
                    SandboxAccount a = matching.get(index);
                    if (candidates.contains(a)) {
                        roundRobinCursor = index + 1;
                        return a;
                    }
                }
                return candidates.get(0);
            case WEIGHTED_RANDOM:
                int total = 0;
                for (SandboxAccount a : candidates) {
                    total += a.getWeight();
                }
                int roll = random.nextInt(total);
                for (SandboxAccount a : candidates) {
                    roll -= a.getWeight();
                    if (roll < 0) {
                        return a;
                    }
                }
                return candidates.get(candidates.size() - 1);
            case FIRST_AVAILABLE:
                return candidates.get(0);
            case LEAST_LOADED:
            default:
                SandboxAccount best = null;
                for (SandboxAccount a : candidates) {
                    if (best == null || isLessLoaded(a, best)) {
                        best = a;
                    }
                }
                return best;
        }
    }

    private boolean isLessLoaded(SandboxAccount a, SandboxAccount b) {
        // compare active/max as fractions without floating point: a.active * b.max < b.active * a.max
        long left = (long) stateOf(a.getId()).getActive() * b.getMaxConcurrent();
        long right = (long) stateOf(b.getId()).getActive() * a.getMaxConcurrent();
        if (left != right) {
            return left < right;
        }
        return a.getWeight() > b.getWeight();
    }
}
