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
  take their `…Defaults.shapes()` too. A custom silhouette goes through the same overload,
  `shapes = ButtonDefaults.shapes(shape = …)`, so it still squeezes; a bare `shape =` does
  not, and the ratchet counts it with the buttons that have no `shapes`. Colour a filled icon
  button with `filledIconButtonColors`, never a `.background()` behind a plain `IconButton`:
  the press shape would squeeze inside a circle that stays round.
- **Connected buttons** (Reconnect | Disconnect) use `ConnectedButtonShapes`: pill ends
  outside, 4dp where the halves meet; pressed, the outer ends squeeze to 8dp
  (`LeadingPressed` / `TrailingPressed`). A split button is material3's `SplitButtonLayout`, and
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

Slider headings have their own three-row table under Components › Sliders.

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

Destructive rows (`DangerItem`) start with `surfaceContainerHigh`. Medium severity blends
16% `tertiaryContainer` into that surface and uses a `tertiary` icon. High severity blends
40% `errorContainer` and uses opaque `onErrorContainer` text and icons for contrast.
These muted fills keep the risk hierarchy visible without full-strength container colors.

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
| A setting with its own inline controls (slider, button group) | Control card, composed locally; see below | `DataSmoothingSettingsScreen.kt` |
| The label and value over a slider | `SliderHeader` + `SliderHeaderStyle`; `SettingsSlider` in a `SettingsControlsCard`; see Sliders | `components/SliderHeader.kt` |
| A destructive row | `DangerItem` | same |
| A thing the app is connected to (Clone link, meter) | `DeviceCard` + `DevicePauseButton`; see Device cards | `components/DeviceCard.kt` |
| Group label | `SectionLabel` (24 above, 8 below) | same |
| Leading icon of a row/card | `IconTile` | `components/IconTile.kt` |
| A sensor family, shown as itself | `SensorArtTile` in a list, `SensorWizardHero` at the top of every wizard's main step; see Sensor art and wizard heroes | `components/SensorArt.kt`, `components/SensorHeroDrawings.kt`, `components/SensorArtTile.kt` |
| 2–5 exclusive choices | `ConnectedButtonGroup` (material3 `ToggleButton`s, connected shapes) | `util/ConnectedButtonGroup.kt` |
| Two buttons as one control | `ConnectedButtonShapes` | `components/ConnectedButtonShapes.kt` |
| An action with a related second action | `SplitButtonLayout` + `SplitButtonDefaults` | material3 |
| A short wait with no progress to show | `LoadingIndicator` | material3 |
| A progress bar | `LinearWavyProgressIndicator` | material3 |
| An icon-only button | the button inside `IconButtonTooltip(label)`, label = its content description | `components/IconButtonTooltip.kt` |
| A "+" that offers several kinds of thing | `FloatingActionButtonMenu` + `ToggleFloatingActionButton` | material3; journal items 44dp, 8dp apart, labelLarge, 16dp ends; FABs keep the spec 16dp corner and 16dp inset; bottom-centre on the dashboard with centred pills, between timestamps and right-aligned glucose values; primaryContainer/onPrimaryContainer at rest, primary/onPrimary when open (the spec default); opaque semantic tints over a dismissible bottom-up scrim that fades into `surface`, never black (black over a light theme reads as a grey smear): transparent through the top 35%, 80% at 70%, and 92% at the bottom; use Glucose in action labels and entry-sheet titles; press and slide off the button, or hold it, and lift on an item to pick it, as in a menu (`JournalExpandableFab`) |
| Add at a point on the chart | `JournalFloatingActionMenu` | One column of the FAB menu's 44dp items, one width, centred on the tapped time's dot (where the chart draws it, followed as the screen moves) and 24dp beside it, left past the middle, inside the plot (top to time chip) whenever it fits; only a column taller than the plot rises past it, never past the window. Items spring out of the dot, middle first, (`motionScheme.fastSpatialSpec`) and fold back into it; corners on the dot's side sharpen with nearness to it; the long-pressing finger can slide onto an item, pressed under it, and lift there to pick it |
| Actions on a multi-selection | `HorizontalFloatingToolbar`; the screen keeps its top bar | material3 |
| Pick one value from a short list | `DropdownMenuPopup` + `DropdownMenuGroup` + `SelectableDropdownMenuItem` | material3 |
| Landscape navigation | collapsed `WideNavigationRail` (portrait: `ShortNavigationBar`) | material3 |
| Switch | `StyledSwitch` | `components/StyledSwitch.kt` |
| Sheet | `StableModalBottomSheet` | `components/StableModalBottomSheet.kt` |

