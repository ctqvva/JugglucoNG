# Lumiflex cloud

JNG can mirror glucose readings to the Lumiflex vendor cloud (`cloud.lumiflex.ru`, brand `lumi`) and
manage the followers the official app shows under "Share/Follow". It is the same channel model as
Dexcom Share, LibreView and iCan Health.

## Enable

Settings → **Lumiflex Cloud** → enter the account (e-mail or phone) and password → **Sign in**.
The card appears in settings once a Lumiflex account is configured or an AiDex X / LinX sensor is
active.

## What is uploaded

Brief CGM records only: sensor serial, minutes since sensor start, glucose, status/quality,
`appTime`/`appTimeZone`, DST offset and the derived `frontRecordId`
(`MD5(sensorId + timeOffset)`). Raw history, calibrations and events are not uploaded in this
version.

Uploads go through a persistent queue: readings are batched, retried with exponential backoff, and a
401 clears the session so the settings screen asks for a new sign-in. Readings are only queued for
sensors JNG itself is driving, and only while the channel is switched on.

## Followers

**Manage followers** lists the accounts this patient shares with. You can invite by account name
with an optional nickname, rename, remove, and toggle the per-follower options the vendor app
offers: *Switch display*, *Regular reminders*, *Urgent low*, *Extreme high*.

## Session and sensor requirements

- The Lumiflex cloud keeps **one session per account**: signing in from JNG signs the official app
  out, and vice versa. Use it as a replacement for the official upload, not alongside it.
- A sensor must already be registered on the account (paired in the official app) — JNG resolves the
  server-side `sensorId` from the device list by `deviceSn`; readings for an unknown serial stay
  queued instead of being sent.

## Known limitations

- Sensor registration on the account (`cgmDevice/userDeviceRegister`) is attempted best-effort; if
  the portal shows the sensor as unregistered, record upload still runs.
- No live-value/trend push in v1 — the follower view is fed by the uploaded records.
- No follower-side consumption: JNG does not read another account's data from this cloud.
- The vendor contract can change with an app update; the channel is isolated behind its own
  constants and can be switched off.
