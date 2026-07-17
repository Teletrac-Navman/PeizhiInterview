### Task 1: Scaffold, harness, validation, and basic put/get

**Files:**
- Create: `src/ExpiringCache.java`
- Create: `src/ExpiringCacheTest.java`

**Interfaces:**
- Consumes: nothing
- Produces:
  - `ExpiringCache(int capacity)`
  - `ExpiringCache(int capacity, LongSupplier clock)` (package-visible or public for tests)
  - `V get(K key)`
  - `void put(K key, V value, long ttlMillis)`
  - `int size()`
  - Stub: `V computeIfAbsent(K key, long ttlMillis, Function<K, V> loader)` throwing `UnsupportedOperationException` until Task 4

- [ ] **Step 1: Create `src/` and write failing tests for validation + basic put/get**

Create `src/ExpiringCacheTest.java`:

```java
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
```

- [ ] **Step 2: Compile â€” expect failure (class missing)**

Run (from repo root, PowerShell):

```powershell
New-Item -ItemType Directory -Force -Path out | Out-Null
javac -d out src\ExpiringCacheTest.java
```

Expected: compiler error â€” `ExpiringCache` cannot be found.

- [ ] **Step 3: Implement minimal `ExpiringCache` for put/get/size/validation (no eviction/expiry/load yet)**

Create `src/ExpiringCache.java`:

```java
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Thread-safe fixed-capacity cache with per-entry TTL and LRU eviction.
 * get/put are average O(1). size() may be O(n) when purging expired entries.
 */
public class ExpiringCache<K, V> {

    private final int capacity;
    private final LongSupplier clock;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<K, Node<K, V>> map = new HashMap<>();
    private Node<K, V> head; // MRU
    private Node<K, V> tail; // LRU

    public ExpiringCache(int capacity) {
        this(capacity, System::currentTimeMillis);
    }

    public ExpiringCache(int capacity, LongSupplier clock) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock must be non-null");
        }
        this.capacity = capacity;
        this.clock = clock;
    }

    public V get(K key) {
        requireKey(key);
        lock.lock();
        try {
            Node<K, V> node = map.get(key);
            if (node == null) {
                return null;
            }
            if (isExpired(node)) {
                removeNode(node);
                return null;
            }
            moveToHead(node);
            return node.value;
        } finally {
            lock.unlock();
        }
    }

    public void put(K key, V value, long ttlMillis) {
        requireKey(key);
        requireValue(value);
        requireTtl(ttlMillis);
        long expireAt = clock.getAsLong() + ttlMillis;
        lock.lock();
        try {
            Node<K, V> existing = map.get(key);
            if (existing != null) {
                existing.value = value;
                existing.expireAtMillis = expireAt;
                moveToHead(existing);
                return;
            }
            // Capacity / eviction added in Task 2
            Node<K, V> node = new Node<>(key, value, expireAt);
            map.put(key, node);
            addToHead(node);
        } finally {
            lock.unlock();
        }
    }

    public V computeIfAbsent(K key, long ttlMillis, Function<K, V> loader) {
        throw new UnsupportedOperationException("not implemented yet");
    }

    public int size() {
        lock.lock();
        try {
            return map.size(); // expiry-aware size in Task 3
        } finally {
            lock.unlock();
        }
    }

    private boolean isExpired(Node<K, V> node) {
        return node.expireAtMillis <= clock.getAsLong();
    }

    private void requireKey(K key) {
        if (key == null) {
            throw new IllegalArgumentException("key must be non-null");
        }
    }

    private void requireValue(V value) {
        if (value == null) {
            throw new IllegalArgumentException("value must be non-null");
        }
    }

    private void requireTtl(long ttlMillis) {
        if (ttlMillis <= 0) {
            throw new IllegalArgumentException("ttlMillis must be positive");
        }
    }

    private void addToHead(Node<K, V> node) {
        node.prev = null;
        node.next = head;
        if (head != null) {
            head.prev = node;
        }
        head = node;
        if (tail == null) {
            tail = node;
        }
    }

    private void moveToHead(Node<K, V> node) {
        if (node == head) {
            return;
        }
        unlink(node);
        addToHead(node);
    }

    private void unlink(Node<K, V> node) {
        if (node.prev != null) {
            node.prev.next = node.next;
        } else {
            head = node.next;
        }
        if (node.next != null) {
            node.next.prev = node.prev;
        } else {
            tail = node.prev;
        }
        node.prev = null;
        node.next = null;
    }

    private void removeNode(Node<K, V> node) {
        unlink(node);
        map.remove(node.key);
    }

    private static final class Node<K, V> {
        final K key;
        V value;
        long expireAtMillis;
        Node<K, V> prev;
        Node<K, V> next;

        Node(K key, V value, long expireAtMillis) {
            this.key = key;
            this.value = value;
            this.expireAtMillis = expireAtMillis;
        }
    }
}
```

- [ ] **Step 4: Compile and run Task 1 tests**

```powershell
javac -d out src\ExpiringCache.java src\ExpiringCacheTest.java
java -cp out ExpiringCacheTest
```

Expected: `Passed: N  Failed: 0` (exit code 0). Dots printed for each assertion.

- [ ] **Step 5: Commit**

```powershell
git add src/ExpiringCache.java src/ExpiringCacheTest.java
git commit -m "feat: add ExpiringCache put/get with validation"
```

---

