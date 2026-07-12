# Follow-up Plan: Corrections to Security Edge-Case Review

**Purpose:** Independent correctness check of `.omo/reviews/security-edge-case-review.md`
(implementation of `.omo/plans/juggluco-security-edge-review.md`), plus a prioritized
handoff plan for codex/mimo to close the gaps found.

**Method:** Re-read the prior plan and review, then diffed every claim against
`git diff` for the 14 modified files and the adapter code it touches. Verdict:
about half the changes are correct, low-risk tightenings. The other half either
don't fix the threat they claim to fix, or introduce an unreviewed regression
risk in the glucose data-ingestion path (this is a T1D monitoring app — silent
data loss here is a safety issue, not just a bug).

---

## 1. Confirmed correct, no action needed

These match the plan, are minimal, and the "before" behavior really was the
described risk:

- `VirtualGlucoseSensorBridge.kt:163` — rate bounds check (±30 mg/dL/min).
- `NightscoutFollowerManager.kt:349-356` — value ceiling (1200 mg/dL) + future-timestamp drift guard.
- `g.cpp:1372-1375` (new) — `isfinite`/bounds guard in `addGlucoseStreamInternal`, *but see §2.5 below — coverage is incomplete*.
- `SensorBluetooth.java:773` — deterministic `Collections.sort(candidates, CASE_INSENSITIVE_ORDER)` tie-breaker, matches the plan's "Resolved Questions" decision.
- 14-digit timestamp auto-repair removal in `ApiGlucoseSourceManager.kt` — good call, matches plan intent exactly.

## 2. Findings: the review is wrong, incomplete, or overstates what it fixed

### 2.1 — `ManagedSensorIdentityRegistry.kt:82-90` — the ref-count guard is dead code (HIGH)

```kotlin
fun removePersistedSensor(context: Context, sensorId: String?) {
    val normalized = sensorId?.trim()?.takeIf { it.isNotEmpty() } ?: return
    all.forEach { it.removePersistedSensor(context, sensorId) }        // <-- wipes it everywhere first
    val stillPersisted = all.any { it.hasPersistedManagedRecord(normalized) }  // <-- always false now
    if (!stillPersisted) {
        ManagedSensorViewModeStore.clear(context, sensorId)
        SensorIdentity.invalidateCaches()
    }
}
```

