# Glucy artwork

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="banner-dark.svg">
  <img alt="Glucy" src="banner-light.svg" width="640">
</picture>

The launcher icon keeps the idea of the original JugglucoNG icon: a glucose trace with the sensor sitting on it. The wordmark is drawn as strokes with the same round ends as the trace, so no font file is needed.

[`build.py`](build.py) generates everything from one set of shapes and colours:

- `Common/src/main/res/drawable/ic_launcher_{background,foreground,monochrome}.xml`, the adaptive icon including the themed (monochrome) layer
- `Common/src/releasedub/res/drawable/ic_launcher_{background,foreground}.xml`, the second installation in violet
- the SVGs in this folder: [icon](icon.svg), [second installation](icon-dub.svg), [two-tone variant](icon-two-tone.svg) for other material, [monochrome layer](monochrome.svg), [wordmark](wordmark.svg) ([light](wordmark-light.svg)), and the [dark](banner-dark.svg) and [light](banner-light.svg) banners

```sh
pip install shapely
python3 docs/brand/build.py
```

`Common/src/main/ic_launcher-playstore.png` is a 512 px render of `icon.svg`.

The artwork is available under this repository's [GPL-3.0 license](../../LICENSE.txt).
