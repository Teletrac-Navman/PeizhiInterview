# Review package Task 3
Base: 28a984a971725ae154e0a9fffc67d716cd4d6407
Head: 3c48fe12bed3fc5b1ec8f8d03a06cb0437bc3042

## Commits
3c48fe1 feat: honor TTL in get, size, and eviction


## Stat
 src/ExpiringCache.java     | 13 ++++++++++++-
 src/ExpiringCacheTest.java | 34 ++++++++++++++++++++++++++++++++++
 2 files changed, 46 insertions(+), 1 deletion(-)


## Diff
```diff
diff --git a/src/ExpiringCache.java b/src/ExpiringCache.java
index 903e623..f990f10 100644
--- a/src/ExpiringCache.java
+++ b/src/ExpiringCache.java
@@ -76,36 +76,47 @@ public class ExpiringCache<K, V> {
         }
     }
 
     /** Evict from LRU end: drop expired nodes; evict first live LRU. May be O(k). */
     private void evictOne() {
         Node<K, V> node = tail;
         while (node != null) {
             Node<K, V> prev = node.prev;
             if (isExpired(node)) {
                 removeNode(node);
+                if (map.size() < capacity) {
+                    return;
+                }
                 node = prev;
                 continue;
             }
             removeNode(node);
             return;
         }
     }
 
     public V computeIfAbsent(K key, long ttlMillis, Function<K, V> loader) {
         throw new UnsupportedOperationException("not implemented yet");
     }
 
     public int size() {
         lock.lock();
         try {
-            return map.size(); // expiry-aware size in Task 3
+            Node<K, V> node = head;
+            while (node != null) {
+                Node<K, V> next = node.next;
+                if (isExpired(node)) {
+                    removeNode(node);
+                }
+                node = next;
+            }
+            return map.size();
         } finally {
             lock.unlock();
         }
     }
 
     private boolean isExpired(Node<K, V> node) {
         return node.expireAtMillis <= clock.getAsLong();
     }
 
     private void requireKey(K key) {
diff --git a/src/ExpiringCacheTest.java b/src/ExpiringCacheTest.java
index 4190447..fc647dd 100644
--- a/src/ExpiringCacheTest.java
+++ b/src/ExpiringCacheTest.java
@@ -7,20 +7,23 @@ public class ExpiringCacheTest {
     private static int failed = 0;
 
     public static void main(String[] args) {
         testRejectsNonPositiveCapacity();
         testRejectsNullKeyAndValueAndBadTtl();
         testPutGetAndSize();
         testGetMissReturnsNull();
         testLruEviction();
         testGetUpdatesLruOrder();
         testUpdateExistingKey();
+        testExpiration();
+        testSizeIgnoresExpired();
+        testExpiredSkippedDuringEviction();
 
         System.out.println();
         System.out.println("Passed: " + passed + "  Failed: " + failed);
         if (failed > 0) {
             System.exit(1);
         }
     }
 
     static void testRejectsNonPositiveCapacity() {
         expectThrows(IllegalArgumentException.class, () -> new ExpiringCache<String, String>(0));
@@ -78,20 +81,51 @@ public class ExpiringCacheTest {
     static void testGetUpdatesLruOrder() {
         AtomicLong now = new AtomicLong(1_000_000L);
         ExpiringCache<String, String> cache = new ExpiringCache<>(2, now::get);
         cache.put("a", "alpha", 5_000);
         cache.put("b", "bravo", 5_000);
         cache.get("a");
         cache.put("c", "charlie", 5_000);
         assertNull(cache.get("b"));
     }
 
+    static void testExpiration() {
+        AtomicLong now = new AtomicLong(1_000_000L);
+        ExpiringCache<String, String> cache = new ExpiringCache<>(2, now::get);
+        cache.put("a", "alpha", 100); // expires at 1_000_100
+        assertEq("alpha", cache.get("a"));
+        now.set(1_000_100L);
+        assertNull(cache.get("a"));
+    }
+
+    static void testSizeIgnoresExpired() {
+        AtomicLong now = new AtomicLong(1_000_000L);
+        ExpiringCache<String, String> cache = new ExpiringCache<>(2, now::get);
+        cache.put("a", "alpha", 50);
+        cache.put("b", "bravo", 5_000);
+        now.set(1_000_050L);
+        assertEq(1, cache.size());
+        assertEq("bravo", cache.get("b"));
+    }
+
+    static void testExpiredSkippedDuringEviction() {
+        AtomicLong now = new AtomicLong(1_000_000L);
+        ExpiringCache<String, String> cache = new ExpiringCache<>(2, now::get);
+        cache.put("a", "alpha", 50);   // will expire
+        cache.put("b", "bravo", 5_000);
+        now.set(1_000_050L);
+        cache.put("c", "charlie", 5_000); // should drop expired a, keep b, add c
+        assertNull(cache.get("a"));
+        assertEq("bravo", cache.get("b"));
+        assertEq("charlie", cache.get("c"));
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
