# Refactoring direction

**Status:** plan of record. Section 1 records maintainer decisions and is not up for re-discussion inside a task PR; everything else is guidance and can be challenged in a PR that says why.
**Date:** 2026-09-23. **Measured at:** `main` @ `5f7edd924` (1.2.1) and the open stack #373–#397.
**Audience:** contributors and the agents they use. Read §1, §3 and §8 before picking up any structural task.
**Russian version:** [`direction.ru.md`](direction.ru.md). The two are kept in sync; if they disagree, the English file wins and the mismatch is a bug.

This document follows up the first round of refactoring PRs (T0.0–T0.3, T5.1, T2.1–T2.3). Those PRs were written against an earlier proposal that never landed in the repository. Two later drafts existed only outside the repo too. This file replaces all three as the single reference: it keeps what they agreed on, records the decisions the maintainer has now taken, and changes course where the first round showed a better path.

---

## 0. Summary

1. **Merge the safety net now.** T0.0–T0.2 (#373–#375) change no runtime behaviour and give the watch its first working unit-test suite and CI. #375 supersedes draft #294.
2. **Accept the first T5.1 batch** (#376–#384, 36 → 25 duplicate classes) after a short on-device check (§7). Continue the remaining pairs with the new classification in §4, not "move into `main` and return early on the watch".
3. **Rework the metrics gate** (#386) so it fails only on structural counts. As written it turns ordinary bug-fix PRs red.
4. **Park the SettingsStore track** (#387–#397). Keep the branch; do not merge. The settings problem is real, but it is solved by a registry that declares each user setting once, including whether it is mirrored to the watch. That registry returns inside the phone↔watch protocol track (§2.4).
5. **Next structural track: a real Room migration harness** (§5). It is the only area where a mistake destroys user data, and today nothing tests it.
6. **Then, one track at a time:** registration seams in place of reflection → typed phone↔watch protocol → the watch features the maintainer wants (IOB/COB, standalone Nightscout, journal entry) → the remaining two-sided pairs (§6).

---

## 1. Maintainer decisions

Decided 2026-09-22. A PR that contradicts one of these needs the maintainer's explicit agreement first, not a justification in the PR body.

| # | Decision | Consequence for the work |
|---|---|---|
| D1 | **The watch is a full client.** IOB/COB display, standalone Nightscout, and journal/meal entry are target features on the watch. | The twins behind these features (`IOB`, `Nightscout`, `Meal`) are *not* resolved as "absent on the watch". Their domain logic must become shared; the watch gets its own UI. See §4, category W. |
| D2 | **Single Gradle module for now.** Boundaries are packages, enforced by source-scanning tests. A package becomes a module only after it has held as a boundary for at least one release. | No `:core-domain` / `:data-*` extraction PRs. Put new boundary code in a clearly named package and add a test that keeps it clean. |
| D3 | **The native C++ base goes behind Kotlin contracts; its end state is not decided.** Making native an optional plugin is a possible horizon, not a goal. | Contracts are written so a non-native implementation *could* exist, but nobody builds one. No "replace mmap store" work. |
| D4 | **One structural track open at a time.** After T0 and the first T5.1 batch, the next track is the Room migration harness. | New structural PRs wait until the open track merges or is explicitly parked. Small bug fixes are not structural and are never blocked by this. |
| D5 | **Review model: pilot plus spot-check.** The maintainer reads the first PR of each recipe in full. Later PRs that apply the same recipe merge on green CI plus a spot-check. | Every mechanical PR names its recipe and links the pilot PR. The areas in §8.3 always get full review. |
| D6 | **The metrics gate hard-fails only on structural counts.** Everything else is reported. | #386 is reworked as described in §2.3. |
| D7 | **SettingsStore is parked.** | #387–#397 are closed with the branch kept. What replaces them is a settings registry, redirected into the protocol track; §2.4 has the design, and the maintainer confirmed the redirect on 2026-09-23. |
| D8 | **This directory is where the plan lives.** `docs/architecture/` holds this file, its Russian version, and any future decision records. | Plans kept in chat, gists or local files do not count. If a PR changes the plan, it edits this file. |

Existing project rules that already applied before this document and still apply:

- **Selective upstream merges continue** for the parts of the legacy base not yet replaced. Prefer refactoring files we have already departed from; avoid gratuitous churn in files still tracked against upstream Juggluco.
- **Never stop publishing glucose readings** or show a sensor as expired because of driver inference. Any change that could withhold a reading needs the maintainer's explicit yes.
- **Driver signal processing, timestamps, and sensor grids** are maintainer sign-off territory, with no exceptions.

---

## 1.5 Where the plan actually stands

Checked against the tree and the PR list on 2026-09-28. Read this before trusting the wording
further down: several items describe prerequisites that were already built, and one counted wrong.

| Plan item | Status | Evidence |
| --- | --- | --- |
| §2.1 T0.0-T0.2, green suites on both flavours | **done** | #373, #374, #375 merged |
| §2.2 T5.1 first batch | **superseded** by the registration-seams recipe; per-PR state not re-verified in this pass | -- |
| §2.3 T0.3 architecture gate | **done** | #386 merged; `ArchitectureGateTests` runs on both flavours |
| §2.4 SettingsStore (#387-#397) | **closed, never merged** | #387 and #397 closed; neither `SettingKey` nor `SettingsStore` exists in the tree |
| §4 category P, phone-only by design | **done** | #459, #461, #469; the pilot and the batches are in |
| §4 category S, two real implementations | **done** | #469, #472, #473, #477, #481 |
| §4 category R, reached by reflection | **done** | `ComposeHostKt` is gone, `ComposeHostAccess` replaced it |
| §4 category W, the watch should get the feature | **not started** -- this is Q3 | -- |
| §5 Room migration harness | **built** | migrations run to 30; `HistoryDatabase.isCompatibleAtStartup` gates startup |
| §6 Q1 registration seams instead of reflection | **done** | `Class.forName` in `src/main` is 1, and it is the exclusion §6 Q1 names: `Log.java:197` |
| §6 Q2 typed phone-watch protocol | **done; §6 reworded to say how the version is carried** | #490, #491, #494; see below |
| §6 Q3 D1 watch features | **in progress; the decisions are made** | #498 and #500 settled; #501 (shared IOB/COB maths), #511/#512 (the watch journal outbox, #502) and #514 (Nightscout glucose identity) merged |
| §6 Q4 category S duplicates | **done, allow-list ends at three by decision** | #469, #472, #473, #477, #481; the floor is set in #475 |
| §6 Q5 storage-ownership document | **all seven facets traced; open questions remain within them** | #478, then #480, #482, #483, #484 and a correction pass |

### PR state, as of 2026-09-30

Every PR number cited above is merged unless it is listed here.

- **Merged since the table in §1.5 was written:** #492 — Bluetooth glucose meters pair, and the
  OneTouch Verio Flex is supported; #494 — the `/settings` path is gone (29 paths), and
  `assertNoDeadPrefix` guards both manifests against a prefix left behind when a path is dropped;
  #495 — §6 Q2 reworded to say how the version is carried; #496 — the 17 `AndroidManifest.xml.*`
  copies no build read are deleted; #497 and #517 — this bookkeeping; #501 — the IOB/COB arithmetic
  moved to `src/main` so the watch can compute it; #503 — a `SECRET` setting can no longer travel
  to the watch through the generic mirror; #506 — a peer's `owns=false` is confirmed for 15 s
  before the sensor is taken back; #511 and #512 — a journal entry made away from the phone is kept
  in a watch outbox under an identity that makes a repeat one row (#502); #514 — a Nightscout
  glucose entry is named after the reading rather than a position in the poll series; #515 and
  #516 — the de-duplication rule behind those, and what the database guarantees about it.
- **Open:** none of the Q-track PRs.
- **Closed unmerged:** #452, #453, #454 — the Q2 version pilots. They added versions to the text
  payloads; #467 superseded them by pinning the version policies that already exist and refusing a
  payload the build cannot read, and #495's payload-version half was dropped for the same reason
  the plan above now describes. #387–#397 — the SettingsStore track of §2.4, never merged, which
  is why the row above says `SettingKey` and `SettingsStore` do not exist.

### On Q2 specifically

Q2's two lessons and its stated starting point are **already in the code**, and the code cites Q2
by name where it did them. This section is stale about all of it.

- **The settings-registry pilot is done.** `SettingsRegistry` holds 13 definitions,
  `SettingScope.MIRRORED` marks the mirrored ones, and `WearPrefsSync` is generated from
  `[mirrored]` rather than keeping its own list -- which is what this section asks for. #467 is
  merged as well ("refuse a payload you cannot read, and pin the three version policies").
- **Lesson 1, a mismatched build must be visible, is implemented.** `MessageReceiver.kt:73` has
  the handshake advertising the protocol version and logging a mismatch; `WearProtocol.kt:63`
  names it as lesson 1. `WearPrefsSync` frames its payload with a version line and refuses one
  from a newer protocol.
- **Lesson 2, per-sensor payloads must carry the sensor identity, is fixed.**
  `SyncedWearCalibrationProvider.selectPayload` takes the requested sensor id, then the device's
  main sensor, and only accepts a single payload when there is no sensor context at all -- the
  comment records that choosing the highest revision is how a second sensor got the first one's
  calibration.
- **The unknown-message rule exists.** `MessageReceiver.kt:343` logs each unknown path once.

What was left here is done as of #490: the paths are `WearMessagePath`, a closed enum, and the
receiver dispatches on it with a `when` the compiler checks for coverage. #494 drops `/settings`,
which no NG build ever spoke, leaving 29. Generating the manifest from the types is **not** done —
the filter is still hand-kept XML, and `WearMessagePathManifestTests` is what notices when the two
disagree. **`SensorOwnershipRuntime` needs nothing here and did not need it:** it has exactly one
message — `onPeerReport` on `/sync2/own` — that payload already carried a version byte
(`VERSION = 1`, and `decode` refuses any other value), and #490 put the path in `WearMessagePath`
with the wire string pinned. A state machine with one message has nothing to type. What remains is
the clone/mirror protocol between phones, which stays on its own envelope.

## 2. Review of the first round

### 2.1 T0.0–T0.2 (#373, #374, #375) — merge

- **T0.0** pins `Locale.US` in two tests that assumed a `.` decimal separator, and points `CloneRecoveryMigrationTests` at the debug KSP output that the debug test task actually builds. Both are test-only changes.
- **T0.1** moves the 91 shared tests that referenced phone-only classes into `src/testMobile`. The watch test task compiles for the first time. The contributor reports 2431 watch tests and 3202 phone tests passing, with no test deleted or ignored.
- **T0.2** replaces the Nightscout-only, path-filtered workflow with `ci.yml`. It runs both flavours' unit suites and compiles phone debug, watch debug and phone release.

Two follow-ups, both small:

1. **Close #294.** #375 replaces it.
2. **Add `assembleWearRelease`** (arm64 is enough) to the compile job. T5.1 moves classes into `src/main`, which puts them in the watch app's R8 path for the first time. Today nothing builds a minified watch APK.

### 2.2 T5.1 first batch (#376–#384) — accept, then change recipe

The first batch cut the duplicate-name classes from 36 to 25 and added `NoDuplicateFqnTest`, whose allow-list can only shrink. The diffs are behaviour-preserving:

- `Applic.isWearable` is a compile-time constant, so the watch-only branches disappear from phone bytecode.
- The `AlarmActionReceiver` merge routes custom alerts through `CustomAlertAccess`. The phone registers the engine in `Applic.onCreate`, so a notification action that cold-starts the process still reaches it.

**What this recipe does not achieve.** "Move the phone class into `main` and add `if (isWearable) return;`" fixes the drift between two copies, which is the main win. But the watch still silently does nothing; the stub has only moved into a branch. That is acceptable for the features the first batch touched, because the watch was never meant to have them: the battery screen, LibreLink broadcasts, Gadgetbridge and the home-screen widget. It is **not** acceptable for the D1 features, and it is not the right tool when the class needs phone-only resources or libraries. §4 gives a recipe per category.

**Stacking.** Eleven one-pair PRs, each showing the whole stack's diff, cost more review time than the changes are worth. From here on, see §8.1.

### 2.3 T0.3 metrics gate (#386) — rework before merging

The script works, but it hard-fails on 15 grep counts, including `runcatching_swallowed`, `kotlin_object_singletons`, `volatile_fields`, `root_package_files` and `root_package_loc`. Run against the maintainer's open bug-fix PR #371 (wear multi-sensor selection), it fails on four of them. The last one means that adding any line anywhere in the root `tk.glucodata` package turns CI red. The predictable outcome is that every PR bumps the baseline and the gate stops meaning anything.

Rework:

- **Hard gate** (the right direction is never in doubt):
  - Composable files that call `Natives.`
  - Composable files that call `Applic.`
  - Composable files that touch `SharedPreferences` directly
  - `Class.forName` lookups from `src/main` into phone or watch code
  - `fallbackToDestructiveMigration` occurrences, fixed at 0
  - duplicate-name classes, which `NoDuplicateFqnTest` already covers, so leave it out here rather than count it twice
- **Report only** (printed in the CI log with the trend, never failing): everything else, including `@Volatile`, `runCatching`, `Thread(`, `Handler(`, `object` count, lines per package, and files over 2000 lines.
- Prefer a JUnit source-scanning test in the style of `ProguardKeepRulesTests` over a JUnit test that launches `bash`. If the shell script stays for local use, the test should not depend on it.
- Take the baseline from `main` at merge time, not from a branch.

### 2.4 T2.1–T2.3 SettingsStore (#387–#397) — parked, then redirected

The code is careful. In the areas checked, stored types, default values and `apply()` semantics match the old code. The settings problem it set out to solve is also real. The track is parked because it aimed at the wrong files and left out the part that removes the bugs.

**What the prefs files actually hold.** There are 24 of them, and they mix four kinds of data that need different treatment:

| Kind | Examples | Where it belongs |
|---|---|---|
| User settings | prediction and smoothing options, units, colours, Nightscout and outbound-API configuration, custom sounds | the settings registry described below |
| Sensor state and secrets | AiDex pair keys, iCan AES keys, MQ per-sensor calibration parameters and packet cursors | the sensor's own records (§6, Q5); never exported, never mirrored to the watch |
| Runtime state | snooze deadlines, last attempt / last error, BLE error history, dismissed banners, telemetry | next to the component that owns it |
| Caches | `wear_journal_cache`, cached update metadata | wherever is convenient |

Most of the 9 migrated areas are runtime state, not settings: snooze, BLE error history, readiness dismissals, backup and update bookkeeping, wear routing requests. The real sprawl is untouched. `tk.glucodata_preferences` is opened from 79 call sites that spell out the file name, plus 41 files that each declare their own constant for it, and no key is declared in one place.

**The settings bug with evidence: defaults defined in several places.** `WearPrefsSync` mirrors 10 phone settings to the watch from a hand-kept list that repeats each key's type and default. Its own comment records the failure: the predictive-simulation toggle showed "off" on the watch while it was "on" on the phone, because the watch fell back to a different default. Today the prediction-horizon default (120) is declared separately in four files, and the carb-absorption default (35 g/h) in five. They agree right now; nothing keeps them agreeing. The watch features in D1 add more settings the watch needs from the phone: insulin types and sensitivity, the Nightscout URL and secret. Each would be one more hand-added list entry.

**What the facade does not fix.** Live updates are not the problem: the dashboard re-reads its settings on every `UiRefreshBus` event, which is crude but works. `observe()` solves nothing that is broken today.

**What to build instead: a settings registry.** Each *user setting* is declared once, and every read goes through that declaration. A declaration names:

- the file, the key, the type and the default
- **scope**: phone only, watch only, or phone-owned and mirrored to the watch
- **backup/export policy**: included, excluded, or secret
- a valid range where one exists (`GlucoseDelta.sanitizeIntervalMinutes` is today's hand-written version)

`WearPrefsSync`'s list is then generated from the declarations whose scope is "mirrored", so a key's default cannot disagree with itself. `SettingKey(file, name, default)` from #388 is the right seed; it lacks the policies.

**Pilot:** the 10 prediction and smoothing settings `WearPrefsSync` already mirrors. They are few and already travel to the watch, and moving them removes a bug class that has actually happened. After that, the settings behind the D1 watch features are declared as "mirrored" from their first day. A settings export/import feature later falls out of the backup flag; whether to build it is open question O5.

**Two risks the pilot must handle:**

1. **Unifying a default can change behaviour.** For each key, list every read site and its default *before* moving it. Where two readers disagree, the maintainer decides which default wins. It is a product decision and is never smoothed over inside a refactor.
2. **An old install may hold a key stored as a different type.** The typed read falls back to the default and logs once. It does not throw.

**Implementation fixes to carry over from the parked code:**

- Check the stored value against the key's declared type instead of `as? T` on an erased type parameter, which lets a mismatch escape as a `ClassCastException` at the call site.
- Read through typed getters, not `SharedPreferences.getAll()`, which copies the whole file on every read. That is harmless on small files and costly on the main prefs file, which is the one that matters.
- One store instance per process, registered at startup like the other bridges (P1).
- Keep the in-memory backend and the contract test; both are good.

**Out of scope:**

- Settings kept in the native store and reached through `Natives` (D3).
- Sensor state and secrets (Q5).
- Runtime state. Pure extractions such as `SnoozeStore` are welcome when the area is touched for another reason, with their tests, directly over `SharedPreferences`. They are not settings work and do not need the registry.

The registry is not a separate track. It belongs to Q2 and Q3 (§6), because mirroring settings is part of the phone↔watch protocol, and the watch features need it. From #387–#397, salvage `SettingKey`, the in-memory backend, the contract test, and the per-area tests. Drop the area migrations.

---

## 3. Architectural principles

These are the ideas behind every recipe below. When a situation does not fit a recipe, go back to these.

**P1. Shared code does not name variant code.** `src/main` must not reach phone or watch classes by name, whether through a same-name class in each source set or through `Class.forName`. Instead, `src/main` declares an interface and each variant registers its implementation in `Application.onCreate`. The working example is `CustomAlertAccess` / `TrendAccess`, registered from `Specific.registerBridges()`. Registration in `onCreate` matters: `Specific.start()` runs too late for boot receivers and restarted services.

**P2. "Not on the watch" is a declared fact, not a silent no-op.** A capability that a variant lacks is absent from the registry. The caller sees that and chooses what to do: hide the button, log once, or return a typed result. A method that returns `null` or does nothing on one variant is the pattern this whole effort exists to remove.

**P3. Share domain, not legacy UI.** Most remaining duplicate classes are upstream-era View screens: `IOB.mkview`, `Nightscout.show`, `Meal.menuview`. When the watch should have a feature (D1), what gets shared is the logic (IOB calculation, the Nightscout uploader, journal writes) behind a contract. The watch gets its own Compose UI. The legacy View screen stays phone-only and is never ported.

**P4. One owner per kind of data.** Every datum (live reading, history, journal, calibration, sensor metadata, setting) has exactly one authoritative writer. Every other component reads or reconciles. New code that writes the same datum from a second place needs the storage-ownership document (§6, Q5) to say so.

**P5. Refactoring and behaviour change never share a PR.** A structural PR says in its body "no behaviour change" and how that was checked. If a refactor uncovers a bug, the fix is a separate PR, before or after.

**P6. Extract from working code.** Introduce an abstraction when two real users of it exist or are about to. Avoid speculative facades, universal stores and frameworks.

**P7. Real-device behaviour outranks elegance.** For BLE, storage and sensor algorithms, a green unit suite is necessary but not sufficient. The PR says what was checked on hardware, or explicitly says that nothing was.

**P8. Test by pinning what exists.** Write characterisation tests before moving code. Prefer fakes over mocks, never use real time, and give tests sentence-style names. A regression test names the commit or issue it guards against.

**No dependency-injection framework.** Manual registration and constructor injection are enough for everything in this plan. Adding Hilt, Koin or Dagger to the `Applic` static world is a project of its own and is out of scope. (This is a proposed default, not one of the §1 decisions. Raise it with the maintainer if you disagree.)

---

## 4. The remaining 25 duplicate-name classes

Measured on #384. "Shared callers" are files in `src/main` that reference the class directly; `Menus.java.bak` and comments are ignored. Line counts are phone / watch.

### Category W — the watch should get the feature (D1)

| Class | Lines | Shared callers | What the phone class is |
|---|---:|---|---|
| `IOB` | 131 / 29 | `Settings.java`, `JugglucoSend.java`, `ForecastIobCoverage.kt` | Legacy View screen for insulin-type settings |
| `Nightscout` | 297 / 28 | `Settings.java` | Legacy View screen for Nightscout and web-server settings |
| `Meal` | 1011 / 27 | `NumberView.java` | Legacy View meal/ingredient picker |

**Recipe (P3).** Do not move these classes into `main`, and do not port them to the watch.

1. Identify the domain logic, which is mostly already in shared code: `JournalIobAccess`, `NightPost`, `WearJournalSync`. The watch already has a Compose `JournalScreen` fed by `WearJournalSync`.
2. Give each feature a contract in `src/main`, such as an IOB/COB snapshot provider or a treatment writer, with phone and watch implementations registered at startup. The watch implementation may rely on data synced from the phone (see Q2 in §6).
3. Build the watch UI in `src/wear` as Compose.
4. Move the legacy View entry point out of shared code. The call in `Settings.java` goes through a phone-only registered navigator, so the watch never compiles a reference to it.

These are features, not refactors, so their PRs follow the behaviour-change rules. **Standalone Nightscout on the watch needs an ownership rule first:** if both phone and watch can upload, exactly one of them does at a time, or Nightscout receives duplicates. Duplicate uploads are an existing regression class in this project. Write the rule before writing the uploader.

### Category P — phone-only by design

| Class | Lines | Shared callers | Why phone-only |
|---|---:|---|---|
| `BluetoothGlucoseMeter` | 380 / 27 | `Applic`, `Backup` | BLE finger-stick meters, phone UI |
| `Dialogs` | 249 / 32 | `GlucoseCurve`, `MainActivity` | legacy View dialogs |
| `HealthConnection` | 322 / 12 | `Applic`, `MainActivity`, `SuperGattCallback`, `Settings` | androidx.health (phone library) |
| `LaunchShit` | 47 / 7 | `MainActivity` | androidx.health |
| `Libreview` | 984 / 36 | `Settings` | legacy View screen (see open question O1) |
| `MeterList` | 215 / 25 | `MainActivity`, `Settings` | legacy View screen |
| `NovoPen.Scan` | 102 / 12 | `MainActivity` | NFC pen import, phone UI |
| `nums.AllData` | 1114 / 62 | `Applic`, `NumberView`, `Notify`, `MainActivity`, `SensorBluetooth`, `SuperGattCallback` | numbers hub + Garmin ConnectIQ, held as the field `Applic.numdata` |
| `settings.LabelsClass` | 369 / 29 | `Settings` | legacy View screen |
| `settings.LibreNumbers` | 163 / 27 | `NightPost` | phone-only layout `R.layout.librenumoptions` |
| `Menus` | 359 / 37 | 8 files | legacy View menus |
| `Stats` | 208 / 28 | none | referenced only from phone `Menus` |
| `Watch` | 189 / 25 | none found | verify at pickup |

**Recipe.**

- **No shared caller** (`Stats`, probably `Watch`): delete the watch stub. That is all.
- **Class needs phone-only libraries or resources** (Health Connect, Garmin, `librenumoptions`): leave the class in `src/mobile` under a phone-specific name. Put the shared call behind a registered interface; the phone registers it, the watch registers nothing, and the caller handles the absence (P2). Do not copy phone resources into `main` just to make a move compile.
- **Otherwise** (plain legacy View screens reached from `Settings.java` / `MainActivity`): use one phone-only screen registry, for example `LegacyScreens` with entries such as `openBatteryScreen` and `openLabels`. The phone registers it once, and shared code calls `LegacyScreens.get()?.openLabels(...)`. This replaces a dozen one-off seams with one, and it is the same shape as the first batch's bridges.
- "Move into `main` with an `isWearable` early return" remains acceptable only for a small class that needs nothing phone-specific. The first batch has already used up most of those.
- **Failure contract** (ruling on #465): keep what the call did before. These were direct calls with a no-op watch stub, so nothing is caught on the phone. Absence on the watch is null (with a caller-side check) or a `src/main` no-op object where the stub was all no-ops and the call sites are many. A catch that did not exist before is a behaviour change in its own PR.
- **Held as a field or shared state** (#465): `AllData` becomes an interface on the field `Applic.numdata`, supplied by the phone and a no-op on the watch. `Dialogs` is really the export screen (`showexport` + `exportlabel`), so it moves into `LegacyScreens`. `Menus.on` is shared state and moves to `src/main`, and `Menus.show()` goes into `LegacyScreens`. No per-flavour subclass of `GlucoseCurve`.

### Category S — two real implementations

| Class | Lines | Shared callers |
|---|---:|---|
| `FloatingConfig` | 298 / 305 | `Settings` |
| `GlucoseAlarms` | 91 / 78 | `SuperGattCallback` |
| `settings.SetColors` | 144 / 96 | `Settings` |
| `ui.AlarmActivity` | 416 / 328 | `AlarmLaunchReceiver`; also a `Class.forName` in `Notify.java` |
| `WearOngoingActivity` | 13 / 61 | `Notify` |
| `glucosecomplication.ColorConfig` | 5 / 288 | `Settings` |
| `glucosecomplication.GlucoseValue` | 2 / 333 | `Applic`, `SuperGattCallback`, `UiRefreshBus` |
| `Specific` | 75 / 122 | 12 files |

**Recipe.** Extract the contract the shared caller actually uses; it is usually one to three methods. Rename both implementations (`MobileX` / `WearX`) and register them. Where the phone side is an empty shell (`ColorConfig`, `GlucoseValue`, `WearOngoingActivity`), the phone simply registers nothing. `GlucoseAlarms` sits on the alert path: characterise it first and give it full review.

**`Specific` is last.** It *is* the per-variant composition root: it registers the bridges and holds variant-only hooks. Once the other pairs are gone, rename it per variant (for example `MobileBootstrap` / `WearBootstrap` behind a `VariantBootstrap` interface), called from `Applic.onCreate`. That removes the final duplicate name and leaves the allow-list empty.

### Category R — reached by reflection

| Class | Lines | Mechanism |
|---|---:|---|
| `ui.ComposeHost` | 38 / 22 | `MainActivity` looks up `tk.glucodata.ui.ComposeHostKt#setComposeContent` by string |

**Recipe.** Belongs to the registration-seams track (§6, Q1). Once the string lookup is replaced by a registered interface, the two files can be renamed freely.

### Order

The first T5.1 batch lands, then the Room harness (§5). After that, category P and R go in with the registration-seams track (Q1), category W with and after the protocol track (Q2–Q3), and category S last (Q4). Within each category, do the pilot as its own PR, then batches of 5–10 pairs per PR.

---

## 5. Next track: Room migration harness

### Why this, and why now

Room migrations are the one place where a bug permanently destroys user history, and nothing currently tests them:

- `HistoryDatabase` is at **version 32** with 21 `Migration` objects and `exportSchema = false`. `CalibrationDatabase` is at **version 5**, also with `exportSchema = false`. No schema JSON is committed anywhere.
- `HistoryDatabaseSafetyTests` checks that a string is absent from a source file. That is source hygiene, not a migration test.
- `CloneRecoveryMigrationTests` reads Room's generated code out of `build/generated/ksp/...`, which is fragile by construction. T0.0 had to repoint it.
- Shipped versions of `HistoryDatabase`: **1.0.3–1.1.2 → v11**, **1.1.3 → v12**, **1.2.0 and 1.2.1 → v32**. The jump from 12 to 32 happened partly on integration branches. The class comment records "main v19, a Clone build at v20–v23, a test build at v24–v31". Version numbers were effectively owned by branches, which is how two builds end up with the same number meaning different schemas.
- Shipped versions of `CalibrationDatabase`: up to 1.1.2 → v3, 1.1.3 and later → v5.

### Steps

**H1. Export schemas.** Set `exportSchema = true` on both databases and pass `room.schemaLocation` to KSP, pointing at `Common/schemas/`. Commit `32.json` and the calibration `5.json`. Add a CI step that fails when the build changes files under `Common/schemas/` without them being committed (`git diff --exit-code Common/schemas`).

**H2. Recover the schemas of shipped versions.** One-off archaeology in a scratch checkout. For each shipped tag (`1.1.2-Alpha` for v11, `1.1.3-Alpha` for v12, and the calibration equivalents), enable schema export locally, run KSP once, and copy out the JSON. Commit it as `11.json`, `12.json`, and so on. None of those tags changes; only the JSON is committed.

**H3. Migration test runner.** The preferred approach is Robolectric plus `androidx.room:room-testing`'s `MigrationTestHelper`, which is the standard tool and validates against the exported JSON. The project uses Room 2.8.4. Robolectric is not a dependency yet; adding it is part of this track. Start with a one-day spike. If Robolectric proves unworkable here, the fallback is a thin `SupportSQLiteDatabase` adapter over the `sqlite-jdbc` driver the tests already use, applying the real `Migration` objects and comparing against the JSON.

**H4. Tests.**

- For every shipped version N (11, 12, 32; calibration 3 and 5): create a database at N with representative rows, including multi-sensor history, journal entries, deleted-reading tombstones and a calibration. Migrate it to the current version. Validate the schema against the JSON, and check that the rows survive with the expected values.
- A test that `fallbackToDestructiveMigration` is not used. This already exists as a source check; keep it.
- Rewrite `CloneRecoveryMigrationTests` onto the new runner, so it no longer reads `build/generated`.

**H5. The rule from now on.** A schema version bump lands on `main` first, in its own PR, with the new JSON and a migration test. Integration branches (`test/*`) never introduce a schema version; they rebase onto `main` to get one.

**Done when:** a PR that bumps a version without a migration fails; a PR whose migration drops a column or loses rows fails; `CloneRecoveryMigrationTests` passes on a clean checkout with no prior build.

**Owner review:** the whole track. It touches storage semantics (§8.3).

---

## 6. After the harness — queue

One at a time, in this order, unless the maintainer reorders it. Each item is a track with a pilot PR.

**Q1. Registration seams instead of reflection.** `src/main` currently has about 20 `Class.forName` lookups across roughly 16 target classes: `HistoryRepository`, `HistorySync`, `CalibrationManager`, `GlucoseUncertaintyStore`, the journal accessors, `OutboundApiJournalSnapshot` (looked up in three places), `JournalTreatmentUploader`, `NightscoutJournalFollowerImporter`, `NotificationPredictionOverlay`, `AlarmActivity`, `ComposeHostKt`, and the two clone-recovery accessors. Each one needs a ProGuard keep rule or it breaks only in minified builds, which has happened before. Replace each with an interface in `src/main` and a registration in `Specific.registerBridges()`, then delete its keep rule and its `ProguardKeepRulesTests` entry. Category P and R duplicates from §4 ride along, since they use the same mechanism. Ratchet: `Class.forName` from `src/main` goes from about 20 to 0. `Log.java`'s dynamic lookup is not a bridge, so exclude it.

**Q2. Typed phone↔watch protocol.** `MessageSender` had 28 string paths; they are now `WearMessagePath`, dispatched in `MessageReceiver` under a `when` the compiler checks for exhaustiveness, and guarded by `WearMessagePathManifestTests` (#490, then #494 for the dropped `/settings`). On the version: **structured payloads carry one, and the handshake owns it** — a `v:` line through `WearProtocol.accepts` for the text payloads, a version byte for `WearSync2`/`WearJournalSync`/`SensorOwnershipRuntime`, `"version"` in the handoff JSON. A field on every message would only repeat the version the handshake already advertised, and it would cost a protocol bump per device. **The two command payloads are the exception that proves it:** `/sync2/calcmd` opens with the command byte and `/displayprefs/mainsensor` with the action token, so a peer that does not know the value already logs and ignores it, and either extends by a new command or a new action word — which is what a version byte would do here, minus the break. A new watch paired with an old phone would otherwise lose its calibration toggles, and the two APKs update separately, so that is a real window. If a payload ever does need a version on top of its tag, the version is `WearProtocol.VERSION` and the sender asks `WearProtocol.peerVersion()` before using the new shape. Everything that carries a shape versions it. What does not is: the two command payloads above, which are discriminated by their own tag; `/start` and `/netinfo`, whose format is a native C struct and which get a version whenever that struct next changes; and the one-byte family -- `/bluetooth`, `/messages`, `/sensorclaimstatus`, `/toggles/req`, `/displayprefs/req`, `/defaults`, `/askforstart`, `/wake`, `/wakestream` -- plus `/calibrate`, which is one mg/dL int. A single byte has nothing to version: a value the receiver does not know is refused as unknown, which is the same outcome a version mismatch would produce. (`/data` is phone-to-phone mirroring and stays on its own envelope, as this section says below.) Old-watch/new-phone and new-watch/old-phone stay a test obligation. Two lessons from recent field work belong in the design:
- The peer must be able to tell that it is talking to a mismatched build. A debug phone package and a release watch package cannot exchange messages at all, and today that failure is silent.
- Per-sensor payloads must carry the sensor identity. The calibration payload currently falls back to "highest revision" when the sensor id is missing, which gives a second sensor the wrong calibration.

`SensorOwnershipRuntime` already is the handoff state machine; type its messages, do not rewrite it. The clone/mirror protocol between phones stays separate for now; do not force a shared envelope.

The settings registry from §2.4 lands in this track. It starts with its pilot, the 10 settings `WearPrefsSync` already mirrors. The typed settings message is then generated from the registry, not from a second hand-kept list.

**Q3. D1 watch features** (IOB/COB, standalone Nightscout, journal/meal entry), built on Q2 and following the category W recipe in §4. These are feature PRs with behaviour-change review, not refactors. Every setting these features need on the watch is declared in the settings registry with "mirrored" scope from its first PR. Secrets such as the Nightscout API secret are marked as secret: they travel only if the maintainer agrees that the watch may hold them.

**Q4. Category S duplicates**, ending with `Specific`. The allow-list **ends at three by decision, not empty**: `Meal` belongs to Q3, and `ui.AlarmActivity` and `tk.glucodata.Specific` are kept deliberately -- the first because its class name is pinned by the manifests, the second because `src/main` cannot name a class in `src/mobile` or `src/wear`, so a same-named per-variant composition root is the mechanism rather than an accident (ruling in #475).

**Q5. Storage-ownership document.** One owner per kind of data. For each, record its identity, authoritative writer, what counts as a duplicate, ordering, clock-rollback handling, reconciliation, and deletion. Write it against today's code: `HistoryRepository` and `CalibrationManager` own Room; the native mmap store owns Libre/Dexcom/native-backed sensors; `VirtualGlucoseSensorBridge` / `VirtualSensorNativeMirror` sit between them; clone recovery is a fourth writer. It also covers the per-sensor driver state and credentials kept in prefs files today (AiDex pair keys, iCan AES keys, MQ calibration parameters), which the settings registry deliberately excludes. This document is the precondition for any storage contract. **First pass written: [`storage-ownership.md`](storage-ownership.md).** The ownership and credentials tables in it are verified against the code; ordering, clock rollback, reconciliation and deletion are recorded as open questions, because an invented answer would be worse than an open one. Q3 should start by closing them.

**Not scheduled** (the direction from earlier proposals still holds, but no work starts without a maintainer decision):
- a manifest of the JNI surface covering both directions, before deleting any "dead" native declaration (C++ calls back into Java, so a Java-side grep cannot prove anything is dead)
- a shared BLE transport
- ViewModels in place of Composables calling `Natives` directly
- one concurrency model

---

## 7. Merging the first T5.1 batch — device checklist

Unit tests and CI are green. The batch moves runtime code between source sets, so before merging, check each of these on a phone and a watch, both on **release** builds:

- [ ] Phone: snooze, dismiss and ignore from a glucose-alert notification. Repeat for a custom alert, including after force-stopping the app so the action cold-starts the process.
- [ ] Watch: the same notification actions.
- [ ] Phone: the home-screen widget updates, and shows the stale-value state when readings stop.
- [ ] Phone: the LibreLink-compatible broadcast (xDrip/xInfuus receivers) still arrives; the sensor-activation broadcast fires on a new sensor.
- [ ] Phone: Gadgetbridge / weather broadcast still arrives, if enabled.
- [ ] Phone: the battery-settings screen opens from settings.
- [ ] Watch: the Wi-Fi binding behaviour (`UseWifi`, now in `main`) still works on the watch that used it.
- [ ] Both: a full-screen alarm opens when the notification cannot carry a full-screen intent (`AlarmLaunchReceiver`). On the watch, a second alarm replaces the one already on screen instead of landing behind it.

Record the result in the PR thread. For anything not checked, write "not checked"; do not leave it out.

---

## 8. Working rules

The project's agent instruction file is not in the repository, so the rules that matter for this work are stated here.

### 8.1 Tracks, stacks and PR size

- **One structural track open at a time** (D4). A track starts from `main`, never from another open track. Stacks at most three deep.
- **Pilot, then batches.** The first PR of a recipe is small and is read in full. Later PRs apply the same recipe to 5–10 items each, link the pilot, and are titled with the recipe name.
- **Each PR's description says:** which recipe it applies, "no behaviour change" (or the behaviour change it makes), what was run (phone and watch unit tests, which builds), and what was checked on a device, or "not checked on a device".
- **Do not open a PR for a numbered task the plan has parked.** Ask first.

### 8.2 Build and test before pushing

- Always run `./gradlew :Common:testMobileDebugUnitTest :Common:testWearDebugUnitTest`.
- After any change to `src/main` or native code, also run `./gradlew :Common:assembleWearDebug`. The watch has its own CMake configuration, so a phone build proves nothing about it.
- For anything that moves code between source sets or touches reflection or keep rules, also build release for both flavours.
- Run `git diff --check` before finishing.
- Logic changes in drivers, alerts or native policy get focused unit tests in `Common/src/test/` (or `testMobile/`, `testWear/` when they are flavour-specific).

### 8.3 Always full maintainer review, regardless of recipe

- driver signal processing, timestamps, sensor grids, calibration math
- alert thresholds, alert delivery, snooze and quiet-window behaviour
- Room schemas and migrations; native storage semantics; anything that could drop or withhold a reading
- phone↔watch sensor ownership and handoff

### 8.4 Gates

- An allow-list or ratchet can only shrink. Lowering a ceiling is part of the PR that earns it.
- Adding a new *hard* gate to CI needs the maintainer's agreement. A gate that turns ordinary bug fixes red costs more than the debt it tracks (§2.3).

### 8.5 Other

- New user-facing strings go into `values/strings.xml` **and every `values-*` locale**, translated, not left in English.
- Re-verify the numbers in this document when you pick up a task. They move as work lands. If a number here is wrong, fix it in the same PR.
- No `Co-Authored-By` trailers for AI tools in commits.

---

## 9. Answers to the T5.1 handoff

The handoff for #373–#384 asked four questions.

1. **Which option (A, B or C)?** None of them as written. Pause the twins now, as in C, because the Room harness comes first. But do not stop at 11/36 for good, and do not go back to a pair-by-pair sweep (A) or build a general mechanism up front (B). The remaining pairs are finished by category, each inside the track where its recipe belongs: P and R with the registration seams (Q1), W as features on top of the typed protocol (Q2–Q3), and S last (Q4). The phone-only screen registry in §4 is a narrow, earned version of B. §4 has the per-class recipes.
2. **May resources move from the phone into `main` for T5.1?** Only case by case, when the resource is small and the move removes a real duplicate. Copying phone resources into the watch app just to make a class compile is the wrong fix; keep the class phone-only behind a registered interface instead.
3. **May a shared interface change inside a pair PR?** Yes, when behaviour does not change and the PR body says so. The `CustomAlertController.snoozeAlert/ignoreAlert` change was a good example. An interface change that changes behaviour goes in its own PR.
4. **Many small PRs or one large one?** Neither: a pilot PR per recipe, then batches of 5–10 items (§8.1).

---

## 10. Open questions

Not decided yet. Do not build anything that depends on them.

- **O1. LibreView from the watch?** `Libreview` is classified as phone-only above. If the watch should upload to LibreView on its own, it moves to category W.
- **O2. Health data on the watch.** Wear OS exposes Health Services, not Health Connect. Whether the watch writes glucose anywhere health-related is a product question.
- **O3. The native-plugin horizon (D3).** Revisit after Q5 and after the harness has covered a release.
- **O4. `test/*` integration branches.** Keep them as soak branches that are rebuilt from `main` plus open PRs, or retire them. Either way, they never own a schema version (H5) and are never cherry-picked back into `main`.
- **O5. Settings export/import.** Android backup is disabled (`allowBackup="false"`), and scheduled backups hold history, journal, foods, insulins and calibrations, but no settings. No export path was found for the settings in prefs files. Nobody checked whether the settings kept in the native store travel with upstream's own sync. With the registry's backup flag, export/import becomes cheap to build. Whether to build it is a product decision.

---

## Appendix — reproducing the numbers

```bash
# duplicate-name classes still allowed
grep -v '^#' Common/src/test/resources/arch/duplicate-fqns-allowlist.txt | grep -c .

# Room versions and schema export
grep -n -A1 'version =' Common/src/mobile/java/tk/glucodata/data/HistoryDatabase.kt | head
grep -n '@Database' Common/src/mobile/java/tk/glucodata/data/calibration/CalibrationDatabase.kt
for t in 1.1.2-Alpha 1.1.3-Alpha 1.2.0-Alpha 1.2.1-Alpha; do
  echo "$t $(git show $t:Common/src/mobile/java/tk/glucodata/data/HistoryDatabase.kt | grep -oE 'version *= *[0-9]+' | head -1)"
done

# reflective lookups from shared code
grep -rn 'Class.forName' Common/src/main/java

# phone<->watch message paths
grep -hoE '"/[a-zA-Z0-9_/-]+"' Common/src/main/java/tk/glucodata/MessageSender.kt | sort -u | wc -l

# Composables coupled to JNI / Applic / prefs
U=Common/src/mobile/java/tk/glucodata/ui
for p in 'Natives\.' 'Applic\.' 'SharedPreferences'; do echo "$p $(grep -rl "$p" $U | wc -l)"; done

# a setting's default declared in several places (example: prediction horizon, carb absorption)
grep -rnE 'HORIZON[A-Z_]*DEFAULT\s*=|HORIZON_DEFAULT\s*=|dashboard_prediction_horizon_minutes' Common/src/main/java Common/src/mobile/java Common/src/wear/java
grep -rnE 'ABSORPTION[A-Z_]*DEFAULT[A-Z_]*\s*=|dashboard_prediction_carb_absorption' Common/src/main/java Common/src/mobile/java Common/src/wear/java

# prefs file names in use
grep -rhoE '"(tk\.glucodata[a-z_.]*|[a-z_]+_prefs?|[A-Za-z]+Prefs[A-Za-z]*)"' Common/src/main/java Common/src/mobile/java Common/src/wear/java | sort | uniq -c | sort -rn

# shared callers of a duplicate-name class (example: IOB)
grep -rlE '(^|[^A-Za-z0-9_])IOB(\.[a-zA-Z_]|::| *\(|\.class)' Common/src/main/java
```
