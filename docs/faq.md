# FAQ

This page answers the recurring questions that are easy to ask but annoying to reconstruct from multiple guides.

## What Is Kototoro Best For?

Kototoro is built for users who want one Android app for manga, novels, and video instead of maintaining separate apps for each content type.

## What Makes Kototoro Different?

The most distinctive capabilities are:

- Local OCR + translation inside the reader
- Video super-resolution (Anime4K), DLNA casting, subtitle & audio track selection
- Tracking discovery across MAL, Kitsu, AniList, Bangumi, Shikimori, and MangaUpdates
- WebDAV-based multi-device sync
- Broad external source compatibility across Mihon, Aniyomi, IReader, Legado, and TVBox ecosystems
- Kotatsu-Redo parser library integration for extended built-in source coverage

## Do I Need Mihon, Aniyomi, Legado, or TVBox Installed?

Not always. Kototoro has built-in sources (including the full Kotatsu-Redo parser library), so the app is still usable on its own. External ecosystems matter when you want broader catalog coverage or already maintain your sources there. Mihon, Aniyomi, and IReader are extension-based, while Legado and TVBox are usually imported from JSON files or JSON URLs.

## What Is Kotatsu-Redo?

Kotatsu-Redo is a parser library that provides additional built-in manga sources. Kototoro integrates it as a supplementary parser layer alongside its own native parsers. These sources appear automatically without any installation — they are bundled with the app.

## What Are IReader Extensions?

IReader extensions are similar to Mihon extensions but focused on novel sources. Install IReader extension APKs on your device and Kototoro will auto-detect and load their sources. This is useful for novel-heavy workflows.

## Can I Discover New Anime / Manga Inside the App?

Yes. Kototoro includes a Discover feature that connects to MAL, Kitsu, AniList, Bangumi, Shikimori, and MangaUpdates. You can browse seasonal anime, rankings, trending titles, and view details including genres, scores, and synopses.

## Can I Cast Video to My TV?

Yes. Kototoro supports DLNA casting. During video playback, open the player menu and select a DLNA-compatible device on your local network.

## Does the Video Player Support Subtitles?

Yes. The built-in player supports subtitle and audio track selection for sources that provide them. You can switch between embedded subtitle tracks and audio languages during playback.

## Where Do I Find Common External Source Repositories?

For many users, these are the most important starting points:

- Mihon: [Keiyoushi Extensions](https://github.com/keiyoushi/extensions-source), [Yuzono Tachiyomi Extensions](https://github.com/yuzono/tachiyomi-extensions), [LittleSurvival CopyManga Copy20](https://github.com/LittleSurvival/copymanga-copy20)
- Aniyomi: [Kohi-den Extensions Source](https://github.com/Kohi-den/extensions-source), [Yuzono Anime Extensions](https://github.com/yuzono/anime-extensions)
- Legado: [XIU2 Yuedu](https://github.com/XIU2/Yuedu)

Read more: [Source Integrations](./source-integrations.md)

## Is OCR + Translation Really Local?

It can be. Kototoro supports on-device OCR and local translation, with downloadable advanced OCR models when needed. `API only` translation is also available for a configured remote provider, but it is a separate mode rather than an automatic fallback.

Read more: [Automatic Translation](./automatic-translation.md)

## What Does WebDAV Sync Cover?

Typical synced data includes favorites, reading history, progress, groups, and related source state where applicable.

Read more: [WebDAV Sync](./webdav-sync.md)

## What Should I Read First?

- If you are new: [Getting Started](./getting-started.md)
- If you want external sources: [Source Integrations](./source-integrations.md)
- If you want on-device translation: [Automatic Translation](./automatic-translation.md)
- If you want to discover anime / manga: [Reader Features](./reader-features.md)
- If something breaks: [Troubleshooting](./troubleshooting.md)
