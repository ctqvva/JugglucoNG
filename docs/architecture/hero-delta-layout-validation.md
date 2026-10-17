# Hero delta geometry

One- and two-value heroes keep the delta beside the primary value on the same
vertical center line, in regular body-large type with an 8dp gap. In a pair, the
dot and secondary follow the delta; dot and secondary share a text baseline.
The delta cannot increase measured or intrinsic hero height. Narrow layouts
use the arrow area when the inline composition cannot fit.

## Render check, 2026-10-17

The production `HeroReadingLayout.kt`, `AppTypography`, and bundled IBM Plex font
were compiled into a separate synthetic preview, rendered in an isolated API 36
Android emulator. The preview shell uses placeholder sensor content and a text
arrow; it does not exercise the full dashboard or any sensor APIs.

![Two-value production typography](../assets/hero-delta/pair-render.png)

The comparison exercises the same intrinsic-height card row with one or two
values and delta disabled or enabled. All four cards measured 195 px high in the latest normal-size comparison. Enabling delta added zero height.
The prior height-preserving composition was also checked at 150% text size;
that earlier render does not validate the final inline spacing at that size.
These measurements apply to the synthetic shell, not every complete dashboard
configuration.

![Equal-height comparison](../assets/hero-delta/height-comparison.png)

`HeroReadingLayoutTests` verifies measured and intrinsic height invariance across
three glyph sizes, dot/secondary baseline alignment, vertically centered delta
placement beside the primary in LTR/RTL, and preservation of the primary's width.
All three tests and the mobile debug compilation pass.
