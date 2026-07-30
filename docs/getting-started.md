# Getting Started

This is the best entry point for new users. The goal is simple: get the app installed, connect the sources you need, and enable optional features only when they matter to your workflow.

> [!IMPORTANT]
> **Kototoro does not come with any built-in content or media.** It is a reader and player framework only. Users must install third-party extensions, import local files, or configure external source endpoints themselves. The developers are not affiliated with any content providers and assume no responsibility for user-sourced content.

## What Kototoro Is Good At

Kototoro is built around these practical strengths:

1. One Android app for manga, novels, and video.
2. Local OCR + translation directly inside the reader.
3. Video super-resolution (Anime4K), DLNA casting, subtitle selection.
4. Tracking discovery through MAL, Kitsu, AniList, Bangumi, Shikimori, MangaUpdates.
5. Reliable multi-device sync through WebDAV.
6. Broad source compatibility through built-in, Kotatsu-Redo, and external ecosystems.

## 10-Minute Setup

1. Install the latest APK from [Releases](https://github.com/Kototoro-app/Kototoro/releases).
2. Open the app and follow the Setup wizard to configure your languages, content types, and built-in source plugins.
3. Set up your sources (if you need extensions beyond the built-in ones).
4. If you use more than one device, configure WebDAV before you build a large library.
5. If you want in-reader translation, enable it and download the required models.

## Setup Wizard

Upon opening Kototoro for the first time, you are greeted by the **Setup wizard**. This wizard helps you configure the most important settings in one click.

### Re-opening the Wizard
If you dismissed the wizard or want to run it again later, you can always open it from:
`Settings -> Content Sources -> Setup wizard`

### 1. Source Language and Content Type
In the first step, you can select which languages you are interested in (e.g., English, Chinese, Japanese, etc.). 
You can also toggle which content types you want enabled globally in the app:
- **Manga**
- **Novels**
- **Video**

### 2. Built-in Source Initialization
Kototoro's dynamic built-in sources (the core Kototoro parsers and Kotatsu-Redo parser library) are packaged as dynamic plugins. 
The wizard allows you to install these with a single click:
1. **GitHub Mirror:** If you are in a region with poor GitHub connectivity (e.g., users in Mainland China), tap the mirror dropdown and select `GHProxy` or another available node.
2. **Select Repositories:** Check the plugin repositories you wish to install.
3. **Deploy:** Tap the install/deploy button to automatically download and initialize the latest parser plugins.

This is the highly recommended way to start using Kototoro, ensuring you have the latest parsers without needing to configure them manually.

## Choose Your Path

### I want manga sources

1. Open Kototoro and review the built-in sources first (includes Kotatsu-Redo parser library).
2. If you also use Mihon, install the Mihon extensions you need there.
3. Return to Kototoro and refresh source detection if the new sources do not appear immediately.

Common Mihon extension repositories:

- [Keiyoushi Extensions](https://github.com/keiyoushi/extensions-source)
- [Yuzono Tachiyomi Extensions](https://github.com/yuzono/tachiyomi-extensions)
- [LittleSurvival CopyManga Copy20](https://github.com/LittleSurvival/copymanga-copy20)

Read more: [Source Integrations](./source-integrations.md)

### I want video sources

1. Choose whether your video workflow is extension-based or JSON-based.
2. For Aniyomi-style sources, install the video extensions you need and let Kototoro detect the installed APKs.
3. For TVBox-style sources, import a JSON file or JSON URL from `Settings -> Content Sources -> Import JSON Sources`.
4. Open Kototoro and check whether the new sources appear in `Browse -> Content Sources`.
5. Configure playback options only after you confirm basic playback works.
6. Try DLNA casting: open the player menu and select a device on your local network.

Common Aniyomi extension repositories:

- [Kohi-den Extensions Source](https://github.com/Kohi-den/extensions-source)
- [Yuzono Anime Extensions](https://github.com/yuzono/anime-extensions)

Read more: [Reader Features](./reader-features.md) and [Source Integrations](./source-integrations.md)

### I want novels

1. Start with built-in reading sources.
2. If your workflow depends on Legado, prepare the JSON file or JSON URL first.
3. Import it from `Settings -> Content Sources -> Import JSON Sources`.
4. Check the imported entries in `Settings -> Content Sources -> JSON Sources Directory`.
5. If you use IReader extensions, install the APKs and Kototoro will auto-detect them.
6. Test chapter loading and formatting before importing a large reading list.

Common Legado source repository:

- [XIU2 Yuedu](https://github.com/XIU2/Yuedu)

Read more: [Source Integrations](./source-integrations.md)

### I want to discover new anime / manga

1. Open the Discover tab in the app.
2. Browse content from MAL, Kitsu, AniList, Bangumi, Shikimori, or MangaUpdates.
3. View details including genres, scores, and synopses.
4. Link discovered titles to your tracking progress.

## Optional Setup

### Automatic Translation

1. Open `Settings -> AI -> Translation`.
2. Set the source and target languages, then choose `Local` or `API only`.
3. Start with `Basic` OCR; select `Advanced` only when you need the downloadable offline OCR pack.
4. Open a manga chapter and enable **Manga translation** from the reader controls.
5. For `API only`, configure the endpoint, key, and model from **Online Translation Service** first.

Read more: [Automatic Translation](./automatic-translation.md)

### WebDAV Sync

1. Open `Settings -> Backup & Restore`.
2. Enter your WebDAV endpoint, username, and password.
3. Run a manual backup.
4. Restore from the same location on your second device.

Read more: [WebDAV Sync](./webdav-sync.md)

## Favorites Import and Site Sync

Kototoro can import favorites from supported logged-in sites or push local favorites back to them.

| Site | Import Favorites | Sync Favorites | Notes |
| :--- | :---: | :---: | :--- |
| CopyManga | ✅ | ✅ | Login required |
| Zaimanhua | ✅ | ✅ | Login required |
| Komiic | ✅ | ✅ | Login required |
| Baozi Manga | ✅ | ✅ | Login required |
| Manhuagui | ✅ | ✅ | Login required |
| Hentai Manga | ✅ | ✅ | Login required |
| Pica Manga | ✅ | ✅ | Login required |

Basic flow:

1. Open `Favorites`.
2. Use the top-right menu.
3. Choose `Import from Site` or `Sync to Site`.
4. Select the target site and continue.

## Next Documents

- [Documentation Hub](./README.md)
- [Reader Features](./reader-features.md)
- [Automatic Translation](./automatic-translation.md)
- [Source Integrations](./source-integrations.md)
- [WebDAV Sync](./webdav-sync.md)
- [FAQ](./faq.md)
- [Troubleshooting](./troubleshooting.md)
