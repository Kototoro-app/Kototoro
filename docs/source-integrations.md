# Source Integrations

Kototoro supports multiple external source ecosystems in addition to its own built-in parsers. This page focuses on the practical setup flow: where to install or import sources, where to manage them later, and where they appear in daily use.

## Overview

Kototoro can work with:

- Built-in sources (native Kototoro parsers + Kotatsu-Redo parsers)
- Mihon manga extensions
- Aniyomi video / anime extensions
- IReader novel extensions
- Legado JSON sources
- TVBox JSON sources

After setup, external sources appear in `Browse -> Content Sources` and are used in the same way as built-in sources for browsing, search, favorites, reading, or playback.

## Important Naming Note

In Simplified Chinese builds, the relevant settings page is labeled `Settings -> Content Sources`.

In some English and older localized builds, the same page may still be labeled `Settings -> Manga sources`. That label is not fully accurate anymore because the page also manages video and JSON-based content sources.

## Key Repositories

These repositories are the common entry point for real-world external-source setups.

### Mihon / Tachiyomi-style manga repositories

- [Keiyoushi Extensions](https://github.com/keiyoushi/extensions-source)
- [Yuzono Tachiyomi Extensions](https://github.com/yuzono/tachiyomi-extensions)
- [LittleSurvival CopyManga Copy20](https://github.com/LittleSurvival/copymanga-copy20) for Chinese-site coverage

### Aniyomi video repositories

- [Kohi-den Extensions Source](https://github.com/Kohi-den/extensions-source)
- [Yuzono Anime Extensions](https://github.com/yuzono/anime-extensions)

### Legado reading repositories

- [XIU2 Yuedu](https://github.com/XIU2/Yuedu)

## Built-In And Kotatsu-Redo Parsers

Kototoro includes its own native parsers and additionally integrates the full Kotatsu-Redo parser library. These sources are available out of the box without any additional installation.

- Sources appear automatically in `Browse → Content Sources`
- Parser updates are bundled with app updates
- Cloudflare-protected sources are handled automatically through headless WebView resolution or the interactive browser challenge flow

## Mihon And Aniyomi Extensions

Mihon and Aniyomi integrations are extension-based. Kototoro detects compatible extension APKs installed on the device and exposes their sources directly inside the app.

### How It Works

- Mihon and Aniyomi sources are imported by detecting installed extension APKs.
- You can install extension APKs outside Kototoro and let Kototoro detect them.
- You can also configure compatible extension repositories inside Kototoro, then install, update, or uninstall extensions without leaving the app.

### Setup Flow

1. Open `Settings -> Content Sources -> Extensions`.
2. Choose the right tab:
   - `Manga` for Mihon
   - `Video` for Aniyomi
3. Add a compatible extension repository if you want in-app browsing and installation.
4. Install the extensions you need:
   - either in Mihon / Aniyomi or by sideloading the APK
   - or directly in Kototoro from the configured repository
5. Reopen Kototoro or refresh the extensions screen if a newly installed extension does not appear immediately.
6. Go to `Browse -> Content Sources` and use the detected sources like built-in ones.

### Best Use Cases

- Mihon for manga-heavy workflows
- Aniyomi for anime / video workflows
- Users who want one app to manage installed extensions and content access together

## IReader Extensions

IReader integrations work similarly to Mihon — Kototoro detects IReader extension APKs installed on the device and loads their novel sources.

### Setup Flow

1. Install IReader extension APKs on your device.
2. Open Kototoro — the extensions are auto-detected.
3. Go to `Browse → Content Sources` and use the detected novel sources.

### Best Use Cases

- Novel-oriented workflows
- Users who already maintain IReader extensions for novel sources

## Legado And TVBox JSON Sources

Legado and TVBox integrations are JSON-based. Instead of detecting extension APKs, Kototoro imports source definitions from a JSON file or a JSON URL.

### What You Need

- A local JSON file, or
- A reachable JSON URL

### Setup Flow

1. Prepare the Legado or TVBox JSON source file, or copy the JSON URL.
2. Open `Settings -> Content Sources -> Import JSON Sources`.
3. Select the correct source type:
   - `Legado`
   - `TVBox`
4. Import the source by one of these methods:
   - select a local JSON file
   - paste the JSON content directly
   - use `From Online URL`
5. After import, open `Settings -> Content Sources -> JSON Sources Directory`.
6. Review, enable, disable, edit, or remove imported JSON sources there.
7. Open `Browse -> Content Sources` to use the imported sources like built-in ones.

### Best Use Cases

- Legado for novel-oriented workflows and reading-source collections
- TVBox for JSON-based video source collections
- Users who maintain source definitions as files or URLs instead of APK extensions

### TVBox Runtime Compatibility

TVBox repositories can mix several runtime styles in one JSON file. Kototoro handles them as a compatibility spectrum:

- direct media, playlists, text live lists, and simpler CMS-style APIs are the most reliable
- `type = 4` JavaScript sources use the built-in QuickJS bridge, but advanced JS dependencies are still partial
- ordinary `type = 3` / `csp_*` JAR spiders use the local JAR spider runtime and are still being expanded
- Guard-native JAR spiders are not treated as ordinary JAR failures because they can depend on native/JNI guard behavior

Read [TVBox Runtime Compatibility](./reference/tvbox-runtime.md) for the current support matrix and diagnostic policy.

## What Happens After Import

Regardless of source type, the practical result is the same:

- Installed or imported sources become available from `Browse -> Content Sources`
- They can be enabled, disabled, and managed from the relevant settings screen
- Once enabled, they participate in normal browsing and content access just like built-in sources

## Expectations And Limits

- Source availability depends on what is installed or imported on the device.
- Mihon, Aniyomi, and IReader compatibility depends on the extension version and upstream website behavior.
- Legado and TVBox compatibility depends on the JSON definition quality and upstream site stability.
- TVBox support is still partial for some site types. Direct media, playlist-based sources, and simpler CMS configurations work better than advanced JavaScript, ordinary JAR spider, or Guard-native setups.
- External ecosystems expand coverage, but they also inherit breakage when websites, repositories, or extension APIs change.
- Kotatsu-Redo parser updates are tied to app releases; a CI pipeline auto-syncs upstream changes.

## Related Documents

- [Documentation Hub](./README.md)
- [Getting Started](./getting-started.md)
- [Mihon Integration Reference](./reference/mihon-integration.md)
- [TVBox Runtime Compatibility](./reference/tvbox-runtime.md)
- [Reader Features](./reader-features.md)
- [FAQ](./faq.md)
- [Troubleshooting](./troubleshooting.md)
