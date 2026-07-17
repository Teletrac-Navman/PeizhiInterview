# Review package Task 2
Base: 72445377da8c247cdfaa0250ddcf87903b03126a
Head: 28a984a971725ae154e0a9fffc67d716cd4d6407

## Commits
28a984a feat: add LRU eviction to ExpiringCache


## Stat
 src/ExpiringCache.java     | 19 ++++++++++++++++++-
 src/ExpiringCacheTest.java | 39 +++++++++++++++++++++++++++++++++++++++
 2 files changed, 57 insertions(+), 1 deletion(-)


## Diff
```diff
diff --git a/src/ExpiringCache.java b/src/ExpiringCache.java
index f663d76..903e623 100644
--- a/src/ExpiringCache.java
+++ b/src/ExpiringCache.java
@@ -58,29 +58,46 @@ public class ExpiringCache<K, V> {
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
-            // Capacity / eviction added in Task 2
+            while (map.size() >= capacity) {
+                evictOne();
+            }
             Node<K, V> node = new Node<>(key, value, expireAt);
             map.put(key, node);
             addToHead(node);
         } finally {
             lock.unlock();
         }
     }
 
+    /** Evict from LRU end: drop expired nodes; evict first live LRU. May be O(k). */
+    private void evictOne() {
+        Node<K, V> node = tail;
+        while (node != null) {
+            Node<K, V> prev = node.prev;
+            if (isExpired(node)) {
+                removeNode(node);
+                node = prev;
+                continue;
+            }
+            removeNode(node);
+            return;
+        }
+    }
+
     public V computeIfAbsent(K key, long ttlMillis, Function<K, V> loader) {
         throw new UnsupportedOperationException("not implemented yet");
     }
 
     public int size() {
         lock.lock();
         try {
             return map.size(); // expiry-aware size in Task 3
         } finally {
             lock.unlock();
diff --git a/src/ExpiringCacheTest.java b/src/ExpiringCacheTest.java
index 1f438e5..4190447 100644
--- a/src/ExpiringCacheTest.java
+++ b/src/ExpiringCacheTest.java
@@ -4,20 +4,23 @@ import java.util.function.LongSupplier;
 public class ExpiringCacheTest {
 
     private static int passed = 0;
     private static int failed = 0;
 
     public static void main(String[] args) {
         testRejectsNonPositiveCapacity();
         testRejectsNullKeyAndValueAndBadTtl();
         testPutGetAndSize();
         testGetMissReturnsNull();
+        testLruEviction();
+        testGetUpdatesLruOrder();
+        testUpdateExistingKey();
 
         System.out.println();
         System.out.println("Passed: " + passed + "  Failed: " + failed);
         if (failed > 0) {
             System.exit(1);
         }
     }
 
     static void testRejectsNonPositiveCapacity() {
         expectThrows(IllegalArgumentException.class, () -> new ExpiringCache<String, String>(0));
@@ -39,20 +42,56 @@ public class ExpiringCacheTest {
         cache.put("a", "alpha", 5_000);
         assertEq("alpha", cache.get("a"));
         assertEq(1, cache.size());
     }
 
     static void testGetMissReturnsNull() {
         ExpiringCache<String, String> cache = new ExpiringCache<>(2);
         assertNull(cache.get("missing"));
     }
 
+    static void testUpdateExistingKey() {
+        AtomicLong now = new AtomicLong(1_000_000L);
+        ExpiringCache<String, String> cache = new ExpiringCache<>(2, now::get);
+        cache.put("a", "alpha", 5_000);
+        cache.put("b", "bravo", 5_000);
+        cache.put("a", "ALPHA", 5_000); // update + becomes MRU
+        cache.put("c", "charlie", 5_000); // evicts b (LRU)
+        assertEq("ALPHA", cache.get("a"));
+        assertNull(cache.get("b"));
+        assertEq("charlie", cache.get("c"));
+        assertEq(2, cache.size());
+    }
+
+    static void testLruEviction() {
+        AtomicLong now = new AtomicLong(1_000_000L);
+        ExpiringCache<String, String> cache = new ExpiringCache<>(2, now::get);
+        cache.put("a", "alpha", 5_000);
+        cache.put("b", "bravo", 5_000);
+        cache.get("a"); // a becomes MRU; b is LRU
+        cache.put("c", "charlie", 5_000); // evict b
+        assertNull(cache.get("b"));
+        assertEq("alpha", cache.get("a"));
+        assertEq("charlie", cache.get("c"));
+        assertEq(2, cache.size());
+    }
+
+    static void testGetUpdatesLruOrder() {
+        AtomicLong now = new AtomicLong(1_000_000L);
+        ExpiringCache<String, String> cache = new ExpiringCache<>(2, now::get);
+        cache.put("a", "alpha", 5_000);
+        cache.put("b", "bravo", 5_000);
+        cache.get("a");
+        cache.put("c", "charlie", 5_000);
+        assertNull(cache.get("b"));
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
