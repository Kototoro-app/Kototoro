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
./gradlew :app:compileDebugKotlin
```

Run unit tests:

```bash
./gradlew :app:testDebugUnitTest --no-daemon
```

## Windows and iOS (Kotlin Multiplatform)

Android, Windows and iOS share the Kotlin Multiplatform modules: `core-db` (Room database and migrations),
`core-domain`, `core-backup`, `core-net`, `core-source` (source protocol), `core-library`, `core-local`,
`reader-core`, `reader-novel` and `core-ui` (Compose shared by Android and Windows). Third-party extensions run in
a JVM through `source-host` / `parser-host`. The overall plan and progress are in the
[KMP / iOS plan](./architecture/kmp-ios-plan-2026-10.md); user-facing status is on [Windows & iOS](./platforms.md).

KMP modules are checked for portability with `./gradlew :<module>:compileCommonMainKotlinMetadata`, and their
tests run with `:<module>:jvmTest` (not `test`).

### Windows desktop app

The desktop modules are opt-in (`-PwithDesktopApp`), so ordinary Android builds and Android Studio sync do not
configure them. They need JDK 21+ and the pinned compatibility JARs (AndroidCompat / Suwayomi APIs). Build the
compatibility directory and the WebView2 SDK inputs once with the same script CI uses (it compiles the pinned
Suwayomi source and verifies every JAR's SHA-256; JDK 25 is required):

```powershell
./.github/scripts/prepare-windows-ci.ps1 -SourceJavaHome "C:/path/to/jdk-25"
# output: build/windows-ci-compatibility
```

Run from source while developing:

```powershell
./desktop-app/run-windows.ps1 -CompatibilityDirectory "build/windows-ci-compatibility" -JavaVersion 21
```

Build a runnable app folder (no ZIP/MSI, no tests) — `--no-configuration-cache` is required:

```powershell
./gradlew.bat -PwithDesktopApp -PwithWindowsDistribution `
  "-PmihonCompatibilityDirectory=build/windows-ci-compatibility" -PmihonCompatibilityJavaVersion=21 `
  :desktop-app:createDistributable --no-configuration-cache
# output: desktop-app/build/compose/binaries/main/app/Kototoro/Kototoro.exe
```

Replace the task with `:desktop-app:windowsDistributionTest` to also run the packaged-image self check (bundled
JVM, database, backup, WebView2 bridge, libmpv and ncnn upscalers), or run `./desktop-app/package-windows.ps1`
for the tested image plus the portable ZIP and MSI. The self check needs Vulkan: a GPU driver locally, or a
software driver such as Mesa lavapipe. More detail is in
[desktop-app/README.md](https://github.com/Kototoro-app/Kototoro/blob/devel/desktop-app/README.md).

### iOS app

Requires an Apple Silicon Mac with **Xcode 27** (the app uses the iOS 27 SDK; deployment target iOS 26) and
JDK 17+. Open `iosApp/Kototoro.xcodeproj`, choose the `Kototoro` scheme and run it on a simulator; the Xcode build
phase compiles the shared `KototoroShared` framework through Gradle.

The extension runtime (OpenJDK Mobile Zero, built from pinned source) is optional and needs JDK 24. To produce
the same unsigned IPA that CI publishes:

```bash
python3 iosApp/Scripts/build-jvm-preview.py                       # simulator VM + Java image
python3 iosApp/Scripts/stage-mihon-preview.py --runtime-only      # extension runtime profile
python3 iosApp/Scripts/build-jvm-preview.py --platform iphoneos \
    --profile iosApp/build/jvm-real-api                           # device VM
iosApp/Scripts/package-ipa.sh --jvm                               # iosApp/build/release-ios/*.ipa
```

Without `--runtime-only`, the staging script also downloads and converts pinned sample extensions for simulator
testing. Simulator workflows, UI tests and real-extension previews are described in
[iosApp/README.md](https://github.com/Kototoro-app/Kototoro/blob/devel/iosApp/README.md) and the
[iOS development handoff](./architecture/ios-development-handoff-2026-10.md).

### CI packaging

`release.yml` (on `v*` tags) and `nightly.yml` (daily, devel) build the Android APKs, then call two reusable
workflows:

- `windows-build.yml` — builds the compatibility JARs from source, installs a pinned software Vulkan driver
  (Khronos loader + Mesa lavapipe, because hosted runners have no GPU), runs the packaged-image test and uploads
  the ZIP (plus the MSI for releases).
- `ios-build.yml` — runs on GitHub's `xcode-27` image, compiles OpenJDK Mobile for the simulator and the device,
  archives the unsigned IPA with the embedded JVM and checks that the bundled JVM host starts.

To try workflow changes without publishing, push a branch and run `gh workflow run nightly.yml --ref <branch>`:
non-devel runs only upload artifacts. Nightly cancels an in-progress run of the same branch, so wait for one to
finish before dispatching the next.

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
- [Windows & iOS (Preview)](./platforms.md)
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