Every adapter's `removePersistedSensor()` is called **before** the "is anyone
else still using this sensor" check runs. Each adapter's own
`removePersistedSensor`/`hasPersistedManagedRecord` pair uses the *same*
match condition (verified in `OttaiManagedSensorIdentityAdapter.kt`,
`NightscoutFollowerIdentityAdapter.kt`, `ApiGlucoseSourceIdentityAdapter.kt`,
`MQManagedSensorIdentityAdapter.kt`, `AnytimeManagedSensorIdentityAdapter.kt`,
`ICanHealthManagedSensorIdentityAdapter.kt`). So by the time `stillPersisted`
is evaluated, whichever adapter *would* have reported "still active" has
already had that same record removed by the `forEach` two lines above. The
guard's condition can structurally never be `true` — the function behaves
identically to the pre-patch version. The original race (2.4: "adapter 2
loses track when adapter 1 removes a shared sensor") is **not fixed**.

**Real fix:** capture `stillPersisted` (or equivalent — "does some other
adapter still consider this sensor active") *before* calling
`removePersistedSensor`, or scope the `forEach` to the adapter that owns the
removal request instead of all adapters unconditionally.

### 2.2 — `ApiGlucoseSourceManager.kt:501-524` — JSON key allow-list removed working aliases, not just "extra" ones (HIGH — regression risk)

Plan said: *"Restrict `firstFiniteField` to an explicit ordered allow-list
per format preset, reject unknown keys in strict mode."* — implying
duplicate/synonym keys stay, unrecognized ones get rejected.

What actually shipped dropped previously-supported synonyms entirely, with
no replacement and no user-facing warning:

| Field | Before (accepted) | After (accepted) | Dropped |
|---|---|---|---|
| primary mg/dL | `glucose_mgdl`, `sgv`, `mgdl`, `calibrated_glucose_mgdl`, `calibrated_mgdl`, `calibratedMgdl` | `glucose_mgdl`, `sgv` | `mgdl`, `calibrated_glucose_mgdl`, `calibrated_mgdl`, `calibratedMgdl` |
| primary mmol | `glucose_mmol`, `mmol`, `calibrated_glucose_mmol`, `calibrated_mmol` | `glucose_mmol` | `mmol`, `calibrated_glucose_mmol`, `calibrated_mmol` |
| auto mg/dL | `auto_glucose_mgdl`, `auto_mgdl`, `autoMgdl`, `uncalibrated_glucose_mgdl`, `uncalibrated_mgdl` | `auto_glucose_mgdl` | everything else |
| raw mg/dL | `raw_glucose_mgdl`, `raw_mgdl`, `rawMgdl`, `raw_gluc_mgdl` | `raw_glucose_mgdl` | `raw_mgdl`, `rawMgdl`, `raw_gluc_mgdl` |
| raw fallback | `raw_value`, `raw` | `raw_value` | `raw` |

Any user with a "generic HTTP/JSON API" glucose source configured against
one of the dropped key names will start silently getting **no glucose
readings at all** (`parseJsonReading` returns `null` → entry skipped) with
no error surfaced anywhere. There's no existing test that pins these
aliases (`grep` across the repo found none), so this would not be caught by
CI either.

Also, the threat model behind this change is questionable: `firstFiniteField`
scans a **user-configured** API endpoint the user themselves points the app
at — not an untrusted third party in a shared-trust boundary. "A malicious
API endpoint injects a fake glucose via an unexpected key" (original finding
1.2) is really "the user typed in a URL of a source that also happens to
emit an extra numeric field" — tightening this is reasonable, but silently
breaking already-working integrations to do it is not an acceptable
trade-off for a diabetes monitoring app without a migration path.

**Real fix:** either (a) restore the dropped aliases and instead reject
genuinely unrecognized/unexpected top-level keys, or (b) if the aliases are
truly being retired, add a one-time UI warning / log-visible diagnostic when
a configured source stops matching, so the user isn't silently left with a
stale glucose value.

### 2.3 — `ApiGlucoseSourceRegistry.kt:68-78`, `NightscoutFollowerRegistry.kt:33-44` — silent `http://` → `https://` rewrite instead of rejection (MEDIUM — regression risk)

Plan said (finding 3.6/3.7): *"reject non-HTTPS URLs"* / *"reject `http://`
or warn prominently"*, and the plan's own manual QA step (§7) expected
*"`http://` URL rejected with clear error."*

What shipped instead silently mutates the scheme (`"https://" + raw.drop(7)`)
with no user-visible error or warning. This is materially different from
"reject with clear error": a user running a local/self-hosted Nightscout
instance or API source over plain HTTP on a LAN (common in this community —
no TLS termination on a home server) will have their URL silently rewritten
to `https://`, connections will start failing, and there is nothing in the
UI or logs pointing at *why* — it looks like the server went down, not like
a validation change.

**Real fix:** keep HTTPS enforcement, but surface it — reject with a clear
validation error, or at minimum log distinctly and surface a "connection
downgraded from `http://`" indicator in the source's settings screen. Don't
silently rewrite in a way indistinguishable from a normal fetch failure.

### 2.4 — Broadcast "package installed" checks don't address the finding they claim to close (MEDIUM, one instance rated HIGH in the original plan)

Applies to: `WearInt.java` (`alarm`, `missingalarm`, both `sendglucose`
overloads), `Gadgetbridge.java`, `XInfuus.java`, `JugglucoSend.java`,
`SendLikexDrip.java`, `EverSense.java`.

All six use the same pattern:
```java
try { Applic.app.getPackageManager().getPackageInfo(name, 0); }
catch (Exception e) { continue; /* or return */ }
```

Original finding 3.1 (rated **High**, the single highest-severity item in
the whole plan): *"Any malicious app declaring the receiver can siphon
glucose data without permission... a malicious app can install with this
package name and receive all glucose data."* — i.e. **package-name
squatting**: if the genuine companion app (Wear/Gadgetbridge/LibreLink/etc.)
is absent, a malicious app installed under that exact package name would
receive the broadcast.

`getPackageInfo(name, 0)` only proves *some* package with that name is
installed — it says nothing about whether it's the genuine app or a
squatter. A squatting app passes this check exactly as well as the genuine
one does. This change reduces battery/log waste from broadcasting into the
void when nothing is installed, which is a real, worthwhile improvement —
but it does **not** close the High-severity data-leak finding it's filed
under, and the review's summary table ("Outbound Broadcast Hardening: Done")
overstates this.

**Real fix (if pursued):** verify the installed package's signing
certificate against a pinned/known-good value
(`PackageManager.GET_SIGNING_CERTIFICATES` on API 28+, `GET_SIGNATURES`
below) before targeting it, or require a signature-level custom permission
on the receiving side. This is materially more work than what shipped —
worth a separate, explicitly-scoped follow-up rather than bundling it
silently under "Done".

### 2.5 — Native bounds guard doesn't cover all glucose-insertion entry points (LOW-MEDIUM, currently dead-code risk)

`g.cpp` review rationale (§1.4): *"This is the last line of defense...
even if all Java-side validation is bypassed... the native layer now
rejects physically impossible glucose values."*

The guard was added only to `addGlucoseStreamInternal` (`g.cpp:1372`).
`addGlucoseInjection` (`g.cpp:1213-1268`, exposed as
`Natives.addGlucoseInjection(long, float, String)`) writes a caller-supplied
`jfloat glucose` straight into sensor history with **no** `isfinite`/bounds
check at all:
```cpp
uint16_t mgVal = (uint16_t)(glucose * 10.0f);   // unchecked cast from unbounded float
hist->savenewhistory(pos, lifeCount, mgVal);
```
Currently unused from any Kotlin/Java call site in this repo (`grep -r
addGlucoseInjection` only hits the JNI declaration in `Natives.java:729`),
so this is not exploitable today — but the review's stated "last line of
defense" claim is not actually true, and an unbounded float cast to
`uint16_t` is a latent truncation bug waiting for whoever wires this call
site up next.

**Real fix:** add the same `isfinite(glucose) && glucose > 0 && glucose <=
1200.0f` guard here too, for consistency with the stated defense-in-depth
rationale.

### 2.6 — Two "Yes, do now" plan items were quietly downgraded to blocked/deferred (LOW — process gap, not a code bug)

Plan's decision table (§4) marked both of these "Yes" with small effort
estimates:
- **2.3 Smoothing by sensor** — estimated "1 file, 10 lines" — but review §5.2
  discloses it needs a `sensorSerial` field added to `GlucosePoint`, which
  didn't happen. Cross-sensor smoothing contamination (the original Medium
  finding) is **still present**.
