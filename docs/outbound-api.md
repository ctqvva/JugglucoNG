# Outbound API — Architecture & Extension Guide

> Outbound-only HTTP / chat delivery of CGM readings to user-configured
> destinations. GlucoDroid does not currently receive from any of these
> channels; the inbound direction is reserved for future work.

---

## 1. Scope

`OutboundApi` is the single framework that ships glucose readings from
GlucoDroid to user-configured third-party destinations. The supported
presets are:

| Preset constant          | Display name        | Protocol                                   |
|--------------------------|---------------------|--------------------------------------------|
| `custom_json`            | Custom JSON webhook | User-supplied HTTP POST with full JSON body |
| `telegram_bot`           | Telegram bot        | `POST https://api.telegram.org/bot{token}/sendMessage` |
| `glucowatch_vk`          | VK direct message   | `POST https://api.vk.com/method/messages.send` (DM) |
| `vk_messages`            | VK text message     | `POST https://api.vk.com/method/messages.send` (text) |
| `delta_chat_relay` *(v2)*| Delta Chat relay    | `POST <user relay URL>/glucose` (chatmail) |

The framework is intentionally generic: a destination is `preset + url +
token + chatId/recipients + template + headers + trigger thresholds + min
interval`. New presets plug in via three extension points (see §7).

---

## 2. Files

| File | Role |
|---|---|
| `Common/src/main/java/tk/glucodata/SuperGattCallback.java` (line 577) | **Producer** — calls `OutboundApi.enqueueGlucose(...)` on every new CGM reading. |
| `Common/src/main/java/tk/glucodata/OutboundApi.kt` | **Orchestrator + worker.** Object `OutboundApi` (queue, trigger gating, rate limit, network guard), data class `Reading` (post-render fields), data class `JournalSnapshot`, and `class OutboundApiWorker` (`runOnce`, `sendTelegram`, `sendVk`, `sendJson`, HTTP plumbing). |
| `Common/src/main/java/tk/glucodata/OutboundApiSettings.kt` | **Persistence + destination model.** `Config`, `Destination`, presets, defaults, encode/decode to JSON, legacy migration. |
| `Common/src/main/java/tk/glucodata/OutboundApiInsulinTokens.kt` | **Per-insulin-type tokens.** Slugs a preset display name into a token family and expands it (see §5.1). |
| `Common/src/mobile/java/tk/glucodata/OutboundApiJournalSnapshot.kt` | **Journal data source.** Builds a JSON snapshot of recent insulin / carb entries for `{journal}`, `{iob}`, `{cob}` and the per-insulin-type tokens. `mobile` flavour only. |
| `Common/src/mobile/java/tk/glucodata/ui/OutboundApiSettingsScreen.kt` | **UI.** Compose screen, preset picker, per-destination editor, status row, test button. |
| `Common/src/mobile/java/tk/glucodata/ui/MainNavigation.kt` | **Wiring** — registers the route into the settings nav graph. |

---

## 3. Data flow

```
   CGM sensor
       │
       ▼
 SuperGattCallback                ◀── producer (Bluetooth LE notify)
       │
       │  outboundApiEnabled && shouldEmitExchangeUpdate
       ▼
 OutboundApi.enqueueGlucose(…)    ◀── public API; 4 overloads (varying
       │                              rawValue / autoValue inclusion)
       │
       │  enqueueGlucoseInternal()
       ▼
 OutboundApiSettings.Config       ◀── cached in-memory; reread from
                                    SharedPreferences("outbound_api")
                                    on first call. JSON-encoded array
                                    of Destination objects.

   For each active, ready destination:
       │
       ├── shouldSendForGlucose(mgdl)  ◀── trigger thresholds
       │
       ├── shouldQueue(...)            ◀── min interval + duplicate event-id guard
       │
       ├── recordQueued(...)
       │
       ├── build androidx.work.Data with reading fields
       │
       └── sendInProcess(...)
              │
              ├── hasUsableNetwork()   ◀── ConnectivityManager gate
              │
              ├── pendingSends < MAX_PENDING_SENDS (=12)
              │
              └── sendExecutor.execute { OutboundApiWorker.runOnce(...) }
                                         │
                                         ▼
                                  send()  ◀── dispatches on preset
                                         │
                            ┌────────────┼────────────┬────────────┐
                            ▼            ▼            ▼            ▼
                       sendTelegram   sendVk       sendJson   sendDeltaChatRelay
                                                                 (v2, not yet)
```

