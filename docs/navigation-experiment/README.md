# Morphing navigation experiment

The portrait experiment in PR #608 uses a broad tonal panel inspired by the transport-app reference: 8dp side margins, 32dp top and 12dp bottom resting corners, neutral labels, and a colored selected indicator. The system navigation inset is inside the panel. Its outline follows the hero's directional corner mapping and spring with smaller changes; item positions stay fixed. Flat and Unknown classifications select the resting outline even when the measured velocity is nonzero.

## Native rendering evidence

These screenshots were captured on the Android 16 / API 36.1 Medium Phone emulator (1080 × 2400, 420dpi), using a separate application ID, `tk.glucodata.navigationpreview`. The isolated renderer compiled the exact production `MorphingNavigationContainer.kt`, `TrendMorph.kt`, `TabIcon.kt`, label helper, and IBM Plex typography. It used Material 3 from the app's Compose BOM and the platform dynamic dark color scheme. The body explicitly identifies the simulated inputs.

- [Stable, four destinations, 1.0x labels](stable-emulator.png).
- [Rising at 1.5 mg/dL/min, five destinations, 1.3x labels](rising-five-tabs-emulator.png).
- [Falling at -1.5 mg/dL/min, five destinations, 1.3x labels](falling-five-tabs-emulator.png).

The captures verify the native panel outline, label layout, and gesture inset in isolation. They do not verify the full CGM app, live glucose updates, animation performance, older Android system-bar behavior, or three-button navigation. The connected Android 10 phone rejected preview installation with `INSTALL_FAILED_USER_RESTRICTED`; its CGM package was not replaced.

Local validation also passed mobile debug compilation, 24 focused JVM tests (12 trend/morph, 5 dashboard history, 7 architecture gates), UI guardrails, and `git diff --check` using Java 21 and an isolated offline Gradle cache.
