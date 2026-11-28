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
  each reading lives; a reading is resolved only once it would otherwise be
  selected for a batch (at most 256), so an up-to-date scan makes no Java calls.
  A calibrated value outside 20–600 is not sent at all (never the stored value
  in its place), and the next reading takes its slot.
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

## Crypto provider and key material

The request protocol uses 31 application keys (`QEK_0..9`, `REK_0..9`, `HSK_0..9`,
`WBCloakedSigKey`) and a 32-byte AES-256 session-wrapping key (`SessionWrapKey`):

- **Binary-free by default**: JugglucoNG implements the entire crypto provider in
  portable C++20 using standard `AES-256-CBC` and `HMAC-SHA256` (`engine.cpp`,
  `cipher.cpp`, `material.cpp`, `block_decoder.cpp`). It does not package or `dlopen`
  upstream's `liblibre_receiver_skb.so`, and `scripts/dist/build-inputs.json` requires
  no Clarity binary input.
- **Out-of-the-box built-in material**: `defaultKeyMaterial()` in `material.cpp`
  embeds the compact 32-key table (`keyOnly = true`), so users can sign in and upload
  to Dexcom Clarity immediately without extracting or importing an external material
  file.
- **Optional field override (`clarity.mat`)**: If `<app data>/clarity-indexed/clarity.mat`
  exists (`O_NOFOLLOW`, verified regular file owned by the app UID with mode `0600`),
  `Engine` loads and validates that file instead of the built-in table. Passing a
  `JGCLMAT` v1 file (either the 3,368-byte key-only format or the 197,552-byte full
  white-box format) to `Natives.clarityImport` validates the keys, recovers
  `SessionWrapKey` via BGE if needed, and atomically writes the 3,368-byte key-only
  file to `<app data>/clarity-indexed/clarity.mat` with mode `0600`. In application
  builds (`CLARITY_JUGGLUCO_LOG`), environment variable and system property overrides
  and `dlopen` are compiled out.

For the white-box AES-256 reduction, BGE algebraic key recovery, binary hashes, RVAs,
and `.mat` extraction tools, see [`docs/reverse-engineering/clarity.md`](reverse-engineering/clarity.md).

## Build and tests

The phone flavour passes `-DCLARITY=ON`; the uploader is built for `arm64-v8a` and
`armeabi-v7a` only. Every flavour has the JNI entry points; without the uploader they
report "not built" rather than failing to link.

`ContextHTTPS` gained two per-request options for Clarity: `client` (a TLS client
certificate for that one connection) and `location` (the `Location` header of the
final response; redirects remain the caller's decision).

Host test suites (require a C++20 compiler, zlib headers and OpenSSL via `pkg-config`):

- `python3 tools/test-clarity-native.py` — Runs the outbox and memory-mapped storage
  on the host: sensor replacement, late backfill, which readings are resolved,
  interrupted checkpoint creation and truncated saved checkpoints.
- `python3 tools/test-clarity-material.py` — Verifies the built-in 32-key table,
  `JGCLMAT` v1 serialization, key-only conversion (3,368 bytes), `0600` file
  permission enforcement, rejection cases and BGE white-box AES-256 key recovery
  against NIST SP 800-38A F.1.5.
- `python3 tools/test-clarity-provider.py` — Builds `Engine` and `Provider` against
  synthetic fixtures and checks standard AES and HMAC vectors, application key
  selection, session-key import, preservation of the previous session when rekeying
  fails, and detection of a deliberately altered decoder.
- `python3 tools/test-context-https.py` — Verifies `ContextHTTPS` mutual TLS client
  certificate and `Location` header handling.

## Known limitations and troubleshooting

The uploader identifies itself as Dexcom's G7 app (`SoftwareId = "0300wAECCzACDwAA0000"`).
It is unofficial and Dexcom can change its wire contract or rotate keys at any time.

If Dexcom breaks compatibility, check these layers in order:

1. **Application or session-wrap key rotation (`QEK_`, `REK_`, `HSK_`, `SessionWrapKey`)**
   - *Symptoms*: `Unknown Clarity application key ID` or `Clarity signature mismatch`
     when opening a `SignedMessage` response (`listOfUrls`, `Certificate/UserCert`,
     `updateSessionKeys`), or `postBulkData` failing immediately after `updateSessionKeys`
     succeeds (indicating `SessionWrapKey` changed).
   - *What to check*: Extract updated key material from a newer provider using
     `tools/clarity-material.cpp` (see [`docs/reverse-engineering/clarity.md`](reverse-engineering/clarity.md)),
     verify it with `Natives.clarityImport` (`<app data>/clarity-indexed/clarity.mat`),
     and update `defaultKeyMaterial()` in `Common/src/main/cpp/net/clarity/material.cpp`
     (plus `SoftwareId` in `engine.cpp` if the software identifier changed).
2. **OAuth client registration or redirect URI**
   - *Symptoms*: The sign-in WebView rejects `client_id` / `redirect_uri`, or code
     exchange / token refresh fails with `invalid_client` or `unauthorized_client`.
   - *What to check*: `Common/src/main/cpp/net/clarity/auth.cpp` and
     `Common/src/main/cpp/net/clarity/oauth_registration.hpp`.
3. **Regional endpoint discovery or service paths**
   - *Symptoms*: `Expected a Dexcom endpoint`, `Wrong Clarity service paths`, or
     `Wrong Dexcom certificate service path` during sign-in discovery or upload.
   - *What to check*: URL validation and path suffixes in
     `Common/src/main/cpp/net/clarity/client.cpp` (`/Certificate/UserCert`,
     `/security/v1/patient/updateSessionKeys`, `/bulkData/v1/patient/postBulkData`,
     `/connect/token`).
4. **Mutual TLS client certificate issuance**
   - *Symptoms*: `ensureUserCertificate` fails during `Certificate/UserCert`, or TLS
     handshakes to `sessionUrl` / `bulkUrl` fail after certificate renewal.
   - *What to check*: CSR generation and P12/PEM assembly in
     `Common/src/main/cpp/net/clarity/certificate.cpp` and `certificate_crypto.cpp`.

### Authorized Clarity upload registration

Dexcom's developer portal offers sandbox credentials and upgrades for access
to Dexcom-generated data. Its public endpoints and scopes include no Clarity
write grant. A separate inquiry can ask whether Dexcom offers an inbound
Clarity integration for patient-authorized uploads and, if so, request its
supported client registration, sandbox, and upload specification. Dexcom
lists Developer Support Requests and Strategic Partnerships as intake routes.
[Scopes and Access](https://developer.dexcom.com/docs/dexcom/scopes-access) ·
[Endpoint Overview](https://developer.dexcom.com/docs/dexcomv3/endpoint-overview) ·
[Partner with Dexcom](https://www.dexcom.com/en-us/global-partner-intake-requests)