`OutboundApiWorker` is invoked directly from the in-process executor; the
`allowRetry=false` argument is hardcoded at the call site, so the
`Result.retry()` branch is currently dead code.

---

## 4. Destination model

```kotlin
data class Destination(
    val id: String,                    // UUID; primary key in the JSON array
    val enabled: Boolean,
    val name: String,                  // user-facing label
    val preset: String,                // one of PRESET_*; normalisePreset() maps legacy values
    val url: String,                   // resolved via resolvedUrl() → defaultUrl(preset) if blank
    val token: String,                 // bot token / VK access_token / relay auth
    val chatId: String,                // comma/semicolon/newline-split recipient list
    val apiVersion: String,            // VK only; defaults to "5.199"
    val headers: String,               // newline-separated `Name: value`, applied via applyHeaders()
    val messageTemplate: String,       // see §6 tokens
    val minIntervalMinutes: Int,       // dedup window; 0 = always
    val triggerMode: String,           // always | at_or_below | at_or_above | outside_range
    val triggerLowMgdl: Int,           // default 70
    val triggerHighMgdl: Int,          // default 180
    val lastQueuedEventId: String,     // dedup guard
    val lastQueuedAtMs: Long,
    val lastAttemptAtMs: Long,
    val lastSuccessAtMs: Long,
    val lastResponseCode: Int,
    val lastError: String?
)
```

`isReady()` gates dispatch:

- Telegram: `token.isNotBlank() && recipients().isNotEmpty() && all valid (numeric or @username)`
- VK: `token.isNotBlank() && recipients().isNotEmpty() && all numeric`
- Custom JSON / Delta Chat relay: just `resolvedUrl().isNotBlank()`

`withPreset(nextPreset)` swaps defaults smartly: if the user hasn't
overridden the URL, template, or name, they're replaced with the new
preset's defaults.

---

## 5. Message template tokens

`OutboundApi.renderMessage(template, reading)` substitutes the
following placeholders. `reading` is a flat view of the glucose + journal
state at the moment of send.

| Token              | Source                                                |
|--------------------|-------------------------------------------------------|
| `{event_id}`       | `{sensorId}:{timeMillis}:{mgdl}` (or `test-<ts>`)     |
| `{recipient}`      | The chat_id / VK peer_id / email the message is going to |
| `{value}`          | `displayText` — primary value formatted per `Applic.unit` |
| `{unit}`           | `mg/dL` or `mmol/L`                                   |
| `{mgdl}`           | Raw mg/dL integer                                     |
| `{mmol}`           | mmol/L, 2 decimals                                    |
| `{auto}` / `{auto_value}` | Raw auto-mode reading formatted per unit         |
| `{auto_mgdl}` / `{auto_mmol}` | Numeric auto reading in the named unit        |
| `{raw}`            | Raw sensor value, unconverted                         |
| `{raw_mgdl}` / `{raw_mmol}` | Raw value converted to mg/dL or mmol/L        |
| `{trend}`          | Trend name (e.g. `SingleUp`, `Flat`)                  |
| `{trend_arrow}`    | Unicode arrow (↑↗→↘↓ etc.)                            |
| `{rate_mgdl}` / `{rate_mmol}` | Rate of change in the named unit             |
| `{timestamp}`      | `timeMillis` as integer                               |
| `{time}`           | Locale-formatted short date+time                      |
| `{sensor}`         | Sensor ID                                             |
| `{sensor_gen}`     | Sensor generation                                     |
| `{alarm}`          | Alarm code                                            |
| `{iob}` / `{journal_iob}` | Active insulin (units)                         |
| `{cob}` / `{journal_cob}` | Active carbs (grams)                           |
| `{journal_events}` | JSON array of recent treatments (last 12h, capped 64) |
| `{journal}`        | Full journal JSON snapshot (`tk.glucodata.journal.snapshot.v2`) |
| `{test}`           | `true` for test sends, `false` for live readings      |

