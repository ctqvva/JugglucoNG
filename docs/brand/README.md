# Glucy artwork

<img alt="Glucy" src="lockup.svg" width="360">

The launcher icon keeps the idea of the original JugglucoNG icon: a glucose trace with the sensor sitting on it. On the launcher it sits in a navy rounded square. Next to the name it sits in a round badge.

The wordmark is custom lettering drawn in [`lettering.py`](lettering.py), so no font file is involved. Its details are deliberate, and some look like inconsistencies:

- **Feet.** The `l` and the `y` end in short feet that turn in opposite directions, with different radii.
- **Spur.** The `u` keeps its spur, with a filleted junction.
- **Thinning.** The stroke thins only where the feet turn.
- **Weight.** It is drawn at 15 on light backgrounds and 14 on dark.
- **Spacing.** It was measured and then corrected by eye.

The reasons for each are written at the top of `lettering.py`. Read them before standardising anything.

## Files

| File | Use it for | Don't use it for |
| --- | --- | --- |
| [`lockup.svg`](lockup.svg) | READMEs, docs, release notes, any page that may be light or dark | sizes below 120 px wide (use the icon) |
| [`wordmark-on-light.svg`](wordmark-on-light.svg) | the name alone on white or light grey | dark backgrounds |
| [`wordmark-on-dark.svg`](wordmark-on-dark.svg) | the name alone on navy, black or dark grey | light backgrounds |
| [`icon.svg`](icon.svg) | the app icon outside Android: avatars, store listings, favicons | |
| [`icon-dub.svg`](icon-dub.svg) | the second installation (`releasedub`) only | anything that means "Glucy" in general |
| [`icon-two-tone.svg`](icon-two-tone.svg) | large renders where a split background helps, such as banners | the launcher (it stays flat) |
| [`monochrome.svg`](monochrome.svg) | single-colour contexts: themed icons, notifications, stamps | |
| [`social-preview.png`](social-preview.png) | the repository's social preview (Settings → Social preview), link cards | |
| `Common/src/main/ic_launcher-playstore.png` | the 512 × 512 store icon | |

Inside the app the lockup is `GlucyLockup` (`Common/src/mobile/java/tk/glucodata/ui/components/GlucyLockup.kt`), shown on the Dashboard's welcome screen and at the bottom of Settings. It follows the theme the way the launcher's themed icon does: the badge is `primaryContainer` with the monochrome layer in `onPrimaryContainer`, and the wordmark takes a text colour. With dynamic colour it matches the home screen, so the app never shows a navy badge on, say, a green theme. The fixed navy colours are for places the app doesn't theme. The wordmark drawables (`Common/src/mobile/res/drawable/glucy_wordmark_{light,dark}.xml`) are white and tinted at runtime, one unit per dp with a 2-unit margin.

The Android launcher resources (`Common/src/main/res/drawable/ic_launcher_*.xml`, `mipmap-anydpi-v26/`, and the violet set in `Common/src/releasedub/`) and the wordmark drawables are generated too. Don't edit them by hand.

The lockup is one file for both themes. GitHub's `<picture>` and `#gh-dark-mode-only` switches follow the operating system rather than GitHub's own theme setting, so a pair of files shows the wrong one for some readers. One file that works on both is more reliable.

## Colours

| Token | Value | Where |
| --- | --- | --- |
| `navy` | `#0D1430` | launcher background, lockup badge, sensor dot, wordmark on light |
| `trace` | `#5B86F5` | launcher trace |
| `trace-muted` | `#6A89D7` | lockup wordmark and badge trace: `trace` with saturation × 0.65 at the same luminance |
| `sensor` | `#F5F7FB` | sensor body |
| `rim` | `#C9D2E3` | sensor rim |
| `paper` | `#F0F3F8` | wordmark on dark |
| `card` | `#141E3F` | social preview background, a lifted navy so the badge still reads as a disc |
| `dub-bg` / `dub-trace` | `#1A1236` / `#9C86F6` | second installation |
| `two-tone` | `#0B1020` above, `#121B36` below the trace | two-tone variant |

Contrast (WCAG): `trace-muted` is 3.4 : 1 on white and 5.6 : 1 on GitHub's dark `#0D1117`, which clears the 3 : 1 needed for large graphic text on both. `trace` on `navy` is 5.3 : 1. `navy` on white is 18.1 : 1, and `paper` on `#0D1117` is 17.0 : 1.

Keep the colours as they are. Don't add gradients, glows, shadows or outlines, and don't recolour the trace per theme.

## Using the lockup

- **Proportions.** The badge diameter is the unit. The wordmark's cap height is 0.43 of it, and the gap between badge and wordmark is 0.28. Don't rescale the parts separately or move the wordmark under the badge.
- **Clear space.** Leave at least the wordmark's cap height (0.43 badge diameters) free on every side. The SVG's own 3-unit margin doesn't count towards it.
- **Minimum size.** The lockup should be at least 120 px wide on screen, which makes the badge about 40 px. Below that the sensor ring and the `y` foot fill in, so use the icon on its own.
- **Icon minimum.** The icon holds up to 24 px. At 16 px the sensor dot disappears, which is acceptable for favicons but nothing larger.
- **Weight.** Use the wordmark file that matches the background. The dark version is a step lighter (14 against 15) because light strokes on dark bleed outwards. Don't apply faux bold or outlines in either direction.
- **Name in text.** In running text the name is "Glucy". Don't set it in the wordmark's style with a font, and don't write it in capitals.

## Building

[`build.py`](build.py) generates every file listed above from one set of shapes and colours, and writes the same bytes on every run.

```sh
pip install shapely resvg-py
python3 docs/brand/build.py
```

resvg-py is only needed for the PNGs (the store icon and the social preview). Without it the SVGs and Android resources are still written.

A 1024 × 500 feature graphic and a 180 × 180 touch icon aren't generated, because the project has no store listing metadata or website that would use them. If one is added, render it in `build.py` from the same shapes rather than drawing it separately.

The artwork is available under this repository's [GPL-3.0 license](../../LICENSE.txt).
