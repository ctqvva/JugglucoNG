# JugglucoNG Security Edge-Case Review — Implementation Plan

## 1. Objective
Audit edge-case logic in three security-critical areas and produce hardened, minimal fixes:
1. **GDH data integrity**: incorrect/malformed glucose data reaching the native layer.
2. **Sensor isolation**: concurrent multi-sensor scenarios leaking or mixing data.
3. **Outbound data leaks**: broadcasts, Nightscout, OutboundApi, Telegram, and generic HTTP sources sending glucose data insecurely or to wrong targets.

## 2. Scope Lock (components)
| # | Component | Files | Risk Surface |
|---|-----------|-------|-------------|
| 1 | Virtual glucose ingress | `VirtualGlucoseSensorBridge.kt`, `ApiGlucoseSourceManager.kt`, `NightscoutFollowerManager.kt`, `OttaiBleManager.kt` | Unvalidated/parsed data entering native storage |
| 2 | Native glucose handler | `g.cpp` (lines 627–1966), `SuperGattCallback.java`, `Natives.java` | Race conditions, double-insertion, timestamp collisions |
| 3 | Sensor routing & identity | `SensorBluetooth.java`, `ManagedSensorIdentityRegistry.kt`, `ExchangeGlucosePayload.java`, `SensorIdentity.kt` | Wrong sensor association when multiple active |
| 4 | Outbound broadcast layer | `Broadcasts.java`, `WearInt.java`, `Gadgetbridge.java`, `EverSense.java`, `XInfuus.java`, `JugglucoSend.java` | Intent spoofing, excessive broadcasts, PII leaks |
| 5 | Network egress (push) | `OutboundApi.kt`, `TelegramStaleCheckWork.kt` | Credential handling, TLS, retry storms |
| 6 | Network ingress (pull) | `ApiGlucoseSourceManager.kt`, `NightscoutFollowerManager.kt` | SSRF, JSON injection, auth bypass, TLS |
| 7 | Data smoothing / display | `DataSmoothing.kt`, `Notify.java` | Cross-sensor smoothing, alert misfire |

## 3. Edge-Case Inventory

### 3.1 GDH Data Integrity — Findings
| # | Finding | Severity | Fix |
|---|---------|----------|-----|
| 1.1 | `VirtualGlucoseSensorBridge.publishCurrent` validates `rate` with `isFinite` then falls back to `0f`. A malicious/buggy source sending `Float.MAX_VALUE` or `-Float.MAX_VALUE` as rate passes `isFinite` and will propagate to UI/alerts. | Medium | Add `|rate| < MAX_REASONABLE_RATE_MGDL_PER_MIN` (e.g. 30.0) bounds check before fallback. |
| 1.2 | `ApiGlucoseSourceManager.parseJsonReading` uses `firstFiniteField` which scans many keys and picks the first positive finite value. A crafted JSON can inject a fake glucose via an unexpected key (e.g. `"calibratedMgdl": 9999`) that shadows the real value. | Medium | Restrict `firstFiniteField` to an explicit ordered allow-list per format preset, reject unknown keys in strict mode. |
| 1.3 | `ApiGlucoseSourceManager.normalizeTimestamp` accepts timestamps with 10-digit (seconds) and 13-digit (millis) precision, but also repairs 14-digit timestamps by dividing by 10. An attacker can exploit this to backdate or post-date readings by crafting a 14-digit timestamp that repairs to a valid historical/future time. | Low-Medium | Remove auto-repair heuristic; reject 14-digit timestamps explicitly. |
| 1.4 | `ApiGlucoseSourceManager.parseJsonRawMgdl` infers unit from raw value ranges (`1.0..40.0` → mmol, `40.0..600.0` → mgdl). A value of exactly `40.0` is ambiguous and could be misinterpreted. A crafted payload with `raw_value: 40` and no unit may flip units silently. | Low | Make unit inference explicit: require `unit` field when `raw_value` is used; reject if unit is missing. |
| 1.5 | `NightscoutFollowerManager.parseEntry` only validates `sgv > 0` and `mills > 0`. It does not check for absurd glucose values (e.g. 99999) or future timestamps. This can pollute the native database with impossible values. | Medium | Add `MAX_REASONABLE_MGDL` (e.g. 1200) and `MAX_FUTURE_TIMESTAMP_DRIFT_MS` validation before calling `publishCurrent`. |
| 1.6 | `OttaiBleManager.evaluateGlucose` has `MIN_REASONABLE_MGDL`/`MAX_REASONABLE_MGDL` checks but passes data to `Natives.addGlucoseStream` directly. There is no deduplication guard against the same packet being processed twice if BLE stack delivers duplicate notifications. | Low | Add last-processed-sequence number cache before native call. |
| 1.7 | `g.cpp` `addGlucoseStreamInternal` and `ensureDirectStreamShellForId` do not appear to validate glucose value bounds from the C++ side. A JNI caller can inject any float. | Medium | Add native-side `isfinite(value) && value > 0 && value < MAX_REASONABLE_MGDL` assert before DB insert. |

