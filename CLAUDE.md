# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

Kototoro 是一个开源的 Android 应用，将漫画、小说和视频整合到一个阅读器中。核心特性包括：
- 本地 OCR + 机器翻译
- 视频超分辨率（Anime4K）
- 多平台进度追踪（MAL、Kitsu、AniList、Bangumi 等）
- 广泛的图源支持：Mihon、Aniyomi、IReader、Legado、TVBox 扩展
- 动态 UI 插件系统（通过外部 classloader）
- 纯 Kotlin 实现的 OTA 增量更新（bspatch）
- WebDAV 多设备同步

# 开发和调试工具

除 Gradle 构建和测试外，优先使用官方 `android` CLI 完成设备/模拟器相关的开发与调试；它能补充 Gradle 不擅长的项目元数据发现、APK 部署、设备交互、UI 层级检查、截图、AVD 管理以及官方 Android 文档检索能力。Gradle 仍是本项目构建和测试结果的权威来源。

```bash
# 检查 CLI 和 SDK 环境
command -v android
android --version
android info

# 分析项目并定位构建目标/APK
android describe --project_dir=.

# 构建后部署并以调试模式启动（可按需指定设备、Activity 或 APK）
./gradlew :app:assembleDebug
android run --debug
# android run --debug --device=<serial> --activity=<activity>
# android run --debug --apks=<path/to.apk>

# 设备和 UI 调试：优先结构化层级，WebView/动画等场景再看截图
adb devices
android layout --pretty
android layout --diff
android screen capture -o /tmp/kototoro-screen.png
adb shell input tap <x> <y>
adb shell input swipe <x1> <y1> <x2> <y2> 500

# AVD 管理与官方 Android 知识库
android emulator list
android emulator create --list-profiles
android emulator create <profile>
android emulator start <avd-name>
android emulator stop <avd-name>
android docs search "<Android topic>"
android docs fetch <kb://...>
```

Android CLI 是面向 Agent 的 Android 终端工具层，而不是另一个 AI 编程模型。Google 官方博客将它与 Android skills、Android Knowledge Base 一起作为 Agent 工作流套件：CLI 负责环境/SDK、项目、设备和部署；skills 提供任务专用指导；Knowledge Base 通过 `android docs` 提供持续更新的 Android、Firebase、Google Developers 和 Kotlin 官方上下文。它可以补充 Gradle、ADB，但不能替代 Gradle 的构建/测试权威性。

官方博客强调，Android CLI 可以创建设备、运行应用并帮助 Agent 导航 UI，也适合 CI、维护和脚本自动化。它不是只能用于新项目；现有 Kototoro 项目应优先使用 `android describe` 发现目标，再结合 Gradle 构建和 `android run` 部署。完成快速原型或终端调试后，仍可转入 Android Studio 做视觉编辑、深度调试和高级性能分析。

当前版本没有 `android doctor`、`android inspect` 或独立的 `android journey` 命令；先运行 `android help`，以实际安装版本暴露的子命令为准。Journeys 是 Android CLI/Agent 的测试工作流概念，应在运行中的应用上按步骤执行并验证结果，而不是直接假设存在同名 CLI 子命令。`android emulator create --list-profiles` 可查看当前版本支持的设备 profile。

使用 `android screen capture` 或带标注截图时，必须先实际查看 PNG，再依据截图操作；对 UI 变化优先使用 `android layout --diff` 以减少无关输出。需要更新 CLI 时运行 `android update`；`android init` 用于初始化环境并安装用户级 Android CLI skill。若 CLI 或 Gradle 下载在当前网络环境失败，可在适用的命令/Java 参数中使用本地代理 `127.0.0.1:7890`。不要将 APK、截图、CLI 缓存或本地配置提交到仓库。

## 构建和测试命令

### 基础构建
```bash
# 构建 debug APK（applicationId 后缀 .debug）
./gradlew :app:assembleDebug

# 构建 release APK（需签名，R8 混淆 + 资源压缩）
./gradlew :app:assembleRelease

# 构建 nightly APK（后缀 .nightly，版本号基于日期自动生成）
./gradlew :app:assembleNightly

# 仅编译 Kotlin 代码（最快的验证方式）
./gradlew :app:compileDebugKotlin --no-daemon

# 本机完整编译命令（需 Java 17 + 代理）
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 \
./gradlew :app:compileDebugKotlin --no-daemon \
  -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7890 \
  -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7890

# 清理构建
./gradlew clean
```

### 测试
```bash
# 运行所有 JVM 单元测试
./gradlew :app:testDebugUnitTest --no-daemon

# 运行单个测试类
./gradlew :app:testDebugUnitTest --tests "org.skepsun.kototoro.ClassName" --no-daemon

# 运行单个测试方法
./gradlew :app:testDebugUnitTest --tests "org.skepsun.kototoro.ClassName.methodName" --no-daemon

# 运行设备/模拟器上的 instrumented 测试
./gradlew :app:connectedDebugAndroidTest

# 运行所有检查（lint + 测试）
./gradlew :app:check
```

