# JugglucoNG review guidance

JugglucoNG reads CGM sensors over BLE, calibrates and displays glucose, raises
alarms, and passes readings to other apps. People dose insulin and react to
alarms from these numbers. The scoped rules in `config.json` list specific
contracts; this file says what counts as a finding and gives the background a
diff does not show.

## What a bug costs here

Roughly in order:

1. A wrong value shown, alarmed on or exported: double calibration, a wrong
   unit, a stale value presented as current, a raw lane reaching alerts, a
   reading attributed to the wrong sensor.
2. A reading silently dropped, delayed or withheld.
3. Lost or corrupted history: a destructive migration, a cursor moved
   backwards, records overwritten or duplicated.
4. A driver or service that loops, stalls or drains the battery: reconnect
   loops, unbounded retries, a receive/wake loop.
5. Crashes, broken builds for either flavour, security and privacy problems.

Ordinary bugs outside these areas are still worth reporting. Style is not.

## What merits a finding

Report a concrete defect that the PR introduces, exposes or makes worse.
Compare with the base revision and follow the relevant callers and consumers
before deciding that a changed line is wrong. A failure in unchanged code is in
scope when the PR makes it reachable or worse.

Each finding names a realistic input or state, the code path, and what goes
wrong for the user. Source-level evidence is enough when it shows the defect; a
device reproduction is not required. If the claim depends on how a sensor,
Android version or external protocol behaves, check that against the code,
tests or docs available, and leave it out if missing evidence could reverse the
conclusion.

These are not findings on their own:

- A missing test or missing device check. If a defect would have been caught by
  a test, report the defect and suggest the test inside that finding.
- Formatting, naming, logging style, refactoring opportunities or architectural
  debt in code the PR did not change.
- A new helper that differs from its siblings. Check whether it keeps the
  contract the sibling helper protects, not whether it is the same helper.
- A performance concern without a reachable hot path, stall, retry storm or
  leak. An unfamiliar lock or an allocation is not enough.
- A new permission, endpoint or dependency, unless it leads to an actual
  exposure.

Group repeated instances of one underlying defect into one finding. Set
severity from demonstrated impact and reachability, not from the directory a
file lives in. If nothing meets this bar, say that no actionable defects were
found and stop; do not fill a checklist.

## How to write the comment

One short, plain paragraph: what triggers it, what goes wrong, who is affected.
Quote enough code to make the claim checkable, and suggest a bounded fix when
the evidence supports one. Avoid generic requests such as "add validation" or
"add tests" with no failure behind them.

## Behaviour changes need to be stated

Changes to driver signal processing, timestamps and sensor grids, calibration
maths, alert thresholds and delivery, Room schemas, native storage, anything
that could drop or withhold a reading, and phone/watch sensor ownership always
get full maintainer review (`docs/architecture/direction.md` section 8.3). A
Greptile review does not replace that. What Greptile adds: when such a change
is not mentioned in the PR description, say so, and check that the code does
what the description claims. A behaviour change is not a defect just because
it is a change.

Refactoring and behaviour changes are meant to land in separate PRs. A PR that
says "no behaviour change" but changes behaviour is a finding.

## Layout

- `Common/src/main` is shared by both flavours; `src/mobile` is phone only,
  `src/wear` is Wear OS only. `Common/build.gradle` adds further source
  directories (`src/dex`, `src/libre3`, `src/mobileSi`, `src/wearSi` and
  others) per variant. Both flavours and their minified release builds must
  keep building; the watch has its own CMake configuration.
- Shared code does not name variant classes. `src/main` declares an interface
  and each variant registers its implementation in `Application.onCreate`
  (`CustomAlertAccess` and `TrendAccess` are the examples). A capability the
  watch lacks is absent from the registry, not a silent no-op.
- Native code is C++ over JNI in `Common/src/main/cpp`; `Natives.java` is the
  main Java side. Libre support is largely native.
- Sensor drivers for Sibionics, AiDEX, Anytime/CT, iCan, Ottai and the
  Nightscout follower live in `tk.glucodata.drivers.*`.
- Live readings reach `SuperGattCallback`, which feeds the exchange outputs
  (`emitExchangeOutputs`), the widget, and the phone/watch sync.
- The Room history database is `tk.glucodata.data.HistoryDatabase`; calibration
  has its own `CalibrationDatabase`. Each kind of data has one authoritative
  writer; `docs/architecture/storage-ownership.md` records which.

## Questions worth asking when a PR touches...

- **A reading's path from sensor to screen or alarm**: where is it calibrated,
  smoothed, rounded, converted to display units, and stored? Each should happen
  once.
- **A feed to another app** (xDrip, AAPS, Gadgetbridge, WearInt, watchdrip,
  Nightscout, outbound API): did the payload's meaning, units, timestamp,
  recipients, enablement or delivery timing change unintentionally?
- **A setting**: follow it from the UI to where it is persisted and to the code
  that reads it. Does a watch-flippable exchange toggle appear in
  `ExchangeToggles`, and does it keep its wire id?
- **A driver reconnect, bind or replay path**: what ends the loop on the second
  pass with the same address, cursor or sensor id?
- **Timestamps in a driver**: which clock, which unit, which origin, and can it
  go backwards?
- **Identity matching by name or serial**: a broad name pattern is deliberate
  (unknown OEM rebrands must still pair). The safeguard is to verify after
  connecting and stop retrying a candidate that is the wrong device, not to
  narrow the pattern. A regex compiled per comparison on this path has caused
  main-thread stalls before.

## Looks wrong, but is intentional

- `Common/src/main/cpp/curve/percentile.cpp` and the nanovg code beside it look
  like dead graphics code. The built-in web server uses them to render its
  `/stats`, `/curve` and `/summarygraph` PNGs. Do not suggest removing them.
- The legacy `Settings.java` dialog and the Compose settings screens both
  exist, and settings they share must stay in step.
- Much of the Java is upstream code with its own formatting, including
  `{if(doLog) {...};};` logging wrappers. Leave it alone unless the PR changes
  those lines.
