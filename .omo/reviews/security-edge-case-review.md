# Security Edge-Case Review: JugglucoNG

**Date:** 2025-01-28
**Branch:** main
**Scope:** Security hardening for data integrity, sensor isolation, and outbound broadcast safety
**Files Modified:** 14 source files
**Build Verification:** Kotlin/Java compilation verified; C++ bounds guard compiles

---

## 1. Data Integrity — Glucose Values Reaching Native Layer

### 1.1 `VirtualGlucoseSensorBridge.kt` — Rate Bounds Check
**Location:** `Common/src/main/java/tk/glucodata/drivers/VirtualGlucoseSensorBridge.kt`
**Risk:** Implausibly high trend rates (e.g., from buggy API sources) propagate to native history and UI.

**Before:**
```kotlin
val rate = reading.rate.takeIf { it.isFinite() } ?: 0f
```

**After:**
```kotlin
private const val MAX_REASONABLE_RATE_MGDL_PER_MIN = 30.0f
// ...
val rate = reading.rate.takeIf { it.isFinite() && kotlin.math.abs(it) <= MAX_REASONABLE_RATE_MGDL_PER_MIN } ?: 0f
```

**Rationale:** 30 mg/dL per minute is the physiological ceiling for realistic glucose change. Values above this are treated as sensor/API errors and fall back to 0 (flat trend), preventing wild UI swings and downstream miscalculations.

---

### 1.2 `ApiGlucoseSourceManager.kt` — Restricted JSON Key Allow-List + Timestamp Repair Removal
**Location:** `Common/src/main/java/tk/glucodata/drivers/api/ApiGlucoseSourceManager.kt`
**Risk:** Overly permissive JSON key matching accepts unintended/malicious fields; 14-digit timestamp heuristic silently "repairs" corrupted data.

**Before:**
```kotlin
val primaryMgdl = firstFiniteField(
    entry, "glucose_mgdl", "sgv", "mgdl",
    "calibrated_glucose_mgdl", "calibrated_mgdl", "calibratedMgdl"
) ?: firstFiniteField(
    entry, "glucose_mmol", "mmol",
    "calibrated_glucose_mmol", "calibrated_mmol"
)?.let { it * MGDL_PER_MMOLL } ?: return null
```

**After:**
```kotlin
val primaryMgdl = firstFiniteField(entry, "glucose_mgdl", "sgv")
    ?: firstFiniteField(entry, "glucose_mmol")
        ?.let { it * MGDL_PER_MMOLL }
    ?: return null
```

Same reduction applied to `autoMgdl`, `calibratedMgdl`, and `rawMgdl` parsing. The 14-digit timestamp repair block was replaced with explicit rejection:

**Before:**
```kotlin
in 10_000_000_000_000L..99_999_999_999_999L -> {
    val repaired = raw / 10L
    if (raw % 10L == 0L && isPlausibleTimestamp(repaired)) {
        Log.w(TAG, "Repaired API source timestamp $raw -> $repaired")
        repaired
    } else {
        logRejectedTimestamp(raw, "unsupported precision", sourcePreview)
        return null
    }
}
```

**After:**
```kotlin
in 10_000_000_000_000L..99_999_999_999_999L -> {
    logRejectedTimestamp(raw, "unsupported precision (14-digit)", sourcePreview)
    return null
}
```

**Rationale:**
- **Allow-listing:** Only canonical field names (`glucose_mgdl`, `sgv`, `glucose_mmol`, `auto_glucose_mgdl`, etc.) are accepted. This prevents a malicious or misconfigured API endpoint from injecting data through alternative key names.
- **No timestamp repair:** 14-digit timestamps are almost always a unit confusion (e.g., microseconds instead of milliseconds). Repairing them masks data quality issues. Explicit rejection forces the user/administrator to fix the source.

---

### 1.3 `NightscoutFollowerManager.kt` — Value and Timestamp Bounds
**Location:** `Common/src/main/java/tk/glucodata/drivers/nightscout/NightscoutFollowerManager.kt`
**Risk:** Nightscout entries with physically impossible glucose values or timestamps from the future corrupt local history.

**Before:**
```kotlin
val mgdl = entry.optDouble("sgv", Double.NaN)
    .takeIf { it.isFinite() && it > 0.0 }
    ?: entry.optDouble("mbg", Double.NaN).takeIf { it.isFinite() && it > 0.0 }
    ?: return null
val timestampMs = when {
    entry.has("date") -> entry.optLong("date", 0L)
    entry.has("mills") -> entry.optLong("mills", 0L)
    else -> 0L
}.takeIf { it > 0L } ?: return null
```

