# Lumiflex cloud

JNG can send glucose to Lumiflex (`cloud.lumiflex.ru`, brand `lumi`), manage sharing, and
receive glucose from an account that has granted access.

## Enable

Settings → **Lumiflex Cloud** → enter the account (e-mail or phone) and password → **Sign in**.
The entry appears when the app language is Russian, including on a follower phone without
a local sensor. Phone numbers accept a leading `+` and display formatting
(for example, `+7 (999) 123-45-67`); JNG sends the country-code digits to the vendor. Email addresses
keep their `+` aliases, and surrounding account whitespace is removed.

Use credentials that work in the official **Lumiflex LinX mobile app**. A website-only account may
not authenticate with the mobile service used by JNG. Lumiflex's
[LinX setup instructions](https://lumiflex.ru/help/linx-step-2/) currently require a Russian phone
number for mobile registration.

## Create a mobile account

Choose **Create account**, enter a Russian number with country code `+7`, and request the SMS
verification code. Enter the received code, then set a password of 8–12 Latin letters or digits.
The vendor uses one SMS flow to create an account or sign in an existing account. Requests have a
60-second resend cooldown; JNG never sends another SMS automatically.

The verified session and unfinished password-setup step survive leaving the screen. If password
setup fails, retry that step without creating another account or requesting another code. If an
SMS request loses its network response, the code field stays available in case the SMS arrived.
Automatic uploads start after password setup succeeds.

## Receive shared readings

Sign in with the follower account and select **Receive**. The sensor owner must add that
account under **Followers** on their phone. Select the account whose glucose you want to see;
one authorized provider is selected automatically when an existing reader has no local sensor.
With several providers, choose one explicitly. **Send** keeps the existing local upload mode.
The feature master switch pauses either mode. Receiving never binds sensors or uploads glucose.

The receiver reads the provider account, rather than the signed-in reader’s own history. It
checks sharing authorization every poll, fetches records about once a minute, and imports them
through the shared virtual-sensor bridge into history, the dashboard, alerts and native exports.
An initial/recovery fetch covers up to three 500-record pages, stopping once it reaches a day of
history. Record timestamps use their reported timezone; glucose is already in mg/dL. The receiver
recovers on process start, network return and app foreground, with an alarm for background polls
and WorkManager as a periodic recovery backstop. Android may defer polling during deep sleep.
Account/provider changes and sign-out discard in-flight results.

## Bind a sensor (Send mode)

JNG registers locally paired AiDex / LinX sensors with Lumiflex automatically when uploads start.
You can also tap **Bind sensor** in the sensor row before the first reading. Registration uses the
local BLE address, activation time and saved PAIR credential; no placeholder keys or addresses are
sent. The cloud device list must confirm the binding before readings are uploaded with its sensor ID.

If pairing details are not available yet, connect the sensor in JNG and retry. The official app is
not needed for this binding. Clone mirrors are never registered or uploaded.

## Account and sync status

The account row checks the saved session with the vendor when the screen opens. It distinguishes
a confirmed sign-in, a check in progress, an unverified connection, and an expired session. Editing
sign-in details does not change the authenticated account until sign-in succeeds.

Entered account/password details are saved locally in an Android Keystore-encrypted draft and are
excluded from settings exports. Sign-out keeps this draft for the next login; clear the password
field to remove the saved password. JNG does not silently sign in again when another app displaces
the session.

After signing in, enable **Automatic upload**. The sync row shows queued readings, the last
successful upload, network failures and missing cloud sensors. **Sync now** checks the connection
and requests an upload. Signing in and checking the connection do not count as successful uploads.

Transient failures use persistent, network-constrained background retries. A periodic recovery task
and process-start initialization recover queued work without waiting for another glucose reading.
Readings leave the queue only after an explicit successful vendor response; an empty HTTP 200
response is not an acknowledgement. If a sensor is missing from the account, its readings stay
queued while JNG binds it using the local pairing details.

## What is uploaded

Brief CGM records only: sensor serial, minutes since sensor start, glucose, status/quality,
`appTime`/`appTimeZone`, DST offset and the derived `frontRecordId`
(`MD5(sensorId + timeOffset)`). Raw history, calibrations and events are not uploaded in this
version.

Uploads go through a persistent queue: readings are batched, retried with exponential backoff, and a
401 or the vendor's expired/replaced-session codes (`800`, `801`, `802`) clear the
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
- A sensor must be locally paired with complete metadata before JNG can register it. JNG confirms
  the server-side `sensorId` through the cloud device list before uploading; unconfirmed readings
  stay queued.

## Known limitations

- No live-value/trend push in v1 — the follower view is fed by the uploaded records.
- The vendor contract can change with an app update; the channel is isolated behind its own
  constants and can be switched off.