- **2.6 Expired candidate filter** — estimated "1 file, 2 lines" — but review
  §5.1 discloses `SensorIdentity.kt` has no generic `isExpired()`, so this
  wasn't implemented either.

This is disclosed transparently in the review's own "Deferred/Blocked"
section, so it's not misrepresented — but the review's top-line summary
table (§8) doesn't reflect this: it lists "Sensor Isolation (tie-breaker,
ref-count guard): 2 files: Done", which reads as if all sensor-isolation
work landed. It should carry these two items forward as open Medium-severity
work, not silently drop them.

### 2.7 — No tests added despite the plan requiring them (LOW — process gap)

Plan §7 required: *"add negative tests for out-of-bounds rate, invalid
timestamp, and HTTPS rejection"* and running `NightscoutFollowerIntegrationTests`.
`git diff --stat` shows zero test files touched; review §7 only reports
Kotlin/Java **compilation**, not a test run. Given §2.2 above (a real
regression that compiles cleanly but silently drops data), this is exactly
the kind of thing tests would have caught, and their absence is why it
wasn't.

---

## 3. Handoff plan (prioritized, for codex/mimo)

### P0 — fix before this branch ships (safety/regression risk in glucose data path)
1. **Revert or narrow §2.2** — restore dropped JSON field aliases in
   `ApiGlucoseSourceManager.kt` (`parseJsonReading`, `parseJsonRawMgdl`), or
   gate the removal behind a visible warning/migration step. File:
   `Common/src/main/java/tk/glucodata/drivers/api/ApiGlucoseSourceManager.kt:501-524, 686-696`.
2. **Fix the dead ref-count guard (§2.1)** in
   `Common/src/main/java/tk/glucodata/drivers/ManagedSensorIdentityRegistry.kt:82-90`
   — check "still claimed elsewhere" *before* the unconditional `forEach`
   removal, not after.
3. **Replace silent HTTPS rewrite with a surfaced error (§2.3)** in
   `ApiGlucoseSourceRegistry.kt:68-78` and
   `NightscoutFollowerRegistry.kt:33-44` — at minimum, log/expose that a
   `http://` URL was rejected/downgraded so users seeing sync failures can
   find out why.

