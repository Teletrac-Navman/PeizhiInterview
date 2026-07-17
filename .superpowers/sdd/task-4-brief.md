### Task 4: `computeIfAbsent` â€” hit, load, single-flight, failure, re-entry

**Files:**
- Modify: `src/ExpiringCache.java` (in-flight map, ThreadLocal, full `computeIfAbsent`)
- Modify: `src/ExpiringCacheTest.java`

**Interfaces:**
- Consumes: `put`/`get` under lock helpers
- Produces:
  - `V computeIfAbsent(K key, long ttlMillis, Function<K, V> loader)`
  - Single-flight per key; loaders outside lock; same-key re-entry â†’ `IllegalStateException`

- [ ] **Step 1: Add failing computeIfAbsent tests**

In `main`:

```java
testComputeIfAbsentHitAndLoad();
testDuplicateLoadSuppression();
testLoaderFailureAndRetry();
testSameKeyReentryThrows();
testLoaderNullResultRejected();
```

```java
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
```

Note: `testDuplicateLoadSuppression` uses `throws Exception` â€” either declare `main throws Exception` or wrap in try/catch that calls `fail`.

- [ ] **Step 2: Run â€” expect UnsupportedOperationException / failures**

```powershell
javac -d out src\ExpiringCache.java src\ExpiringCacheTest.java
java -cp out ExpiringCacheTest
```

Expected: FAIL on computeIfAbsent tests.

- [ ] **Step 3: Implement `computeIfAbsent`**

Add fields:

```java
private final Map<K, java.util.concurrent.CompletableFuture<V>> inFlight = new HashMap<>();
private final ThreadLocal<java.util.Set<K>> loadingKeys =
        ThreadLocal.withInitial(java.util.HashSet::new);
```

Implement two-phase `computeIfAbsent` (lock only for bookkeeping; loader runs unlocked). Also extract `putUnderLock` and refactor public `put` to call it.

```java
public V computeIfAbsent(K key, long ttlMillis, Function<K, V> loader) {
    requireKey(key);
    requireTtl(ttlMillis);
    if (loader == null) {
        throw new IllegalArgumentException("loader must be non-null");
    }

    java.util.concurrent.CompletableFuture<V> assigned = null;

    lock.lock();
    try {
        Node<K, V> existing = map.get(key);
        if (existing != null) {
            if (!isExpired(existing)) {
                moveToHead(existing);
                return existing.value;
            }
            removeNode(existing);
        }

        if (loadingKeys.get().contains(key)) {
            throw new IllegalStateException("re-entrant computeIfAbsent for key: " + key);
        }

        java.util.concurrent.CompletableFuture<V> pending = inFlight.get(key);
        if (pending != null) {
            lock.unlock();
            try {
                return unwrapJoin(pending);
            } finally {
                lock.lock();
            }
        }

        assigned = new java.util.concurrent.CompletableFuture<>();
        inFlight.put(key, assigned);
        loadingKeys.get().add(key);
    } finally {
        lock.unlock();
    }

    // Loader path (lock released)
    try {
        V value = loader.apply(key);
        if (value == null) {
            throw new IllegalArgumentException("loader must not return null");
        }
        lock.lock();
        try {
            putUnderLock(key, value, ttlMillis);
            assigned.complete(value);
            return value;
        } finally {
            inFlight.remove(key, assigned);
            loadingKeys.get().remove(key);
            lock.unlock();
        }
    } catch (RuntimeException e) {
        lock.lock();
        try {
            assigned.completeExceptionally(e);
            inFlight.remove(key, assigned);
            loadingKeys.get().remove(key);
        } finally {
            lock.unlock();
        }
        throw e;
    }
}

private V unwrapJoin(java.util.concurrent.CompletableFuture<V> pending) {
    try {
        return pending.join();
    } catch (java.util.concurrent.CompletionException e) {
        Throwable cause = e.getCause() == null ? e : e.getCause();
        if (cause instanceof RuntimeException) {
            throw (RuntimeException) cause;
        }
        throw new IllegalStateException(cause);
    }
}

/** Caller must hold lock. */
private void putUnderLock(K key, V value, long ttlMillis) {
    long expireAt = clock.getAsLong() + ttlMillis;
    Node<K, V> existing = map.get(key);
    if (existing != null) {
        existing.value = value;
        existing.expireAtMillis = expireAt;
        moveToHead(existing);
        return;
    }
    while (map.size() >= capacity) {
        evictOne();
    }
    Node<K, V> node = new Node<>(key, value, expireAt);
    map.put(key, node);
    addToHead(node);
}
```

Refactor public `put`:

```java
public void put(K key, V value, long ttlMillis) {
    requireKey(key);
    requireValue(value);
    requireTtl(ttlMillis);
    lock.lock();
    try {
        putUnderLock(key, value, ttlMillis);
    } finally {
        lock.unlock();
    }
}
```

Note: waiter path unlocks before `join`, re-locks in its `finally`, then the outer `finally` unlocks once more before returning â€” that pairing is intentional.

- [ ] **Step 4: Run â€” expect pass**

```powershell
javac -d out src\ExpiringCache.java src\ExpiringCacheTest.java
java -cp out ExpiringCacheTest
```

Expected: all pass. If `testDuplicateLoadSuppression` flakes, increase the short sleep or wait until `inFlight` is observed via a latch on the waiter side.

- [ ] **Step 5: Commit**

```powershell
git add src/ExpiringCache.java src/ExpiringCacheTest.java
git commit -m "feat: add single-flight computeIfAbsent"
```

---

