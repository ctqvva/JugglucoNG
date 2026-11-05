# Lumiflex cloud

JNG can mirror glucose readings to the Lumiflex vendor cloud (`cloud.lumiflex.ru`, brand `lumi`) and
manage the followers the official app shows under "Share/Follow". It is the same channel model as
Dexcom Share, LibreView and iCan Health.

## Enable

Settings → **Lumiflex Cloud** → enter the account (e-mail or phone) and password → **Sign in**.
The card appears only when the app language is Russian, and either a Lumiflex account is configured
or an AiDex X / LinX sensor is active. Phone numbers accept a leading `+` and display formatting
(for example, `+7 (999) 123-45-67`); JNG sends the country-code digits to the vendor. Email addresses
keep their `+` aliases, and surrounding account whitespace is removed.

Use credentials that work in the official **Lumiflex LinX mobile app**. A website-only account may
not authenticate with the mobile service used by JNG. Lumiflex's
[LinX setup instructions](https://lumiflex.ru/help/linx-step-2/) currently require a Russian phone
number for mobile registration.

## What is uploaded

Brief CGM records only: sensor serial, minutes since sensor start, glucose, status/quality,
`appTime`/`appTimeZone`, DST offset and the derived `frontRecordId`
(`MD5(sensorId + timeOffset)`). Raw history, calibrations and events are not uploaded in this
version.

Uploads go through a persistent queue: readings are batched, retried with exponential backoff, and a
401 or the vendor's `802` ("this account has been logged in from other equipments") clears the
session so the settings screen asks for a new sign-in. Readings are only queued for AiDex sensors JNG
itself is driving, and only while the channel is switched on: history mirrored in through
phone-to-phone Clone is never uploaded, and a reading enqueued for one account is dropped when the
signed-in account changes.

## Followers

**Manage followers** lists the accounts this patient shares with. You can invite by account name
with an optional nickname, rename, remove, and toggle the per-follower options the vendor app
offers: *Switch display*, *Regular reminders*, *Urgent low*, *Extreme high*.

## Session and sensor requirements

- The Lumiflex cloud keeps **one session per account**: signing in from JNG signs the official app
  out, and vice versa. Use it as a replacement for the official upload, not alongside it.
- The account, token and user id are stored as one session and only replaced after a successful
  sign-in; a failed attempt keeps the previous session, and the pending queue is bound to the account
  that produced it.
- A sensor must already be registered on the account (paired in the official app) — JNG resolves the
  server-side `sensorId` from the device list by `deviceSn`; readings for an unknown serial stay
  queued instead of being sent.

## Known limitations

- JNG does not create sensor records on the account: the vendor's `userDeviceRegister` needs
  `deviceMac`, `deviceKey`, `sensorStartUp` and a device-key pair that only the official app has, and
  the server accepts a registration only with a complete payload. A sensor that was never paired in
  the official app therefore cannot be uploaded.
- No live-value/trend push in v1 — the follower view is fed by the uploaded records.
- No follower-side consumption: JNG does not read another account's data from this cloud.
- The vendor contract can change with an app update; the channel is isolated behind its own
  constants and can be switched off.
