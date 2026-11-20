# ICAN Cloud — Stop Point (2026-11-10)

## Branch / commits
- Branch: `fork/ican-cloud-final` (pushed to https://github.com/JetFoxy/JugglucoNG)
- Head: `ae44086fe` test(ican): add uploader regression tests (4xx not retried, partial progress)
- PR: #810 (OPEN) — https://github.com/ctqvva/JugglucoNG/pull/810

## What is done
- Login contract (phone_cgm / account / 000000, Argon2id password, flat LoginModel).
- Password persistence (AndroidKeystore AES-GCM), silent re-login on 401 (uploader + follower manager).
- Follower endpoints: encrypted GET (key/data) + response decryption, SessionExpired on 401.
- Live readings reach uploader (SuperGattCallback payload resolution includes iCan).
- Shared queue instance (account isolation).
- Relogin cap (2 cycles), partial lastSyncMs, enabled recheck, transient-only retry.
- refresh() method + refresh-token preference in relogin.
- UI: password field unlock, followers IO dispatch, generation guard, invite-failure status.
- P0 redactions: docs userId, lsp_decrypt keys, b64url padding, HOST_TO_CTRL 0x1E.
- Device snapshot helper + auto-deploy.
- Locales: ican_health_secure_storage_failed added to all 9 locales.
- Regression tests: 4xx not retried, partial progress preserved.

## What remains (no protocol reverse engineering)
1. P2 translations for remaining locales (already done for secure_storage_failed; full audit).
2. Backfill: filter imported rows by sensor source (architecturally already scoped by serial).
3. Regression tests: iCan-only upload path, account switching mid-flush (partially covered).
4. Protocol open items: AES IV, upload schema (MAC), heartbeat — require real server.

## APK
- Installed on Redmi (4HU4QKA6FMFIUC89): JugglucoNG-1.2.8-Alpha-debug.

## Key files
- Common/src/main/java/tk/glucodata/share/ican/ICanHealthCloud*.kt
- Common/src/main/java/tk/glucodata/SuperGattCallback.java (iCan payload resolution)
- docs/ican/ICAN-RU-*.md
- tools/ican-capture/*