Waiting: `LoadingIndicator` for a few seconds with nothing to count (a network check, a list
loading). Work that can run longer or knows its progress keeps a progress indicator. A spinner
inside a button stays a small `CircularProgressIndicator`; the loading indicator's shapes don't
read at 18dp.

### Sensor art and wizard heroes

A sensor family is shown as the device itself, never as a logo or a generic icon.

**The drawings** (`SensorArt.kt`)

- Drawn from the makers' photos and published dimensions, in theme roles, with no maker's
  name. They are stylised, not photographs: one camera and one light for every drawing, white
  or grey plastic in the theme's neutrals, the colour of what they lie on thrown back into the
  shade.
- The device alone, never its adhesive patch. A family with two current devices is drawn as a
  pair at their true sizes against each other (Sibionics GS1 and Sibionics 2, iCan i3 and i6,
  Anytime CT4 and 5 Pro).
- Each drawing is built once per size and theme and kept, in memory and in the app's cache, so
  nothing redraws or blinks when a screen opens. Add a `SensorType` and the compiler asks for
  its drawing.

**The list tile** (`SensorArtTile`)

- One soft square for every row, the same in the launch cards and the picker sheet, so the
  sensors' own outlines tell the rows apart. No family gets its own colour. Its colour is
  `sensorBackdropColor`, the one the wizard heroes use, so the tile you tap and the hero it
  opens match: `secondaryContainer`, opaque in light themes and 65% over the card in dark ones.
- The sensor is drawn bigger than the square and lies across its upper edge a few degrees off
  straight, clear of the title. Pressing the card rounds the square, pushes the sensor in and
  squares it to the row.

**The wizard hero** (`SensorWizardHero`)

- Every wizard's main step opens with one, never a bespoke card. The sensor and a phone acting
  out how the family connects stand in front of one shape, never tailored to a sensor:
  - the shape is drawn fresh each time a hero is shown (`BackdropStyle`), one of: a soft hull
    round the pieces' boxes, a capsule spanning the scene, a bean (a disc behind the sensor and
    one behind the phone joined by tangents), or a superellipse, each at a random tilt within
    5°. A tap trades it for a different style. No single style is the only face a wizard has;
  - every style is built round the same boxes (one per piece: the sensor, the pack, the phone
    at its lean), so each fits the scene it is given. A new style must be rendered against
    every family before it ships;
  - the pieces are drawn 1.1 times the size the shape is built for and stand 1.05 times as far
    apart, so they extend out of it. Every piece stays inside the hero's own box (1.6 tile
    high); nothing reaches into the top bar or a progress bar above it;
  - fill: `sensorBackdropColor`, the list tiles' colour;
  - the heading is 12dp under the hero's box.

  Rules:
  - The sensor stands free: nothing is centred under it (a disc or square under a sensor reads
    as its adhesive patch).
  - No strokes and no second shape.
  - Rejected by the owner (2026-11-14/15): a cookie halo, a colour-blocked pill with a success
    burst, a 3D stage, the list tile reused, rings drawn as lines, a fixed 280 x 148 box with
    hand-drawn blobs, a free-form silhouette ("blobs of randomness"), one rounded rectangle per
    family, a Medtrum mound cut flat (read as a doorstop), small grounding discs that left
    tight `_|` corners round the phone, a shape fitted round the scene with the pieces inside
    it, a shape shrunk to a floor under them (the pieces should grow, not the shape shrink), a
    per-family lean and roundness (a slanted slab behind Glutec), and a 1.24x size-up that ran
    the phone into the progress bar. The fix for a bad corner is the rule, not another disc.