### 文档
```bash
# 启动本地文档站点（VitePress）
npm ci && npm run docs:dev

# 构建静态文档
npm run docs:build
```

### 版本信息
```bash
# 获取版本号
./gradlew printVersionName
./gradlew printVersionCode
```

## 技术栈

- **Kotlin** 2.2.10 / **AGP** 8.12.0 / **Gradle** 9.0.0（使用 `gradle/libs.versions.toml` 版本目录）
- **compileSdk** 36 / **minSdk** 26 / **targetSdk** 36
- **JVM 目标**: Java 11（开启 desugaring 以使用现代 API）
- **UI**: Jetpack Compose + Material3 + ViewBinding（混合过渡期）
- **数据库**: Room 2.7.2，KSP 代码生成
- **DI**: Hilt/Dagger（dagger 2.57.2，hilt-gradle-plugin 1.3.0）
- **网络**: OkHttp 5.2.1
- **原生代码**: `app/src/main/cpp/CMakeLists.txt`（CMake 3.22.1，4 种 ABI）
- **序列化**: kotlinx.serialization
- **测试**: JUnit5 + Kotest + MockK + MockWebServer

注意：`app/build.gradle` 使用 Groovy DSL（非 Kotlin DSL），顶层脚本用 Gradle 9.0。

## 项目架构

### 模块结构
- `app/` - 主应用模块，包含所有功能实现（compose + view）
- `parser-api/` - 共享的解析器接口定义
- `docs/` - VitePress 文档站点

### 代码组织（app/src/main/kotlin/org/skepsun/kototoro/）
项目按功能模块组织，每个模块通常包含 `data`、`domain`、`ui` 三层：

**核心模块**：
- `core/` - 核心基础设施（数据库、网络、缓存、异常处理、模型）
  - `core/db/` - Room 数据库、DAO、实体、迁移
  - `core/network/` - OkHttp 拦截器、代理、Cookie 管理、WebView 集成
  - `core/parser/` - 解析规则引擎
  - `core/model/` - 核心数据模型
- `main/` - 主入口 Activity

**内容源集成**：
- `mihon/` - Mihon/Tachiyomi 扩展集成（动态 ClassLoader、依赖注入桥接）
- `aniyomi/` - Aniyomi 扩展集成
- `ireader/` - IReader 源集成
- `extensions/` - 扩展管理框架
- `local/` - 本地文件导入（CBZ、EPUB 等）
- `alternatives/` - 替代源/镜像站管理
- `explore/` - 源预设探索
- `remotelist/` - 远程内容列表
- `picker/` - 文件/内容选择器

**内容管理**：
- `home/` - 主页和内容列表
- `discover/` - 发现和浏览
- `search/` - 搜索功能
- `details/` - 内容详情页
- `favourites/` - 收藏管理
- `history/` - 历史记录
- `bookmarks/` - 书签
- `suggestions/` - 推荐/建议
- `filter/` - 内容筛选
- `list/` - 列表视图
- `entitygraph/` - 实体关系图谱（统一管理漫画/小说/视频之间的关联）
- `stats/` - 阅读统计

**阅读体验**：
- `reader/` - 漫画/小说阅读器
- `video/` - 视频播放器（超分辨率、DLNA）
- `image/` - 图片处理和 OCR + 翻译

**同步和备份**：
- `sync/` - WebDAV 同步
- `backups/` - 备份和恢复
- `tracker/` - 外部平台进度追踪（MAL、AniList、Bangumi 等）
- `tracking/` - 追踪网站发现与候选匹配
- `scrobbling/` - 进度同步

**其他功能**：
- `settings/` - 设置界面
- `download/` - 下载管理
- `browser/` - 内置浏览器（Cloudflare 绕过）
- `widget/` - 桌面小部件

### 关键技术实现

**外部扩展集成**（参考 `docs/architecture/external-extension-integration-guide.md`）：
- 使用 ChildFirstPathClassLoader 隔离扩展依赖
- 通过依赖注入桥接提供 Application Context 和网络实例
- 动态监听 APK 安装/卸载事件（BroadcastReceiver）
- 处理 Cloudflare 挑战和 Cookie 同步

**增量 OTA 更新**（参考 `docs/architecture/incremental-updates.md`）：
- CI/CD 使用 `bsdiff` 生成增量补丁
- 纯 Kotlin 实现的 `bspatch` 算法（无 NDK 依赖）
- 严格的版本匹配验证
- 自动回退到完整 APK 下载

