# Review package Task 1
Base: b8f299f9f66dbe148c8811d02d0e961915c0f078
Head: 72445377da8c247cdfaa0250ddcf87903b03126a

## Commits
7244537 feat: add ExpiringCache put/get with validation


## Stat
 src/ExpiringCache.java     | 165 +++++++++++++++++++++++++++++++++++++++++++++
 src/ExpiringCacheTest.java |  96 ++++++++++++++++++++++++++
 2 files changed, 261 insertions(+)


## Diff
```diff
diff --git a/src/ExpiringCache.java b/src/ExpiringCache.java
new file mode 100644
index 0000000..f663d76
--- /dev/null
+++ b/src/ExpiringCache.java
@@ -0,0 +1,165 @@
+import java.util.HashMap;
+import java.util.Map;
+import java.util.concurrent.locks.ReentrantLock;
+import java.util.function.Function;
+import java.util.function.LongSupplier;
+
+/**
+ * Thread-safe fixed-capacity cache with per-entry TTL and LRU eviction.
+ * get/put are average O(1). size() may be O(n) when purging expired entries.
+ */
+public class ExpiringCache<K, V> {
+
+    private final int capacity;
+    private final LongSupplier clock;
+    private final ReentrantLock lock = new ReentrantLock();
+    private final Map<K, Node<K, V>> map = new HashMap<>();
+    private Node<K, V> head; // MRU
+    private Node<K, V> tail; // LRU
+
+    public ExpiringCache(int capacity) {
+        this(capacity, System::currentTimeMillis);
+    }
+
+    public ExpiringCache(int capacity, LongSupplier clock) {
+        if (capacity <= 0) {
+            throw new IllegalArgumentException("capacity must be positive");
+        }
+        if (clock == null) {
+            throw new IllegalArgumentException("clock must be non-null");
+        }
+        this.capacity = capacity;
+        this.clock = clock;
+    }
+
+    public V get(K key) {
+        requireKey(key);
+        lock.lock();
+        try {
+            Node<K, V> node = map.get(key);
+            if (node == null) {
+                return null;
+            }
+            if (isExpired(node)) {
+                removeNode(node);
+                return null;
+            }
+            moveToHead(node);
+            return node.value;
+        } finally {
+            lock.unlock();
+        }
+    }
+
+    public void put(K key, V value, long ttlMillis) {
+        requireKey(key);
+        requireValue(value);
+        requireTtl(ttlMillis);
+        long expireAt = clock.getAsLong() + ttlMillis;
+        lock.lock();
+        try {
+            Node<K, V> existing = map.get(key);
+            if (existing != null) {
+                existing.value = value;
+                existing.expireAtMillis = expireAt;
+                moveToHead(existing);
+                return;
+            }
+            // Capacity / eviction added in Task 2
+            Node<K, V> node = new Node<>(key, value, expireAt);
+            map.put(key, node);
+            addToHead(node);
+        } finally {
+            lock.unlock();
+        }
+    }
+
+    public V computeIfAbsent(K key, long ttlMillis, Function<K, V> loader) {
+        throw new UnsupportedOperationException("not implemented yet");
+    }
+
+    public int size() {
+        lock.lock();
+        try {
+            return map.size(); // expiry-aware size in Task 3
+        } finally {
+            lock.unlock();
+        }
+    }
+
+    private boolean isExpired(Node<K, V> node) {
+        return node.expireAtMillis <= clock.getAsLong();
+    }
+
+    private void requireKey(K key) {
+        if (key == null) {
+            throw new IllegalArgumentException("key must be non-null");
+        }
+    }
+
+    private void requireValue(V value) {
+        if (value == null) {
+            throw new IllegalArgumentException("value must be non-null");
+        }
+    }
+
+    private void requireTtl(long ttlMillis) {
+        if (ttlMillis <= 0) {
+            throw new IllegalArgumentException("ttlMillis must be positive");
+        }
+    }
+
+    private void addToHead(Node<K, V> node) {
+        node.prev = null;
+        node.next = head;
+        if (head != null) {
+            head.prev = node;
+        }
+        head = node;
+        if (tail == null) {
+            tail = node;
+        }
+    }
+
+    private void moveToHead(Node<K, V> node) {
+        if (node == head) {
+            return;
+        }
+        unlink(node);
+        addToHead(node);
+    }
+
+    private void unlink(Node<K, V> node) {
+        if (node.prev != null) {
+            node.prev.next = node.next;
+        } else {
+            head = node.next;
+        }
+        if (node.next != null) {
+            node.next.prev = node.prev;
+        } else {
+            tail = node.prev;
+        }
+        node.prev = null;
+        node.next = null;
+    }
+
+    private void removeNode(Node<K, V> node) {
+        unlink(node);
+        map.remove(node.key);
+    }
+
+    private static final class Node<K, V> {
+        final K key;
+        V value;
+        long expireAtMillis;
+        Node<K, V> prev;
+        Node<K, V> next;
+
+        Node(K key, V value, long expireAtMillis) {
+            this.key = key;
+            this.value = value;
+            this.expireAtMillis = expireAtMillis;
+        }
+    }
+}
diff --git a/src/ExpiringCacheTest.java b/src/ExpiringCacheTest.java
new file mode 100644
index 0000000..1f438e5
--- /dev/null
+++ b/src/ExpiringCacheTest.java
@@ -0,0 +1,96 @@
+import java.util.concurrent.atomic.AtomicLong;
+import java.util.function.LongSupplier;
+
+public class ExpiringCacheTest {
+
+    private static int passed = 0;
+    private static int failed = 0;
+
+    public static void main(String[] args) {
+        testRejectsNonPositiveCapacity();
+        testRejectsNullKeyAndValueAndBadTtl();
+        testPutGetAndSize();
+        testGetMissReturnsNull();
+
+        System.out.println();
+        System.out.println("Passed: " + passed + "  Failed: " + failed);
+        if (failed > 0) {
+            System.exit(1);
+        }
+    }
+
+    static void testRejectsNonPositiveCapacity() {
+        expectThrows(IllegalArgumentException.class, () -> new ExpiringCache<String, String>(0));
+        expectThrows(IllegalArgumentException.class, () -> new ExpiringCache<String, String>(-1));
+    }
+
+    static void testRejectsNullKeyAndValueAndBadTtl() {
+        ExpiringCache<String, String> cache = new ExpiringCache<>(2);
+        expectThrows(IllegalArgumentException.class, () -> cache.put(null, "v", 1000));
+        expectThrows(IllegalArgumentException.class, () -> cache.put("k", null, 1000));
+        expectThrows(IllegalArgumentException.class, () -> cache.put("k", "v", 0));
+        expectThrows(IllegalArgumentException.class, () -> cache.put("k", "v", -5));
+        expectThrows(IllegalArgumentException.class, () -> cache.get(null));
+    }
+
+    static void testPutGetAndSize() {
+        AtomicLong now = new AtomicLong(1_000_000L);
+        ExpiringCache<String, String> cache = new ExpiringCache<>(2, now::get);
+        cache.put("a", "alpha", 5_000);
+        assertEq("alpha", cache.get("a"));
+        assertEq(1, cache.size());
+    }
+
+    static void testGetMissReturnsNull() {
+        ExpiringCache<String, String> cache = new ExpiringCache<>(2);
+        assertNull(cache.get("missing"));
+    }
+
+    static void assertEq(Object expected, Object actual) {
+        if (expected == null ? actual != null : !expected.equals(actual)) {
+            fail("Expected " + expected + " but was " + actual);
+        } else {
+            pass();
+        }
+    }
+
+    static void assertNull(Object actual) {
+        if (actual != null) {
+            fail("Expected null but was " + actual);
+        } else {
+            pass();
+        }
+    }
+
+    static void assertTrue(boolean condition, String message) {
+        if (!condition) {
+            fail(message);
+        } else {
+            pass();
+        }
+    }
+
+    static void expectThrows(Class<? extends RuntimeException> type, Runnable action) {
+        try {
+            action.run();
+            fail("Expected " + type.getSimpleName());
+        } catch (RuntimeException e) {
+            if (!type.isInstance(e)) {
+                fail("Expected " + type.getSimpleName() + " but got " + e);
+            } else {
+                pass();
+            }
+        }
+    }
+
+    static void pass() {
+        passed++;
+        System.out.print('.');
+    }
+
+    static void fail(String message) {
+        failed++;
+        System.out.println();
+        System.out.println("FAIL: " + message);
+    }
+}

```
