# Hero delta geometry

The two-value delta belongs to the primary reading. It sits below the separator
dot, within the primary line's height. The dot and secondary value share a text
baseline. The supporting group cannot increase the hero's measured or intrinsic
height. Single-value mode uses a standard centered Row, with width checked before
the optional delta is included; narrow layouts use the arrow area instead.

## Render check, 2026-10-17

The production `HeroReadingLayout.kt`, `AppTypography`, and bundled IBM Plex font
were compiled into a separate synthetic preview, rendered in an isolated API 36
Android emulator. The preview shell uses placeholder sensor content and a text
arrow; it does not exercise the full dashboard or any sensor APIs.

![Two-value production typography](../assets/hero-delta/pair-render.png)

The comparison exercises the same intrinsic-height card row with one or two
values and delta disabled or enabled. All four cards measured 195 px high at
normal text size, and 261 px at 150% text size. Enabling delta added zero height
in either comparison. These measurements apply to the synthetic shell, not to
every complete dashboard configuration.

![Equal-height comparison](../assets/hero-delta/height-comparison.png)

`HeroReadingLayoutTests` verifies measured and intrinsic height invariance across
three glyph sizes, dot/secondary baseline alignment, delta placement beside the
primary and below the dot in LTR/RTL, and preservation of the primary's width.
All three tests and the mobile debug compilation pass.
