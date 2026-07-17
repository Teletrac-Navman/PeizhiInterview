import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

public class ExpiringCacheTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        testRejectsNonPositiveCapacity();
        testRejectsNullKeyAndValueAndBadTtl();
        testPutGetAndSize();
        testGetMissReturnsNull();
        testLruEviction();
        testGetUpdatesLruOrder();
        testUpdateExistingKey();
        testExpiration();
        testSizeIgnoresExpired();
        testExpiredSkippedDuringEviction();
        testComputeIfAbsentHitAndLoad();
        testDuplicateLoadSuppression();
        testLoaderFailureAndRetry();
        testSameKeyReentryThrows();
        testLoaderNullResultRejected();
        testConcurrentGetPut();
        testConcurrentLoadDifferentKeys();

        System.out.println();
        System.out.println("Passed: " + passed + "  Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    static void testRejectsNonPositiveCapacity() {
        expectThrows(IllegalArgumentException.class, () -> new ExpiringCache<String, String>(0));
        expectThrows(IllegalArgumentException.class, () -> new ExpiringCache<String, String>(-1));
    }

    static void testRejectsNullKeyAndValueAndBadTtl() {
        ExpiringCache<String, String> cache = new ExpiringCache<>(2);
        expectThrows(IllegalArgumentException.class, () -> cache.put(null, "v", 1000));
        expectThrows(IllegalArgumentException.class, () -> cache.put("k", null, 1000));
        expectThrows(IllegalArgumentException.class, () -> cache.put("k", "v", 0));
        expectThrows(IllegalArgumentException.class, () -> cache.put("k", "v", -5));
        expectThrows(IllegalArgumentException.class, () -> cache.get(null));
    }

    static void testPutGetAndSize() {
        AtomicLong now = new AtomicLong(1_000_000L);
        ExpiringCache<String, String> cache = new ExpiringCache<>(2, now::get);
        cache.put("a", "alpha", 5_000);
        assertEq("alpha", cache.get("a"));
        assertEq(1, cache.size());
    }

    static void testGetMissReturnsNull() {
        ExpiringCache<String, String> cache = new ExpiringCache<>(2);
        assertNull(cache.get("missing"));
    }

    static void testUpdateExistingKey() {
        AtomicLong now = new AtomicLong(1_000_000L);
        ExpiringCache<String, String> cache = new ExpiringCache<>(2, now::get);
        cache.put("a", "alpha", 5_000);
        cache.put("b", "bravo", 5_000);
        cache.put("a", "ALPHA", 5_000); // update + becomes MRU
        cache.put("c", "charlie", 5_000); // evicts b (LRU)
        assertEq("ALPHA", cache.get("a"));
        assertNull(cache.get("b"));
        assertEq("charlie", cache.get("c"));
        assertEq(2, cache.size());
    }

    static void testLruEviction() {
        AtomicLong now = new AtomicLong(1_000_000L);
        ExpiringCache<String, String> cache = new ExpiringCache<>(2, now::get);
        cache.put("a", "alpha", 5_000);
        cache.put("b", "bravo", 5_000);
        cache.get("a"); // a becomes MRU; b is LRU
        cache.put("c", "charlie", 5_000); // evict b
        assertNull(cache.get("b"));
        assertEq("alpha", cache.get("a"));
        assertEq("charlie", cache.get("c"));
        assertEq(2, cache.size());
    }

    static void testGetUpdatesLruOrder() {
        AtomicLong now = new AtomicLong(1_000_000L);
        ExpiringCache<String, String> cache = new ExpiringCache<>(2, now::get);
        cache.put("a", "alpha", 5_000);
        cache.put("b", "bravo", 5_000);
        cache.get("a");
        cache.put("c", "charlie", 5_000);
        assertNull(cache.get("b"));
    }

    static void testExpiration() {
        AtomicLong now = new AtomicLong(1_000_000L);
        ExpiringCache<String, String> cache = new ExpiringCache<>(2, now::get);
        cache.put("a", "alpha", 100); // expires at 1_000_100
        assertEq("alpha", cache.get("a"));
        now.set(1_000_100L);
        assertNull(cache.get("a"));
    }

    static void testSizeIgnoresExpired() {
        AtomicLong now = new AtomicLong(1_000_000L);
        ExpiringCache<String, String> cache = new ExpiringCache<>(2, now::get);
        cache.put("a", "alpha", 50);
        cache.put("b", "bravo", 5_000);
        now.set(1_000_050L);
        assertEq(1, cache.size());
        assertEq("bravo", cache.get("b"));
    }

    static void testExpiredSkippedDuringEviction() {
        AtomicLong now = new AtomicLong(1_000_000L);
        ExpiringCache<String, String> cache = new ExpiringCache<>(2, now::get);
        cache.put("a", "alpha", 50);   // will expire
        cache.put("b", "bravo", 5_000);
        now.set(1_000_050L);
        cache.put("c", "charlie", 5_000); // should drop expired a, keep b, add c
        assertNull(cache.get("a"));
        assertEq("bravo", cache.get("b"));
        assertEq("charlie", cache.get("c"));
    }

    static void testComputeIfAbsentHitAndLoad() {
        AtomicLong now = new AtomicLong(1_000_000L);
        ExpiringCache<String, String> cache = new ExpiringCache<>(2, now::get);
        String v = cache.computeIfAbsent("a", 5_000, k -> "loaded-" + k);
        assertEq("loaded-a", v);
        assertEq("loaded-a", cache.get("a"));
        String again = cache.computeIfAbsent("a", 5_000, k -> {
            fail("loader should not run on hit");
            return "nope";
        });
        assertEq("loaded-a", again);
    }

    static void testDuplicateLoadSuppression() throws Exception {
        AtomicLong now = new AtomicLong(1_000_000L);
        ExpiringCache<String, String> cache = new ExpiringCache<>(2, now::get);
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger loads = new java.util.concurrent.atomic.AtomicInteger();

        Thread t1 = new Thread(() -> cache.computeIfAbsent("k", 5_000, key -> {
            loads.incrementAndGet();
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return "v";
        }));
        t1.start();
        started.await();
        Thread t2 = new Thread(() -> cache.computeIfAbsent("k", 5_000, key -> {
            loads.incrementAndGet();
            return "other";
        }));
        t2.start();
        Thread.sleep(50); // give t2 time to attach as waiter
        release.countDown();
        t1.join();
        t2.join();
        assertEq(1, loads.get());
        assertEq("v", cache.get("k"));
    }

    static void testLoaderFailureAndRetry() {
        AtomicLong now = new AtomicLong(1_000_000L);
        ExpiringCache<String, String> cache = new ExpiringCache<>(2, now::get);
        java.util.concurrent.atomic.AtomicInteger loads = new java.util.concurrent.atomic.AtomicInteger();
        expectThrows(RuntimeException.class, () -> cache.computeIfAbsent("k", 5_000, key -> {
            loads.incrementAndGet();
            throw new RuntimeException("boom");
        }));
        assertNull(cache.get("k"));
        String v = cache.computeIfAbsent("k", 5_000, key -> {
            loads.incrementAndGet();
            return "ok";
        });
        assertEq("ok", v);
        assertEq(2, loads.get());
    }

    static void testSameKeyReentryThrows() {
        ExpiringCache<String, String> cache = new ExpiringCache<>(2);
        expectThrows(IllegalStateException.class, () -> cache.computeIfAbsent("k", 5_000, key ->
                cache.computeIfAbsent("k", 5_000, k2 -> "nested")));
    }

    static void testLoaderNullResultRejected() {
        ExpiringCache<String, String> cache = new ExpiringCache<>(2);
        expectThrows(IllegalArgumentException.class, () ->
                cache.computeIfAbsent("k", 5_000, key -> null));
        assertNull(cache.get("k"));
    }

    static void testConcurrentGetPut() throws Exception {
        ExpiringCache<Integer, Integer> cache = new ExpiringCache<>(100);
        int threads = 8;
        int ops = 500;
        Thread[] ts = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            final int id = t;
            ts[t] = new Thread(() -> {
                for (int i = 0; i < ops; i++) {
                    int k = (id * ops + i) % 150;
                    cache.put(k, k, 60_000);
                    cache.get(k);
                }
            });
            ts[t].start();
        }
        for (Thread t : ts) {
            t.join();
        }
        assertTrue(cache.size() <= 100, "size must respect capacity");
    }

    static void testConcurrentLoadDifferentKeys() throws Exception {
        ExpiringCache<String, String> cache = new ExpiringCache<>(10);
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger concurrent = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger maxConcurrent = new java.util.concurrent.atomic.AtomicInteger();

        Runnable loadA = () -> cache.computeIfAbsent("a", 5_000, k -> {
            ready.countDown();
            try {
                go.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            int c = concurrent.incrementAndGet();
            maxConcurrent.accumulateAndGet(c, Math::max);
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            } finally {
                concurrent.decrementAndGet();
            }
            return "A";
        });
        Runnable loadB = () -> cache.computeIfAbsent("b", 5_000, k -> {
            ready.countDown();
            try {
                go.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            int c = concurrent.incrementAndGet();
            maxConcurrent.accumulateAndGet(c, Math::max);
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            } finally {
                concurrent.decrementAndGet();
            }
            return "B";
        });

        Thread t1 = new Thread(loadA);
        Thread t2 = new Thread(loadB);
        t1.start();
        t2.start();
        ready.await();
        go.countDown();
        t1.join();
        t2.join();
        assertTrue(maxConcurrent.get() >= 2, "different keys should load concurrently");
        assertEq("A", cache.get("a"));
        assertEq("B", cache.get("b"));
    }

    static void assertEq(Object expected, Object actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            fail("Expected " + expected + " but was " + actual);
        } else {
            pass();
        }
    }

    static void assertNull(Object actual) {
        if (actual != null) {
            fail("Expected null but was " + actual);
        } else {
            pass();
        }
    }

    static void assertTrue(boolean condition, String message) {
        if (!condition) {
            fail(message);
        } else {
            pass();
        }
    }

    static void expectThrows(Class<? extends RuntimeException> type, Runnable action) {
        try {
            action.run();
            fail("Expected " + type.getSimpleName());
        } catch (RuntimeException e) {
            if (!type.isInstance(e)) {
                fail("Expected " + type.getSimpleName() + " but got " + e);
            } else {
                pass();
            }
        }
    }

    static void pass() {
        passed++;
        System.out.print('.');
    }

    static void fail(String message) {
        failed++;
        System.out.println();
        System.out.println("FAIL: " + message);
    }
}
