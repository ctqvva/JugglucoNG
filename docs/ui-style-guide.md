# UI style guide

How the phone app's Compose UI (`Common/src/mobile/java/tk/glucodata/ui`) is put together:
the grid, the type and colour roles, which component to reach for, how copy is written, and
the performance rules screens follow. It describes the app as it is, and the shared
components that keep it that way. `scripts/ui-guardrails.py` checks the mechanical parts.

The base is Material 3 Expressive. Where this guide and the Material spec disagree, this guide
records a deliberate choice; where the code and this guide disagree, fix one of them.

---

## 1. Principles

1. **One left edge per thought.** Text that reads as one block starts at one x. A settings
   row's text starts 68dp from the card edge (16 padding + 40 icon tile + 12 gap); anything
   that sits beside settings rows uses the same inset.
2. **Everything on the grid.** Spacing is a multiple of 4dp. Radii come from the shape scale.
   An odd number is a bug unless a comment says why it is an optical correction.
3. **One component per job.** If two screens need the same thing, they use the same
   composable. A difference between them is a named parameter, never an accident of which
   copy was edited last.
4. **Glucose is read first.** The current value, its trend and its age are the most important
   pixels in the app. Nothing decorative competes with them, and nothing about them depends
   on the wallpaper.
5. **Say what the tap does.** Buttons are labelled with their effect, not the card's topic.

---

## 2. Spacing

The grid is 4dp. These are the steps and what each one means:

| dp | Use |
|---:|---|
| 2 | Between rows of one grouped card stack (`CardPosition` TOP/MIDDLE/BOTTOM) |
| 4 | Title → its own supporting line; inside dense chips |
| 8 | Section label → the group it labels; sibling buttons; icon → label inside a button |
| 12 | Icon tile → text; block separation inside a card |
| 16 | Screen gutter; card padding; content → actions |
| 24 | Between unrelated groups; above a section label |
| 32 | Screen bottom clearance |

Component internals follow their M3 specs instead (a chip is 32dp tall, a standard button
40dp, a full-width screen action 56dp, every touch target at least 48dp). Pin those with
`heightIn(min = …)` rather than reverse-engineering padding: a 6dp vertical pad that "makes"
a 32dp chip breaks the moment the text style changes.

---

## 3. Shapes

Corner radii come from the M3 Expressive scale: **4, 8, 12, 16, 20, 28, 32, full**.

| Radius | Use |
|---:|---|
| 4 | Inner corners of grouped rows and connected buttons |
| 12 | Settings rows and groups (`cardShape`), icon tiles, small tiles |
| 16 | Cards inside cards, list containers |
| 20 | Status cards beside settings rows |
| 28 | Dialogs, bottom sheets, large hero containers |
| full | Buttons, chips, pills, circular indicators (`CircleShape`) |

- **Buttons are pills that squeeze when pressed.** Pass `shapes = ButtonDefaults.shapes()`
  (`IconButtonDefaults.shapes()` for icon buttons): round at rest, 8dp corners while pressed.
  The shapes overload defaults to 16dp padding, so keep `contentPadding =
  ButtonDefaults.ContentPadding` where the old width matters. Filter chips and time pickers
  take their `…Defaults.shapes()` too. The ratchet counts buttons without `shapes`.
- **Connected buttons** (Reconnect | Disconnect) use `ConnectedButtonShapes`: pill ends
  outside, 4dp where the halves meet. A split button is material3's `SplitButtonLayout`, and
  a group of choices is `ConnectedButtonGroup`; both bring their own shapes and press morph.
- **Statistics cards** use the one leaf shape, `StatsCardShape` (28 / 16).
- **The dashboard hero** morphs its corners with the trend direction. That is the app's
  signature shape and the one deliberate exception to the scale.

---

## 4. Typography

The face is IBM Plex Sans (variable; width and weight axes). The scale is the M3 type scale
in `ui/theme/Type.kt`, with `titleMedium` at 400 for the "sleek list" look.

- **Emphasis is a style, not an override.** Use `titleMediumEmphasized`,
  `titleSmallEmphasized`, `titleLargeEmphasized`, `headlineSmallEmphasized`,
  `labelLargeEmphasized`, `labelMediumEmphasized` (M3 Expressive names). Don't add
  `fontWeight =` to a style at the call site.
- Each font width registers real instances at weights 100–700, so a weight always selects the
  matching instance of the variable font. Never build a `FontFamily` with one pinned weight.
- Numbers that change in place use `fontFeatureSettings = "tnum"` so they don't jitter.
- `fontSize = n.sp` at a call site is for canvas-adjacent text only (chart labels). Everything
  else uses a style.

