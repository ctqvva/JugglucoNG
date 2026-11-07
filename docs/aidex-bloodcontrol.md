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

No values are committed. Builds without the inputs show an unavailable message on an
explicit request. Partially configured or malformed inputs fail configuration without
printing values. This repository has mobile/wear flavors; it has no full/foss dimension.
No new distribution flavor is introduced. Protect these inputs in release CI and do not
provide them to untrusted pull requests. APK credentials are recoverable from the APK.

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

## Validation

`BloodControlAidexTests` uses freshly generated synthetic RSA credentials and an injected
transport. It covers the wire envelope, exact-byte signatures, parsing, eligibility,
negative caching, throttling, and address fallback. `AiDexKeyManagementTests` checks the
Russian switch/form, English visibility, and landscape scrolling.

Initial validation passed 594 focused phone tests and 540 focused Wear tests, with zero
failures or skips, plus UI guardrails and `git diff --check`. The rendered Russian form was
inspected from the Compose test capture. A partial configuration using a synthetic token
was rejected with a generic configuration error.

The supplied serials `22222FXYT3` and `22222FY2M8` are authorized for live checks. No live
request was possible during initial implementation because client credentials were absent.
The supplied API audit previously reported `AIDEX_KEYS_NOT_AVAILABLE` for two F-series
sensors; this is background evidence, not a result from this implementation. A live signed
200 response and physical pairing/reconnect still require verification with configured
credentials and the sensors.