The full list is also exposed at runtime in
`templateTokens` (private val in `OutboundApiSettingsScreen.kt`).

### 5.1 Per-insulin-type tokens

`{iob}` cannot say *which* insulin moved, so every insulin type the journal has
active also gets a token family of its own, named after the type
(`OutboundApiInsulinTokens`). The slug is the display name lowercased with
non-alphanumeric runs collapsed to `_`, in whatever script the name is written
in; the stock English presets give `rapid_acting_insulin` and
`long_acting_insulin`. Colliding slugs get a `_2`, `_3` suffix.

| Token             | Renders                                              |
|-------------------|------------------------------------------------------|
| `{<slug>}`        | Last dose in units, empty when there is none         |
| `{<slug>_time}`   | Locale-formatted short time of that dose             |
| `{<slug>_ago}`    | Whole minutes since that dose                        |
| `{<slug>_iob}`    | Units of that type still active (`0` when none)      |
| `{<slug>_today}`  | Units of that type since local midnight              |

The per-type `_iob` ignores the preset's `countsTowardIob` switch — that switch
governs the single aggregate `{iob}`, while a token named after a type reports
what is actually left of it. Values come from the `insulin_types` array in the
journal snapshot, which the custom-JSON body also carries as a top-level field.
The settings screen lists the live tokens as chips (`insulinTemplateTokens()`),
which is the only practical way to enter a non-Latin slug.

Default templates:

- `DEFAULT_CHAT_TEMPLATE = "{value} {unit} {trend_arrow} {time}"`
  (Telegram, VK messages)
- `DEFAULT_GLUCO_WATCH_TEMPLATE = "GV:{mmol}|RAW:{raw}|TR:{trend_arrow}|AL:{alarm}|RT:{rate_mmol}|IOB:{iob}|COB:{cob}|TS:{timestamp}"`
  (GlucoWatch VK)
- `DEFAULT_CUSTOM_TEMPLATE = "{value} {unit} {trend_arrow} RAW:{raw} ({rate_mgdl} mg/dL/min) IOB:{iob} COB:{cob} {time}"`
  (Custom JSON webhook)

---

## 6. HTTP plumbing

`OutboundApiWorker.executePost(...)` is shared by all senders. It:

- `HttpURLConnection` with 15 s connect / 30 s read timeout (20 s / 30 s
  for Telegram because the bot API can be slow).
- `User-Agent: JugglucoNG API destinations`.
- Sends body as UTF-8 bytes; content-type differs by preset (JSON or
  form-urlencoded).
- Applies extra headers from `Destination.headers` (newline-separated
  `Name: value`); `Content-Type` cannot be overridden.
- Pre-response retry loop (off by default, `preResponseRetries=0`).
- Parses the response into `SendResponse(code, ok, retryable, error)`.
  Retryable: HTTP 429, 5xx, plus preset-specific API error codes
  (Telegram: 429/500/502/503; VK: 6/9/10).
- Records outcome: `recordSuccess()` on 2xx, `recordAttempt()` on failure
  (writes `lastResponseCode`, `lastError`, `lastAttemptAtMs`).

`friendlyError()` rewrites raw `HttpURLConnection` exceptions for
Telegram failures so the user sees
`"Telegram Bot API connection failed before a JSON response: <cause>"`
instead of the underlying `SocketTimeoutException` text.

---

## 7. Adding a new preset (e.g. `delta_chat_relay`)

Three extension points; for the Delta Chat relay all three are needed.

### 7.1 Constant in `OutboundApiSettings.kt`

```kotlin
const val PRESET_DELTA_CHAT_RELAY = "delta_chat_relay"
```

