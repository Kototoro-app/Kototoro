# Windows & iOS (Preview)

Kototoro is an Android app first. The same Kotlin Multiplatform core (database, backup format, source protocol,
reader geometry, shared Compose UI) now also powers a **Windows desktop app** and an **iOS app**. Both are
**previews**: they are built and smoke-tested by CI on every Nightly and Release, but they cover fewer features
than Android and have not been through full install/upgrade or real-device acceptance.

## Downloads

| Platform | Stable releases | Nightly builds |
| --- | --- | --- |
| Android | [Releases](https://github.com/Kototoro-app/Kototoro/releases) (APK) | [Kototoro-Nightly](https://github.com/Kototoro-app/Kototoro-Nightly/releases) (APK) |
| Windows x64 | [Releases](https://github.com/Kototoro-app/Kototoro/releases): `…-windows-x64-release.zip` and `.msi` | [Kototoro-Nightly](https://github.com/Kototoro-app/Kototoro-Nightly/releases): `…-windows-x64-nightly.zip` |
| iOS / iPadOS | [Releases](https://github.com/Kototoro-app/Kototoro/releases): `…-ios-release-unsigned.ipa` | [Kototoro-Nightly](https://github.com/Kototoro-app/Kototoro-Nightly/releases): `…-ios-nightly-unsigned.ipa` |

Every package is published with a `.sha256` file next to it.

## Windows

### Install

- **Portable ZIP** — extract the whole `Kototoro` folder and run `Kototoro.exe`. Keep the folder together: the
  EXE starts the bundled Java runtime (`runtime/`) and loads the app, browser bridge, player and upscalers from
  `app/`. No system JDK is needed.
- **MSI** (stable releases only) — a per-user installer. The program goes to `%LOCALAPPDATA%\Kototoro-App`.

Your library lives in `%LOCALAPPDATA%\Kototoro`, separate from the program, so replacing or reinstalling the
program keeps it. `Kototoro.exe --data-dir <folder>` runs with a different data folder.

Requirements: Windows 10/11 x64. The built-in browser (web debugging, Cloudflare verification) needs the
[Microsoft Edge WebView2 Runtime](https://developer.microsoft.com/microsoft-edge/webview2/), which current
Windows versions already include. Video playback and page super-resolution use Vulkan, which comes with your GPU
driver.

### What works

- Tablet-style layout shared with Android: home, favourites, browse, history, updates, details.
- Extensions: **Mihon** (manga), **Tsundoku** (novels), **Aniyomi** (video), **CloudStream**, and
  kototoro-parsers / kotatsu-parsers / UMA plugin JARs. Add repositories or import files from
  *More → Extensions & repositories*; APK-only extensions are converted to JVM JARs on your machine.
- Manga reader: single/double page, RTL, continuous scroll, zoom, bookmarks, cross-chapter reading, chapter
  downloads with offline reading, RealCUGAN / Real-ESRGAN / Anime4K super-resolution.
- Novel reader: continuous paragraphs, shared settings panel, table of contents, bookmarks.
- Video: libmpv playback in the window, subtitles/audio tracks, DLNA casting.
- Favourites, history and bookmarks in the same database format as Android; library backup export/merge using the
  Android ZIP format.
- Source settings, WebView2-based Cloudflare verification and cookie sync.

### Not yet available

WebDAV / Google Drive sync, OCR and translation, TTS, Spaces, IReader / Legado / TVBox sources, batch download
management, and several Android settings pages. The detailed status is tracked in
[Windows Feature Parity](./architecture/windows-feature-parity-2026-10.md).

## iOS / iPadOS

### Install

The IPA is **unsigned** and requires **iOS 26 or later**. Kototoro is not on the App Store or TestFlight; sign and
install the IPA yourself with a sideloading tool such as AltStore or SideStore (or your own Apple developer
account). Free Apple accounts need to refresh the signature every 7 days.

### What works

- SwiftUI app on the shared Kotlin core: library with categories, history, bookmarks, continue reading;
  source browsing and global search; recommendations and tracking-site discovery.
- **Extensions through an embedded JVM**: Mihon, Tsundoku, Aniyomi and kototoro/kotatsu/UMA parser JARs run
  inside the app on OpenJDK Mobile. Install them from repositories or import APK/JAR files; APKs are converted on
  the device. The release IPA ships no extensions.
- **Apple CMS / TVBox** (type 0/1) sources run natively without the JVM.
- Manga reader (five modes, zoom, offline downloads), novel reader (paged/continuous, EPUB, styles, bookmarks),
  AVPlayer-based video with subtitles, Picture in Picture and AirPlay.
- Local import of CBZ/ZIP, EPUB/TXT and single video files.
- Backup: Android-format ZIP merge plus Mihon / Aniyomi / Venera import.
- Tracking accounts (Bangumi, AniList, MyAnimeList, Kitsu, Shikimori, MangaUpdates, SIMKL), with credentials kept
  in the Keychain.

### Limitations

- The embedded JVM is an interpreter (OpenJDK "Zero"), so extension calls are slower than on Android and the
  first launch of the runtime takes a moment.
- Not yet available: WebDAV sync, OCR and translation, TTS, video downloads, DLNA, custom Spaces, IReader /
  Legado / CloudStream sources, background update checks and notifications.
- Real-device performance, iPad landscape layouts and accessibility have had limited testing.

The detailed status is tracked in [iOS / Android Feature Parity](./architecture/ios-android-feature-parity-2026-10.md).

## Reporting problems

Open an issue and say which platform and which build (Release version or Nightly date) you used. For Windows,
attach `logs/source-errors.log` from the data folder if a source fails; it records error categories and request
IDs but no request parameters, cookies or page content.

Building the Windows and iOS apps from source is covered in [Development](./development.md#windows-and-ios-kotlin-multiplatform).
