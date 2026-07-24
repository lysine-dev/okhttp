---
name: flake-detector
description: "Use this skill to identify, reproduce, and triage flaky tests in the OkHttp project. Triggers: any mention of flaky tests, CI flakes, intermittent failures, test instability, or requests to investigate why tests fail non-deterministically in GitHub Actions. Provides scripts to fetch CI failure logs, aggregate failures per test class, reproduce locally with @RepeatedTest, and documents known flaky tests with root causes and fixes."
compatibility: "OkHttp project (square/okhttp) — requires gh CLI authenticated to square/okhttp"
license: Apache-2.0
---

# Flake Detector Skill

## Description

This skill identifies, reproduces, and triages flaky tests in the OkHttp project by analyzing
recent failures in the GitHub Actions `build.yml` workflow on the `master` branch. It fetches
failed run logs, extracts test failure patterns, aggregates failure counts per test class, and
provides guidance on root-cause categories and fixes.

---

## Identification

Run the identification script to pull recent CI failures and update `flaky-tests.txt`:

```bash
./.github/skills/flake-detector/identify-flakes.sh [LIMIT]
```

- `LIMIT` defaults to 10 (number of recent failed workflow runs to scan).
- Results are written to `.github/skills/flake-detector/flaky-tests.txt`.
- Each line is `ClassName.methodName` — one test per line, sorted and deduplicated.

**Output**: a ranked summary of failures per class, and a clean list of flaky test names.

---

## Reproduction

### Step 1 — Identify the failing method

```bash
./.github/skills/flake-detector/identify-flakes.sh
```

### Step 2 — Apply @RepeatedTest to the target

Open the failing test file and replace `@Test` with `@RepeatedTest(100)`:

```kotlin
// Before
@Test fun cancelBeforeBodyIsRead() { ... }

// After
@RepeatedTest(100)
fun cancelBeforeBodyIsRead() { ... }
```

Add the import:

```kotlin
import org.junit.jupiter.api.RepeatedTest
```

### Step 3 — Run the reproduction script

```bash
# Run all tests from flaky-tests.txt
./.github/skills/flake-detector/reproduce-flakes.sh

# Override with a specific test
./.github/skills/flake-detector/reproduce-flakes.sh okhttp3.CacheTest.testGoldenCacheHttpsResponseOkHttp27
```

The script auto-discovers the Gradle module and task (`:okhttp:jvmTest` etc.) and runs the tests.

### Step 4 — Cleanup

Once a flake is fixed and verified locally, revert `@RepeatedTest` back to `@Test` before
merging. Leaving `@RepeatedTest(100)` in the codebase makes CI 100x slower for those tests.

---

## Root-cause taxonomy

When a new flake is found, classify it before attempting a fix:

| Category | Symptoms | Typical fix |
|----------|----------|-------------|
| **Race condition** | Assertion sees partial state; fails ~1% of runs | Use `AtomicInteger`, `@Synchronized`, or `CountDownLatch` |
| **Missing flush** | Deadlock / `TimeoutException`; one side waits forever | Call `flush()` after writing to the connection |
| **CI timing** | Failures only on loaded CI runners; local runs clean | Add sleep, increase timeout, or decouple assertion from time |
| **Cleanup ordering** | Wrong count/state immediately after close/cancel | Assert intermediate state before checking final invariant |
| **Network (CI env)** | `ConnectException`, `SocketTimeoutException` to external hosts | Skip or mock the external call; use `@Tag("flaky")` to quarantine |

---

## Known flakes (as of March 2026)

Tests are ordered by observed CI frequency. Status reflects whether a fix has landed in `master`.

### High frequency (> 2 CI failures / 10 runs)

#### `CacheTest` — Golden cache tests ⚠ Open

- `testGoldenCacheHttpsResponseOkHttp27`
- `testGoldenCacheHttpsResponseOkHttp30`
- `testGoldenCacheHttpResponseOkHttp30`
- `testGoldenCacheHttpResponseOkHttp27`

**Symptom**: `SocketTimeoutException: Read timed out` or content mismatch `AssertionFailedError`.  
**Root cause**: Golden file cache relies on live TLS handshake timing. CI network latency or
cert-validation races push past the read timeout.  
**Suggested fix**: Mock the TLS layer in golden-cache tests; or increase the socket timeout
specifically for these tests via `OkHttpClient.Builder.readTimeout()`.

#### `RouteFailureTest` — HTTP/2 retry tests ⚠ Open

- `http2OneBadHostRetryOnConnectionFailure`
- `http2OneBadHostRetryOnConnectionFailureFastFallback`
- `http2OneBadHostOneGoodNoRetryOnConnectionFailure`
- `http2OneBadHostOneGoodNoRetryOnConnectionFailureFastFallback`

