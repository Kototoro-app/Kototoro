# Development

This document keeps contributor-oriented setup notes that were intentionally removed from the main README.

## Requirements

- Android Studio 2022.3+
- JDK 17+
- Android SDK 33+
- Gradle 9.0+

## Build

Debug build:

```bash
./gradlew :app:assembleDebug
```

Release build:

```bash
./gradlew :app:assembleRelease --no-daemon
```

Compile Kotlin only:

```bash
./gradlew :app:compileDebugKotlin --no-daemon
```

Run unit tests:

```bash
./gradlew :app:testDebugUnitTest --no-daemon
```

## Documentation Website

Kototoro now uses VitePress for the documentation website and GitHub Pages for hosting.

Local docs development:

```bash
npm ci
npm run docs:dev
```

Static docs build:

```bash
npm run docs:build
```

Deployment model:

- The site source lives in `docs/`
- VitePress configuration lives in `docs/.vitepress/`
- GitHub Actions publishes the site through `.github/workflows/docs-pages.yml`
- The production base path is configured for `https://kototoro-app.github.io/Kototoro/`

## Documentation Map

- [Documentation Hub](./README.md)
- [Getting Started](./getting-started.md)
- [Reader Features](./reader-features.md)
- [Automatic Translation](./automatic-translation.md)
- [WebDAV Sync](./webdav-sync.md)
- [Source Integrations](./source-integrations.md)
- [FAQ](./faq.md)
- [Troubleshooting](./troubleshooting.md)
- [Contributing](./contributing.md)
- [Mihon Integration Reference](./reference/mihon-integration.md)
- [External Extension Integration Guide](./architecture/external-extension-integration-guide.md)
- [Architecture Review](./architecture/architecture-review.md)
- [Architecture Roadmap](./architecture/architecture-roadmap.md)
- [UI Improvement](./architecture/ui_improvement.md)

## Development Expectations

- Keep changes focused.
- Verify behavior locally before opening a pull request.
- Update docs when a user-visible workflow changes.
- Keep `README.md` product-oriented and move detailed setup into `docs/`.

## Contribution Workflow

1. Fork the project.
2. Create a feature branch.
3. Make and verify your changes.
4. Open a pull request with a focused description.

For contribution expectations, see [Contributing](./contributing.md).

## Advanced References

- [Mihon Integration Reference](./reference/mihon-integration.md)
- [External Extension Integration Guide](./architecture/external-extension-integration-guide.md)
- [Architecture Review](./architecture/architecture-review.md)
- [Architecture Roadmap](./architecture/architecture-roadmap.md)
- [UI Improvement](./architecture/ui_improvement.md)
