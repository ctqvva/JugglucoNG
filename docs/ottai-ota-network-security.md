# OTA cloud transport

The supplied `juggluco-trace-20261027-023359.log` reaches authenticated command state 3,
records the baseline, and receives HTTP 403 from `/cgm/app/server/ota/upgradeVersion/judge`:
`NET_SECURITY_PLAINTEXT_ROUTE_FORBIDDEN`. The old API route is forbidden even over HTTPS.
This is distinct from the earlier BLE discovery and software-revision parser failures.

The subsequent `juggluco-trace-20261027-031949.log` reaches the secure key exchange, but
receives HTTP 200 with business code `Error` and `签名校验失败` (signature verification
failed). The first integration signed the registry's `n:…`/`g:…` suffix while the common
HTTP header sent `ottai:a:n:…`/`ottai:a:g:…`. The transport now takes its complete crypto
identity directly from the same header snapshot used by every outgoing request. This applies
to exchange signatures, server verification, HKDF, AAD and `cipherUserId` together.

## Source and scope

The current CN client was downloaded from the [Tencent distribution listing](https://sj.qq.com/appdetail/com.ottai.tag):
`com.ottai.tag` 1.63.0 / 264122, 222,946,835 bytes, listing MD5
`14011893CC93D43D7B0B6A179CD984A7` (verified). Its ARM64 Dart snapshot is
`1ce86630892e2dca9a8543fdb8ed8e22`, compatible with the existing Blutter Dart 3.10.9 tool.
APK, disassembly and raw probes remain outside Git. No app installation or sensor activation
was needed. Obfuscated library URLs differ only by case; use a case-sensitive output directory.

`OttaiNetSecurity` implements the recovered codec. The explicit OTA client uses main's
`OttaiRegionalTransport` with the experiment's captured backend/account headers. Its CN calls
use the 1.63 identity/version and `cipherUserId`; global/Syai use main's regional compatibility
version and `webDeviceId`. CN forwarded-IP headers are never added to global/Syai requests.
Each operation clears its ephemeral keys. OTA check, the explicit V3-authenticated recheck,
report and user-confirmed preparation
bind are scoped to this client.

Ordinary CN login, device lists, binding and activation use the secure transport dependency
in [draft PR #755](https://github.com/ctqvva/JugglucoNG/pull/755). PR #739 is stacked on it,
pending signed-in CN validation. The supplied plaintext-route rejection is evidence for that
fix, but anonymous probes do not prove real account compatibility. No general CN dispatcher
change needs review in the OTA diff; the combined test build includes it from the base PR.
Main's global/Syai login and serial lookup remain intact.

Successful worker shutdown drains advisory reporting without retaining BLE. Reopening a
saved target only verifies it; it does not re-send reporting. Fresh factory preparation waits
for a separate confirmation after both an original flash and a reopen. Failures can be
explicitly discarded after the owner stops, without changing sensor firmware or credentials.

## Recovered protocol

All routes below are relative to `https://api.ottai.com/cgm/security/app/server`.

1. Generate a fresh P-256 key pair. `POST /security/exchangeKey` sends Base64-encoded,
   32-byte big-endian `clientPubKeyX`/`clientPubKeyY`, epoch-millisecond `clientTimestamp`,
   and lowercase hex `clientSignature = SHA256(deviceId + publicX + publicY + timestamp)`.
2. The response supplies `serverPubKeyX`, `serverPubKeyY`, `serverTimestamp`,
   `serverSignature`, `expireTime`, `secretId`, `ciphertext`, `iv`, and `authTag`.
   Verify the server signature using the same concatenation with the server fields.
3. Derive the 32-byte P-256 ECDH X coordinate. HKDF-SHA256 emits 64 bytes, with
   salt `UTF8(secretId)` and info `UTF8(deviceId + "#" + expireTime + "#" + secretId)`.
   The first 32 bytes are the AES key; the last 32 are the HMAC key. `expireTime`, not
   `serverTimestamp`, belongs in the HKDF context.
4. Authenticate the exchange ciphertext using AES-256-GCM, decoded Base64 IV/tag,
   and AAD `UTF8(deviceId + "|" + secretId)`. The resulting plaintext must equal the
   concatenated Base64 server coordinates. No application request is sent before this succeeds.
5. Every request carries headers `cipherNonceId` (positive decimal Snowflake ID),
   `cipherTimeNow` (epoch ms), `cipherSecretId`, `cipherUserId` (the common **device ID**),
   and `cipherSeq` (incrementing per secret). The account user ID is not substituted here.
6. Serialize original query/body data as UTF-8 JSON, and encrypt each independently with
   AES-256-GCM, a fresh 12-byte random nonce, the same AAD, and a 16-byte tag. The query
   becomes `cipherParamText`, `cipherParamMac`, `cipherParamNonce`, `cipherParamSignature`;
   POST JSON uses the corresponding `cipherBody…` fields. Ciphertext, tag and nonce are Base64.
7. Each signature is **Base64** HMAC-SHA256 over UTF-8 concatenation
   `nonceId + sequence + timeNow + ciphertextBase64`. It is not the hex encoding used
   for the exchange signature. Common cipher metadata is in headers, not query/body JSON.
8. A successful response contains `cipherSecretId`, `cipherText`, `cipherMac`, and
   `cipherNonce`. Require the matching secret and authenticate/decrypt before parsing the
   original business response, including the firmware offer. A plaintext success or changed
   authentication tag cannot authorize installation. Bounded known business codes survive
   diagnostics; raw messages, tokens, encrypted payloads and signed download URLs do not.

The client corrects an explicit `CLIENT_CLOCK_OFFSET` response once during exchange.
Expired secrets require a fresh exchange. Application requests are not silently retried on
network/security failure; reopen the check to retry. BLE transfer retry rules remain unchanged.

Static anchors in this APK: network prefix at pool offset `0xa7a0`; key exchange at
`0x121ba7c`; exchange request/signature builder at `0x121d8a4`; server verification and
key derivation at `0x121bc98`; HKDF at `0x121ca40`; AAD builder at `0x68c10c`; request
envelope at `0x121eb80`; encryption at `0x121f488`; response decryption at `0x69944c`.

## Verification and limits

The reconstructed exchange and encrypted GET were verified against the live service using
a fresh synthetic identity and the anonymous `/user/apiToken` endpoint. Server signature,
authenticated exchange plaintext, encrypted query signature and authenticated response all
passed. The same round trip also passed using the **production Kotlin codec**, compiled with
`SecureCloudProbe.kt`. Returned tokens and ephemeral keys were neither printed nor retained.
No account token, sensor MAC, account mutation or OTA report was used in those probes.

An anonymous negative probe signing the stored suffix while sending the complete header ID
reproduced `ota_exchange_http=200`; the matching full identity passed the secure round trip.
The earlier standalone probe supplied the full ID directly and therefore missed the app's
header-builder integration defect. A regression now captures the actual `OtaClient` HTTP
exchange and independently verifies its signature against its outgoing header, for both native
and generated ID suffixes. It fails with the old integration and passes with the shared identity.

The offline fixture `ottai-net-security.json` is independently generated with PyCryptodome
by `scripts/ottai/net_security_vectors.py`. Its fixed scalars are public synthetic test inputs.
Tests cover exact exchange/GET/POST vectors, sequence and nonce changes, response authentication,
server signature, plaintext rejection, safe business diagnostics, expiry, close and clock correction.
Synthetic HTTP tests exercise the OTA client on all three account backends, including the
preparation bind, authentication and failure handling. The app-wide CN dispatch tests come
from the base PR, and the combined branch verifies ordinary CN phone account calls use that
secure dependency before OTA. They cover the device-list and validation routes rejected in
the fresh-CN field trace, as well as login and credential-bootstrap calls.

To reproduce the optional anonymous production-code probe, compile these files together with
`kotlinc -cp <org.json.jar> -include-runtime -d <probe.jar>` from the repository root:

- `OttaiNetSecurity.kt`, `OttaiRegionalTransport.kt`, `OttaiConstants.kt`, `OttaiBleAuth.kt`, and
  `OttaiCrypto.kt` from `drivers/ottai`;
- `scripts/ottai/RegionalLoginProbe.kt`.

Run `java -cp <probe.jar>:<org.json.jar> tk.glucodata.drivers.ottai.RegionalLoginProbeKt`.
It performs anonymous requests to the regional API-token endpoints and prints only pass messages.
It is not part of CI. The CN production probe is provided by the base PR #755.

The authenticated OTA offer for the user's running sensor, firmware-host download, and physical
transfer still require the phone test. Anonymous transport success does not establish that the
cloud offers an image for that sensor or accepts an already-started sensor update.
The supplied latest OTA screenshot displays **No firmware update offered** in Started sensor
mode. This state means the authenticated offer returned both `requiredUpgrade=false` and
`allowUpgrade=false`; the next trace also logs both flags and whether a download URL exists,
without recording that URL. This does not test installation. Normal activation after the
complete CN migration still needs the phone test.

## Global and Syai field-test extension

The OTA entry point now accepts all three supported account backends and is integrated into
main's refactored sensor card. CN-only UI restrictions and warning text were removed in all
16 locales. The selected BLE sensor must still authenticate with its saved keys. Fresh and
Started modes, journaling, target verification and explicit installation are shared across
regions; BLE packet/finish behavior for global and Syai remains physically untested.

Synthetic server tests invoke the actual OTA client on every backend, decrypt its judge
parameters and verified-success report, and check the destination, authorization and issuing
identity. They cover required offers with `allowUpgrade=false`, app-version business rejection,
closed clients, plaintext success and changed authentication tags. Regional API transport success
alone is not evidence that a particular sensor has an available or required firmware image.
