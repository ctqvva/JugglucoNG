# Morphing navigation experiment

The portrait experiment in PR #608 uses a tonal panel with 8dp side margins and an 8dp gap above the system navigation area. The gesture/button inset is outside the panel, so its entire bottom edge stays above the system gesture line. The scrolling page remains visible behind the side gutters and bottom gap. Resting corners are 32dp at the top and 20dp at the bottom; labels and selected indicator stay fixed.

The primary hero, sensor card, and navigation read one shared `DashboardTrendMorph` holder. `TrendCornerMotion` owns four animated directional weights and one spring configuration. The bar reads those animated weights directly instead of starting independent radius animations. Hero target ranges are preserved. The bar scales changes visibly, up to 16dp from rest (16–48dp top and 4–36dp bottom), with bounds applied to spring overshoot. Flat and Unknown select the resting motion, even with nonzero measured velocity. At an ordinary +/-0.8mg/dL/min slope, the right-corner offsets differ by at least 16dp; the regression test prevents reverting to barely visible 1–2dp changes.

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

The above-gesture placement passed mobile debug compilation, UI guardrails, and `git diff --check`. The shared-motion implementation also passed 26 focused JVM tests (14 trend/morph, 5 dashboard history, 7 architecture gates), using Java 21 and an isolated offline Gradle cache.
