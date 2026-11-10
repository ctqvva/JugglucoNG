# Narrow screens and large fonts

The reported AiDex scan row gave its filter button first claim on the row width,
then laid out the weighted heading in the remaining sliver. A long translation or
large font could turn the heading into a column of individual letters.

- `AdaptiveTrailingRow` keeps a reading width for the text that grows with the font
  (never narrower than its longest word) and moves the action below when the two
  can't share a line. Used by the scan headers (AiDex, Anytime, MQ, Ottai), the shared
  settings, switch, expandable and master rows, the alarm rows, preset selectors, number
  steppers and the glucose range labels, all with the same 12dp text-to-action gap.
- `AdaptiveButtonRow` keeps paired actions at equal widths while every label fits on one
  line; otherwise each button takes a full-width line. Used for account, key, export,
  endpoint and date-sheet actions.
- `ConnectedButtonGroup` keeps the height each screen asks for and grows only when a
  label needs more. Sizing it from intrinsics alone draws every group 48dp tall, because
  intrinsics include the invisible 48dp touch target.
- Text buttons and fields take `heightIn(min)` instead of `height`. Compact wizard
  buttons are 48dp, matching the regular layout.
- Setup status content scrolls; it stays centred when it fits.
- Journal menus use the existing dense-surface font cap and keep chart-menu width
  inside the window.
- Anytime and MQ scan lists filter hidden devices before laying them out, so the
  sensors-only view no longer leaves blank gaps.

The AiDex scan screen keeps its fixed header, edge-to-edge results and pinned
Keys & account row.

## Verification

`AdaptiveLayoutTests` (native-rendered Compose, Robolectric): scan header across
240-600 dp, densities 1-2, font scales 1-2, a wider bold typeface, RTL, and every shipping
locale in both filter states; shared switch rows stay readable and clickable; connected
custom labels grow while a 36dp group stays 36dp; button pairs stack instead of wrapping
labels; short rows stay centred in their minimum height.

Not rendered on a device or emulator.
