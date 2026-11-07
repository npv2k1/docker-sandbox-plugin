package io.jenkins.plugins.dockersandbox;

import hudson.AbortException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * In-JVM mutual exclusion per persistent sandbox name (keyed by Jenkins instance id and name): two builds must never
 * be inside the same long-lived sandbox at once. A {@link Hold} must be released on every path; use
 * try-with-resources.
 */
final class PersistentSandboxLocks {

    private static final Object LOCK = new Object();
    /** key to description of the current holder; guarded by LOCK. */
    private static final Map<String, String> HOLDERS = new HashMap<>();

    private PersistentSandboxLocks() {}

    /** A held per-name lock. Closing it more than once is harmless. */
    static final class Hold implements AutoCloseable {
        private final String key;
        private boolean released; // guarded by LOCK

        private Hold(String key) {
            this.key = key;
        }

        @Override
        public void close() {
            synchronized (LOCK) {
                if (!released) {
                    released = true;
                    HOLDERS.remove(key);
                    LOCK.notifyAll();
                }
            }
        }
    }

    static String key(String instanceId, String name) {
        return instanceId + "/" + name;
    }

    /**
     * Waits until the lock for {@code key} is free and takes it.
     *
     * @param owner short description of the caller, shown to others that have to wait
     * @param timeout longest wait; {@link Duration#ZERO} fails at once when the lock is taken
     * @throws AbortException if the wait timed out
     * @throws InterruptedException if the waiting thread is interrupted (build aborted)
     */
    static Hold acquire(String key, String name, String owner, Duration timeout, Consumer<String> log)
            throws AbortException, InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        boolean logged = false;
        synchronized (LOCK) {
            while (true) {
                String holder = HOLDERS.get(key);
                if (holder == null) {
                    HOLDERS.put(key, owner);
                    return new Hold(key);
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new AbortException("Persistent sandbox '" + name + "' is in use by " + holder
                            + (timeout.isZero()
                                    ? "; try again when it is done"
                                    : " (waited " + timeout.toMinutes() + " minute(s))"));
                }
                if (!logged) {
                    logged = true;
                    log.accept("Waiting for persistent sandbox '" + name + "' (in use by " + holder + ")");
                }
                LOCK.wait(Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
            }
        }
    }

    /** Current holder of a lock, or null; for tests and diagnostics. */
    static String holderOf(String key) {
        synchronized (LOCK) {
            return HOLDERS.get(key);
        }
    }
}
