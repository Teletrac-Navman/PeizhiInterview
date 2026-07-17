# Review package Task 4
Base: 3c48fe12bed3fc5b1ec8f8d03a06cb0437bc3042
Head: cc92ee621df4aab7dce4d38a189797bac45ffbe1

## Commits
cc92ee6 feat: add single-flight computeIfAbsent


## Stat
 src/ExpiringCache.java     | 119 +++++++++++++++++++++++++++++++++++++++------
 src/ExpiringCacheTest.java |  83 ++++++++++++++++++++++++++++++-
 2 files changed, 186 insertions(+), 16 deletions(-)


## Diff
```diff
diff --git a/src/ExpiringCache.java b/src/ExpiringCache.java
index f990f10..8f66c0f 100644
--- a/src/ExpiringCache.java
+++ b/src/ExpiringCache.java
@@ -1,26 +1,32 @@
 import java.util.HashMap;
+import java.util.HashSet;
 import java.util.Map;
+import java.util.Set;
+import java.util.concurrent.CompletableFuture;
+import java.util.concurrent.CompletionException;
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
+    private final Map<K, CompletableFuture<V>> inFlight = new HashMap<>();
+    private final ThreadLocal<Set<K>> loadingKeys = ThreadLocal.withInitial(HashSet::new);
     private Node<K, V> head; // MRU
     private Node<K, V> tail; // LRU
 
     public ExpiringCache(int capacity) {
         this(capacity, System::currentTimeMillis);
     }
 
     public ExpiringCache(int capacity, LongSupplier clock) {
         if (capacity <= 0) {
             throw new IllegalArgumentException("capacity must be positive");
@@ -48,61 +54,144 @@ public class ExpiringCache<K, V> {
             return node.value;
         } finally {
             lock.unlock();
         }
     }
 
     public void put(K key, V value, long ttlMillis) {
         requireKey(key);
         requireValue(value);
         requireTtl(ttlMillis);
-        long expireAt = clock.getAsLong() + ttlMillis;
         lock.lock();
         try {
-            Node<K, V> existing = map.get(key);
-            if (existing != null) {
-                existing.value = value;
-                existing.expireAtMillis = expireAt;
-                moveToHead(existing);
-                return;
-            }
-            while (map.size() >= capacity) {
-                evictOne();
-            }
-            Node<K, V> node = new Node<>(key, value, expireAt);
-            map.put(key, node);
-            addToHead(node);
+            putUnderLock(key, value, ttlMillis);
         } finally {
             lock.unlock();
         }
     }
 
+    /** Caller must hold lock. */
+    private void putUnderLock(K key, V value, long ttlMillis) {
+        long expireAt = clock.getAsLong() + ttlMillis;
+        Node<K, V> existing = map.get(key);
+        if (existing != null) {
+            existing.value = value;
+            existing.expireAtMillis = expireAt;
+            moveToHead(existing);
+            return;
+        }
+        while (map.size() >= capacity) {
+            evictOne();
+        }
+        Node<K, V> node = new Node<>(key, value, expireAt);
+        map.put(key, node);
+        addToHead(node);
+    }
+
     /** Evict from LRU end: drop expired nodes; evict first live LRU. May be O(k). */
     private void evictOne() {
         Node<K, V> node = tail;
         while (node != null) {
             Node<K, V> prev = node.prev;
             if (isExpired(node)) {
                 removeNode(node);
                 if (map.size() < capacity) {
                     return;
                 }
                 node = prev;
                 continue;
             }
             removeNode(node);
             return;
         }
     }
 
     public V computeIfAbsent(K key, long ttlMillis, Function<K, V> loader) {
-        throw new UnsupportedOperationException("not implemented yet");
+        requireKey(key);
+        requireTtl(ttlMillis);
+        if (loader == null) {
+            throw new IllegalArgumentException("loader must be non-null");
+        }
+
+        CompletableFuture<V> assigned = null;
+
+        lock.lock();
+        try {
+            Node<K, V> existing = map.get(key);
+            if (existing != null) {
+                if (!isExpired(existing)) {
+                    moveToHead(existing);
+                    return existing.value;
+                }
+                removeNode(existing);
+            }
+
+            if (loadingKeys.get().contains(key)) {
+                throw new IllegalStateException("re-entrant computeIfAbsent for key: " + key);
+            }
+
+            CompletableFuture<V> pending = inFlight.get(key);
+            if (pending != null) {
+                lock.unlock();
+                try {
+                    return unwrapJoin(pending);
+                } finally {
+                    lock.lock();
+                }
+            }
+
+            assigned = new CompletableFuture<>();
+            inFlight.put(key, assigned);
+            loadingKeys.get().add(key);
+        } finally {
+            lock.unlock();
+        }
+
+        // Loader path (lock released)
+        try {
+            V value = loader.apply(key);
+            if (value == null) {
+                throw new IllegalArgumentException("loader must not return null");
+            }
+            lock.lock();
+            try {
+                putUnderLock(key, value, ttlMillis);
+                assigned.complete(value);
+                return value;
+            } finally {
+                inFlight.remove(key, assigned);
+                loadingKeys.get().remove(key);
+                lock.unlock();
+            }
+        } catch (RuntimeException e) {
+            lock.lock();
+            try {
+                assigned.completeExceptionally(e);
+                inFlight.remove(key, assigned);
+                loadingKeys.get().remove(key);
+            } finally {
+                lock.unlock();
+            }
+            throw e;
+        }
+    }
+
+    private V unwrapJoin(CompletableFuture<V> pending) {
+        try {
+            return pending.join();
+        } catch (CompletionException e) {
+            Throwable cause = e.getCause() == null ? e : e.getCause();
+            if (cause instanceof RuntimeException) {
+                throw (RuntimeException) cause;
+            }
+            throw new IllegalStateException(cause);
+        }
     }
 
     public int size() {
         lock.lock();
         try {
             Node<K, V> node = head;
             while (node != null) {
                 Node<K, V> next = node.next;
                 if (isExpired(node)) {
                     removeNode(node);
diff --git a/src/ExpiringCacheTest.java b/src/ExpiringCacheTest.java
index fc647dd..827aaaf 100644
--- a/src/ExpiringCacheTest.java
+++ b/src/ExpiringCacheTest.java
@@ -1,29 +1,34 @@
 import java.util.concurrent.atomic.AtomicLong;
 import java.util.function.LongSupplier;
 
 public class ExpiringCacheTest {
 
     private static int passed = 0;
     private static int failed = 0;
 
-    public static void main(String[] args) {
+    public static void main(String[] args) throws Exception {
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
+        testComputeIfAbsentHitAndLoad();
+        testDuplicateLoadSuppression();
+        testLoaderFailureAndRetry();
+        testSameKeyReentryThrows();
+        testLoaderNullResultRejected();
 
         System.out.println();
         System.out.println("Passed: " + passed + "  Failed: " + failed);
         if (failed > 0) {
             System.exit(1);
         }
     }
 
     static void testRejectsNonPositiveCapacity() {
         expectThrows(IllegalArgumentException.class, () -> new ExpiringCache<String, String>(0));
@@ -112,20 +117,96 @@ public class ExpiringCacheTest {
         ExpiringCache<String, String> cache = new ExpiringCache<>(2, now::get);
         cache.put("a", "alpha", 50);   // will expire
         cache.put("b", "bravo", 5_000);
         now.set(1_000_050L);
         cache.put("c", "charlie", 5_000); // should drop expired a, keep b, add c
         assertNull(cache.get("a"));
         assertEq("bravo", cache.get("b"));
         assertEq("charlie", cache.get("c"));
     }
 
+    static void testComputeIfAbsentHitAndLoad() {
+        AtomicLong now = new AtomicLong(1_000_000L);
+        ExpiringCache<String, String> cache = new ExpiringCache<>(2, now::get);
+        String v = cache.computeIfAbsent("a", 5_000, k -> "loaded-" + k);
+        assertEq("loaded-a", v);
+        assertEq("loaded-a", cache.get("a"));
+        String again = cache.computeIfAbsent("a", 5_000, k -> {
+            fail("loader should not run on hit");
+            return "nope";
+        });
+        assertEq("loaded-a", again);
+    }
+
+    static void testDuplicateLoadSuppression() throws Exception {
+        AtomicLong now = new AtomicLong(1_000_000L);
+        ExpiringCache<String, String> cache = new ExpiringCache<>(2, now::get);
+        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
+        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
+        java.util.concurrent.atomic.AtomicInteger loads = new java.util.concurrent.atomic.AtomicInteger();
+
+        Thread t1 = new Thread(() -> cache.computeIfAbsent("k", 5_000, key -> {
+            loads.incrementAndGet();
+            started.countDown();
+            try {
+                release.await();
+            } catch (InterruptedException e) {
+                Thread.currentThread().interrupt();
+                throw new IllegalStateException(e);
+            }
+            return "v";
+        }));
+        t1.start();
+        started.await();
+        Thread t2 = new Thread(() -> cache.computeIfAbsent("k", 5_000, key -> {
+            loads.incrementAndGet();
+            return "other";
+        }));
+        t2.start();
+        Thread.sleep(50); // give t2 time to attach as waiter
+        release.countDown();
+        t1.join();
+        t2.join();
+        assertEq(1, loads.get());
+        assertEq("v", cache.get("k"));
+    }
+
+    static void testLoaderFailureAndRetry() {
+        AtomicLong now = new AtomicLong(1_000_000L);
+        ExpiringCache<String, String> cache = new ExpiringCache<>(2, now::get);
+        java.util.concurrent.atomic.AtomicInteger loads = new java.util.concurrent.atomic.AtomicInteger();
+        expectThrows(RuntimeException.class, () -> cache.computeIfAbsent("k", 5_000, key -> {
+            loads.incrementAndGet();
+            throw new RuntimeException("boom");
+        }));
+        assertNull(cache.get("k"));
+        String v = cache.computeIfAbsent("k", 5_000, key -> {
+            loads.incrementAndGet();
+            return "ok";
+        });
+        assertEq("ok", v);
+        assertEq(2, loads.get());
+    }
+
+    static void testSameKeyReentryThrows() {
+        ExpiringCache<String, String> cache = new ExpiringCache<>(2);
+        expectThrows(IllegalStateException.class, () -> cache.computeIfAbsent("k", 5_000, key ->
+                cache.computeIfAbsent("k", 5_000, k2 -> "nested")));
+    }
+
+    static void testLoaderNullResultRejected() {
+        ExpiringCache<String, String> cache = new ExpiringCache<>(2);
+        expectThrows(IllegalArgumentException.class, () ->
+                cache.computeIfAbsent("k", 5_000, key -> null));
+        assertNull(cache.get("k"));
+    }
+
     static void assertEq(Object expected, Object actual) {
         if (expected == null ? actual != null : !expected.equals(actual)) {
             fail("Expected " + expected + " but was " + actual);
         } else {
             pass();
         }
     }
 
     static void assertNull(Object actual) {
         if (actual != null) {

```
