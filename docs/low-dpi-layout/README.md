# Narrow screens and large fonts

The reported AiDex scan row gave its filter button first claim on the row width,
then laid out the weighted heading in the remaining sliver. A long translation or
large font could turn the heading into a column of individual letters.

The rule for every fix here: **at normal font size and width, nothing moves.** A layout
only changes once a Row would otherwise break a word into letters.

- `AdaptiveTrailingRow` is the Row it replaces, with that Row's original gap, until the
  text gets less room than its longest word; then the action moves below the text.
  Used by the scan headers (AiDex, Anytime, MQ, Ottai), the shared settings, switch,
  expandable and master rows, the alarm rows, preset selectors, number steppers and
  the glucose range labels.
- `AdaptiveButtonRow` is a Row of `weight(1f)` buttons on the same rule; past it, each
  button takes a full-width line. Used for account, key, export, endpoint and date-sheet
  action pairs.
- `ConnectedButtonGroup` keeps its fixed item height unless a label needs more than
  the button's own minimum and touch target.
- Text buttons and fields take `heightIn(min)` instead of `height`; Material's own
  minimum and touch target do not apply once a minimum is set, so they keep their size.
- Setup status content scrolls; it stays centred when it fits.
- Journal menus use the existing dense-surface font cap and keep chart-menu width
  inside the window.

Screen structure is unchanged: the AiDex scan screen keeps its fixed header, edge-to-edge
results and pinned Keys & account row.

## Verification

`AdaptiveLayoutTests` (native-rendered Compose, Robolectric):

- Normal size matches the old layout: a long title stays beside a wide control,
  short rows stay centred in their minimum height, a 36dp connected group stays
  36dp, `heightIn` buttons keep 32dp and 46dp, weighted button pairs keep equal shares.
  These fail against the first version of this change.
- Large text: scan header across 240–600 dp, densities 1–2, font scales 1–2, a wider
  bold typeface, RTL, and every shipping locale in both filter states; shared switch
  rows stay readable and clickable; connected custom labels grow.

Not rendered on a device or emulator after the rework.