**Symptom**: `AssertionFailedError: expected:<[1]> but was:<[0]>` (retry count mismatch).  
**Root cause**: The retry decision path races against connection teardown — the retry counter
can be incremented after the assertion fires.  
**Partial fix landed** (PR #9247): Added `takeRequest()` to synchronize server state.  
**Remaining work**: Add a synchronization point (e.g. `MockWebServer.requestCount` latch)
before the retry count assertion.

### Medium frequency (1–2 CI failures / 10 runs)

#### `HttpOverHttp2Test_HTTP_2` — Timeout tests ⚠ Open

- `connectionTimeout`
- `oneStreamTimeoutDoesNotBreakConnection`
- `readResponseHeaderTimeout`
- `readTimeoutOnSlowConnection`
- `streamTimeoutDegradesConnectionAfterNoPong`

**Symptom**: Tests pass locally but fail on slow CI runners.  
**Root cause**: Hard-coded timeout thresholds (e.g. 250ms) are too tight for overloaded CI VMs.  
**Suggested fix**: Use a `TestTimeout` rule that scales with `System.getenv("CI")`, or replace
fixed delays with `MockWebServer`-driven synchronization (latches instead of wall-clock sleeps).

#### `EventListenerTest` — HTTP/2 over HTTPS ⚠ Open

- `timeToFirstByteHttp2OverHttps`

**Symptom**: Event ordering assertion fails intermittently.  
**Root cause**: TLS handshake and HTTP/2 settings frame exchange can interleave differently
under load, producing a different event sequence than expected.  
**Suggested fix**: Relax ordering constraints or add an explicit sync point after the settings
frame is acknowledged.

### Fixed ✅

#### `EventListenerTest_Relay` — Fixed in PR #9246

- `cancelAsyncCall`
- `successfulCallEventSequenceForEnqueue`

**Root cause**: `eventCount: Int` was not thread-safe. Non-atomic `eventCount++` allowed two
concurrent events to both see 0 and both fire the relay, recording events out of order.  
**Fix**: Changed to `AtomicInteger` with `getAndIncrement()`.

#### `DuplexTest.duplexWithRedirect` — Fixed in PR #9246

**Root cause**: `Http2ExchangeCodec.writeRequestHeaders()` did not flush the connection after
writing headers. Okio's write buffer held them — the server never received the request and
blocked, causing a 30-second timeout deadlock.  
**Fix**: Added `http2Connection.flush()` in `writeRequestHeaders()`.

#### `Http2ConnectionTest.discardedDataFramesAreCounted` — Fixed in PR #9246

**Root cause**: `WindowCounter.total` and `acknowledged` lacked a `@Synchronized` getter.
Without a happens-before edge, the write on one thread was invisible to the asserting thread.  
**Fix**: Added `@Synchronized` on both getters.

#### `WebSocketHttpTest.closeWithoutSuccessfulConnect` — Fixed in PR #9246

**Root cause**: Connection pool count checked before connection teardown completed.  
**Fix**: Added `assertFailure()` call to drain the close sequence before the count assertion.

#### `HttpOverHttp2Test.recoverFromMultipleCancelReusesConnection` — Fixed in PR #9246

**Root cause**: Cancel propagation is asynchronous through OkHttp's dispatcher. Assertion
fired before the cancelled stream updated the connection pool state.  
**Fix**: Added 500ms sleep after cancel; increased to 500ms for CI robustness.

#### `ServerTruncatesRequestTest` — Environment-specific ⚠ Quarantined

- `serverTruncatesRequestButTrailersCanStillBeReadHttp1`
- `serverTruncatesRequestOnLongPostHttp1`

**Symptom**: `SocketException: An established connection was aborted by the software in your
host machine`.  
**Root cause**: Windows-specific RST behavior — server sends RST before client finishes writing.  
**Status**: Confirmed environment-specific; no universal fix. Tagged for Windows-only skip.

---

## Quarantine strategy

For flakes that cannot be fixed immediately, use `@Tag("flaky")` and exclude them from blocking
CI while investigation continues:

```kotlin
@Tag("flaky")
@Test fun someFlakeyTest() { ... }
```

In `build.gradle.kts`:

```kotlin
tasks.withType<Test>().configureEach {
  if (System.getenv("CI") != null) {
    excludeTags("flaky")
  }
}
```

This keeps the test in the codebase (so it continues to be tracked and reproduced) without
blocking merges.

---

## Adding a new flake

When you discover a new flake:

1. Add it to `flaky-tests.txt` (`ClassName.methodName`, one per line).
2. Add a section to this SKILL.md under the appropriate frequency tier.
3. Include: symptom, root-cause hypothesis, and suggested fix.
4. Open a tracking issue linking the CI run URL, the test name, and the error message.
5. Apply `@RepeatedTest(100)` locally to reproduce, then revert before merging the fix.
