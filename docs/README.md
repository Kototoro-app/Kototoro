# Kototoro Documentation

## UI development

- [Kototoro design language](design/README.md)
- [Interface style system implementation](development/interface-style-system.md)

This directory is organized by task, not by internal implementation. Start with the page that matches what you want to do next.

> [!IMPORTANT]
> **Kototoro does not bundle, host, or distribute any content sources, media, or copyrighted material.** The application is a generic reader and player framework. All content sources are provided by the user through third-party extensions, local file imports, or self-configured JSON endpoints. The developers of Kototoro are not responsible for any content accessed through user-installed sources.

> **Join the community:** [Discord](https://discord.gg/xBXvPz7tr7)

## Start Here

| If you want to... | Read this |
| :--- | :--- |
| Install the app and get productive quickly | [Getting Started](./getting-started.md) |
| Understand the product at a high level | [Reader Features](./reader-features.md) |
| Understand works, projections, and entity cleanup | [Entity System And Organize Guide](./entity-system.md) |
| Import local files (CBZ, EPUB, video) | [Local Import Guide](./local-import.md) |
| Set up local OCR + translation | [Automatic Translation](./automatic-translation.md) |
| Connect external source ecosystems | [Source Integrations](./source-integrations.md) |
| Sync across devices with WebDAV | [WebDAV Sync](./webdav-sync.md) |
| Get quick answers to common questions | [FAQ](./faq.md) |
| Solve common setup problems | [Troubleshooting](./troubleshooting.md) |
| Build, test, or contribute | [Development](./development.md) and [Contributing](./contributing.md) |

## Documentation Map

### For users

- [Getting Started](./getting-started.md)
- [Reader Features](./reader-features.md) — manga, novels, video, tracking discovery, DLNA, subtitles
- [Entity System And Organize Guide](./entity-system.md) — works, projections, tracking bindings, entity merge, repair, and source completion
- [Local Import Guide](./local-import.md) — CBZ, EPUB, TXT, MKV, MP4 and more
- [Automatic Translation](./automatic-translation.md)
- [Source Integrations](./source-integrations.md) — built-in, Kotatsu-Redo, Mihon, Aniyomi, IReader, Legado, TVBox
- [WebDAV Sync](./webdav-sync.md)
- [FAQ](./faq.md)
- [Troubleshooting](./troubleshooting.md)

### For advanced users

- [Architecture Review](./architecture/architecture-review.md)
- [Architecture Roadmap](./architecture/architecture-roadmap.md)
- [Novel Reader Immersive Refinement (2026-05)](./architecture/novel-reader-immersive-refinement-2026-05.md)
- [Tracking Site Support Plan (2026-04)](./architecture/tracking-site-support-plan-2026-04.md)
- [UI Improvement](./architecture/ui_improvement.md)
- [Mihon Integration Reference](./reference/mihon-integration.md)
- [TVBox Runtime Compatibility](./reference/tvbox-runtime.md)
- [Kotatsu-Redo Integration Reference](./KotatsuRedoIntegration.md)

### For contributors

- [Development](./development.md)
- [Contributing](./contributing.md)

### Design drafts

- [Unified Source Management UI](./unified_source_management.md)

### Archived materials

- [OCR Roadmap Review, March 2026](./archive/ocr-roadmap-review-2026-03.md)
- [OCR Pipeline V2](./archive/ocr-pipeline-v2.md)
- [OCR Architecture Review](./archive/ocr-architecture-review.md)
- [Archived Chinese Mihon Compatibility Notes](./archive/zh/mihon-compatibility.md)

## Documentation Rules

- `README.md` stays short and product-oriented.
- `docs/` keeps task-oriented guides and reference material.
- User-facing guides should explain what a feature is, when to use it, and how to set it up.
- Contributor-facing guides should focus on build, verification, and change expectations.
- Time-sensitive review packs and superseded language variants belong in `archive/`.