Add to `normalizePreset(...)` whitelist so the value round-trips through
JSON. Decide on validation rules for `Destination.isReady()` — the
relay needs `url.isNotBlank() && token.isNotBlank()` (token is the relay
auth bearer). Recipients are required; add an `isDeltaChatRecipient()`
helper accepting either a chatmail address (`@<server>`) or a numeric
chat-id-equivalent (delta-chat doesn't use numeric ids, so the helper
just validates that the address contains an `@`).

### 7.2 Default URL + template + name in `OutboundApiSettings.kt`

```kotlin
fun defaultUrl(preset: String, token: String = ""): String = when (normalizePreset(preset)) {
    …
    PRESET_DELTA_CHAT_RELAY -> ""  // user must configure
    else -> DEFAULT_CUSTOM_URL
}

fun defaultTemplate(preset: String): String = when (normalizePreset(preset)) {
    …
    PRESET_DELTA_CHAT_RELAY -> DEFAULT_CHAT_TEMPLATE  // same concise format as Telegram
    else -> DEFAULT_CUSTOM_TEMPLATE
}

fun defaultName(preset: String): String = when (normalizePreset(preset)) {
    …
    PRESET_DELTA_CHAT_RELAY -> "Delta Chat relay"
    else -> "Custom JSON webhook"
}
```

### 7.3 Send branch in `OutboundApiWorker.send(...)` and the `OutboundApiSettingsScreen` preset list

```kotlin
// OutboundApi.kt
private fun send(
    destination: OutboundApiSettings.Destination,
    reading: OutboundApi.Reading
): SendResponse {
    val message = OutboundApi.renderMessage(destination.resolvedTemplate(), reading)
    return when (destination.normalizedPreset()) {
        OutboundApiSettings.PRESET_TELEGRAM_BOT -> sendTelegram(destination, reading, message)
        OutboundApiSettings.PRESET_GLUCO_WATCH_VK,
        OutboundApiSettings.PRESET_VK_MESSAGES -> sendVk(destination, reading, message)
        OutboundApiSettings.PRESET_DELTA_CHAT_RELAY -> sendDeltaChatRelay(destination, reading, message)
        else -> sendJson(destination, reading, message)
    }
}

private fun sendDeltaChatRelay(
    destination: OutboundApiSettings.Destination,
    reading: OutboundApi.Reading,
    message: String
): SendResponse {
    val body = JSONObject()
        .put("preset", "delta_chat_relay")
        .put("event_id", reading.eventId)
        .put("recipient", reading.recipient)
        .put("message", message)
        .put("test", reading.test)
        .toString()
        .toByteArray(Charsets.UTF_8)
    val headers = "Authorization: Bearer ${destination.token.trim()}"
    return executePost(
        urlString = destination.resolvedUrl(),
        contentType = "application/json; charset=UTF-8",
        headers = headers,
        body = body,
        parseApiError = ::parseDeltaChatRelayError,
        connectTimeoutMs = 20_000,
        readTimeoutMs = 30_000
    )
}
```

```kotlin
// OutboundApiSettingsScreen.kt
private fun destinationPresetSpecs(): List<PresetSpec> = listOf(
    …,
    PresetSpec(
        id = OutboundApiSettings.PRESET_DELTA_CHAT_RELAY,
        titleRes = R.string.outbound_api_preset_delta_chat_relay,
        descriptionRes = R.string.outbound_api_preset_delta_chat_relay_desc,
        icon = Icons.Filled.MailOutline  // or similar; pick from filled icons
    )
)
```

For v1 (outbound only, no commands) this is the entire change set:
~50 lines in `OutboundApiSettings.kt`, ~30 lines in `OutboundApi.kt`, one
`PresetSpec` entry, and corresponding string resources. No changes to
`SuperGattCallback` — the producer is preset-agnostic.

### 7.4 String resources

Add to `Common/src/mobile/res/values/strings.xml`:

- `outbound_api_preset_delta_chat_relay` = "Delta Chat relay"
- `outbound_api_preset_delta_chat_relay_desc` = "Send readings to a Delta Chat relay (chatmail)."
- `outbound_api_delta_chat_relay_url_label` = "Relay URL"
- `outbound_api_delta_chat_relay_url_help` = "URL of your Delta Chat relay, e.g. https://glucodroid-relay.example.com"
- `outbound_api_delta_chat_relay_token_label` = "Relay auth token"
- `outbound_api_delta_chat_relay_recipient_help` = "One chatmail address per line (e.g. you@chatmail.example)."