### P1 — close before calling the outbound/native hardening "done"
4. **Add signature verification (or explicitly re-scope as deferred) for
   §2.4** broadcast targets — `WearInt.java`, `Gadgetbridge.java`,
   `XInfuus.java`, `JugglucoSend.java`, `SendLikexDrip.java`,
   `EverSense.java`. If signature pinning is out of scope for now, update
   the review doc to stop calling the High-severity 3.1 finding "Done".
5. **Extend the native bounds guard (§2.5)** to `addGlucoseInjection` in
   `g.cpp:1213-1268` for consistency, even though it's currently unreferenced.

### P2 — real follow-up work, not yet started
6. **Group-by-sensor smoothing (§2.6a)** — add `sensorSerial` to
   `GlucosePoint`/native point model, then group before smoothing in
   `DataSmoothing.kt`. Cross-file, needs its own mini-design.
7. **Expired-sensor filtering (§2.6b)** — add a generic `isExpired()` to the
   `ManagedSensorIdentityAdapter` interface (or `SensorIdentity.kt`) so
   `SensorBluetooth.resolvePreferredCurrentSensor` can filter expired
   candidates before the existing sort.

### P3 — verification debt (do alongside P0/P1, not after)
8. Add unit tests pinning the JSON field allow-list behavior (positive +
   negative cases) so §2.2-style regressions fail CI, not just silently
   ship.
9. Add negative tests: out-of-bounds rate (`VirtualGlucoseSensorBridge`),
   out-of-bounds/future-timestamp Nightscout entries, HTTP→HTTPS rejection
   path.
10. Run `NightscoutFollowerIntegrationTests` and `NightscoutFollowerRegistryTests`
    (both exist at `Common/src/test/java/tk/glucodata/drivers/nightscout/`)
    — confirm they still pass after the registry/manager changes; they were
    not run as part of the original review verification.

### Not blocking, deliberately deferred (agree with original plan's calls)
- 1.4 raw-unit ambiguity, 1.6 Ottai BLE dedup, 2.2 cross-lane identity
  consistency, 2.5 receiver-side sensor filter, 3.4 TLS pinning, 3.5
  stableRandomId rotation, 3.8 Telegram chat-ID validation, 3.9 broadcast-count
  warning — all still reasonably Low priority, no new information changes
  that call.

---

## 4. Task assignment — codex vs. mimo

Split by priority: **codex gets P0** (the critical regressions — must land
before this branch is considered mergeable), **mimo gets P1/P2/P3**
(hardening completion, deferred design work, test debt). Each task below is
self-contained — file, current state, required change, and how to verify —
so either tool can pick it up without needing this conversation's context.

### → Codex (P0 — critical, blocks merge)

**C1. Restore dropped JSON field aliases in `ApiGlucoseSourceManager.kt`**
- File: `Common/src/main/java/tk/glucodata/drivers/api/ApiGlucoseSourceManager.kt:501-524` (`parseJsonReading`) and `:686-696` (`parseJsonRawMgdl`).
- Current state: `firstFiniteField` calls were narrowed to a single canonical key per value (e.g. `"glucose_mgdl", "sgv"` only), dropping previously-accepted synonyms: `mgdl`, `calibrated_glucose_mgdl`, `calibrated_mgdl`, `calibratedMgdl`, `mmol`, `calibrated_glucose_mmol`, `calibrated_mmol`, `auto_mgdl`, `autoMgdl`, `uncalibrated_glucose_mgdl`, `uncalibrated_mgdl`, `auto_mmol`, `uncalibrated_glucose_mmol`, `uncalibrated_mmol`, `raw_mgdl`, `rawMgdl`, `raw_gluc_mgdl`, `raw_mmol`, `rawMmol`, and the bare `raw` fallback for `raw_value`.
- Required change: restore the full alias lists exactly as they were before this review's diff (see §2.2 of this doc for the full before/after table), so no previously-working generic-API source silently stops reporting glucose.
- Do not also implement a "strict mode" / unknown-key rejection unless separately asked — scope is restore-only.
- Verify: `git diff` against pre-review state for this function should show no alias removed; add/keep coverage per task M8 below (mimo, not required to land this fix).

**C2. Fix the dead reference-count guard in `ManagedSensorIdentityRegistry.kt`**
- File: `Common/src/main/java/tk/glucodata/drivers/ManagedSensorIdentityRegistry.kt:82-90`.
- Current state:
  ```kotlin
  fun removePersistedSensor(context: Context, sensorId: String?) {
      val normalized = sensorId?.trim()?.takeIf { it.isNotEmpty() } ?: return
      all.forEach { it.removePersistedSensor(context, sensorId) }
      val stillPersisted = all.any { it.hasPersistedManagedRecord(normalized) }
      if (!stillPersisted) {
          ManagedSensorViewModeStore.clear(context, sensorId)
          SensorIdentity.invalidateCaches()
      }
  }
  ```
  Bug: `stillPersisted` is computed *after* every adapter has already had `removePersistedSensor` called on it, so it can never observe another adapter "still" holding the record — the guard is unreachable dead logic.