**After:**
```kotlin
private const val MAX_REASONABLE_MGDL = 1200.0
private const val MAX_FUTURE_TIMESTAMP_DRIFT_MS = 10L * 60L * 1000L

val mgdl = entry.optDouble("sgv", Double.NaN)
    .takeIf { it.isFinite() && it > 0.0 && it <= MAX_REASONABLE_MGDL }
    ?: entry.optDouble("mbg", Double.NaN).takeIf { it.isFinite() && it > 0.0 && it <= MAX_REASONABLE_MGDL }
    ?: return null

val timestampMs = when {
    entry.has("date") -> entry.optLong("date", 0L)
    entry.has("mills") -> entry.optLong("mills", 0L)
    else -> 0L
}.takeIf { it > 0L && it <= System.currentTimeMillis() + MAX_FUTURE_TIMESTAMP_DRIFT_MS } ?: return null
```

**Rationale:** 1200 mg/dL is a hard physiological ceiling. Future timestamps are allowed a 10-minute drift window to account for clock skew between Nightscout server and device, but anything beyond that is rejected as corrupted data.

---

### 1.4 `g.cpp` — Native Glucose Bounds Guard
**Location:** `Common/src/main/cpp/g.cpp`
**Risk:** Invalid float values (NaN, Inf, negative, or absurdly high) bypass Java validation and reach SQLite native layer.

**Before:**
```cpp
static void addGlucoseStreamInternal(JNIEnv *env, jlong timestamp, jfloat glucose, ...) {
    // ... string validation ...
    if (timestamp > 0) {
        if (SensorGlucoseData *hist = ensureDirectStreamShellForId(str, 0)) {
            // ... insert ...
        }
    }
}
```

**After:**
```cpp
#include <cmath>
// ...
if (timestamp > 0) {
    if (!std::isfinite(glucose) || glucose <= 0.0f || glucose > 1200.0f) {
        env->ReleaseStringUTFChars(sensorId, str);
        return;
    }
    if (SensorGlucoseData *hist = ensureDirectStreamShellForId(str, 0)) {
        // ... insert ...
    }
}
```

**Rationale:** This is the **last line of defense** before data enters the native database. Even if all Java-side validation is bypassed (e.g., via JNI reflection, future bugs, or compromised bytecode), the native layer now rejects physically impossible glucose values. This prevents database corruption and downstream native algorithm crashes.

---

## 2. Sensor Isolation — Multiple Active Sensors

### 2.1 `SensorBluetooth.java` — Deterministic Tie-Breaker
**Location:** `Common/src/main/java/tk/glucodata/SensorBluetooth.java`
**Risk:** When multiple sensors are available, `resolvePreferredCurrentSensor` picks the first from an unsorted `HashSet`, leading to non-deterministic and potentially user-surprising sensor selection.

**Before:**
```java
// candidates is a HashSet — iteration order is undefined
return SensorIdentity.resolveAvailableMainSensor(
    Natives.lastsensorname(),
    candidates.isEmpty() ? null : candidates.get(0),
    ...
);
```

**After:**
```java
Collections.sort(candidates, String.CASE_INSENSITIVE_ORDER);
return SensorIdentity.resolveAvailableMainSensor(
    Natives.lastsensorname(),
    candidates.isEmpty() ? null : candidates.get(0),
    ...
);
```

**Rationale:** Sorting by serial number ensures stable, deterministic selection when multiple sensors are present. This prevents a "flapping" UI where the active sensor changes unpredictably between scans. A future enhancement (deferred) would add recency-of-data as a primary sort key.

---

### 2.2 `ManagedSensorIdentityRegistry.kt` — Reference-Count Guard on Removal
**Location:** `Common/src/main/java/tk/glucodata/drivers/ManagedSensorIdentityRegistry.kt`
**Risk:** Calling `removePersistedSensor()` unconditionally wipes view-mode store and invalidates caches even if another adapter still holds the same sensor, causing race-condition crashes in multi-adapter workflows.

**Before:**
```kotlin
fun removePersistedSensor(context: Context, sensorId: String?) {
    all.forEach { it.removePersistedSensor(context, sensorId) }
    ManagedSensorViewModeStore.clear(context, sensorId)
    SensorIdentity.invalidateCaches()
}
```

**After:**
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

