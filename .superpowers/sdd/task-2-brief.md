### Task 2: LRU eviction at capacity

**Files:**
- Modify: `src/ExpiringCache.java` (`put` eviction path)
- Modify: `src/ExpiringCacheTest.java` (add LRU tests; keep calling them from `main`)

**Interfaces:**
- Consumes: Task 1 API + list helpers
- Produces: same API; `put` evicts LRU live entries when over capacity

- [ ] **Step 1: Add failing LRU tests to `ExpiringCacheTest`**

Add to `main`:

```java
testLruEviction();
testGetUpdatesLruOrder();
testUpdateExistingKey();
```

Add methods:

```java
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
```

- [ ] **Step 2: Run tests â€” expect LRU failure**

```powershell
javac -d out src\ExpiringCache.java src\ExpiringCacheTest.java
java -cp out ExpiringCacheTest
```

Expected: FAIL â€” `c` insert grows size past 2 / `b` still present (until eviction implemented).

- [ ] **Step 3: Implement eviction in `put`**

Replace the â€œnew nodeâ€ branch in `put` with:

```java
while (map.size() >= capacity) {
    evictOne();
}
Node<K, V> node = new Node<>(key, value, expireAt);
map.put(key, node);
addToHead(node);
```

Add:

```java
/** Evict from LRU end: drop expired nodes; evict first live LRU. May be O(k). */
private void evictOne() {
    Node<K, V> node = tail;
    while (node != null) {
        Node<K, V> prev = node.prev;
        if (isExpired(node)) {
            removeNode(node);
            node = prev;
            continue;
        }
        removeNode(node);
        return;
    }
}
```

- [ ] **Step 4: Run tests â€” expect pass**

```powershell
javac -d out src\ExpiringCache.java src\ExpiringCacheTest.java
java -cp out ExpiringCacheTest
```

Expected: all pass.

- [ ] **Step 5: Commit**

```powershell
git add src/ExpiringCache.java src/ExpiringCacheTest.java
git commit -m "feat: add LRU eviction to ExpiringCache"
```

---

