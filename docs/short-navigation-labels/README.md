# Short navigation label gutters

PR #607 reserves 8dp on each side of every portrait navigation label before `TextAutoSize` measures its available width. The font range, single-line ellipsis, icon positions, and full-width selectable item bounds are preserved. This covers both four destinations and the optional fifth Journal destination. The landscape rail is unchanged.

Material 3 1.5.0-beta01 gives top-icon items vertical padding and measures their labels against the full equal-weight item width. It supplies no horizontal label gutter. The fix therefore belongs in our label content, rather than padding the selectable item or placing margins outside the bar.

## Native evidence

These Android 16 / API 36.1 emulator captures use a separate application ID (`tk.glucodata.navigationpreview`), the exact updated production label helper, `TabIcon`, IBM Plex typography, and the same `ShortNavigationBar` / `ShortNavigationBarItem` configuration as `MainNavigation.kt`. Label strings come from the repository resources. The fixture contains no CGM code or data.

- [Russian, four destinations](russian-four.png).
- [Russian, five destinations](russian-five.png), matching the count in the reported screenshot.
- [German, five destinations, requested 2.0x font scale](german-five-large.png).
- [French, four destinations, requested 2.0x font scale](french-four-large.png).
- [RTL, Arabic configuration, five destinations, requested 2.0x font scale](arabic-five-large.png).

The Arabic configuration uses the current repository labels, which are English for these destinations; this verifies RTL placement, not Arabic-script text.

Large-font cases apply the app's existing 1.3x dense font cap. Long labels shrink within the existing labelSmall/labelMedium range and ellipsize if necessary. The captures verify inset labels rather than allowing them to touch the screen or neighboring items.

The [native accessibility bounds](native-label-bounds.json) confirm at least 21px (8dp at 420dpi) between every Russian label and each side of its item. The outer selectable items still reach x=0 and x=1080. Taps at x=1 and x=1079 selected Statistics and Settings respectively; the [right-edge tap capture](right-edge-tap.png) shows the selected Settings route.

Validation: Java 21 mobile debug Kotlin compilation, UI guardrails, and `git diff --check` passed. Native accessibility bounds and edge taps were checked in the isolated fixture. Full-app physical-device behavior remains unverified.