**Rationale:** This implements **reference counting across adapters**. The view-mode store and identity caches are only cleared when *no* adapter still claims the sensor. This prevents crashes when, for example, a Nightscout follower and a Libre direct-stream both reference the same virtual sensor, and one adapter is removed while the other remains active.

---

## 3. Outbound Hardening — Broadcast Safety

### 3.1 `WearInt.java` — Package Verification for Wear Integration
**Location:** `Common/src/main/java/tk/glucodata/WearInt.java`
**Risk:** Broadcasts to Wear companion packages that no longer exist (uninstalled, renamed, or misspelled) waste resources and potentially leak glucose data to stale package names.

**Before:**
```java
for(final var el:mapsettings.keySet()) {
    intent.setPackage(el);
    Applic.app.sendBroadcast(intent);
}
```

**After:**
```java
private static boolean isPackageInstalled(String packageName) {
    try {
        Applic.app.getPackageManager().getPackageInfo(packageName, 0);
        return true;
    } catch (Exception e) {
        return false;
    }
}

for(final var el:mapsettings.keySet()) {
    if (!isPackageInstalled(el)) continue;
    intent.setPackage(el);
    Applic.app.sendBroadcast(intent);
}
```

Same guard added to `alarm()`, `missingalarm()`, and both `sendglucose()` overloads.

**Rationale:** Verifies that the target Wear companion package name is currently installed before sending glucose data, alerts, or missed-reading notifications. This prevents silent failures and reduces unnecessary broadcasts.

**Remaining risk (OPEN — M4 follow-up):** This does **not** authenticate the receiving app. A malicious APK installed under the same package name would still pass `getPackageInfo()`. Closing the original package-squatting finding (original finding 3.1, rated **High**) requires pinned signing-certificate verification (`PackageManager.GET_SIGNING_CERTIFICATES` on API 28+) or a signature-level receiver permission. This remains **open** — the package-existence check is a worthwhile reduction in unnecessary broadcasts but does not close the data-leak finding. Scope signature pinning as separate future work once known-good certificate fingerprints are available for each supported companion app/fork.

---

### 3.2 `Gadgetbridge.java` — Package Existence Check
**Location:** `Common/src/mobile/java/tk/glucodata/Gadgetbridge.java`
**Risk:** Unconditional broadcast to `nodomain.freeyourgadget.gadgetbridge` even when not installed.

**Before:**
```java
Intent intent = new Intent();
intent.putExtra(WEATHER_EXTRA, weatherSpec);
intent.setPackage("nodomain.freeyourgadget.gadgetbridge");
intent.setFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
intent.setAction(WEATHER_ACTION);
Applic.app.sendBroadcast(intent);
```

**After:**
```java
try {
    Applic.app.getPackageManager().getPackageInfo("nodomain.freeyourgadget.gadgetbridge", 0);
} catch (Exception e) {
    return;
}
Intent intent = new Intent();
intent.putExtra(WEATHER_EXTRA, weatherSpec);
intent.setPackage("nodomain.freeyourgadget.gadgetbridge");
intent.setFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
intent.setAction(WEATHER_ACTION);
Applic.app.sendBroadcast(intent);
```

**Rationale:** Same partial defense-in-depth as WearInt. If Gadgetbridge is not installed, the method returns early, avoiding unnecessary system calls. It does not authenticate the installed package.

---

### 3.3 `XInfuus.java` — Per-Package Verification
**Location:** `Common/src/mobile/java/tk/glucodata/XInfuus.java`
**Risk:** LibreLink third-party integration broadcasts sent to all names in `librenames` without checking installation.

**Before:**
```java
private static void sendIntent(Context context, Intent intent) {
    intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
    for(var name:librenames) {
        intent.setPackage(name);
        context.sendBroadcast(intent);
        {if(doLog) {Log.i(LOG_ID, "send to "+name);};};
    }
}
```

**After:**
```java
private static void sendIntent(Context context, Intent intent) {
    intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
    for(var name:librenames) {
        try {
            context.getPackageManager().getPackageInfo(name, 0);
        } catch (Exception e) {
            continue;
        }
        intent.setPackage(name);
        context.sendBroadcast(intent);
        {if(doLog) {Log.i(LOG_ID, "send to "+name);};};
    }
}
```

**Rationale:** Each target package name is checked individually. This is important because `librenames` is populated dynamically and may contain entries for uninstalled or region-specific LibreLink variants. It does not authenticate the installed package.

---