**数据库**：
- Room 数据库（`MangaDatabase`），DATABASE_VERSION = 40，schema 位于 `app/schemas/org.skepsun.kototoro.core.db.MangaDatabase/`
- 迁移文件 `core/db/migrations/Migration1To2.kt` 到 `Migration39To40.kt`
- 使用 KSP 生成 Kotlin 代码

**依赖注入**：
- Hilt/Dagger 用于依赖注入
- 需要在修改后运行 KSP 处理器

**测试框架**：
- JUnit5 + Kotest + MockK（单元测试）
- AndroidX Test + Hilt Testing（instrumented 测试）
- MockWebServer（网络测试）

## 开发注意事项

### 签名配置
Release 构建需要在 `local.properties` 或环境变量中配置签名：
```properties
RELEASE_STORE_FILE=/path/to/keystore
RELEASE_STORE_PASSWORD=***
RELEASE_KEY_ALIAS=***
RELEASE_KEY_PASSWORD=***
```

### 本地属性
`local.properties` 中可配置：
- `tg_backup_bot_token` - Telegram 备份机器人 token
- `dandanplay.appId` / `dandanplay.appSecret` - 弹弹 Play API 凭证

### 版本管理
在 `app/build.gradle` 中更新：
- `versionCode` - 每次发布递增
- `versionName` - 语义化版本号（如 "1.0.0.prev"）

### 代码风格
- 遵循 `.editorconfig`：UTF-8、LF、4 空格缩进、120 字符行宽
- Kotlin 官方代码风格，启用尾随逗号
- 类名使用 PascalCase，方法和属性使用 camelCase
- Android 资源使用小写下划线命名（如 `pref_appearance.xml`）

### 命名空间说明
项目主代码使用 `org.skepsun.kototoro` 命名空间（从 Kotatsu 分叉而来）。部分历史代码（如 instrumented test runner `org.koitharu.kotatsu.HiltTestRunner` 和部分 androidTest 测试类）仍保留原始包路径，这是正常现象，不要批量重命名。

### 提交规范
推荐使用 Conventional Commits 格式：
- `feat: ...` - 新功能
- `fix(scope): ...` - 修复
- `docs: ...` - 文档
- `chore: ...` - 杂项

### 翻译
翻译内容通过 Weblate 管理，避免手动批量修改字符串资源。

### 发布流程
参考 `.github/RELEASE_GUIDE.md`：
1. 更新 `versionCode` 和 `versionName`
2. 创建 Git 标签（如 `v1.0.0`）
3. 推送标签触发 GitHub Actions 自动构建
4. CI 自动生成 APK 和增量补丁并创建 Release

## 重要文档

- `docs/contributing.md` - 贡献指南
- `docs/development.md` - 开发入门
- `docs/getting-started.md` - 用户快速上手
- `docs/architecture/external-extension-integration-guide.md` - 外部扩展集成详解
- `docs/architecture/incremental-updates.md` - 增量更新机制
- `docs/architecture/dynamic_plugin_system.md` - 动态 UI 插件系统
- `docs/architecture/entity-graph-implementation-plan.md` - 实体关系图谱实现计划
- `docs/architecture/ncnn-super-resolution.md` - 超分辨率实现
- `docs/unified_source_management.md` - 源管理 UI 重构方案（草案）
- `.github/RELEASE_GUIDE.md` - 发布指南

## 常见任务

### 添加新的数据库迁移
1. 在 `core/db/migrations/` 创建新的 `MigrationXToY.kt`
2. 在 `MangaDatabase` 的 `companion object` 中注册迁移
3. 递增 `DATABASE_VERSION` 常量
4. 更新 `app/schemas/org.skepsun.kototoro.core.db.MangaDatabase/` 中的 schema JSON（可通过 Room KSP 自动生成）

### 添加新的源类型
1. 在对应模块（如 `mihon/`、`ireader/`）实现源接口
2. 注册到源管理系统
3. 添加 UI 入口（通常在 `settings/sources/`）

### 修改阅读器功能
1. 核心逻辑在 `reader/` 模块
2. 图片处理在 `image/` 模块
3. 视频播放在 `video/` 模块
4. 测试时验证不同内容类型（漫画、小说、视频）

### 调试网络问题
1. 检查 `core/network/` 中的拦截器
2. 查看 `browser/` 模块的 WebView 集成
3. 验证 Cookie 和代理配置


# 开发流程

## 核心规则

1. **收到任务时，先检查是否有匹配的 skill** — 哪怕只有 1% 的可能性也要检查（使用 `Skill` 工具加载，不要用 Read 读 SKILL.md）
2. **设计先于编码** — 收到功能需求时，先用 brainstorming skill 做需求分析
3. **测试先于实现** — 写代码前先写测试（TDD）
4. **验证先于完成** — 声称完成前必须运行验证命令并确认输出