---

## 8. Persistence and migration

Destinations are stored as a JSON array under the key
`destinations_json` in `SharedPreferences("outbound_api")`. Top-level
boolean `enabled` is kept for backward compatibility with the
pre-destinations migration.

`migrateLegacy(context)` (in `OutboundApiSettings.kt`) runs once if no
JSON array is present: reads the flat `provider/url/token/chat_id/...`
keys (where `provider` is `webhook_json` or `vk`) and synthesises a
single `Destination` of the matching preset. The result is
immediately `save()`d back so the legacy keys become inert.

JSON encoding is symmetric via `encodeDestinations(...)` /
`decodeDestinations(...)`. The `id` field is deduped
case-insensitively on load.

---

## 9. Known limitations and v2 ideas

- **No inbound.** GlucoDroid never listens for messages on any of these
  channels. Adding inbound means a foreground service, a server
  endpoint (or long-poll), and a command grammar.
- **No retry on transient failure.** The `allowRetry=false` hardcode
  means a failed POST is logged but not re-queued. A simple fix is to
  persist `(destinationId, eventId, payload)` and have a periodic
  alarm retry pending failures.
- **No media (images, charts).** All senders are text-only.
- **No auth refresh.** VK tokens and Telegram tokens are user-supplied
  strings; rotation is manual. A future preset could integrate
  service-account OAuth.
- **Single-process executor.** A single thread
  (`OutboundApiSend`) handles all sends. Throughput is fine for a
  single user but is a bottleneck if multiple destinations are
  configured and one is slow.
- **No "send only on change" beyond min-interval.** Two identical
  readings 6 minutes apart are sent. ~~Adding a
  "only on delta ≥ N mg/dL" trigger would help noisy sensors.~~
  **Resolved by `suppressDeltaBelowMgdl` — see §9.**

These are tracked in the audit document
(`docs/chatbots-branch-audit.md`).

---

## 9. Telegram bubble refresh (v0.2.4.4+)

To stop the chat from filling with one-bubble-per-reading noise, the
Telegram destination supports an "edit in place" model. Each destination
gets a small bag of per-recipient state (last message id, last sent
time, last sent mg/dL, last stale time) keyed by recipient chat id, so
multi-recipient configs don't collide.

### 9.1 Per-destination fields

| Field | Type | Default | Meaning |
|---|---|---|---|
| `refreshInPlaceEnabled` | `Boolean` | `true` | Master switch. When off, every reading posts a new message (legacy behaviour). |
| `refreshWindowMinutes` | `Int` | `15` | After this many minutes since the last send, the next reading posts a fresh message instead of editing. 1–60. |
| `suppressDeltaBelowMgdl` | `Int` | `1` | 0 = never suppress; >0 = skip the edit if `|new_mgdl - last_mgdl| < threshold`. With integer mg/dL values, default 1 skips unchanged readings only. |
| `staleEnabled` | `Boolean` | `true` | Post separate "Stale" / "Missed reading." Telegram messages when no new reading arrives in time. |
| `staleThresholdMinutes` | `Int` | `10` | After this many minutes of silence, post "⚠️ Stale — waiting for next reading. (HH:mm)". |
| `missedThresholdMinutes` | `Int` | `15` | After this many minutes of silence, post "⚪ Missed reading. (HH:mm)". Must be > `staleThresholdMinutes`. |
| `lastMessageIdByRecipient` | `Map<String, Long>` | `{}` | Per-recipient last Telegram `message_id`, used as the `message_id` for `editMessageText`. |
| `lastSentAtMsByRecipient` | `Map<String, Long>` | `{}` | Per-recipient timestamp of the last successful send. |
| `lastSentMgdlByRecipient` | `Map<String, Int>` | `{}` | Per-recipient last sent mg/dL (for delta suppression). |
| `lastStaleAtMsByRecipient` | `Map<String, Long>` | `{}` | Per-recipient timestamp of the last stale/missed post; used to throttle retry posts to 70 s. |