| Style | Use |
|---|---|
| `displayLarge` (condensed) | The current glucose value |
| `displaySmall` / `headlineMedium` | Tab titles (`TabScreenHeader`, by window size) |
| `titleLarge` | Top app bar titles |
| `titleMedium` | Settings row titles, card titles (`…Emphasized` for a card's title) |
| `bodyMedium` | Row subtitles, card body |
| `labelLarge` | Buttons, section labels |
| `labelMedium` / `labelSmall` | Chips, metadata, chart axis (11sp minimum) |

---

## 5. Colour

- **Roles, not hex.** UI colour comes from `MaterialTheme.colorScheme`. Dynamic colour on
  API 31+; below that a full tonal-spot scheme generated from the app blue (`#1565C0`).
- **Glucose colours are fixed**, not dynamic: `GlucoseRangeColors` / `GlucoseValueTone`
  (and the Statistics band colours in `StatsVisualizations.kt`). A reading means the same
  colour on every wallpaper.
- **Success and warning** have no M3 role: use `StatusPalette.success()` / `warning()`
  (light/dark pairs, readable as text). Failure is `colorScheme.error`. A colour that has no
  role and no palette belongs in a `*Palette.kt` file with a name, not inline.
- **Dark or light:** call `isAppInDarkTheme()`, never `isSystemInDarkTheme()`. The in-app
  Theme setting can disagree with the system. (Windows drawn over other apps, like the
  floating overlay, are the exception.)

| Role | Use |
|---|---|
| `primary` | Main flow, general settings, "you're fine" |
| `secondary` | Notifications, files, data, system rows |
| `tertiary` | Integrations, something new or optional |
| `error` | Danger, alerts, failures |
| `onSurfaceVariant` | Supporting text, advanced/technical rows, chart axis |
| `surfaceContainerHigh` | Settings rows and cards |
| `surfaceContainerHighest` | An "off" icon tile, a pressed or stale container |

Icon tiles wash their tint at 12% (`IconTileDefaults.ContainerAlpha`). A tile whose row can
be switched off uses `IconTileDefaults.toggleContainerColor(tint, active)`.

---

## 6. Components

Reach for these before building a surface by hand.

| Need | Use | File |
|---|---|---|
| Top bar of a pushed screen | `AppTopBar(title, onNavigateBack, subtitle?, actions)` | `components/AppTopBar.kt` |
| Title of a top-level tab | `TabScreenHeader` + `TabScreenDefaults.contentPadding()` | `components/TabScreenHeader.kt` |
| A setting or a destination | `SettingsItem`, `SettingsSwitchItem`, `SettingsNavSwitchItem` | `components/SettingsComponents.kt` |
| A setting that reveals more | `ExpandableSettingsCard`, `DisclosingSwitchCard` | same |
| A feature's on/off hero | `MasterSwitchCard` | same |
| A destructive row | `DangerItem` | same |
| Group label | `SectionLabel` (24 above, 8 below) | same |
| Leading icon of a row/card | `IconTile` | `components/IconTile.kt` |
| 2–5 exclusive choices | `ConnectedButtonGroup` (material3 `ToggleButton`s, connected shapes) | `util/ConnectedButtonGroup.kt` |
| Two buttons as one control | `ConnectedButtonShapes` | `components/ConnectedButtonShapes.kt` |
| An action with a related second action | `SplitButtonLayout` + `SplitButtonDefaults` | material3 |
| A short wait with no progress to show | `LoadingIndicator` | material3 |
| A progress bar | `LinearWavyProgressIndicator` | material3 |
| An icon-only button | the button inside `IconButtonTooltip(label)`, label = its content description | `components/IconButtonTooltip.kt` |
| A "+" that offers several kinds of thing | `FloatingActionButtonMenu` + `ToggleFloatingActionButton` | material3; journal items trimmed to 44dp, labelLarge, 16dp ends (`JournalExpandableFab`) |
| Actions on a multi-selection | `HorizontalFloatingToolbar`; the screen keeps its top bar | material3 |
| Pick one value from a short list | `DropdownMenuPopup` + `DropdownMenuGroup` + `SelectableDropdownMenuItem` | material3 |
| Landscape navigation | collapsed `WideNavigationRail` (portrait: `ShortNavigationBar`) | material3 |
| Switch | `StyledSwitch` | `components/StyledSwitch.kt` |
| Sheet | `StableModalBottomSheet` | `components/StableModalBottomSheet.kt` |

Waiting: `LoadingIndicator` for a few seconds with nothing to count (a network check, a list
loading). Work that can run longer or knows its progress keeps a progress indicator. A spinner
inside a button stays a small `CircularProgressIndicator`; the loading indicator's shapes don't
read at 18dp.

### Row, card, dialog or sheet

- **Row** — a persistent setting or a destination. Its subtitle carries the current value.
  Group related rows with `CardPosition` and a 2dp gap.
- **Card** — status: where things stand, and the thing to do about it. Title first, actions
  at the bottom. A card holding one line of text is a row in costume; use a caption.
- **Dialog** — a decision that blocks: confirmation, short text entry, 2–4 exclusive options.
- **Bottom sheet** — a task with its own content, or one that needs the screen behind it.

### Actions

- An action is a **button**, never a settings row. A row is a place or a setting.
- Emphasis tracks importance: `Button` for the one you want pressed, `FilledTonalButton` or
  `OutlinedButton` for the rest, `TextButton` in dialogs. A screen-level action spans the
  width at 56dp.
- Destructive actions confirm first, and say what they destroy.

### Icons

- One glyph per meaning. Repeating a glyph for things of the same kind is right; two
  adjacent things that mean different things must not share one.
- Glyph sizes: 24 (default, tiles), 20, 18 (in buttons), 16 or 14 (inline with label text).
- `Icons.AutoMirrored.*` for anything directional (back, forward, lists), so RTL works.
- Decorative icons get `contentDescription = null`; icon-only buttons get a translated label,
  and the same label as their tooltip (`IconButtonTooltip`).

---

## 7. Copy

- **Sentence case** for titles, buttons, labels and section headers: "Export data", not
  "Export Data". Proper nouns, acronyms and the names of tabs and system settings keep their
  capitals (Dashboard, Data Saver, CGM, QR).
- **Label the tap.** "Turn on" for a setting; "Install", "Connect", "Download" for an act.
  After the tap, what has changed? The label should say.
- **Typography in strings:** `…` not `...`; an en dash for ranges (`3.9–10.0`, `Sep 23 – Oct 7`);
  `≥ ≤` not `>= <=`; ` · ` between items on one line.
- **Numbers are formatted per locale:** `String.format(Locale.getDefault(), …)` or
  `NumberFormat`. A decimal written into a string ("<7.0%") is wrong in half the languages
  the app ships.
- **Every string is a resource**, in `values/strings.xml` and translated into every
  `values-*` locale. No `Text("…")` literals; product names are the only exception.
- One or two sentences on a card. Implementation details (hosts, protocols, services) belong
  on the advanced screen that configures them, not in everyday copy.

---

## 8. Motion

- Springs from `ExpressiveMotion`: the spatial springs (position, size, corners) may
  overshoot; the effects springs (alpha, colour) never do.
- Top-level navigation fades (200ms).
- Attention motion is brief and repeats with rest: the signal-quality glyph shakes in
  half-second bursts every four seconds, not continuously.
- An animation that runs while nothing changes is a bug: it costs battery and frames.

---

## 9. Accessibility

- Touch targets ≥ 48dp. Icon buttons keep `IconButton`'s minimum size.
- Selectable controls expose their role and state (`Modifier.selectable` / `toggleable`
  with a `Role`); a bare `.clickable` box announces neither.
- Headings use `semantics { heading() }` (`TabScreenHeader` does).
- Text scales with the system font size, up to the system's 2×. Dense surfaces whose
  layout is arithmetic (the dashboard header, chart and stat strip, sensor cards,
  Statistics, the nav labels) wrap themselves in `FontScaleCap`, which stops them at 1.3×.
- Anything holding text gets a minimum height, not a height: `heightIn(min = 56.dp)` on a
  button or a text field, never `height(56.dp)`. A fixed height cuts the text off at large
  font sizes.

---

## 10. Performance

- Collect with `collectAsStateWithLifecycle()`, so a screen behind another app stops
  collecting. (Windows owned by a service keep `collectAsState()`.)
- A value that changes every second or every frame is read in the draw phase
  (`drawBehind`, `graphicsLayer { }`), not in composition.
- Infinite transitions exist only while they show something: create them behind the
  condition, not with a no-op target.
- No JNI or database calls in a composable body; they belong in a ViewModel.
- One `NavHost`. Rotation is handled by the activity, so the composition survives it, as long
  as the tree keeps its shape.

---

## 11. Guardrails

`scripts/ui-guardrails.py` counts the patterns above that can be checked mechanically
(off-grid spacing, off-scale radii, hex colours, `isSystemInDarkTheme()`, `collectAsState()`,
literal `Text("…")` and content descriptions, hand-built top bars, weight overrides, buttons
without the press-morph `shapes`) and
compares each count with `scripts/ui-guardrails-baseline.json`. A count may go down; it may
not go up. When you remove debt, lower the baseline in the same change:

```sh
python3 scripts/ui-guardrails.py             # check
python3 scripts/ui-guardrails.py --update    # after reducing a count
```

A count is debt, so the script leaves out what isn't: commented-out code, colours defined in
`lightColorScheme()`/`darkColorScheme()` or a `*Palette.kt` file, and the floating overlay's
`collectAsState()`/`isSystemInDarkTheme()` (a service window over other apps). Anything else
that breaks a rule on purpose says why, on the line or the line above:

```kotlin
// ui-guardrails: allow text_literal - a version number, the same in every language
```

or around a block with `// ui-guardrails: allow-begin <rule>[, <rule>] - <reason>` …
`// ui-guardrails: allow-end`. Allowed hits are counted as `allowed_exceptions` and ratcheted
too, so a new exception shows up in the baseline diff and gets reviewed.