### 3.2 Sensor Isolation — Findings
| # | Finding | Severity | Fix |
|---|---------|----------|-----|
| 2.1 | `SensorBluetooth.java` `resolvePreferredCurrentSensor` iterates `gattcallbacks` and picks the first connected sensor, but there is no explicit tie-breaker when multiple sensors are simultaneously connected. The UI may flicker between sensors. | Medium | Add stable priority ranking (e.g. most-recent-data > strongest signal > alphabetical). |
| 2.2 | `ExchangeGlucosePayload.java` builds a payload with `primary`, `auto`, `raw` lanes but does not validate that all lanes belong to the same sensor identity. A cross-sensor payload could mix primary from sensor A with raw from sensor B. | Medium | Add cross-lane identity consistency check before payload assembly. |
| 2.3 | `DataSmoothing.kt` `smoothNativePoints` operates on a chunk of points without checking they all share the same `sensorSerial`. If history backfill from two sensors overlaps in time, smoothing may average across sensors. | Medium | Group points by `sensorSerial` before smoothing; never cross-sensor smooth. |
| 2.4 | `ManagedSensorIdentityRegistry.kt` `removePersistedSensor` removes a sensor from all adapters but does not check if that sensor is currently active in another adapter before wiping its identity. Race: sensor A removed from adapter 1 while still streaming on adapter 2 → identity wiped, adapter 2 loses track. | Medium | Add reference-count or `isCurrentlyActive()` guard before wiping persisted identity. |
| 2.5 | `SuperGattCallback.handleGlucoseResultInternal` broadcasts to all receivers (`XInfuus`, `JugglucoSend`, `WearInt`, `Gadgetbridge`, `EverSense`, `OutboundApi`) using the same `ExchangeGlucosePayload`. The payload includes sensor identity but receivers may ignore it. A Wearable or xDrip receiving data from sensor B while the phone is connected to sensor A may display the wrong value. | Low-Medium | Add `sensorIdentity` filter to broadcast receivers: each receiver should verify the payload matches its subscribed sensor before displaying/sending. |
| 2.6 | `SensorIdentity.kt` `resolveAvailableMainSensor` picks the "best" sensor candidate but `availableSensorCandidates` does not filter out expired sensors. An expired sensor still in the registry can win resolution. | Low | Add `!isExpired()` filter to candidates. |

### 3.3 Outbound Data Leaks — Findings
| # | Finding | Severity | Fix |
|---|---------|----------|-----|
| 3.1 | `WearInt.java` `sendglucose` broadcasts an explicit Intent with `GLUCOSE_VALUE` and `TIMESTAMP`. The Intent is sent to all packages listening for the action. Any malicious app declaring the receiver can siphon glucose data without permission. | High | Switch to explicit Intents targeting only known Wear companion package; or guard with `android.permission.BROADCAST_STICKY` / custom signature permission. |
| 3.2 | `Gadgetbridge.java` sends a broadcast to `nodomain.freeyourgadget.gadgetbridge` action. There is no verification that Gadgetbridge is actually installed or that the package name is genuine. A malicious app can install with this package name and receive all glucose data. | Medium | Verify target package exists via `PackageManager` before broadcast; use explicit Intent. |
| 3.3 | `XInfuus.java` sends broadcast to `com.freestylelibre.app.us` (or region variant). Same package-name spoofing risk as Gadgetbridge. | Medium | Verify target package exists before broadcast. |
| 3.4 | `OutboundApi.kt` `executePost` uses `HttpsURLConnection` but does not implement certificate pinning or hostname verification beyond the JVM default. A MITM on the Telegram/Vk/Nightscout endpoint can intercept credentials and glucose data. | Medium | Add optional certificate pinning for known good Telegram/Vk/Nightscout certs; warn user if TLS chain is unexpected. |
| 3.5 | `OutboundApi.kt` `buildJsonBody` includes `stableRandomId` but the ID is derived from a deterministic hash. An observer who sees two JSON payloads can correlate them to the same user/session. | Low | Rotate `stableRandomId` periodically (e.g. daily) using a keyed hash with a session secret. |
| 3.6 | `ApiGlucoseSourceManager.kt` `fetchHttpReadings` and `fetchVkDirectReadings` use `HttpURLConnection` without pinning. The user may enter an HTTP URL (not HTTPS) for the API source, and the code does not enforce TLS. | Medium | Reject non-HTTPS URLs in `ApiGlucoseSourceRegistry.normalizeUrl`; require `https://` prefix. |
| 3.7 | `NightscoutFollowerManager.kt` `fetchReadings` uses `HttpURLConnection` with basic auth via `applyAuth`. The secret is passed in URL or header but there is no check that the connection is HTTPS. | Medium | Enforce HTTPS in `NightscoutFollowerRegistry.normalizeUrl`; reject `http://` or warn prominently. |
| 3.8 | `TelegramStaleCheckWork.kt` posts new messages to Telegram chat but does not verify the chat ID belongs to the same user. If the user changes the bot token or chat ID, stale check messages may leak to a new/unintended recipient. | Low | Add chat-ID validation against a locally stored hash; require confirmation before sending to new chat ID. |
| 3.9 | `Broadcasts.java` UI allows enabling multiple broadcast receivers simultaneously. There is no limit or warning about battery/performance or data exfiltration risk. | Low | Add a warning dialog when enabling >2 broadcast receivers; log a security note. |

