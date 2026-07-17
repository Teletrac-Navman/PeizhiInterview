# Design: Concurrent Expiring Cache (`ExpiringCache`)

Date: 2026-07-17  
Status: Approved for implementation planning  
Source requirements: repository `README.md` (Senior Java Coding Interview Question)

## Goal

Implement a generic, thread-safe, in-memory `ExpiringCache<K, V>` matching the README API and requirements: fixed capacity with LRU eviction, per-entry TTL, concurrent access, and single-flight `computeIfAbsent` loading.

## Decisions (from brainstorming)

| Topic | Decision |
| --- | --- |
| Architecture | Single global lock + `HashMap` + doubly linked LRU list |
| Null keys / values / loader results | Reject with `IllegalArgumentException` |
| Non-positive capacity / TTL | Reject with `IllegalArgumentException` |
| Loader re-entry | Allowed for other keys; same-key re-entry throws `IllegalStateException` |
| Project layout | Plain `.java` files (no Maven/Gradle) + short build/run note in README |
| Clock | Injectable `LongSupplier` (default `System::currentTimeMillis`) for deterministic expiry tests |

## Architecture

### Data structures

- `HashMap<K, Node>` for O(1) key lookup.
- Intrusive doubly linked list of `Node`s for LRU order:
  - MRU at head, LRU at tail. Eviction always starts at the tail.
- Each `Node` holds: `key`, `value`, `expireAtMillis`, `prev`, `next`.
- In-flight load registry: `Map<K, CompletableFuture<V>>` (or equivalent wait/notify structure) under the same lock, used only by `computeIfAbsent`.
- One `ReentrantLock` protects the map, LRU list, and in-flight registry.

### Public API (unchanged from README)

```java
public class ExpiringCache<K, V> {
    public ExpiringCache(int capacity) { ... }
    public V get(K key) { ... }
    public void put(K key, V value, long ttlMillis) { ... }
    public V computeIfAbsent(K key, long ttlMillis, Function<K, V> loader) { ... }
    public int size() { ... }
}
```

Package-private or public test-facing constructor may accept a `LongSupplier` clock for tests.

## Behavior details

### Validation

- `capacity <= 0` → `IllegalArgumentException`
- `key == null`, `value == null`, `loader == null` → `IllegalArgumentException`
- `ttlMillis <= 0` → `IllegalArgumentException`
- Loader returning `null` → `IllegalArgumentException` (nothing cached; in-flight cleared)

### Expiration

- Entry expiry time = `clock.getAsLong() + ttlMillis` at insert/update time.
- Expired entries must never be returned.
- Removal may be lazy (on `get` / `put` / `size` / eviction scan).
- `size()` counts only non-expired entries (may purge expired while counting).

### LRU

- Successful `get` (live hit) counts as access → move to MRU.
- Insertions and updates count as access → move to MRU.
- When capacity would be exceeded, evict least-recently-used **non-expired** entries until space exists; remove expired nodes encountered while scanning from the LRU end.

### Miss semantics

- `get` on missing or expired key returns `null` (after lazy remove of expired).

### Thread safety

- All mutations of shared structures happen under the single `ReentrantLock`.
- Loader execution happens **outside** the lock after registering an in-flight future, so loading key A does not block loading key B.

### `computeIfAbsent` single-flight

Under lock:

1. If a live (non-expired) entry exists → touch LRU, return value.
2. Else if an in-flight future exists for the key → release lock while waiting on that future (`CompletableFuture.join` / equivalent); return the loaded value or propagate the loader’s failure without invoking `loader` again. If waiting is interrupted, restore the interrupt status and throw an unchecked wrapper (e.g. `IllegalStateException`).
3. Else register a new in-flight future for the key, record the key in a thread-local “loading keys” set, release lock, invoke `loader`.

After loader returns:

4. Re-acquire lock: on success, insert/update via normal `put` path; complete the future with the value; remove in-flight entry; remove key from thread-local set.
5. On loader exception (including null result → `IllegalArgumentException`): remove in-flight entry, complete the future exceptionally, remove key from thread-local set, then propagate. Waiters must always be unblocked.

Different keys may load concurrently. Same key: only one loader runs at a time.

### Loader re-entry

- Nested cache operations from a loader on **other** keys are allowed.
- Nested `computeIfAbsent` (or load registration) on the **same** key from the loading thread → `IllegalStateException` (detect via thread-local loading-keys set).

## File layout

```
src/ExpiringCache.java
src/ExpiringCacheTest.java
README.md   # keep question; append edge-case policy + how to compile/run tests
```

No Maven/Gradle. Tests use a simple assert harness / `main` so no external dependencies are required.

## Testing plan

Cover at least:

1. LRU eviction after capacity exceeded
2. Expiration (lazy remove; `get` null; `size` ignores expired)
3. Updating an existing key (value, TTL, LRU position)
4. Concurrent `get`/`put` smoke
5. Duplicate-load suppression (same key → one loader invocation)
6. Loader failure → nothing cached; subsequent retry can succeed
7. Concurrent loading of different keys
8. Null / bad TTL / bad capacity → `IllegalArgumentException`
9. Same-key loader re-entry → `IllegalStateException`

Expiry tests advance the injectable clock instead of relying on wall-clock sleeps.

## Complexity

- Target average O(1) `get` and `put` under the lock for map + list operations.
- `size()` may be O(n) if it scans/purges expired entries; document that.
- Eviction may remove multiple expired nodes before finding an LRU live victim; document that this can be O(k) in the number of trailing expired entries.

## Out of scope

- Background purge threads / scheduled cleanup
- Persistence, weak references, size-based weighing
- Maven/Gradle/JUnit dependency management
- Fine-grained/striped locking for higher concurrency

## Rationale for rejected alternatives

- **Fine-grained / ConcurrentHashMap + striped locks:** better throughput, higher correctness risk for LRU + size + TTL together; overkill for this interview deliverable.
- **Access-order `LinkedHashMap` only:** less custom list code, but awkward for per-entry TTL, lazy expiry, and single-flight loading without extra machinery.