- The phone acts out how the family connects; its screen says it in one mark:

  | Family connects by | Phone | Screen | Beside them |
  |---|---|---|---|
  | NFC (Libre; Ottai China and Syai) | Held over the sensor, arcs running from its edge to the sensor | The NFC mark, pulsing once | — |
  | A printed code (Dexcom, iCan, Accu-Chek, CareSens Air, Sibionics) | Standing beside what the code is printed on | The viewfinder sweeping, then closing on the code | What the user points the camera at: the G7 applicator, the iCan i3 pack and i6 kit, the SmartGuide applicator turned cap up, the CareSens pouch. Sibionics has none: its code is on the box |
  | Bluetooth (AiDEX, Anytime, MQ, Ottai Global, Medtrum) | Standing apart, arcs running from the sensor to it | The Bluetooth mark | — |

  The phone has a rim in `outline`, a `surfaceContainerHighest` screen and `primary` marks in
  both themes (a lit white screen in dark themes was rejected). Its marks are drawn at screen
  pixels, never scaled up from drawing units, so they stay sharp.
- A step whose choice changes the picture passes it in: `pairLead` brings the chosen sensor of
  a pair to the front (Sibionics picker), `companion` swaps the phone's story (Ottai's region).
  The change animates; it is never a cut.
- Text: `title` is the step's instruction, kept short. The family's name is already in the
  top bar, so a title that only names the family again ("Choose a Sibionics sensor") says
  nothing; say what to do ("Choose sensor variant"). Option names keep the full product name
  (Sibionics GS1 Europe). A subtitle only when the title can't
  carry the instruction alone.
  A step whose own header already says it shows the picture alone.
- Room: `rememberSensorHeroCompact` folds the hero into a row (a small picture beside its
  title) whenever the keyboard is up, the screen is under 600dp tall, or a camera or long list
  shares a screen under 860dp. The fold animates on the spatial spring.
- Size: 128dp per tile side on wide windows, 112dp on phones, 56dp folded. The stage is
  2.1–2.75 tile sides wide, depending on how many pieces it holds.

**Hero motion and touch**