## 4. Decision Table

| Finding | Do Now? | Effort | Risk if Skipped |
|---------|---------|--------|-----------------|
| 1.1 Rate bounds | Yes | 1 file, 3 lines | Alert misfire, wrong trend arrow |
| 1.2 JSON field allow-list | Yes | 1 file, 15 lines | Spoofed glucose via extra keys |
| 1.3 Timestamp repair removal | Yes | 1 file, 5 lines | Backdated readings, audit trail corruption |
| 1.4 Raw unit ambiguity | No (Low) | 1 file, 5 lines | Rare, user can fix source format |
| 1.5 Nightscout value bounds | Yes | 1 file, 5 lines | DB pollution with impossible values |
| 1.6 Ottai dedup | No (Low) | 1 file, 10 lines | BLE stack rarely duplicates |
| 1.7 Native bounds assert | Yes | 1 file, 3 lines | Defense in depth |
| 2.1 Preferred sensor tie-breaker | Yes | 1 file, 10 lines | UI flicker, wrong active sensor |
| 2.2 Cross-lane consistency | No (Medium) | 2 files, 20 lines | Theoretical, payload built from same source |
| 2.3 Smoothing by sensor | Yes | 1 file, 10 lines | Cross-sensor averaging |
| 2.4 Identity wipe refcount | Yes | 1 file, 10 lines | Active sensor identity loss |
| 2.5 Receiver sensor filter | No (Low) | 6 files, 30 lines | Receivers should already handle identity |
| 2.6 Expired candidate filter | Yes | 1 file, 2 lines | Wrong sensor resolution |
| 3.1 Wear explicit Intent | Yes | 1 file, 5 lines | Data leak to any app |
| 3.2 Gadgetbridge verify | Yes | 1 file, 3 lines | Package spoofing |
| 3.3 XInfuus verify | Yes | 1 file, 3 lines | Package spoofing |
| 3.4 TLS pinning | No (Medium) | 1 file, 30 lines | MITM risk, but JVM default is acceptable for most users |
| 3.5 RandomId rotation | No (Low) | 1 file, 5 lines | Correlation risk is minor |
| 3.6 API source HTTPS enforce | Yes | 1 file, 3 lines | Credential/plaintext glucose leak |
| 3.7 Nightscout HTTPS enforce | Yes | 1 file, 3 lines | Credential/plaintext glucose leak |
| 3.8 Telegram chat validation | No (Low) | 1 file, 10 lines | User misconfiguration, not code bug |
| 3.9 Broadcast warning | No (Low) | 1 file, 5 lines | UX improvement, not security fix |

## 5. Implementation Order

**Phase A — Data Integrity (Day 1)**
1. `VirtualGlucoseSensorBridge.kt` — add `MAX_REASONABLE_RATE_MGDL_PER_MIN` bounds.
2. `ApiGlucoseSourceManager.kt` — restrict `firstFiniteField` to explicit key allow-list; remove 14-digit timestamp repair; enforce HTTPS in `normalizeUrl`.
3. `NightscoutFollowerManager.kt` — add `MAX_REASONABLE_MGDL` and `MAX_FUTURE_TIMESTAMP_DRIFT_MS` validation.
4. `g.cpp` — add native glucose bounds assert before DB insert.
5. `SensorIdentity.kt` — filter expired candidates.

**Phase B — Sensor Isolation (Day 1–2)**
6. `SensorBluetooth.java` — stable tie-breaker in `resolvePreferredCurrentSensor`.
7. `DataSmoothing.kt` — group by `sensorSerial` before smoothing.
8. `ManagedSensorIdentityRegistry.kt` — reference-count guard before identity wipe.

