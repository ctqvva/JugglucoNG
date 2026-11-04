# Dexcom Clarity upload

Settings → Sharing → **Dexcom Clarity** uploads sensor glucose to the user's own Clarity
account, so a clinic that works from Clarity sees the data. It is a port of upstream
Juggluco 11.5.6's native uploader (`Common/src/main/cpp/net/clarity`, GPL-3.0-or-later),
requested in [issue 790](https://github.com/ctqvva/JugglucoNG/issues/790).

## How it works

- **Sign-in** is Dexcom's own OAuth page in a WebView (`ClarityLoginScreen`, window
  `FLAG_SECURE`, only `https://*.dexcom.com`/`*.dexcom.eu` may load). The `dexcomg7:`
  callback goes straight to native code, which exchanges the code and discovers the
  account's regional endpoints. Tokens, the account's TLS client certificate and the
  outbox stay in native code and never cross JNI. If discovery fails after Dexcom
  accepted the sign-in, its tokens are kept (also across restarts) and the settings
  screen offers **Finish pending sign-in**; starting a new sign-in discards them.
- **Upload** runs on a native worker thread. It snapshots the native sensor streams
  (Dexcom, Libre 3 and the other natively stored sensors), keeps a memory-mapped
  outbox under `<app data>/clarity-indexed/<account>/`, and posts batches over mutual TLS.
  It wakes on every new reading (`wakewithcurrent`), on network changes and every
  five minutes, and backs off exponentially on errors with the batch kept.
- **Values** are the ones the app shows: each uploaded reading goes through
  `resolveExportedMgdl()` (calibration and chosen lane), like Nightscout,
  the webserver and LibreView. Libre 3 history records have no raw value, so
  they use the auto lane there too. The snapshot keeps stored values and where
  each reading lives; only the readings `Outbox::prepare` puts in a batch (at
  most 256) are resolved, so an up-to-date scan makes no Java calls.
- **Which sensor** a reading may come from: the one activated last, from its
  first usable reading on. Upstream ordered sensors by planned wear end, which
  blocked a 10-day G7 that replaced a 15-day Libre early. A sensor's first
  reading moves earlier when backfill arrives after it was first seen.
- **Storage** maps through its own file descriptor and logs only the operation
  and errno, never a path: outbox paths contain the Dexcom account ID. A
  checkpoint restarts only when it provably holds an unfinished first creation
  (4096 zero bytes, or two slots with slot 1 untouched and slot 0 empty or at
  generation 1). Any other damaged or truncated checkpoint stops uploads rather
  than forget acknowledgments.
- **Options** live at the end of `Tings` (`settings->data()->clarity`): enabled,
  start date (default: 14 days before first start), Libre 3 history instead of
  stream, and amounts.
- **Amounts** (insulin/carbs/finger sticks) exist natively but need label
  categories, which NG has no screen for yet. The NG screen never turns them on.

The uploader identifies itself as Dexcom's G7 app. It is unofficial and Dexcom can
break it at any time.

## The provider library

The request crypto needs Dexcom's application keys and session-key decoder.
Upstream packages them as `liblibre_receiver_skb.so` (it also serves upstream's
Libre receiver; NG only uses the exported `jg_clarity_abi` table) and the native code
`dlopen`s it by name. Without it the screen opens but every upload and sign-in fails
with "Cannot load Clarity provider".

| ABI | size | sha256 |
|---|---:|---|
| arm64-v8a | 4468408 | `7b946a3012ad278a74c9cccfc7da615582a494ae963439e8c9f0b6931b7fa038` |
| armeabi-v7a | 4819728 | `b8b2fad33c68e46762a11b6e08fa0e2e38a3f60d5acdae165af0097109877492` |

Source: `lib/<abi>/liblibre_receiver_skb.so` inside upstream's
`Juggluco-11.5.6.apk` (SourceForge, linked from juggluco.nl/Juggluco/download.html;
the copy used had sha256 `454bf168e74514f388d83234c3b65f9e3d0baf08924fe698d9c629c7489af4a0`).
Both builds depend only on `libc.so`.

To use it locally:

```sh
unzip -j Juggluco-11.5.6.apk 'lib/arm64-v8a/liblibre_receiver_skb.so' -d Common/src/main/jniLibs/arm64-v8a/
unzip -j Juggluco-11.5.6.apk 'lib/armeabi-v7a/liblibre_receiver_skb.so' -d Common/src/main/jniLibs/armeabi-v7a/
```

The inventory in `scripts/dist/build-inputs.json` lists both files, so the pinned
1.2.3-Alpha APK cannot supply them yet and the inventory is in bootstrap state. Ship
it with the documented procedure in [distribution.md](distribution.md#update-vendor-library--test--release--new-baseline):
a local release with the files present, then `scripts/update-build-inputs.sh --baseline <version>`.
Review redistribution of the library first.

## Build

The phone flavour passes `-DCLARITY=ON`; the uploader is built for `arm64-v8a` and
`armeabi-v7a` only. Every flavour has the JNI entry points; without the uploader they
report "not built" rather than failing to link.

`ContextHTTPS` gained two per-request options for it: `client` (a TLS client
certificate for that one connection) and `location` (the `Location` header of the
final response; redirects are still the caller's decision).
`tools/test-context-https.py` covers both. `tools/test-clarity-native.py` runs the
outbox and storage on the host: sensor replacement, late backfill, which readings
are resolved, interrupted checkpoint creation and truncated saved checkpoints.