- The entrance plays the first time a hero opens after launch, and when a new companion comes
  in (Ottai's region): the sensor springs up and the shape opens out from it on an
  overshooting bounce (damping 0.45), the phone and what the code is printed on come in, and
  the phone does its one beat. It is silent. Later visits, rotation, and the fold between sizes
  find it already in place. Nothing runs at rest.
- It is a toy, not a control, so it adds nothing for accessibility services. Under the finger
  the sensor squares up and the shape gathers in a little. A tap never makes anything vanish
  or jump:
  - on release, one click (`NavigationTick.play`);
  - the shape breathes out and melts into its next style over 420ms, the old one shrinking
    and fading as the new one swells in;
  - the sensor hops 7% and settles, and the phone leans in towards it and back, on a soft
    spring (damping 0.55, stiffness 260);
  - the signal plays again and ends on one light click (`NavigationTick.playLight`, 0.3).
- In the hero, a pair is drawn 1.1 times larger with its two sensors 1.3 times further apart
  than in the list, and the phone stands further off.
- A pair trading places clicks once as they pass.

### Device cards

Everything the app is connected to (a Clone link, a meter) is one card (`DeviceCard`), never a
settings row with a sheet behind it:

- **Header:** the name, its state in words under it, and Pause / Play at the far edge
  (`DevicePauseButton`; the way back to running is the filled one).
- **Facts:** one quiet panel. What is checked most, and only this device has, is always in it
  (a connected Hybrid link's route, the other phone's address and since when; a TCP link's
  address); a tap on the panel adds the rest to the same table, and a
  second tap folds it. The chevron sits centred under the facts, never in a column beside them.
- **Controls:** ordinary buttons in one row, 8dp apart: [QR code] [Edit] [Delete], Delete last
  and outlined in the error colour. Equal widths while the labels fit; icons give way before a
  label is cut; only when bare labels can't share a line does each take its own.
- **Under the row:** what a control opens inline (a QR code; a meter's name and code), then a task or setting only this device
  has (History recovery; a Satellite's recheck wait), full width.
- The 20dp status-card corner on the settings surface; paused, `surfaceContainerLow`. Cards stand
  8dp apart.

### Row, card, dialog or sheet

- **Row** — a persistent setting or a destination. Its subtitle carries the current value.
  Group related rows with `CardPosition` and a 2dp gap.
- **Card** — status: where things stand, and the thing to do about it. Title first, actions
  at the bottom. A card holding one line of text is a row in costume; use a caption.
- **Control card** — one setting whose control doesn't fit a row's trailing slot: a slider,
  a `ConnectedButtonGroup`, or a switch that reveals one. Not the same as a
  `SettingsSwitchItem` (one setting, one trailing switch) or a `MasterSwitchCard` (the whole
  feature on or off, at the top of its screen). A control card is a local composition, not a
  shared component:
  - A `Card` in `shapes.extraLarge` on `surfaceContainerLow` (`surfaceContainerLowest` while
    the feature is off), with no elevation. One surface throughout, with no divider, inner
    card, header surface or icon tile.
  - 16dp padding on every side. Title in `titleMediumEmphasized`, then 4dp, then the
    supporting line in `bodyMedium` / `onSurfaceVariant`. A value chip or `StyledSwitch` can
    trail the heading, 12dp from the text.
  - The control sits 16dp under the heading, and a `bodySmall` caption sits 12dp under the
    control. A revealed section under a heading switch adds 16dp top padding to the heading's
    16dp, so its controls stay clear of the heading's press highlight. With 16dp horizontal
    and bottom padding, it lives inside `AnimatedVisibility`, so the card closes to the
    heading when the section is hidden.
  - A heading switch makes the whole heading its touch target (`toggleable`,
    `Role.Switch`) with `indication = ripple(bounded = true)`, and passes its
    `interactionSource` to the switch (`onCheckedChange = null`), so both show the press.
    The heading spans the card edge to edge. Its modifiers go in this order:
    `clip(shape)`, then `toggleable`, then `padding(16.dp)`. The shape is the card's shape
    while collapsed, and only the top corners while expanded. When the feature is off,
    every control in the card is disabled, not only dimmed.
  - Examples: Smoothing window (slider) and Fewer readings (switch that reveals a
    multi-select group).
- **Dialog** — a decision that blocks: confirmation, short text entry, 2–4 exclusive options.
- **Bottom sheet** — a task with its own content, or one that needs the screen behind it.

### Sliders

A slider has one heading: what it sets at the start, its current value at the end, on one
line, and the slider 4dp under it (`SliderHeaderGap`). Build it with `SliderHeader`; in a
`SettingsControlsCard`, `SettingsSlider` does it for you.

| `SliderHeaderStyle` | Label | Value | Use |
|---|---|---|---|
| `Standard` (default) | `titleMedium`, `onSurface` | `titleMediumEmphasized`, `primary` | A slider among settings rows: control cards, grouped stacks, sheets, dialogs |
| `Compact` | `bodyMedium`, `onSurface` | `bodyMediumEmphasized`, `primary` | Inside an alert card, whose own rows are set in `bodyMedium` (`DurationSlider`, a secondary threshold) |
| `Glucose` | `titleMedium`, `onSurfaceVariant` | `titleLargeEmphasized`, `onSurface` | A glucose threshold that is the point of its card: Glucose range in Dashboard and charts, an alert's own threshold |

The rule behind the table: the label takes the type of the rows beside it, so a slider reads
as one more setting of its group, and the value is the same style made emphasized. Only
`Glucose` lets the number outrank its label, because there the number is a reading.

- **The value is never written into the label.** No "Opacity: 100%" strings: the label is
  its own resource and the value is formatted on its own (`percentage_format`, `dp_value`,
  `sp_value`, a plural). The value then sits at the same end of every slider in a card, and
  translations don't have to place a colon.
- Values are tabular (`tnum`) and rounded, not truncated: 1.4999 is 150%, not 149%.
- A long translated label wraps. At large font sizes the value moves under the label
  (`AdaptiveTrailingRow`) instead of squeezing it.
- Pass the slider's `enabled` to the header, so the text dims with the track.
- A supporting line under the label (`supportingText`, `bodyMedium` / `onSurfaceVariant`) is
  for context the value alone doesn't give, like the sensor's own figure beside an override.
- A slider in an icon row (Voice speed and pitch) puts the header beside the `IconTile`,
  where a row's text would go, and the track spans the card under both.

Deliberate exceptions, each an owner design:

- **A control card that is one slider** (Smoothing window): the card heading carries the
  title and its description in the control-card style, the value is a chip trailing the
  heading, and the track's two ends are captioned under it. The Freeze displayed values
  dialog has the same shape.
- **A time range** (alert Active time range): a `RangeSlider` whose values are the Start and
  End chips under it.
- **A scrubber** (prediction inspector): no heading; what it picks is shown above it.

Material 3 Expressive specifies the track, handle, stops and the value indicator shown while
dragging, but says nothing about a label or value that stays on screen. This rule follows
the list-item anatomy instead (headline at the start, trailing text at the end), which is
what the rows around a slider already use.

### Actions

- An action is a **button**, never a settings row. A row is a place or a setting.
- Every interactive surface shows visible press feedback. Don't pass `indication = null`
  unless another press feedback is implemented and seen working.
- Emphasis tracks importance: `Button` for the one you want pressed, `FilledTonalButton` or
  `OutlinedButton` for the rest, `TextButton` in dialogs. A screen-level action spans the
  width at 56dp.
- Destructive actions confirm first, and say what they destroy.
- A pair of actions that each open an inline panel (Clone's Send readings | Get readings)
  stays the connected group, with the group's own shapes, colours and label size. Only at
  rest is the icon larger (24dp) and over the label; opening one moves the icon back beside
  the label on an arc, and the group returns to 56dp. Opening never scrolls the screen
  (`ClonePairButtons`).
- State is said in words with a plain glyph (linked, linking, off), never a coloured dot.

### Icons

- The icon set is **Material Symbols Rounded**, as `Symbols.<Name>` (outlined) and
  `Symbols.<Name>Filled`, in `ui/symbols/Symbols.kt`. The file is generated: add the glyph's
  name and style to `scripts/material-symbols.txt` (with `mirrored` for anything directional)
  and run `python3 scripts/material-symbols.py`. Browse names at fonts.google.com/icons. The
  frozen `material-icons` libraries are not a dependency any more (guardrail `material_icons`).
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
- Navigation bar and rail keep the stock in-place indicator; the chosen icon acts out its
  glyph once (`TabIcon`), never on launch or rotation. A switch ticks with
  `NavigationTick`, one `PRIMITIVE_CLICK` at 0.5, only on vibrators that support it.
- Haptics answer the user, never the app: a click when something they touched gives way
  (`NavigationTick.play`), one light click when something they started finishes
  (`NavigationTick.playLight`, 0.3). An entrance that plays by itself is silent.
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
- A label beside an action uses `AdaptiveTrailingRow` when the action could crowd out
  the text. It keeps a reading width that grows with font size (and is never narrower than
  the longest word) and moves the action below when needed. Its slots must support
  intrinsic measurement; keep lazy lists and `BoxWithConstraints` outside them. Sibling
  action buttons that share a line equally use `AdaptiveButtonRow`: equal widths while
  every label fits on one line, one full-width line each when one would wrap. Don't use a
  `FlowRow` of weighted buttons for this; it breaks lines on the shortest word, so the
  labels wrap inside the buttons instead.
- Journal menus share the chart's 1.3× font cap because their positions follow the chart
  geometry. Chart menu width stays inside the window; long labels ellipsize on one line.

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
