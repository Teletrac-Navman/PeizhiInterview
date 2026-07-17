# Concurrent Expiring Cache Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement a thread-safe `ExpiringCache<K,V>` with LRU eviction, per-entry TTL, and single-flight `computeIfAbsent`, plus a dependency-free test harness and README run notes.

**Architecture:** One `ReentrantLock` guards a `HashMap` + doubly linked LRU list (MRU at head, LRU at tail) and an in-flight `CompletableFuture` map. Loaders run outside the lock. Clock is an injectable `LongSupplier` for deterministic expiry tests.

**Tech Stack:** Java 11+ (for `var` optional; stick to Java 8+ APIs: `ReentrantLock`, `CompletableFuture`, `LongSupplier`, `Function`). Plain `javac`/`java` — no Maven/Gradle/JUnit.

**Spec:** `docs/superpowers/specs/2026-07-17-expiring-cache-design.md`

## Global Constraints

- Reject null keys, null values, null loader results, null loaders, non-positive capacity, and non-positive TTL with `IllegalArgumentException`.
- Same-key loader re-entry throws `IllegalStateException`; other-key re-entry is allowed.
- Expired entries never returned; lazy removal OK; `size()` ignores expired.
- Average O(1) `get`/`put`; document if `size()`/eviction exceed O(1).
- No build tool; tests via `main` + assert helpers.
- Keep existing README question text; append policy + compile/run instructions only.

---

## File Structure

| File | Responsibility |
| --- | --- |
| `src/ExpiringCache.java` | Cache implementation (Node, map, LRU list, lock, in-flight loads, API) |
| `src/ExpiringCacheTest.java` | Dependency-free test runner (`main` + assert helpers + all cases) |
| `README.md` | Existing interview question + appended edge-case policy and build/run |

---

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

- [ ] **Step 2: Compile — expect failure (class missing)**

Run (from repo root, PowerShell):

```powershell
New-Item -ItemType Directory -Force -Path out | Out-Null
javac -d out src\ExpiringCacheTest.java
```

Expected: compiler error — `ExpiringCache` cannot be found.

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

- [ ] **Step 2: Run tests — expect LRU failure**

```powershell
javac -d out src\ExpiringCache.java src\ExpiringCacheTest.java
java -cp out ExpiringCacheTest
```

Expected: FAIL — `c` insert grows size past 2 / `b` still present (until eviction implemented).

- [ ] **Step 3: Implement eviction in `put`**

Replace the “new node” branch in `put` with:

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

- [ ] **Step 4: Run tests — expect pass**

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

### Task 3: Expiration semantics and accurate `size()`

**Files:**
- Modify: `src/ExpiringCache.java` (`size`, ensure expired never returned — already partly in `get`)
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

- [ ] **Step 2: Run — expect `size` failure (still counts expired)**

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

- [ ] **Step 4: Run — expect pass**

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

### Task 4: `computeIfAbsent` — hit, load, single-flight, failure, re-entry

**Files:**
- Modify: `src/ExpiringCache.java` (in-flight map, ThreadLocal, full `computeIfAbsent`)
- Modify: `src/ExpiringCacheTest.java`

**Interfaces:**
- Consumes: `put`/`get` under lock helpers
- Produces:
  - `V computeIfAbsent(K key, long ttlMillis, Function<K, V> loader)`
  - Single-flight per key; loaders outside lock; same-key re-entry → `IllegalStateException`

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

Note: `testDuplicateLoadSuppression` uses `throws Exception` — either declare `main throws Exception` or wrap in try/catch that calls `fail`.

- [ ] **Step 2: Run — expect UnsupportedOperationException / failures**

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

Note: waiter path unlocks before `join`, re-locks in its `finally`, then the outer `finally` unlocks once more before returning — that pairing is intentional.

- [ ] **Step 4: Run — expect pass**

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

### Task 5: Concurrency smoke + different-key loads + README

**Files:**
- Modify: `src/ExpiringCacheTest.java`
- Modify: `README.md`

**Interfaces:**
- Consumes: full `ExpiringCache` API
- Produces: concurrent tests green; README documents policy + compile/run

- [ ] **Step 1: Add concurrency tests**

In `main`:

```java
testConcurrentGetPut();
testConcurrentLoadDifferentKeys();
```

```java
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
```

Update `main` signature to `throws Exception`.

- [ ] **Step 2: Run full suite**

```powershell
javac -d out src\ExpiringCache.java src\ExpiringCacheTest.java
java -cp out ExpiringCacheTest
```

Expected: `Failed: 0`.

- [ ] **Step 3: Append README sections (keep existing question intact)**

Append to `README.md`:

```markdown
## Documented edge-case policy

- Null keys, null values, null loaders, and null loader results throw `IllegalArgumentException`.
- Non-positive capacity or TTL throws `IllegalArgumentException`.
- Loader re-entry on the same key throws `IllegalStateException`; re-entry on other keys is allowed.
- Expired entries are never returned; removal is lazy. `size()` ignores expired entries.
- `get`/`put` aim for average O(1). `size()` may be O(n). Eviction may be O(k) over trailing expired LRU nodes.

## Solution layout

- `src/ExpiringCache.java` — implementation
- `src/ExpiringCacheTest.java` — dependency-free tests (`main`)

## Build and run tests

Requires JDK 8+ on `PATH`.

```powershell
New-Item -ItemType Directory -Force -Path out | Out-Null
javac -d out src\ExpiringCache.java src\ExpiringCacheTest.java
java -cp out ExpiringCacheTest
```

Expected output ends with `Failed: 0`.
```

Also add a short “Implementation notes” bullet list covering: `HashMap` + doubly linked LRU, single `ReentrantLock`, in-flight `CompletableFuture` for single-flight loads, injectable clock for tests.

- [ ] **Step 4: Final verification**

```powershell
javac -d out src\ExpiringCache.java src\ExpiringCacheTest.java
java -cp out ExpiringCacheTest
```

Expected: pass.

- [ ] **Step 5: Commit**

```powershell
git add src/ExpiringCache.java src/ExpiringCacheTest.java README.md
git commit -m "test: add concurrency coverage and document run instructions"
```

---

## Spec coverage checklist

| Spec requirement | Task |
| --- | --- |
| Fixed capacity + LRU | Task 2 |
| Per-entry TTL, lazy expiry, size ignores expired | Task 3 |
| Thread safety (single lock) | Tasks 1–5 |
| Atomic / single-flight loading | Task 4 |
| Loader failure / no cache / no deadlock | Task 4 |
| Null / bad TTL policy (`IllegalArgumentException`) | Task 1, 4 |
| Same-key re-entry (`IllegalStateException`) | Task 4 |
| O(1) target + document non-O(1) | Task 1 javadoc + Task 5 README |
| Plain Java + README run notes | Tasks 1, 5 |
| Unit tests listed in README question | Tasks 1–5 |
| Injectable clock | Task 1 |

## Self-review notes

- No Maven/JUnit — matches spec.
- `putUnderLock` shared by `put` and loader success path — avoids divergent insert logic.
- Waiter path unlocks before `join` so loaders for other keys and the active loader are not blocked by waiters holding the lock.
- Commit steps included per plan skill; skip committing during execution if the user has asked not to commit unless requested.
