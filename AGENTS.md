# Repository Guidelines

See `CLAUDE.md` for comprehensive project architecture, dependency details, and module descriptions. This file focuses on gotchas and non-obvious constraints.

## Build, Test, and Development Commands
Use the bundled wrappers and keep commands scoped:

- `./gradlew :app:assembleDebug` builds a debug APK (ABI-split: arm64-v8a, armeabi-v7a, x86_64, x86 + universal).
- `./gradlew :app:compileDebugKotlin --no-daemon` is the fastest compile-only validation for Kotlin changes.
- `./gradlew :app:testDebugUnitTest --no-daemon` runs JVM unit tests.
- `./gradlew :app:testDebugUnitTest --tests "org.skepsun.kototoro.ClassName.methodName" --no-daemon` runs a single test.
- `./gradlew :app:connectedDebugAndroidTest` runs instrumented tests on a device or emulator.
- `npm ci && npm run docs:dev` starts the local VitePress docs site.
- `npm run docs:build` builds the static docs output.

### Android CLI for development and debugging
Prefer the official [Android CLI](https://developer.android.com/blog/posts/android-cli-build-android-apps-3x-faster-using-any-agent) for agent-oriented Android development from the terminal when it provides a better signal than Gradle alone. Google positions it as the primary terminal interface for Android development: it complements Gradle/ADB with environment setup, SDK management, project creation, device management, deployment, UI navigation, and easy updates. Gradle remains the project build/test authority.

The official Agent workflow consists of three related pieces:

- **Android CLI** — terminal entry point for environment, project, SDK, device, and deployment workflows.
- **Android skills** — installable, task-specific guidance that makes an Agent more effective; inspect available skills with `android skills list` and manage them with `android skills ...`.
- **Android Knowledge Base** — authoritative, frequently updated Android, Firebase, Google Developers, and Kotlin guidance queried through `android docs search` and `android docs fetch`.

- Check availability with `command -v android` and inspect the environment with `android info`.
- Use `android describe --project_dir=.` to discover project targets and generated APK locations before deploying.
- Use `android run --debug` (optionally with `--device=<serial>`, `--activity=<activity>`, or `--apks=<path>`) to deploy and launch a debug build/APK on a connected device or emulator.
- Use `android layout --pretty` or `android layout --diff` as the primary UI inspection tool; use `android screen capture -o <path>.png` for visual/WebView cases and examine the resulting PNG before acting on it.
- Use `android emulator list|create|start|stop|remove` to manage AVDs (run `android emulator create --list-profiles` to see supported profiles), and use `adb devices`/`adb shell input ...` for device connection and interaction when needed.
- Use `android docs search \"<Android topic>\"` and `android docs fetch <kb://...>` for current official Android guidance.
- The installed CLI currently exposes `info`, `describe`, `run`, `layout`, `screen`, `emulator`, `docs`, `sdk`, `studio`, and `skills`; use `android help` to verify version-specific capabilities rather than inventing commands such as `android doctor` or `android inspect`.
- Run `android update` periodically. `android init` initializes the local Agent environment; use `android skills` to browse/install the growing skill collection.
- If Android CLI or Gradle downloads need network access in this environment, retry with the local proxy at `127.0.0.1:7890` where applicable. Never commit generated APKs, screenshots, CLI caches, or local configuration.

## Non-Obvious Build Facts

- **JDK 17 is required** for Gradle, even though `compileOptions` target Java 11. CI uses `temurin-17`.
- **`app/build.gradle` uses Groovy DSL**, not Kotlin DSL — do not write `.kts` syntax in it.
- **Nightly variant** auto-generates `versionCode`/`versionName` from date in `applicationVariants.configureEach` — do not manually set those for nightly.
- **Hilt `enableAggregatingTask = true`** is set; removing it can break release builds with generic assisted factory validation errors.
- **Cloudstream runtime jar** is sanitized by a custom `prepareCloudstreamRuntimeJar` task that strips duplicate classes (Coil, AndroidX, Material, etc.) from a pre-built jar. If you touch `cloudstream-runtime/` or the exclude list, verify the sanitization task still passes.
- **`kotlinx.serialization-json-okio` is pinned to 1.7.3**, while the main `kotlinx-serialization` is 1.9.0 — the versions intentionally diverge.
- **`decoroutinator` plugin is commented out** in root `build.gradle` and `app/build.gradle`. Do not uncomment unless you have a specific reason.
- **CMake 3.22.1** builds native code from `app/src/main/cpp/CMakeLists.txt` for 4 ABIs. Native changes require CMake + NDK toolchain.
- **DJL tokenizers** ship a local AAR (`libs/tokenizer-native-0.33.0.aar`) and exclude desktop native binaries in `packagingOptions`.
- **`generateLocaleConfig = false`** is set — workaround for Google issuetracker 408030127. Do not enable without verifying the issue is resolved.

## Project Structure & Module Organization
`app/` contains the Android application. Main Kotlin sources live under `app/src/main/kotlin/org/skepsun/kototoro` and are organized by feature with `data`, `domain`, and `ui` layers. Shared parser contracts are in `parser-api/`. Unit tests in `app/src/test/kotlin`, instrumented tests in `app/src/androidTest/kotlin`, Room schemas in `app/schemas/`. Docs in `docs/` built with VitePress.

### Namespace Caveat
The main code uses `org.skepsun.kototoro`. Some test classes and the Hilt test runner retain the original fork path `org.koitharu.kotatsu` — this is intentional. **Do not bulk-rename** these legacy paths.

## Coding Style & Naming Conventions
Follow `.editorconfig`: UTF-8, LF, 4-space indentation, 120-character line width. Kotlin official style with trailing commas enabled. Name classes and tests in `PascalCase`, methods and properties in `camelCase`, Android resources in lowercase underscore style. Prefer extending existing feature modules over creating parallel implementations.

## Testing Guidelines
Uses JUnit5, Kotest, MockK, and MockWebServer for unit tests, plus AndroidX Test, Hilt, and Room testing. Test files typically named `*Test.kt`, `*IntegrationTest.kt`, or `*PropertyTest.kt`. Unit tests use JUnit Platform (`useJUnitPlatform()`). Changes to parser flows, networking, database, downloads, or reader behavior should include coverage or at least pass a compile/test check.

## Commit & Pull Request Guidelines
Use Conventional Commits: `feat:`, `fix(scope):`, `docs:`, `chore:`. Keep commits narrowly scoped. PRs should explain motivation, affected areas, validation commands, and risks. Include screenshots for UI changes. Link related issues.

## Security & Contributor Notes
- Never commit `local.properties`, signing files, secrets, caches, or generated artifacts.
- Translation content is managed through **Weblate** — avoid bulk manual string rewrites unless fixing a clear defect.
- Keep `README.md` product-focused; put deeper engineering notes in `docs/`.
- `CONTRIBUTING.md` prohibits adding new dependencies unless required (APK size matters).
- Release workflow triggers on `v*` tags pushed to the repo.
