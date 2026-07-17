# Task 3 Report: Expiration semantics and accurate size()

## Status: DONE

## Commits

| SHA | Subject |
|-----|---------|
| 3c48fe1 | feat: honor TTL in get, size, and eviction |

Base: `28a984a` (Task 2).

## TDD Steps Executed

1. **Added failing expiry tests** — `testExpiration`, `testSizeIgnoresExpired`, `testExpiredSkippedDuringEviction` wired from `main` (verbatim from brief).
2. **Verified RED** — `javac` + `java -cp out ExpiringCacheTest` → `Passed: 24  Failed: 2`. Failures: `size()` returned 2 with one expired (`Expected 1 but was 2`); eviction wrongly dropped live `b` after purging expired `a` (`Expected bravo but was null`). `testExpiration` already green via existing `get` expiry handling.
3. **Implemented** — expiry-aware `size()` (purge expired while walking list, return `map.size()`); fixed `evictOne` to return early when removing expired entries frees a slot (`map.size() < capacity`), so a live LRU is not evicted unnecessarily.
4. **Verified GREEN** — `Passed: 26  Failed: 0`, exit code 0.
5. **Committed** — only `src/ExpiringCache.java` and `src/ExpiringCacheTest.java`.

## Test Summary

26 assertions across 10 test methods (prior 19 + 7 new):
- `testExpiration` — get returns value before TTL; null at expire boundary (`expireAt <= now`)
- `testSizeIgnoresExpired` — size purges expired; returns 1; live key still readable
- `testExpiredSkippedDuringEviction` — put at capacity drops expired LRU, keeps live entry, inserts new

## Self-Review

### Requirements met

- `size()` counts only non-expired entries and may purge while counting
- Expired never returned from `get` (already Task 1; covered by `testExpiration`)
- Eviction skips / prefers reclaiming expired slots before live LRU eviction
- Put for existing keys still refreshes TTL (unchanged update path)
- Prior Task 1–2 tests still pass

### Design notes

- Brief assumed `evictOne` already handled the expired-skip case correctly; RED revealed it continued after purging expired and still removed a live node. Early return when `map.size() < capacity` is the minimal fix matching `testExpiredSkippedDuringEviction`.
- `size()` walks from head; order does not matter for purge correctness.
- Expire boundary remains inclusive (`expireAtMillis <= clock`), matching Task 1 and `testExpiration`.

### Concerns

- Git identity unset; commit used one-off `GIT_AUTHOR_*` / `GIT_COMMITTER_*` env vars (no git config changes).
- `computeIfAbsent` still stubbed (out of scope).
- No lazy background purge; expired entries linger until `get`/`size`/`evictOne` touch them (acceptable per design).

## Files Modified

- `src/ExpiringCache.java` — expiry-aware `size()`; `evictOne` early-return after reclaiming expired capacity
- `src/ExpiringCacheTest.java` — three expiry tests

## Files Not Modified

- `docs/`, `.idea/`, `out/`, and other paths outside Task 3 scope.
