package io.jenkins.plugins.dockersandbox.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.AbortException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AccountPoolTest {

    private static final Duration LONG = Duration.ofSeconds(30);

    private static SandboxAccount account(String id, int max, int weight, String labels) {
        SandboxAccount a = new SandboxAccount(id);
        a.setMaxConcurrent(max);
        a.setWeight(weight);
        a.setLabels(labels);
        return a;
    }

    private static AccountPool pool(SandboxAccount... accounts) {
        return new AccountPool(() -> List.of(accounts));
    }

    private static AccountPool.Lease lease(AccountPool p, LoadBalancingStrategy s) throws Exception {
        return p.lease(AccountSelector.any(), s, LONG, m -> {});
    }

    /** Clock moved by hand. */
    private static final class ManualClock extends Clock {
        private volatile long millis = 1_000_000;

        void advance(Duration d) {
            millis += d.toMillis();
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }
    }

    @Test
    void leastLoadedPrefersLowerRatioThenHigherWeight() throws Exception {
        SandboxAccount big = account("big", 10, 1, "");
        SandboxAccount small = account("small", 2, 1, "");
        AccountPool p = pool(big, small);
        // tie at 0 -> equal weights -> config order -> big
        AccountPool.Lease l1 = lease(p, LoadBalancingStrategy.LEAST_LOADED);
        assertEquals("big", l1.getAccount().getId());
        // big 1/10 vs small 0/2 -> small
        AccountPool.Lease l2 = lease(p, LoadBalancingStrategy.LEAST_LOADED);
        assertEquals("small", l2.getAccount().getId());
        // big 1/10 vs small 1/2 -> big
        AccountPool.Lease l3 = lease(p, LoadBalancingStrategy.LEAST_LOADED);
        assertEquals("big", l3.getAccount().getId());

        SandboxAccount light = account("light", 4, 1, "");
        SandboxAccount heavy = account("heavy", 4, 5, "");
        AccountPool p2 = pool(light, heavy);
        assertEquals("heavy", lease(p2, LoadBalancingStrategy.LEAST_LOADED).getAccount().getId());
    }

    @Test
    void roundRobinCyclesAndSkipsFullAccounts() throws Exception {
        AccountPool p = pool(account("a", 1, 1, ""), account("b", 5, 1, ""), account("c", 5, 1, ""));
        List<String> order = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            order.add(lease(p, LoadBalancingStrategy.ROUND_ROBIN).getAccount().getId());
        }
        // a is full after its first lease and is skipped afterwards
        assertEquals(List.of("a", "b", "c", "b"), order);
    }

    @Test
    void firstAvailableFollowsPriorityOrder() throws Exception {
        AccountPool p = pool(account("p1", 1, 1, ""), account("p2", 1, 1, ""));
        assertEquals("p1", lease(p, LoadBalancingStrategy.FIRST_AVAILABLE).getAccount().getId());
        assertEquals("p2", lease(p, LoadBalancingStrategy.FIRST_AVAILABLE).getAccount().getId());
    }

    @Test
    void weightedRandomRespectsWeights() throws Exception {
        AccountPool p = new AccountPool(
                () -> List.of(account("w1", 100000, 1, ""), account("w3", 100000, 3, "")),
                Clock.systemUTC(),
                new Random(42));
        int w3 = 0;
        int n = 4000;
        for (int i = 0; i < n; i++) {
            AccountPool.Lease l = lease(p, LoadBalancingStrategy.WEIGHTED_RANDOM);
            if ("w3".equals(l.getAccount().getId())) {
                w3++;
            }
            l.release();
        }
        double share = (double) w3 / n;
        assertTrue(share > 0.70 && share < 0.80, "w3 share was " + share);
    }

    @Test
    void waitsForCapacityAndWakesOnRelease() throws Exception {
        AccountPool p = pool(account("only", 1, 1, ""));
        AccountPool.Lease first = lease(p, LoadBalancingStrategy.LEAST_LOADED);
        List<String> logs = new ArrayList<>();
        ExecutorService ex = Executors.newSingleThreadExecutor();
        try {
            Future<AccountPool.Lease> second = ex.submit(() -> p.lease(
                    AccountSelector.any(), LoadBalancingStrategy.LEAST_LOADED, LONG, m -> {
                        synchronized (logs) {
                            logs.add(m);
                        }
                    }));
            long end = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < end) {
                synchronized (logs) {
                    if (!logs.isEmpty()) {
                        break;
                    }
                }
                Thread.sleep(10);
            }
            assertFalse(second.isDone());
            first.release();
            AccountPool.Lease got = second.get(10, TimeUnit.SECONDS);
            assertEquals("only", got.getAccount().getId());
            assertEquals(List.of("Waiting for Docker sandbox capacity (all 1 accounts busy)"), logs);
            got.release();
        } finally {
            ex.shutdownNow();
        }
    }

    @Test
    void timesOutWhenNoCapacity() throws Exception {
        AccountPool p = pool(account("only", 1, 1, ""));
        AccountPool.Lease held = lease(p, LoadBalancingStrategy.LEAST_LOADED);
        AbortException e = assertThrows(AbortException.class, () -> p.lease(
                AccountSelector.any(), LoadBalancingStrategy.LEAST_LOADED, Duration.ofMillis(150), m -> {}));
        assertTrue(e.getMessage().contains("Timed out"));
        held.release();
    }

    @Test
    void interruptWhileWaitingThrowsInterruptedException() throws Exception {
        AccountPool p = pool(account("only", 1, 1, ""));
        AccountPool.Lease held = lease(p, LoadBalancingStrategy.LEAST_LOADED);
        CountDownLatch waiting = new CountDownLatch(1);
        ExecutorService ex = Executors.newSingleThreadExecutor();
        try {
            Future<Object> f = ex.submit(() -> p.lease(
                    AccountSelector.any(), LoadBalancingStrategy.LEAST_LOADED, LONG, m -> waiting.countDown()));
            assertTrue(waiting.await(5, TimeUnit.SECONDS));
            ex.shutdownNow();
            java.util.concurrent.ExecutionException ee =
                    assertThrows(java.util.concurrent.ExecutionException.class, () -> f.get(5, TimeUnit.SECONDS));
            assertTrue(ee.getCause() instanceof InterruptedException);
        } finally {
            held.release();
        }
    }

    @Test
    void selectorFiltersByPinAndLabelsAndFailsFastWhenNothingMatches() throws Exception {
        AccountPool p = pool(account("eu", 2, 1, "eu large"), account("us", 2, 1, "us"));
        assertEquals("eu", p.lease(AccountSelector.of("eu", ""), LoadBalancingStrategy.FIRST_AVAILABLE, LONG, m -> {})
                .getAccount().getId());
        assertEquals("us", p.lease(AccountSelector.of(null, "US"), LoadBalancingStrategy.FIRST_AVAILABLE, LONG, m -> {})
                .getAccount().getId());
        assertEquals("eu", p.lease(AccountSelector.of(null, "large eu"), LoadBalancingStrategy.FIRST_AVAILABLE, LONG, m -> {})
                .getAccount().getId());
        assertThrows(AbortException.class, () -> p.lease(
                AccountSelector.of(null, "eu us"), LoadBalancingStrategy.FIRST_AVAILABLE, LONG, m -> {}));
        assertThrows(AbortException.class, () -> p.lease(
                AccountSelector.of("nope", ""), LoadBalancingStrategy.FIRST_AVAILABLE, LONG, m -> {}));
    }

    @Test
    void disabledAccountsAreNeverLeased() throws Exception {
        SandboxAccount off = account("off", 5, 1, "");
        off.setEnabled(false);
        AccountPool p = pool(off, account("on", 5, 1, ""));
        for (int i = 0; i < 3; i++) {
            assertEquals("on", lease(p, LoadBalancingStrategy.FIRST_AVAILABLE).getAccount().getId());
        }
    }

    @Test
    void cooldownSkipsAccountUntilItExpires() throws Exception {
        ManualClock clock = new ManualClock();
        SandboxAccount a = account("a", 5, 1, "");
        SandboxAccount b = account("b", 5, 1, "");
        AccountPool p = new AccountPool(() -> List.of(a, b), clock, new Random(1));
        p.markFailure(a, new RuntimeException("quota"), Duration.ofMinutes(5));
        assertEquals("quota", p.stateOf("a").getLastError());
        assertEquals(1, p.stateOf("a").getTotalFailures());
        assertEquals("b", lease(p, LoadBalancingStrategy.FIRST_AVAILABLE).getAccount().getId());
        clock.advance(Duration.ofMinutes(4));
        assertEquals("b", lease(p, LoadBalancingStrategy.FIRST_AVAILABLE).getAccount().getId());
        clock.advance(Duration.ofMinutes(2));
        assertEquals("a", lease(p, LoadBalancingStrategy.FIRST_AVAILABLE).getAccount().getId());
    }

    @Test
    void waitsThroughCooldownWhenEveryAccountIsCoolingDown() throws Exception {
        SandboxAccount a = account("a", 5, 1, "");
        AccountPool p = pool(a);
        p.markFailure(a, new RuntimeException("quota"), Duration.ofMillis(200));
        long start = System.nanoTime();
        AccountPool.Lease l = lease(p, LoadBalancingStrategy.LEAST_LOADED);
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) >= 150);
        l.release();
    }

    @Test
    void failoverVisitsAccountsInOrderViaExclusion() throws Exception {
        AccountPool p = pool(account("a1", 5, 1, ""), account("a2", 5, 1, ""), account("a3", 5, 1, ""));
        AccountSelector selector = AccountSelector.any();
        List<String> tried = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            try (AccountPool.Lease l = p.lease(selector, LoadBalancingStrategy.FIRST_AVAILABLE, LONG, m -> {})) {
                tried.add(l.getAccount().getId());
                selector = selector.excluding(List.of(l.getAccount().getId()));
            }
        }
        assertEquals(List.of("a1", "a2", "a3"), tried);
        AccountSelector exhausted = selector;
        assertThrows(AbortException.class, () -> p.lease(exhausted, LoadBalancingStrategy.FIRST_AVAILABLE, LONG, m -> {}));
    }

    @Test
    void releaseIsIdempotent() throws Exception {
        AccountPool p = pool(account("a", 1, 1, ""));
        AccountPool.Lease l = lease(p, LoadBalancingStrategy.LEAST_LOADED);
        l.release();
        l.close();
        assertEquals(0, p.stateOf("a").getActive());
    }

    @Test
    void neverExceedsMaxConcurrentUnder50Threads() throws Exception {
        SandboxAccount a = account("a", 3, 1, "");
        SandboxAccount b = account("b", 2, 2, "");
        SandboxAccount c = account("c", 4, 1, "");
        Map<String, Integer> max = Map.of("a", 3, "b", 2, "c", 4);
        for (LoadBalancingStrategy strategy : LoadBalancingStrategy.values()) {
            AccountPool p = new AccountPool(() -> List.of(a, b, c), Clock.systemUTC(), new Random(7));
            Map<String, AtomicInteger> inUse = new ConcurrentHashMap<>();
            Map<String, AtomicInteger> peak = new ConcurrentHashMap<>();
            for (String id : max.keySet()) {
                inUse.put(id, new AtomicInteger());
                peak.put(id, new AtomicInteger());
            }
            AtomicInteger completed = new AtomicInteger();
            ExecutorService ex = Executors.newFixedThreadPool(50);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < 50; t++) {
                futures.add(ex.submit(() -> {
                    start.await();
                    for (int i = 0; i < 6; i++) {
                        try (AccountPool.Lease l =
                                p.lease(AccountSelector.any(), strategy, Duration.ofSeconds(60), m -> {})) {
                            String id = l.getAccount().getId();
                            int now = inUse.get(id).incrementAndGet();
                            peak.get(id).accumulateAndGet(now, Math::max);
                            Thread.sleep(1);
                            inUse.get(id).decrementAndGet();
                        }
                        completed.incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(120, TimeUnit.SECONDS);
            }
            ex.shutdown();
            assertEquals(300, completed.get());
            for (Map.Entry<String, Integer> e : max.entrySet()) {
                assertTrue(peak.get(e.getKey()).get() <= e.getValue(),
                        strategy + ": account " + e.getKey() + " peaked at " + peak.get(e.getKey()));
                assertEquals(0, p.stateOf(e.getKey()).getActive());
            }
        }
    }
}