**Phase C — Outbound Hardening (Day 2)**
9. `WearInt.java` — explicit Intent to known Wear package.
10. `Gadgetbridge.java` — verify package exists before broadcast.
11. `XInfuus.java` — verify package exists before broadcast.
12. `ApiGlucoseSourceRegistry.kt` / `NightscoutFollowerRegistry.kt` — HTTPS enforcement.

**Phase D — Verification (Day 2)**
13. Run unit tests for `VirtualGlucoseSensorBridge`, `ApiGlucoseSourceManager`, `NightscoutFollowerManager`.
14. Run integration test `NightscoutFollowerIntegrationTests`.
15. Manual QA: connect two sensors, verify no cross-sensor smoothing and correct active sensor resolution.
16. Manual QA: enable broadcasts, verify explicit Intents via `adb shell am monitor`.

## 6. Files to Modify (chronological order)

1. `Common/src/main/java/tk/glucodata/drivers/VirtualGlucoseSensorBridge.kt`
2. `Common/src/main/java/tk/glucodata/drivers/api/ApiGlucoseSourceManager.kt`
3. `Common/src/main/java/tk/glucodata/drivers/nightscout/NightscoutFollowerManager.kt`
4. `Common/src/main/cpp/g.cpp` (native bounds)
5. `Common/src/main/java/tk/glucodata/SensorIdentity.kt`
6. `Common/src/main/java/tk/glucodata/SensorBluetooth.java`
7. `Common/src/main/java/tk/glucodata/DataSmoothing.kt`
8. `Common/src/main/java/tk/glucodata/drivers/ManagedSensorIdentityRegistry.kt`
9. `Common/src/main/java/tk/glucodata/WearInt.java`
10. `Common/src/mobile/java/tk/glucodata/Gadgetbridge.java`
11. `Common/src/mobile/java/tk/glucodata/XInfuus.java`
12. `Common/src/main/java/tk/glucodata/drivers/api/ApiGlucoseSourceRegistry.kt` (or wherever `normalizeUrl` lives)
13. `Common/src/main/java/tk/glucodata/drivers/nightscout/NightscoutFollowerRegistry.kt` (or wherever `normalizeUrl` lives)

## 7. Verification Plan

- **Unit tests**: existing test suites for `VirtualGlucoseSensorBridge` and `NightscoutFollowerIntegrationTests` must pass; add negative tests for out-of-bounds rate, invalid timestamp, and HTTPS rejection.
- **LSP diagnostics**: `ktlint` / Android Studio inspections on all modified `.kt`/`.java` files.
- **Build**: `./gradlew :Common:assembleDebug` must succeed.
- **Manual QA**:
  - Two-sensor scenario: verify active sensor is stable and history does not mix.
  - Broadcast scenario: `adb shell am monitor` shows only explicit Intents to expected packages.
  - Nightscout follower: `http://` URL rejected with clear error.

## 8. Resolved Questions

1. **ApiGlucoseSourceRegistry.kt / NightscoutFollowerRegistry.kt**: Both exist. `normalizeUrl` is in both files. It auto-prefixes `https://` if no scheme is present, but does **not** reject `http://` explicitly. Fix: add `http://` rejection in both registries.
2. **RSSI tie-breaker**: `SensorBluetooth.java` `resolvePreferredCurrentSensor` simply picks `candidates.get(0)` — no RSSI ranking. `onLeScan` receives RSSI but it is not stored or used for selection. Fix: add stable priority (most-recent-data > alphabetical) since RSSI is not available at selection time.
3. **Certificate pinning**: No existing utilities in the project (grep for `CertificatePinner`, `TrustManager`, `pinning` returned nothing). If pinning is ever needed, it must be added from scratch. Plan already defers this to Phase C optional.
4. **Wear companion package**: `WearInt.java` broadcasts to action `com.eveningoutpost.dexdrip.watch.wearintegration.BROADCAST_SERVICE_SENDER` without explicit package. The Wear companion package is **not** hardcoded in this file. Fix: switch to explicit Intent targeting the known Wear companion package (configurable via settings) or guard with a signature permission.

## 9. Risk Summary

| Area | Critical | Medium | Low | Deferred |
|------|----------|--------|-----|----------|
| Data Integrity | — | 4 (1.1, 1.2, 1.5, 1.7) | 2 (1.3, 1.4) | 1 (1.6) |
| Sensor Isolation | — | 4 (2.1, 2.3, 2.4, 2.5) | 2 (2.2, 2.6) | — |
| Outbound Leaks | 1 (3.1 Wear) | 4 (3.2, 3.3, 3.4, 3.6, 3.7) | 3 (3.5, 3.8, 3.9) | — |

**Total immediate fixes: 13 files, ~90 lines of code.**

---
*Plan updated with resolved questions. Ready for `$start-work` after user confirmation.*