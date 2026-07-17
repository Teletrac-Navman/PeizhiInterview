import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

public class ExpiringCacheTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        testRejectsNonPositiveCapacity();
        testRejectsNullKeyAndValueAndBadTtl();
        testPutGetAndSize();
        testGetMissReturnsNull();
        testLruEviction();
        testGetUpdatesLruOrder();
        testUpdateExistingKey();

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