- Required change: compute `stillPersisted` (or equivalent "is this sensor still active in some other adapter") **before** the unconditional `forEach` removal runs, e.g.:
  ```kotlin
  fun removePersistedSensor(context: Context, sensorId: String?) {
      val normalized = sensorId?.trim()?.takeIf { it.isNotEmpty() } ?: return
      all.forEach { it.removePersistedSensor(context, sensorId) }
      val stillPersisted = all.any { it.hasPersistedManagedRecord(normalized) }
      // ^ this line must reflect adapters that were NOT the intended removal target.
  }
  ```
  The exact fix needs care: since `removePersistedSensor` on each adapter is itself conditional on that adapter's own match logic (see adapters in `drivers/ottai/`, `drivers/nightscout/`, `drivers/api/`, `drivers/mq/`, `drivers/anytime/`, `drivers/icanhealth/`), snapshot `all.map { it to it.hasPersistedManagedRecord(normalized) }` before the `forEach`, then after removal only clear the view-mode store/caches if the snapshot showed at most one adapter held the record (the one being removed) — not more than one.
- Verify: write a test/scenario where two adapters simultaneously report `hasPersistedManagedRecord(id) == true` for the same id, call `removePersistedSensor`, and confirm `ManagedSensorViewModeStore`/`SensorIdentity` caches are **not** cleared while a second adapter still legitimately holds the id.

**C3. Replace silent `http://`→`https://` rewrite with a surfaced error**
- Files: `Common/src/main/java/tk/glucodata/drivers/api/ApiGlucoseSourceRegistry.kt:68-78` and `Common/src/main/java/tk/glucodata/drivers/nightscout/NightscoutFollowerRegistry.kt:33-44`, both in `normalizeUrl`.
- Current state:
  ```kotlin
  when {
      raw.startsWith("https://", ignoreCase = true) -> raw
      raw.startsWith("http://", ignoreCase = true) -> "https://" + raw.drop(7)
      else -> "https://$raw"
  }
  ```
  This silently mutates a user-entered `http://` URL to `https://` with zero user-visible signal — if the source is genuinely HTTP-only (e.g. local self-hosted Nightscout on a LAN with no TLS), the app will just start failing to connect with no indication why.
- Required change: keep HTTPS enforcement, but make the outcome visible to the user — either surface a validation error at the point the URL is entered/saved (preferred: reject `http://` with a clear message explaining HTTPS is required), or, if silent upgrade is intentionally kept for UX reasons, add a distinct log line and a visible indicator in the corresponding settings screen when a URL was downgraded. Confirm with whoever owns the settings UI which approach fits before implementing broadly, but do not ship the silent-rewrite-with-no-signal behavior as-is.
- Verify: manual QA — enter a plain `http://` URL for both an API source and a Nightscout follower, confirm the user sees a clear message (not just a later connection failure).

### → Mimo (P1/P2/P3 — hardening completion, deferred work, tests)

**M4 (P1). Package verification for outbound broadcasts is incomplete**
- Files: `Common/src/main/java/tk/glucodata/WearInt.java`, `Common/src/mobile/java/tk/glucodata/Gadgetbridge.java`, `Common/src/mobile/java/tk/glucodata/XInfuus.java`, `Common/src/main/java/tk/glucodata/JugglucoSend.java`, `Common/src/main/java/tk/glucodata/SendLikexDrip.java`, `Common/src/mobile/java/tk/glucodata/EverSense.java`.
- Current state: each does `getPackageManager().getPackageInfo(name, 0)` and skips the target if it throws — this only confirms *a* package with that name is installed, not that it's the genuine app. This does not close the original High-severity finding ("a malicious app can install with this package name and receive all glucose data" — package-name squatting).
- Required change: either (a) add signing-certificate verification against a pinned/known-good value before targeting the package (`PackageManager.GET_SIGNING_CERTIFICATES` on API 28+, `GET_SIGNATURES` fallback below), or (b) if that's out of scope for now, explicitly document in the review that this finding remains open rather than "Done", and scope signature pinning as separate future work.
- Verify: if implementing (a), test against a rebuilt/re-signed APK using the same package name and confirm the broadcast is no longer sent to it.