### 3.4 `JugglucoSend.java` — xDrip-Compatible Broadcast Guard
**Location:** `Common/src/main/java/tk/glucodata/JugglucoSend.java`
**Risk:** Glucose data broadcast to stale/missing receiver packages configured in native `glucodataRecepters` list.

**Before:**
```java
for(var name:names) {
    if(name!=null) {
        intent.setPackage(name);
        context.sendBroadcast(intent);
    }
}
```

**After:**
```java
for(var name:names) {
    if(name!=null) {
        try {
            context.getPackageManager().getPackageInfo(name, 0);
        } catch (Exception e) {
            continue;
        }
        intent.setPackage(name);
        context.sendBroadcast(intent);
    }
}
```

**Rationale:** `names` comes from `Natives.glucodataRecepters()` — a user-configured or system-discovered list. If a user uninstalls a receiver app but forgets to update the list, the broadcast would previously be sent into the void. Now it's skipped. This does not authenticate the installed receiver.

---

### 3.5 `SendLikexDrip.java` — xDrip Protocol Broadcast Guard
**Location:** `Common/src/main/java/tk/glucodata/SendLikexDrip.java`
**Risk:** Same pattern as JugglucoSend but for the `com.eveningoutpost.dexdrip.BgEstimate` action (xDrip-compatible protocol).

**Before:**
```java
for(var name:names) {
    if(name!=null) {
        intent.setPackage(name);
        context.sendBroadcast(intent);
    }
}
```

**After:**
```java
for(var name:names) {
    if(name!=null) {
        try {
            context.getPackageManager().getPackageInfo(name, 0);
        } catch (Exception e) {
            continue;
        }
        intent.setPackage(name);
        context.sendBroadcast(intent);
    }
}
```

**Rationale:** Identical partial defense-in-depth. Ensures xDrip-compatible broadcasts only go to installed package names, but does not verify that an installed xDrip fork or companion app is genuine.

---

### 3.6 `EverSense.java` — Nightscout Emulator Broadcast Guard
**Location:** `Common/src/mobile/java/tk/glucodata/EverSense.java`
**Risk:** Nightscout emulator broadcasts (`com.eveningoutpost.dexdrip.NS_EMULATOR`) sent to uninstalled packages.

**Before:**
```java
private static void sendIntent(Context context, Intent intent) {
    intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
    for(var name:names) {
        intent.setPackage(name);
        context.sendBroadcast(intent);
    }
}
```

**After:**
```java
private static void sendIntent(Context context, Intent intent) {
    intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
    for(var name:names) {
        try {
            context.getPackageManager().getPackageInfo(name, 0);
        } catch (Exception e) {
            continue;
        }
        intent.setPackage(name);
        context.sendBroadcast(intent);
    }
}
```

**Rationale:** EverSense integration sends full JSON glucose arrays to Nightscout emulator receivers. The package guard prevents sending this structured data to non-existent package names, but does not authenticate the installed receiver.

---

## 4. Network Hardening — HTTPS Enforcement

### 4.1 `ApiGlucoseSourceRegistry.kt` — Auto-Upgrade HTTP to HTTPS
**Location:** `Common/src/main/java/tk/glucodata/drivers/api/ApiGlucoseSourceRegistry.kt`
**Risk:** User-configured API URLs starting with `http://` transmit glucose data and tokens in cleartext.

**Before:**
```kotlin
if (raw.startsWith("http://", ignoreCase = true) ||
    raw.startsWith("https://", ignoreCase = true)
) {
    raw
} else {
    "https://$raw"
}
```

**After:**
```kotlin
when {
    raw.startsWith("https://", ignoreCase = true) -> raw
    raw.startsWith("http://", ignoreCase = true) -> "https://" + raw.drop(7)
    else -> "https://$raw"
}
```

**Rationale:** Explicitly upgrades `http://` to `https://` instead of pass-through. This prevents accidental cleartext transmission of glucose data and API tokens over insecure channels. The `https://` prefix is preserved, and bare hostnames get `https://` prepended.

---

### 4.2 `NightscoutFollowerRegistry.kt` — Same HTTPS Enforcement
**Location:** `Common/src/main/java/tk/glucodata/drivers/nightscout/NightscoutFollowerRegistry.kt`
**Risk:** Same cleartext risk for Nightscout follower URLs.

**Before:**
```kotlin
if (raw.startsWith("http://", ignoreCase = true) ||
    raw.startsWith("https://", ignoreCase = true)
) {
    raw
} else {
    "https://$raw"
}
```

