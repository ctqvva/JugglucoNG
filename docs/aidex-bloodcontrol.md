# AiDEX BloodControl keys

Russian app language exposes **AideX CN | BloodControl API** in AiDEX key management.
BloodControl uses the existing encrypted pairing-material store, so downloaded keys also
work with the existing import/export and reconnect paths.

BloodControl requests are manual-only. The form retains nearby sensor identities from the
scan screen and lists known AiDEX sensors, with connected sensors first. It also scans for
new nearby AiDEX serials while open. A connected sensor (or a sole available sensor) fills
an empty, untouched serial field; tapping another row changes that field. Neither entering
the screen, choosing a sensor, nor BLE pairing/reconnecting contacts BloodControl.
The user must select BloodControl and tap **Request key** to contact the service. That
request sends the selected serial and a persistent random device UUID to the configured
BloodControl endpoints. Saved/imported keys are reused before any network request.

The BLE driver retains its original local/saved pairing flow and missing-key reporting.
There is no F-series prefix classifier, automatic cloud recovery, or network-driven second
F001 challenge on an existing link. Explicitly downloaded keys use the same encrypted store
as imports and CN account downloads, and are available on the next connection.

## Build configuration

Supply all four values through environment variables or private Gradle properties:

```
BC_AIDEX_BEARER_TOKEN
BC_AIDEX_ENVELOPE_AES_BASE64
BC_AIDEX_SIGNING_KEY_PKCS8_BASE64
BC_AIDEX_SERVER_PUBLIC_DER_BASE64
```

No values are committed. Builds without the inputs omit the BloodControl selector and
retain the CN form. Partially configured or malformed inputs fail configuration without
printing values. This repository has mobile/wear flavors; it has no full/foss dimension.
No new distribution flavor is introduced. Protect these inputs in release CI and do not
provide them to untrusted pull requests. APK credentials are recoverable from the APK.
Trusted distribution and owner-approved development builds read the four secrets from the
existing `production-signing` environment, only in their build step. Ordinary PR CI does
not receive them.

PR test builds execute their controller workflow from trusted `main`, while checking out
the approved PR's application source separately. Adding new secret references to a PR's
workflow does not change that controller until its workflow update reaches `main`. Run
2026-11-07's `42386954279` therefore produced an unconfigured APK from source `dd77005dd`.
Rerunning that controller cannot inject the new inputs. A local test APK can be built with
the same production signing and relay inputs through `scripts/build-dist.sh phone` and the
four BloodControl variables, without changing the trusted-controller policy.

The dedicated HTTPS client trusts only `bloodcontrol_ca.pem`, retains hostname validation,
rejects redirects, verifies RSA-PSS signatures over raw response bytes before JSON parsing,
and never logs request/response bodies or credentials. The public CA must be updated if the
service rotates it.

Eligibility is cached for five minutes and stale eligibility can be used after a connection
failure. A missing allowlist blocks key requests. Unavailable keys are cached for an hour;
network/rate/server failures back off for 15 minutes, one hour, six hours, then 24 hours.
Authentication or signature failures disable requests for the process session. Key material
must decode to two 16-byte values. The response must include a serial that matches the
requested sensor; missing, empty and mismatched serials are rejected.
Allowlist membership does not guarantee that keys exist.

HTTP 5xx tries the alternate IP once before backing off. Unsigned HTTP 429/5xx responses
also trigger backoff; successful responses still require a valid signature. Signed
allowlist client errors cache for an hour. An active refresh backoff propagates through a
stale allowlist miss, since a stale miss does not establish that the sensor is ineligible.

## Validation

`BloodControlAidexTests` uses freshly generated synthetic RSA credentials and an injected
transport. It covers the wire envelope, exact-byte signatures, parsing, eligibility,
negative caching, throttling, and address fallback. `AiDexKeyManagementTests` checks the
Russian switch/form, English visibility, connected prefill, tap-to-fill selection,
manual edit preservation, MAC-fallback exclusion, and landscape scrolling.

Live validation on 2026-11-07 used the production protocol, transport and provider with
the supplied credentials and only the two authorized serials. The allowlist returned HTTP
200 with a valid server signature. `22222FY2M8` was listed and its single key request also
returned HTTP 200 with a valid signature; both decoded keys were 16 bytes. `22222FXYT3`
was absent from the current allowlist, so the provider correctly skipped its key request.
No key values or client credentials were printed or committed. Physical pairing and
reconnect remain unverified; live API success does not establish sensor acceptance.
The credential-enabled phone build and protocol/provider tests passed.

Android crypto validation found that AndroidOpenSSL exposes `SHA256withRSA/PSS` but not
generic `RSASSA-PSS`; the earlier desktop-only live probe had masked this difference. The
client now prefers the Android digest-specific algorithm and falls back to the generic name
for desktop providers, retaining SHA-256/MGF1-SHA256, salt length 32 and trailer field 1.
An actual Android runtime probe using the production protocol/transport/provider completed
signed HTTP 200 allowlist and `22222FY2M8` key requests, with two 16-byte keys. A provider-
availability regression covers the absence of the generic algorithm.

Safe diagnostics record endpoint category, HTTP status and exception class only. The first
session-disable reason remains visible on later requests; no exception messages, response
bodies, headers, allowlist entries, client credentials or sensor keys are logged.

Manual-only follow-up validation passed 618 focused phone tests and 558 focused Wear tests,
with no failures or skips, plus UI guardrails and `git diff --check`. The Russian sensor
picker render was inspected. Automatic-recovery-only state, helpers and tests were removed;
the BLE manager now matches the pairing behavior before this PR.