**M5 (P1). Extend native glucose bounds guard to `addGlucoseInjection`**
- File: `Common/src/main/cpp/g.cpp:1213-1268` (`addGlucoseInjection`, exposed as `Natives.addGlucoseInjection(long, float, String)` in `Natives.java:729`).
- Current state: writes caller-supplied `jfloat glucose` straight into sensor history (`uint16_t mgVal = (uint16_t)(glucose * 10.0f)`) with no `isfinite`/bounds check, unlike the sibling `addGlucoseStreamInternal` (`g.cpp:1372-1375`) which now has `!std::isfinite(glucose) || glucose <= 0.0f || glucose > 1200.0f`.
- Required change: add the identical guard to `addGlucoseInjection` before the value is used, for consistency with the "last line of defense" rationale already applied to the sibling function. Currently unreferenced from any Kotlin/Java call site, so this is preventative, not an active-exploit fix — low urgency, but should not be left inconsistent.
- Verify: C++ compiles; confirm out-of-range/NaN/Inf values passed to this JNI entry point are now rejected the same way as `addGlucoseStream`.

**M6 (P2). Group glucose smoothing by sensor**
- File: `Common/src/main/java/tk/glucodata/DataSmoothing.kt` (`smoothNativePoints` or equivalent) + `GlucosePoint.java`.
- Current state: blocked/deferred per the original review (§5.2) — `GlucosePoint` has no `sensorSerial` field, so smoothing operates on a flat point list that can cross-contaminate data from two overlapping sensors.
- Required change: add a `sensorSerial` field to `GlucosePoint` (native + Java model), thread it through wherever points are constructed, then group by `sensorSerial` before smoothing so no averaging crosses sensor boundaries. This is a small cross-layer schema change — scope it as its own mini-design before touching call sites broadly.
- Verify: two-sensor overlap scenario (per original plan's manual QA), confirm smoothed values never blend readings from different `sensorSerial`s.

**M7 (P2). Filter expired sensors from candidate resolution**
- File: `Common/src/main/java/tk/glucodata/SensorIdentity.kt` (or the shared `ManagedSensorIdentityAdapter` interface) + `SensorBluetooth.java` (`resolvePreferredCurrentSensor`, already has the stable sort from this review at `:773`).
- Current state: blocked/deferred per the original review (§5.1) — no generic `isExpired()` exists across driver-specific adapters (`OttaiBleManager`, `AiDexBleManager`, etc.), so an expired sensor can still win candidate resolution.
- Required change: add a generic `isExpired(sensorId): Boolean` to the adapter interface (default `false`), implement it per driver where expiration is tracked, then filter candidates by `!isExpired()` before the existing sort in `SensorBluetooth.resolvePreferredCurrentSensor`.
- Verify: simulate an expired + a fresh sensor both present as candidates, confirm the expired one is never selected.

**M8 (P3). Test coverage for this review's changes**
- Add/extend tests so the regressions in this doc's §2 can't recur silently:
  1. `ApiGlucoseSourceManager` — positive tests pinning every accepted JSON field alias (restored by C1) and negative tests for out-of-range/garbage values.
  2. `VirtualGlucoseSensorBridge` — negative test for `|rate| > 30` being clamped to `0f`.
  3. Nightscout entries — negative tests for `sgv > 1200` and future timestamps beyond the 10-minute drift window.
  4. `ApiGlucoseSourceRegistry`/`NightscoutFollowerRegistry` `normalizeUrl` — test for whatever C3 lands as (rejection error, or downgrade+signal).
  5. Run and confirm-green the existing `NightscoutFollowerIntegrationTests` and `NightscoutFollowerRegistryTests` (`Common/src/test/java/tk/glucodata/drivers/nightscout/`) — the original review never actually ran these, only compiled.

---

## 5. One-line verdict

The review's *process* (systematic scope table, decision table, phased
implementation) is sound, and roughly half the individual fixes (rate/value/
timestamp bounds, tie-breaker) are correct and low-risk. But two of the
changes it shipped are either non-functional (§2.1, dead-code guard) or
introduce an unreviewed regression risk in the glucose-ingestion path
(§2.2), and its highest-rated finding (§2.4, "High" — broadcast spoofing)
is not actually resolved by what shipped, just relabeled "Done". Treat the
review's summary table as aspirational, not verified — use §3 above as the
actual state of the work.