### 9.2 Send path

`OutboundApiWorker.runOnce()` → `send()` → `sendTelegram()`:

```
1. Compute windowMs = refreshWindowMinutes * 60_000
2. withinWindow = enabled && lastMsgId > 0 && lastSentAt > 0 &&
                  (now - lastSentAt) <= windowMs
3. if withinWindow && |newMgdl - lastMgdl| < suppressDeltaBelowMgdl:
       return synthetic 200 response, no API call (suppressed)
4. if withinWindow:
       response = editMessageText(chat_id, message_id, text)
       if response.ok or response is "not modified": return success
       if response is retryable: return response  // preserve bubble state
       else: clearRecipientState()  // bubble gone or inaccessible
5. response = sendMessage(chat_id, text)  // fresh bubble
6. on success: recordBubbleSent(msgId, now, mgdl)
              schedule stale-check Handler(delay = staleThresholdMinutes*60s + 10s)
```

### 9.3 Stale-check scheduler

`TelegramStaleCheckWork` is a thin in-process Handler-based scheduler.
After every successful `sendMessage`, `editMessageText`, or suppressed
unchanged reading, the producer schedules a single Handler post-delayed by
`staleThresholdMinutes*60_000 + 10_000` (the 10 s slack avoids firing on
slightly-late deliveries).

When the post fires:

```
for each recipient in destination.recipients():
    elapsed = now - lastSentAtMsByRecipient[recipient]
    status = missed if elapsed >= missedThresholdMs
           else stale if elapsed >= staleThresholdMs
           else continue
    if lastStaleAt within last 70s: continue  // throttle retry cycle
    if lastMessageIdByRecipient[recipient] <= 0: continue
    text = "⚠️ Stale — waiting for next reading. (HH:mm)"   # if stale
         or "⚪ Missed reading. (HH:mm)"                    # if missed
    if sendMessage(chat_id, text) returns 2xx:
        recordStaleAt(recipient, now)
    else if definitive 4xx:
        clearRecipientState(recipient)  // bubble deleted by user
    else:
        retry after 60s
```

**Limitation:** in-process only. If Android kills the app between sends,
the stale check is lost until the next CGM reading arrives and
re-schedules. Acceptable for a 1:1 CGM bubble — worst case is one missed
stale period after a phone restart. A future revision could persist the
pending `WorkRequest` via WorkManager for stricter guarantees.

### 9.4 Template tokens for status

| Token | Renders | Example |
|---|---|---|
| `{status}` | Status key | `in_range`, `high`, `low`, `stale`, `missed` |
| `{status_emoji}` | Status emoji | 🟢 / 🟡 / 🔴 / ⚠️ / ⚪ |

`Destination.rangeStatus(mgdl)` returns one of the five status keys
based on the per-destination `triggerLowMgdl` / `triggerHighMgdl`
thresholds. The default Telegram template
(`DEFAULT_CHAT_TEMPLATE`) now includes `{status_emoji}` at the start:

```
{status_emoji} {value} {unit} {trend_arrow} {time}
```

Existing destinations with a non-empty `messageTemplate` are not
touched; only the default for new destinations changes.

### 9.5 Wire-level details

- **Edit URL:** `sendMessage` → `editMessageText` (last segment swap).
  Bot token stays the same. No additional endpoint or scope needed.
- **`SendResponse` extended** with `messageId: Long?` (parsed from
  `result.message_id` in the Telegram 2xx response) and `suppressed:
  Boolean` (true for the synthetic "no API call" path).
- **Edit failures are split by class.** 400 "message is not modified" is
  treated as success. Transient failures such as 429 and 5xx preserve the
  cached message id so the next reading can retry the edit. Definitive
  failures such as "message to edit not found" clear the cached recipient
  state and the current reading posts a fresh message.
- **Headers on the edit path:** any custom `headers` field on the
  destination is also applied to `editMessageText` so e.g.
  `Authorization: Bearer …` relays keep working.
