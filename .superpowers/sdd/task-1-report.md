# Task 1 Report: Scaffold, harness, validation, and basic put/get

## Status: DONE

## Commits

| SHA | Subject |
|-----|---------|
| 7244537 | feat: add ExpiringCache put/get with validation |

## TDD Steps Executed

1. **Created failing tests** — `src/ExpiringCacheTest.java` with validation + put/get/size/miss assertions and dependency-free harness (`main`, dot output, exit code 1 on failure).
2. **Verified compile failure** — `javac -d out src\ExpiringCacheTest.java` failed with 8 "cannot find symbol: ExpiringCache" errors as expected.
3. **Implemented minimal ExpiringCache** — `src/ExpiringCache.java` with HashMap + doubly-linked list scaffold, ReentrantLock, validation, put/get/size, expiry check on get, and `computeIfAbsent` stub throwing `UnsupportedOperationException`.
4. **Verified tests pass** — `javac -d out src\ExpiringCache.java src\ExpiringCacheTest.java` then `java -cp out ExpiringCacheTest` → `Passed: 10  Failed: 0`, exit code 0.
5. **Committed** — only `src/ExpiringCache.java` and `src/ExpiringCacheTest.java`.

## Test Summary

10 assertions across 4 test methods: non-positive capacity (2), null key/value/bad TTL (5), put/get/size (2), get miss (1).

## Self-Review

### Requirements met

- Plain javac/java, no Maven/Gradle/JUnit
- `ExpiringCache(int capacity)` and `ExpiringCache(int capacity, LongSupplier clock)` constructors
- Validation rejects null keys/values and non-positive capacity/TTL with `IllegalArgumentException`
- Basic put/get/size work; get miss returns null
- `computeIfAbsent` stub throws `UnsupportedOperationException`
- No eviction on capacity overflow (deferred to Task 2)
- `size()` returns raw map size (expiry-aware purge deferred to Task 3)

### Design notes

- LRU list nodes and lock are in place for later tasks; put does not enforce capacity yet (per brief).
- Expiry is checked on get only; expired entries remain in map until accessed (Task 3 will address size/expiry purge).
- Clock injection via `LongSupplier` enables deterministic tests.

### Concerns

- Git user identity was unset; commit used one-off `GIT_AUTHOR_*` / `GIT_COMMITTER_*` env vars (no git config changes).
- Intentionally out of scope: LRU eviction, expiry-aware size, computeIfAbsent, concurrency tests.

## Files Created

- `src/ExpiringCache.java`
- `src/ExpiringCacheTest.java`

## Files Not Modified

- `docs/`, `.idea/`, and all other paths outside Task 1 scope.