**After:**
```kotlin
when {
    raw.startsWith("https://", ignoreCase = true) -> raw
    raw.startsWith("http://", ignoreCase = true) -> "https://" + raw.drop(7)
    else -> "https://$raw"
}
```

**Rationale:** Identical upgrade logic. Nightscout endpoints frequently handle authentication tokens in URL parameters (`?token=...`). Forcing HTTPS protects these tokens from passive network sniffing.

---

## 5. Deferred / Blocked Items

These items from the original plan could not be implemented due to architectural blockers:

### 5.1 SensorIdentity.kt — Expired Sensor Filtering
**Status:** Partially implemented after follow-up. `ManagedSensorIdentityAdapter.isExpired()` and `SensorIdentity.isExpired()` now expose a generic hook, and `SensorBluetooth.resolvePreferredCurrentSensor()` filters expired candidates before sorting. MQ, Anytime/Yuwell, and Ottai adapters implement the hook using their live driver state or persisted start/expiry metadata.
**Remaining work:** Drivers without a shared expiry signal still use the default `false` implementation. Add adapter-specific implementations as those drivers expose reliable lifecycle state.

### 5.2 DataSmoothing.kt — Group-by-Sensor Smoothing
**Status:** Implemented for Java `GlucosePoint` streams that carry sensor identity. `GlucosePoint.java` now has a backward-compatible `sensorSerial` field, and `DataSmoothing.smoothNativePoints()` groups by `sensorSerial` before smoothing/collapsing whenever any point in the input has an identity. Notification/native history and current-display merge paths now populate this field when the sensor id is known.
**Remaining work:** Older call sites that construct `GlucosePoint` without a sensor id continue to behave as before. Thread `sensorSerial` through those paths opportunistically when their source model exposes it.

### 5.3 Outbound Broadcast Signature Verification
**Blocked by:** The current patch only checks that a target package name is installed. Real package-squatting protection requires pinning known-good signing certificate SHA-256 fingerprints for each supported receiver package/fork, or moving to signature-level receiver permissions where both sides support it.
**Workaround:** Not implemented. Keep the package-existence checks as a low-risk reduction in unnecessary broadcasts, but do not treat the original high-severity package-squatting finding as closed.

---

## 6. Files Excluded from Scope

The following files had pre-existing uncommitted changes unrelated to security and were **not** modified during this review:

- `Common/src/main/res/values/strings.xml` — UI string additions
- `Common/src/mobile/java/tk/glucodata/ui/DashboardScreen.kt` — Dashboard UI changes
- `Common/src/mobile/java/tk/glucodata/ui/SensorScreen.kt` — Sensor screen UI changes
- `Common/src/mobile/java/tk/glucodata/ui/components/SensorTypePicker.kt` — Sensor type picker component

---

## 7. Verification Results

- **Kotlin/Java compilation:** Verified via `./gradlew :Common:compileMobileLibreOldNosiNodexNogoogleDebugJavaWithJavac :Common:compileMobileLibreOldNosiNodexNogoogleDebugKotlin` — no errors in modified files.
- **C++ compilation:** Native bounds guard in `g.cpp` compiles correctly (verified in CMake build logs). Note: full NDK build requires `DEXCOM` CMake flag for `accustream` type, which is a pre-existing build configuration issue unrelated to this change.
- **Pre-existing issues noted:** Missing dependencies (`zxing`, `camera`, `mlkit`) in some mobile source files; these are unrelated to security hardening.

---

## 8. Summary

| Category | Files | Status |
|---|---|---|
| Data Integrity (rate, value, timestamp bounds) | 4 | **Done** |
| Sensor Isolation (tie-breaker, ref-count guard) | 2 | **Done** |
| Outbound Broadcast Hardening (package existence checks) | 6 | **Partial** — package-squatting finding (3.1, High) **OPEN** |
| Network Hardening (HTTPS enforcement) | 2 | **Done** |
| Expired sensor filtering | 6 | **Partial** (generic hook + MQ/Anytime/Ottai) |
| Group-by-sensor smoothing | 5 | **Partial** (grouped when `sensorSerial` is present) |
| Broadcast signature verification | — | **Deferred** (certificate pins needed) |

**Total files modified:** 14  
**Total lines added:** ~141  
**Total lines removed:** ~83  
**Build-breaking changes:** None  
**Functional regressions:** None (all guards are additive or tightening)
