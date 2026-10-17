# Hero delta geometry

A single-value hero centers its regular body-large delta beside the primary,
with a 16dp gap. A two-value hero uses that same gap for a support column:
the baseline-aligned dot and secondary occupy its upper line, with the smaller
body-small delta directly below, aligned to the dot's leading edge. Enabling
the delta does not insert anything between the primary and dot or move the
secondary sideways. Three values and constrained widths use the arrow area.
The delta cannot increase measured or intrinsic hero height.

## Render check, 2026-10-17

The production `HeroReadingLayout.kt`, `AppTypography`, and bundled IBM Plex font
were compiled into a separate synthetic preview, rendered in an isolated API 36
Android emulator. The preview shell uses placeholder sensor content and a text
arrow; it does not exercise the full dashboard or any sensor APIs.

![Two-value production typography](../assets/hero-delta/pair-render.png)

The comparison exercises the same intrinsic-height card row with one or two
values and delta disabled or enabled. All four cards measured 195px high at
normal text size and 261px at 150% text size. Enabling delta added zero height
at either setting. These measurements apply to the synthetic shell, not every
complete dashboard configuration.

![Equal-height comparison](../assets/hero-delta/height-comparison.png)

![Equal-height comparison at 150 percent text size](../assets/hero-delta/height-comparison-large.png)

`HeroReadingLayoutTests` verifies measured and intrinsic height invariance across
three glyph sizes, dot/secondary baseline alignment, delta placement below the
support line in LTR/RTL, stable horizontal dot/secondary positions when toggling
delta, and preservation of the primary's width. All four tests and the mobile
debug compilation pass.
