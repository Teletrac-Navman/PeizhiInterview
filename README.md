# Senior Java Coding Interview Question: Concurrent Expiring Cache

## Question

Implement a generic, thread-safe, in-memory cache:

```java
public class ExpiringCache<K, V> {

    public ExpiringCache(int capacity) {
        // Implement
    }

    public V get(K key) {
        // Implement
    }

    public void put(K key, V value, long ttlMillis) {
        // Implement
    }

    public V computeIfAbsent(
            K key,
            long ttlMillis,
            java.util.function.Function<K, V> loader) {
        // Implement
    }

    public int size() {
        // Implement
    }
}
```

The cache must meet these requirements:

1. **Fixed capacity**
   - The constructor receives a positive maximum capacity.
   - When capacity is exceeded, evict the **least recently used** non-expired entry.
   - Both successful `get` calls and insertions count as access.

2. **Expiration**
   - Every entry has its own time-to-live.
   - An expired entry must never be returned.
   - Expired entries may be removed lazily.
   - `size()` must not count expired entries.

3. **Thread safety**
   - Multiple threads may call every public method concurrently.
   - The internal data structures must not become corrupted.

4. **Atomic loading**
   - `computeIfAbsent` returns the current value if a non-expired entry exists.
   - Otherwise, it invokes `loader` and caches the result.
   - For the same key, only one thread may invoke `loader` at a time.
   - Loading one key should not unnecessarily block loading a different key.

5. **Failure handling**
   - If `loader` throws an exception, no value is cached.
   - Waiting callers must not deadlock.
   - Define and document how `null` keys, values, loader results, non-positive TTLs, and loader re-entry are handled.

6. **Complexity target**
   - Aim for average **O(1)** `get` and `put`.
   - Explain any operations that may exceed O(1).

### Example

```java
ExpiringCache<String, String> cache = new ExpiringCache<>(2);

cache.put("a", "alpha", 5_000);
cache.put("b", "bravo", 5_000);

cache.get("a"); // "alpha"; "a" becomes most recently used

cache.put("c", "charlie", 5_000);
// "b" should be evicted because it is least recently used

cache.get("b"); // null
cache.get("c"); // "charlie"
```

## Request

Provide:

- A compilable Java implementation.
- A brief explanation of:
  - the data structures used;
  - the synchronization strategy;
  - LRU maintenance;
  - expiration semantics;
  - prevention of duplicate loads;
  - exception and re-entry behavior.
- Unit tests covering:
  - LRU eviction;
  - expiration;
  - updating an existing key;
  - concurrent access;
  - duplicate-load suppression;
  - loader failure and retry;
  - concurrent loading of different keys.
