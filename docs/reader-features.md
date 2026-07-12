# Reader Features

Kototoro is designed as an all-in-one Android reader instead of a single-purpose manga app. This page summarizes the core user-facing capabilities and explains where each one fits.

## Core Product Areas

### Manga, Novels, and Video in One App

Kototoro handles three content types in one place:

- Manga
- Novels
- Video

This keeps history, favorites, source access, and sync in one app instead of splitting the workflow across multiple readers.

### Local OCR + Translation

Kototoro includes OCR + translation directly inside the reader. This is one of the most distinctive parts of the project because it keeps the workflow close to the page instead of sending users to external desktop tools.

**Manga translation workflow:**

- Detect text from the page using the configured detector (ONNX bubble detector, DB-Net, or catch-all OCR)
- Recognize text with the configured OCR engine (ML Kit, PaddleOCR, or MangaOCR)
- Translate on-device in `Local` mode, or through a configured API provider in `API only` mode
- Render a translated layer over the original page, with a reader control to return to the original image
- Retranslate the current page, failed pages, or the current chapter and inspect their state in the translation task panel

**Novel translation:**

- Paragraphs are batched and sent to the same translation engine
- Results stream in progressively via Flow
- Supports `Translation only` and `Bilingual` display modes

Read more: [Automatic Translation](./automatic-translation.md)

### Video Playback

Kototoro includes a built-in video player with:

- Online playback with source-side episode browsing
- Resolution / stream selection when available
- **Subtitle and audio track selection** — choose embedded or external subtitle tracks, switch audio languages
- **DLNA casting** — discover and cast to DLNA-compatible devices on the local network via SSDP
- **Anime4K super-resolution** — real-time video upscaling with configurable Anime4K filter presets
- Seek gesture feedback with proportional seeking and synchronized progress bar
- Screen rotation and standard playback controls

### Tracking & Discovery

Kototoro integrates with multiple tracking sites for discovering new content and tracking reading/watching progress:

| Site | Discovery | Tracking |
| :--- | :---: | :---: |
| MyAnimeList (MAL) | ✅ Seasonal anime, rankings | ✅ |
| Kitsu | ✅ Trending, categories | ✅ |
| AniList | ✅ Trending, popular | ✅ |
| Bangumi | ✅ | ✅ |
| Shikimori | ✅ | ✅ |
| MangaUpdates | ✅ | ✅ |

Discovery pages show rich data including genres, scores, synopses, and cover images in a multi-carousel UI.

### Sync and Portability

Kototoro uses WebDAV for multi-device backup and synchronization. This is intended for users who want device-independent sync without relying on a closed cloud service.

Read more: [WebDAV Sync](./webdav-sync.md)

### Immersive Interface

Kototoro aims to present an extremely modern and premium user experience:

- **Shared Element Transitions (Hero Animations):** A smooth morphing effect when transitioning from browse lists (Home, Explore, Search) directly into the details cover. Configurable under `Settings -> Appearance`.
- **Dynamic Content:** Automatic palette generation for backgrounds and panoramic header blurring based on cover images.

### Broad Source Integration

Kototoro supports several source ecosystems:

- Built-in sources (native Kototoro parsers + Kotatsu-Redo parser library)
- Mihon manga extensions
- Aniyomi video / anime extensions
- IReader novel extensions
- Legado JSON reading sources
- TVBox JSON video sources

This lets users keep broader catalogs in one app without being locked to one source format.

Read more: [Source Integrations](./source-integrations.md)

## By Content Type

### Manga Reading

Kototoro includes a full manga workflow with:

- Source browsing with multi-select saved filters
- Library and favorites management
- Chapter reading
- Downloads for offline use
- Reader customization and layout options

### Novel Reading

Kototoro supports novel-oriented workflows including:

- Online novel sources (built-in, Legado, IReader extensions)
- Local novel reading
- Illustrated chapter handling
- EPUB-related flows where supported by the source and pipeline
- **Novel translation** — automatic paragraph-level translation with `Translation only` and `Bilingual` display modes
- **TTS (Text-to-Speech)** — voice reading with configurable engine and voice settings

### Video Consumption

Kototoro supports video source browsing and playback inside the same app, including:

- Aniyomi extension-based and TVBox JSON-based video sources
- DLNA casting to network devices
- Subtitle and audio track selection
- Anime4K super-resolution filters

## Related Documents

- [Documentation Hub](./README.md)
- [Getting Started](./getting-started.md)
- [Automatic Translation](./automatic-translation.md)
- [Source Integrations](./source-integrations.md)
- [WebDAV Sync](./webdav-sync.md)
- [FAQ](./faq.md)
- [Troubleshooting](./troubleshooting.md)
