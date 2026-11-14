# iCan Health cloud integration — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Wire the iCan Health cloud module (completed, tested) into JNG: glucose stream → uploader, settings screen with login/logout, follower management UI, backfill on first login.

**Architecture:** Mirror the DexcomShare integration: `HistoryRepository.forwardBackfillToExchanges` calls `ICanHealthCloudUploader.onHistoryStored` when `ICanHealthCloudSettings.isEnabled`. Compose settings screen mirrors `DexcomShareSettingsScreen`. Navigation entry in `MainNavigation` + `ExpressiveSettingsScreen`.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt-free manual DI via `Applic.app`, SharedPreferences.

**Spec:** `docs/superpowers/specs/2026-10-31-ican-health-cloud-design.md` (§9 data flow)

## Global Constraints

- Module is in `tk.glucodata.share.ican` (already complete, 10 tasks, tested).
- Follow DexcomShare wiring pattern exactly (HistoryRepository:732-740).
- NO comments unless explicitly asked (AGENTS.md).
- Compose Material3, matching existing screens.
- Tests: run `:Common:testMobileDebugUnitTest`.

## Tasks

### Task 1: Wire uploader to glucose stream
- Modify `HistoryRepository.kt` (mobile): add `ICanHealthCloudSettings.isEnabled` check + `ICanHealthCloudUploader.onHistoryStored` call in `forwardBackfillToExchanges`, mirroring DexcomShare (lines 732-740).
- Add `icanRows()` helper analogous to `dexcomRows()`.
- Test: update `ExchangeCollapseLatencyTests` or add `ICanHealthCloudWiringTests`.

### Task 2: Settings persistence wiring
- `ICanHealthCloudSettings` already exists. Add `isEnabled(app)` companion function (mirror `DexcomShareSettings.isEnabled`).
- Test: settings round-trip already covered.

### Task 3: Settings screen (Compose)
- Create `ICanHealthCloudSettingsScreen.kt` mirroring `DexcomShareSettingsScreen.kt`: account/password fields, login/logout button, status, last sync time, enable toggle.
- Wire into `MainNavigation.kt` and `ExpressiveSettingsScreen.kt`.

### Task 4: Follower management screen
- Create `ICanHealthCloudFollowersScreen.kt`: list followers, invite (email/phone/name), remove, rename, shareFollower toggle.
- Wire into navigation.

### Task 5: Backfill on first login
- On successful login in settings screen, call `ICanHealthCloudBackfill.backfill(...)` with last 7 days of glucose.

### Task 6: Integration smoke test
- Test that a glucose store triggers `onHistoryStored` when settings enabled.

---
EOF
echo "plan created"