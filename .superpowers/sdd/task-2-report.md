# Task 2 Report: LRU eviction at capacity

## Status: DONE

## Commits

| SHA | Subject |
|-----|---------|
| 28a984a | feat: add LRU eviction to ExpiringCache |

Base: `7244537` (Task 1).

## TDD Steps Executed

1. **Added failing LRU tests** — `testLruEviction`, `testGetUpdatesLruOrder`, `testUpdateExistingKey` wired from `main` (verbatim from brief).
2. **Verified RED** — `javac` + `java -cp out ExpiringCacheTest` → `Passed: 14  Failed: 5`. Failures matched expectation: `b` still present (`Expected null but was bravo`) and size grew past capacity (`Expected 2 but was 3`).
3. **Implemented eviction** — replaced new-node `put` branch with `while (map.size() >= capacity) evictOne();` plus `evictOne()` (verbatim from brief): walk from LRU tail, drop expired nodes, evict first live LRU.
4. **Verified GREEN** — `Passed: 19  Failed: 0`, exit code 0.
5. **Committed** — only `src/ExpiringCache.java` and `src/ExpiringCacheTest.java`.

## Test Summary

19 assertions across 7 test methods (prior 10 + 9 new):
- `testLruEviction` — get makes `a` MRU; put `c` evicts `b`; size stays 2
- `testGetUpdatesLruOrder` — get updates order so `b` is evicted
- `testUpdateExistingKey` — update does not grow size; updated key becomes MRU; LRU `b` evicted

## Self-Review

### Requirements met

- `put` evicts when `map.size() >= capacity` before inserting a new key
- Update path still refreshes value/TTL and `moveToHead` without eviction
- `get` already moved nodes to head (Task 1); LRU tests confirm order
- `evictOne` skips expired tail nodes before evicting a live LRU
- Existing Task 1 tests still pass

### Design notes

- Eviction only runs on the new-key path; in-place updates correctly stay within capacity without calling `evictOne`.
- `evictOne` may be O(k) when many expired nodes sit at the LRU end (documented; acceptable per brief).
- If the list were empty while `map.size() >= capacity` (should not happen with consistent helpers), the `while` could spin; not reachable with current `removeNode`/`addToHead` invariants.

### Concerns

- Git identity unset; commit used one-off `GIT_AUTHOR_*` / `GIT_COMMITTER_*` env vars (no git config changes).
- No dedicated test for expired-only eviction path inside `evictOne` (expiry-focused coverage deferred to later tasks).
- `size()` still raw map size; `computeIfAbsent` still stubbed (out of scope).

## Files Modified

- `src/ExpiringCache.java` — eviction in `put` + `evictOne`
- `src/ExpiringCacheTest.java` — three LRU/update tests

## Files Not Modified

- `docs/`, `.idea/`, `out/`, and other paths outside Task 2 scope.
