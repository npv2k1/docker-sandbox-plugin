package io.jenkins.plugins.dockersandbox.account;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** In-memory runtime state of one account (reset on restart). */
public class AccountState {

    final AtomicInteger active = new AtomicInteger();
    final AtomicLong totalRuns = new AtomicLong();
    final AtomicLong totalFailures = new AtomicLong();
    private volatile long cooldownUntilMillis;
    private volatile String lastError = "";

    public int getActive() {
        return active.get();
    }

    public long getTotalRuns() {
        return totalRuns.get();
    }

    public long getTotalFailures() {
        return totalFailures.get();
    }

    public long getCooldownUntilMillis() {
        return cooldownUntilMillis;
    }

    public String getLastError() {
        return lastError;
    }

    public boolean isCoolingDown(long nowMillis) {
        return nowMillis < cooldownUntilMillis;
    }

    void startCooldown(long untilMillis, String error) {
        this.cooldownUntilMillis = untilMillis;
        this.lastError = error == null ? "" : error;
        totalFailures.incrementAndGet();
    }
}
