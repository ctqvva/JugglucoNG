# Morphing navigation experiment

The portrait experiment in PR #608 uses a tonal panel with 8dp side margins. The gesture/button inset is outside the panel, keeping its entire bottom edge above the system gesture line. There is no additional bottom margin: the bar has 8dp internal padding at both the top and bottom. The scrolling page remains visible behind the side gutters and system navigation area. Labels and selected indicator stay fixed.

The primary hero, sensor card, and navigation read one shared `DashboardTrendMorph` holder. `TrendCornerMotion` owns four animated directional weights and one spring configuration. The navigation now uses the primary hero's exact corner-radius mapping, including its resting outline and per-corner ranges: 22–52dp top-start, 8–24dp top-end and bottom-end, and 22–46dp bottom-start. Compose fits those radii to the panel height. There is no separate amplification, top/bottom baseline, or animation clock for navigation. Both consumers clamp spring overshoot through the same mapping.

The original Greptile finding about stable readings was valid and is fixed by `trendShapeVelocity`: Flat and Unknown select zero before choosing the shared motion target, even when the measured velocity is nonzero. Tests cover actual Flat slopes of +/-0.4mg/dL/min, Unknown with retained velocity, and a classification change at unchanged velocity. The resting outline now matches the hero; the earlier promise of symmetric 32dp navigation corners has been superseded by this matching geometry.

The main tab lists draw behind the panel. Their scroll-end padding includes the measured panel height; FABs, export confirmation, and dashboard snackbars retain clearance. Dashboard chart expansion uses the unobscured viewport height. Nested detail screens retain their reserved viewport. The theme alone owns the transparent system navigation window.

## Native rendering evidence

The captures use the Android 16 / API 36.1 Medium Phone emulator (1080 x 2400, 420dpi) and the separate application ID `tk.glucodata.navigationpreview`. The isolated renderer compiles the exact production `OverlayNavigationScaffold.kt`, `MorphingNavigationContainer.kt`, `TrendCornerMotion.kt`, `TrendMorph.kt`, `TabIcon.kt`, label helper, and IBM Plex typography. It uses the app's Material 3 version and platform dynamic dark colors.

The top shape probe uses the production hero corner targets. Both it and the production navigation container read the same production motion holder. The fixture cycles simulated slopes 0, +1.2, -1.2, and 0 to show the transition; it contains no CGM data. Colored rows expose the content under the panel and its bottom gap. This verifies the shared shape/animation functions and parent layout, rather than presenting a fabricated CGM screen.

- [Native shared-motion recording](shared-morph-emulator.mp4).
- [Stable shape, five destinations, 1.0x labels](stable-emulator.png).
- [Rising shape, five destinations, 1.3x labels](rising-five-tabs-emulator.png).
- [Falling shape, five destinations, 1.3x labels](falling-five-tabs-emulator.png).
- [Last fixture row scrolled clear of the panel](scroll-end-emulator.png).

The evidence checks shared corner motion, the native parent layout, visible content below the panel, label layout, clearance above the gesture area, and list-end clearance in isolation. Full-app live-data behavior, frame-time performance, older Android system-bar behavior, and three-button navigation remain unverified. The connected Android 10 phone rejected preview installation; its CGM package was not replaced.

The matching geometry and revised padding passed mobile debug compilation, UI guardrails, and `git diff --check`. The shared-motion implementation also passed 26 focused JVM tests (14 trend/morph, 5 dashboard history, 7 architecture gates), using Java 21 and an isolated offline Gradle cache.
