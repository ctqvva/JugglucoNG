# Glucy artwork

<img alt="Glucy" src="lockup-on-light.svg#gh-light-mode-only" width="360">
<img alt="Glucy" src="lockup-on-dark.svg#gh-dark-mode-only" width="360">

The launcher icon keeps the idea of the original JugglucoNG icon: a glucose trace with the sensor sitting on it. On the launcher it sits in a navy rounded square. Next to the name it sits in a round badge.

The wordmark is custom lettering drawn in [`lettering.py`](lettering.py), so no font file is involved. Its details are deliberate, and some look like inconsistencies:

- **Feet.** The `l` and the `y` end in short feet that turn in opposite directions, with different radii.
- **Spur.** The `u` keeps its spur, with a filleted junction.
- **Thinning.** The stroke thins only where the feet turn.
- **Weight.** It is drawn at 15 on light backgrounds and 14 on dark.
- **Spacing.** It was measured and then corrected by eye.

The reasons for each are written at the top of `lettering.py`. Read them before standardising anything.

[`build.py`](build.py) generates everything from one set of shapes and colours:

- `Common/src/main/res/drawable/ic_launcher_{background,foreground,monochrome}.xml`, the adaptive icon including the themed (monochrome) layer
- `Common/src/releasedub/res/drawable/ic_launcher_{background,foreground}.xml`, the second installation in violet
- the SVGs in this folder:
  - icons: [icon](icon.svg), [second installation](icon-dub.svg), [two-tone variant](icon-two-tone.svg) for other material, [monochrome layer](monochrome.svg)
  - wordmarks for [light](wordmark-on-light.svg) and [dark](wordmark-on-dark.svg) backgrounds
  - transparent lockups for [light](lockup-on-light.svg) and [dark](lockup-on-dark.svg) backgrounds; the dark one adds a hairline edge so the badge doesn't merge into dark pages

```sh
pip install shapely
python3 docs/brand/build.py
```

`Common/src/main/ic_launcher-playstore.png` is a 512 px render of `icon.svg`.

The artwork is available under this repository's [GPL-3.0 license](../../LICENSE.txt).
