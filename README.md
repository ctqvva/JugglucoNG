![Logo-icon](Common/src/main/res/mipmap-xxhdpi/ic_launcher_round.webp)

# JugglucoNG

JugglucoNG is an experimental continuous glucose monitoring app for Android.

Originally forked from [Juggluco](https://github.com/j-kaltes/Juggluco) by Jaap Korthals Altes, it continues to evolve with a modern Compose UI using Material 3, a sensor-independent data layer, support for multiple CGM systems, a treatment journal with IOB/eIOB/COB tracking, predictive simulation, a redesigned alarm engine, and bidirectional Nightscout integration.

![Screenshot](juggluco_screenshot.png)

<sub>English · Беларуская · 中文 · Deutsch · Français · Italiano · Nederlands · Polski · Português · Русский · Svenska · Soomaali · Türkçe · Українська · Монгол</sub>

> [!WARNING]
> **Experimental software — not a medical device.**
>
> JugglucoNG may contain bugs and may display incorrect, delayed, or missing data. Do not rely on it as the sole basis for treatment, insulin dosing, diagnosis, or other medical decisions. Always verify clinically significant readings using the manufacturer's official system or another appropriate method.

## Download

**Start here for an Android phone:** the standard JugglucoNG app, for Android 8.0
or newer. The APK supports both 64-bit and 32-bit ARM phones.

[![Download for Android — standard phone APK](https://img.shields.io/badge/Download_for_Android-Phone_APK-2ea44f?style=for-the-badge&logo=android&logoColor=white)][download-phone]
[![Latest regular release][download-version-badge]][download-release]

The download button gets the **latest regular release**, excluding prereleases
and nightlies. These alpha releases are still experimental; see the warning above.

1. Download the APK and open it on your phone.
2. If Android asks, allow installation from the browser or file manager you used.
3. For an existing standard JugglucoNG installation, it updates the app and keeps
   its data. Back up your settings and data before updating.

<sub>[Release notes][download-release] · [Wear OS][download-wear] · [Phone DUB][download-phone-dub] · [Wear OS DUB][download-wear-dub] · [Nightly test builds](https://github.com/ctqvva/JugglucoNG/releases?q=nightly-&expanded=true) · [All releases](https://github.com/ctqvva/JugglucoNG/releases)</sub>

<details>
<summary>Other versions: Wear OS, DUB and nightlies</summary>

The smaller download links above get these variants from the same regular release:

| Version | Use it for | APK filename |
| --- | --- | --- |
| **[Standard phone][download-phone]** | Most Android phone users | `JugglucoNG-<version>.apk` |
| [Wear OS][download-wear] | A Wear OS watch | `JugglucoNG-<version>-wear.apk` |
| [Phone DUB][download-phone-dub] | A second, separate phone app with its own data | `JugglucoNG-<version>-dub.apk` |
| [Wear OS DUB][download-wear-dub] | The matching separate app on a Wear OS watch | `JugglucoNG-<version>-wear-dub.apk` |

Use the standard phone APK to update the standard app, and a DUB APK to update
an existing DUB installation. The watch APKs are installed on the watch.

[Nightlies](https://github.com/ctqvva/JugglucoNG/releases?q=nightly-&expanded=true)
are experimental test builds for trying recent changes. Choose the file ending
in `-phone.apk` for a standard phone; use the other variants only when needed.

</details>

<!-- download-links:start -->
[download-phone]: https://github.com/ctqvva/JugglucoNG/releases/download/1.2.2-Alpha/JugglucoNG-1.2.2-Alpha.apk
[download-wear]: https://github.com/ctqvva/JugglucoNG/releases/download/1.2.2-Alpha/JugglucoNG-1.2.2-Alpha-wear.apk
[download-phone-dub]: https://github.com/ctqvva/JugglucoNG/releases/download/1.2.2-Alpha/JugglucoNG-1.2.2-Alpha-dub.apk
[download-wear-dub]: https://github.com/ctqvva/JugglucoNG/releases/download/1.2.2-Alpha/JugglucoNG-1.2.2-Alpha-wear-dub.apk
[download-release]: https://github.com/ctqvva/JugglucoNG/releases/tag/1.2.2-Alpha
[download-version-badge]: https://img.shields.io/badge/Latest_regular_release-1.2.2--Alpha-blue
<!-- download-links:end -->

## Sensor and data-source support

- Abbott FreeStyle Libre / 2 / 2+ / 3 / 3+
- Dexcom G7 / ONE+
- CareSens Air
- Accu-Chek SmartGuide

- Sibionics GS1 (EU, Chinese, and Hematonix), Sibionics 2
- AiDex X / LinX
- iCan i3 / i6 (Sinocare)
- Anytime / Yuwell
- MQ / Glutec
- Ottai / SyAi

Follower sources: Nightscout and the HTTP API.

Multiple direct sensors can run at the same time. An opt-in handover mode starts the next sensor automatically when the current one reaches its official expiry. Bluetooth fingerstick meters can log readings straight into the journal.

Support for individual sensors is community-developed and may change or stop working as manufacturers update firmware, protocols, or online services.

## Features

**Glucose display.** Material 3 dashboard with trend arrow, delta, and reading history; statistics (time in range, GMI, AGP percentile views, exportable reports) with persistent time ranges; charts in the notification shade; always-on display; floating overlay; home-screen widgets; automatic dark mode.

**Alarms.** Custom alarm engine: low/high with time ranges and episodes, rate-of-change alarms (falling/rising fast), sensor-expiry warnings at configurable thresholds, signal-loss and forecast alerts, follower alerts. Alarms can be spoken (TTS), delayed, or vibrate-first, and a failed delivery does not silently consume the alarm.

**Journal and insulin.** Food database with meal curves, dose calculator, insulin-on-board (IOB/eIOB) and carbs-on-board (COB) tracking, treatment intake from AAPS, treatment sync with Nightscout, quick-add entries.

**Prediction.** On-device predictive simulation of glucose, insulin, and meals, with configurable model profiles.

**Nightscout.** Upload readings and treatments, follow a remote site, and exchange IOB/eIOB/COB through `devicestatus` in both roles (opt-in). Long-acting insulin entries are supported.

**Data.** Sensor-independent local database with export/import, direct import of Juggluco's TSV export, settings export, and non-destructive calibration exports. Per-sensor calibration models with chart and table views.

**Sharing and integrations.** [Clone the app to another phone](docs/clone.md) over LAN or the internet (ICE/TURN, no account needed); `glucodata`-style broadcasts for other apps and watchfaces; Health Connect; Pebble; an [outbound API](docs/outbound-api.md) that pushes readings to chatbots, emergency SMS, or any webhook.

## Building

Requirements: JDK 21, Android SDK with NDK and CMake, and the libjuice submodule:

```sh
git submodule update --init

./gradlew :Common:assembleMobileDebug --no-daemon
--
./gradlew :Common:assembleMobileRelease --no-daemon
```

The app is localized into 15 languages; new user-facing strings go into `Common/src/main/res/values/strings.xml` and every `values-*` locale.

## Version history

See the [Releases page](https://github.com/ctqvva/JugglucoNG/releases) for the changelog of each Alpha build.

## License and credits

GPL-3.0 (see [LICENSE.txt](LICENSE.txt)). Forked from [Juggluco](https://github.com/j-kaltes/Juggluco) by Jaap Korthals Altes. Developed by [ctqvva](https://github.com/ctqvva) with contributions from the community.

**Disclaimer:** This software is provided **"as is"**, without warranty of any kind, to the fullest extent permitted by applicable law. JugglucoNG is experimental software and is **not a medical device**. The authors and contributors make no guarantees regarding its accuracy, reliability, availability, or fitness for any particular purpose. Always verify clinically significant readings using the manufacturer's official system or another appropriate method before making treatment decisions.
