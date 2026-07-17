### Task 3: Expiration semantics and accurate `size()`

**Files:**
- Modify: `src/ExpiringCache.java` (`size`, ensure expired never returned â€” already partly in `get`)
- Modify: `src/ExpiringCacheTest.java`

**Interfaces:**
- Consumes: clock-injected constructor
- Produces: `size()` counts only non-expired; may purge while counting

- [ ] **Step 1: Add failing expiry tests**

In `main`:

```java
testExpiration();
testSizeIgnoresExpired();
testExpiredSkippedDuringEviction();
```

```java
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
```

- [ ] **Step 2: Run â€” expect `size` failure (still counts expired)**

```powershell
javac -d out src\ExpiringCache.java src\ExpiringCacheTest.java
java -cp out ExpiringCacheTest
```

Expected: `testSizeIgnoresExpired` fails until `size()` purges.

- [ ] **Step 3: Implement expiry-aware `size()`**

```java
public int size() {
    lock.lock();
    try {
        Node<K, V> node = head;
        while (node != null) {
            Node<K, V> next = node.next;
            if (isExpired(node)) {
                removeNode(node);
            }
            node = next;
        }
        return map.size();
    } finally {
        lock.unlock();
    }
}
```

Ensure `put` for existing keys still refreshes TTL (already done). No other changes required if `get`/`evictOne` already handle expiry.

- [ ] **Step 4: Run â€” expect pass**

```powershell
javac -d out src\ExpiringCache.java src\ExpiringCacheTest.java
java -cp out ExpiringCacheTest
```

Expected: all pass.

- [ ] **Step 5: Commit**

```powershell
git add src/ExpiringCache.java src/ExpiringCacheTest.java
git commit -m "feat: honor TTL in get, size, and eviction"
```

---

