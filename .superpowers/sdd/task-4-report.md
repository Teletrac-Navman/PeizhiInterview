# Task 4 Report: `computeIfAbsent` — hit, load, single-flight, failure, re-entry

## Status

**Complete.** All suite tests pass (38 assertions, 0 failures). Committed on `feature/expiring-cache`.

## What changed

### `ExpiringCache.java`
- Added `inFlight` (`Map<K, CompletableFuture<V>>`) for single-flight loads.
- Added `ThreadLocal<Set<K>> loadingKeys` for same-key re-entry detection.
- Implemented two-phase `computeIfAbsent`: bookkeeping under lock; loader runs unlocked.
- Waiter path unlocks before `join`, re-locks in `finally`; outer `finally` unlocks once (intentional pairing).
- Extracted `putUnderLock`; public `put` delegates to it.
- Null loader result → `IllegalArgumentException`; clears in-flight and completes exceptionally so waiters unblock.
- Same-key re-entry → `IllegalStateException`.

### `ExpiringCacheTest.java`
- Added five tests called from `main` (`throws Exception`):
  - `testComputeIfAbsentHitAndLoad`
  - `testDuplicateLoadSuppression`
  - `testLoaderFailureAndRetry`
  - `testSameKeyReentryThrows`
  - `testLoaderNullResultRejected`

## TDD evidence

1. **RED:** Tests added first; run failed with `UnsupportedOperationException: not implemented yet` at `computeIfAbsent`.
2. **GREEN:** Implemented per brief; full suite passed.
3. Re-ran suite 3× — no flake on `testDuplicateLoadSuppression` (50ms sleep sufficient here).

## Commits

- `feat: add single-flight computeIfAbsent` (sources only)

## Test summary

```
javac -d out src\ExpiringCache.java src\ExpiringCacheTest.java
java -cp out ExpiringCacheTest
→ Passed: 38  Failed: 0
```

## Concerns / notes

- Duplicate-load test still relies on a short sleep for waiter attach; if CI flakes, prefer a latch that signals when t2 has entered the waiter/`join` path.
- Waiter unlock/re-lock pairing is subtle; leave as-is unless a deadlock or double-unlock bug appears.
- No production change beyond Task 4 scope.
