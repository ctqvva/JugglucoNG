# AiDEX BloodControl keys

Russian app language exposes **AideX CN | BloodControl API** in AiDEX key management.
BloodControl uses the existing encrypted pairing-material store, so downloaded keys also
work with the existing import/export and reconnect paths.

For F-series sensors in Russian, a sensor's explicit F001 missing-key response starts
automatic recovery after saved PAIR credentials and local/imported provisioning material
have been tried. A saved CN account is tried first. If it fails, or its downloaded material
is rejected by the sensor, BloodControl is the final fallback. Other languages and product
lines keep their existing automatic pairing behavior. Product detection affects only the
optional cloud fallback, never the local key derivation.

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
must decode to two 16-byte values and any response serial must match the requested sensor.
Allowlist membership does not guarantee that keys exist.

Automatic fetches return uncommitted material. The BLE handler checks the exact GATT,
exchange generation and saved-material revision before committing it; a newer import wins.
Transient failures carry their backoff deadline to the recovery state, which permits at
most four later reconnect retries. Permanent failures and a completed BC installation stop
automatic requests. HTTP 5xx tries the alternate IP once before backing off; successful
responses still require a valid signature. Signed allowlist client errors cache for an hour.

## Validation

`BloodControlAidexTests` uses freshly generated synthetic RSA credentials and an injected
transport. It covers the wire envelope, exact-byte signatures, parsing, eligibility,
negative caching, throttling, and address fallback. `AiDexKeyManagementTests` checks the
Russian switch/form, English visibility, and landscape scrolling.

Initial validation passed 594 focused phone tests and 540 focused Wear tests, with zero
failures or skips, plus UI guardrails and `git diff --check`. The rendered Russian form was
inspected from the Compose test capture. A partial configuration using a synthetic token
was rejected with a generic configuration error.

Live validation on 2026-11-07 used the production protocol, transport and provider with
the supplied credentials and only the two authorized serials. The allowlist returned HTTP
200 with a valid server signature. `22222FY2M8` was listed and its single key request also
returned HTTP 200 with a valid signature; both decoded keys were 16 bytes. `22222FXYT3`
was absent from the current allowlist, so the provider correctly skipped its key request.
No key values or client credentials were printed or committed. Physical pairing and
reconnect remain unverified; live API success does not establish sensor acceptance.
The credential-enabled phone build and all 13 BloodControl protocol/provider tests passed.

Review follow-up validation passed 616 phone tests and 560 Wear tests. Added regressions
cover stale/duplicate completions, a newer saved import, bounded reconnect retry deadlines,
signed allowlist failure caching, HTTP 5xx route fallback, and unconfigured Russian UI.
