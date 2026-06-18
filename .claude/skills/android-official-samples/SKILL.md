---
name: android-official-samples
description: >
  Android 官方示例代码库完整索引。涵盖架构、Compose UI、性能、测试、存储、网络、相机、媒体、安全等所有领域。
  安卓开发第一要义：参考官方文档和案例！遇到任何 Android 开发问题，首先查阅官方示例而非第三方博客。
  Triggers: "Android 官方示例", "参考官方代码", "Android sample", "官方架构", "Jetpack sample",
  "Compose 示例", "Room 示例", "Navigation 示例", "如何实现 XX 功能", "最佳实践"
---

# Android 官方示例代码库索引

> **安卓开发第一要义：参考官方文档和案例！**
>
> Google 在 https://github.com/android 维护了数百个高质量示例项目，涵盖 Android 开发的方方面面。
> 遇到任何问题，优先查阅这些官方仓库——它们是 Android 团队维护的权威最佳实践来源，
> 比第三方博客、StackOverflow 回答更可靠、更新更及时。
>
> **💡 最全面的参考仓库：[nowinandroid](https://github.com/android/nowinandroid)**
> 一个仓库覆盖了 Compose、Room、Hilt、Navigation、Coroutines、WorkManager、Paging、DataStore 八大技术栈，
> 是 Google 官方维护的**生产级**参考应用。如果你的技术栈和它相似（Kototoro 就是），
> 优先以 nowinandroid 为单一参考源，保持模式一致性。

---

## 📊 技术栈 → 官方仓库速查表

| 技术 | 最佳参考仓库 | 补充参考 |
|------|------------|---------|
| Jetpack Compose | [nowinandroid](https://github.com/android/nowinandroid) | [compose-samples](https://github.com/android/compose-samples) |
| Room Database | [nowinandroid](https://github.com/android/nowinandroid) (:core:data) | [architecture-samples](https://github.com/android/architecture-samples) |
| Hilt/DI | [nowinandroid](https://github.com/android/nowinandroid) (全模块) | [google/dagger](https://github.com/google/dagger) |
| Navigation | [nowinandroid](https://github.com/android/nowinandroid) (:feature:*) | [nav3-recipes](https://github.com/android/nav3-recipes) |
| Kotlin Coroutines | [nowinandroid](https://github.com/android/nowinandroid) | [codelab-kotlin-coroutines](https://github.com/android/codelab-kotlin-coroutines) |
| WorkManager | [nowinandroid](https://github.com/android/nowinandroid) (:sync:work) | [codelab-android-workmanager](https://github.com/android/codelab-android-workmanager) |
| Paging 3 | [nowinandroid](https://github.com/android/nowinandroid) (新闻流) | [codelab-android-paging](https://github.com/android/codelab-android-paging) |
| DataStore | [nowinandroid](https://github.com/android/nowinandroid) (:core:datastore) | [storage-samples](https://github.com/android/storage-samples) |
| CameraX | [camera-samples](https://github.com/android/camera-samples) | — |
| Media3/ExoPlayer | [androidx/media](https://github.com/androidx/media) ⚠️ | [media-samples](https://github.com/android/media-samples) |
| Testing | [testing-samples](https://github.com/android/testing-samples) | [nowinandroid](https://github.com/android/nowinandroid) (测试示例) |
| Performance | [performance-samples](https://github.com/android/performance-samples) | [nowinandroid](https://github.com/android/nowinandroid) (benchmark 模块) |

---

## 🏗️ 架构与应用设计 (Architecture & App Design)

这些仓库是学习 Android 应用架构、模块化设计、以及完整应用开发的最佳入口。

| 仓库 | ⭐ | 说明 |
|------|-----|------|
| [architecture-samples](https://github.com/android/architecture-samples) | 45.7k | **架构示例集合**：展示不同架构模式（MVI、MVVM、UDF）及其演进。UDF + StateFlow 是当前推荐模式。 |
| [nowinandroid](https://github.com/android/nowinandroid) | 21.4k | **完整功能应用**：纯 Kotlin + Jetpack Compose 构建的新闻应用。展示模块化架构、Gradle 版本目录、CI/CD 等生产级实践。**架构参考首选。** |
| [architecture-templates](https://github.com/android/architecture-templates) | 3.1k | **架构模板**：官方 Android 项目脚手架，提供 Activity/Compose 等多套模板，快速启动新项目。 |
| [sunflower](https://github.com/android/sunflower) | 17.8k | **园艺应用**：展示从 View 迁移到 Jetpack Compose 的最佳实践。含 Room、WorkManager、Navigation、Paging。 |
| [platform-samples](https://github.com/android/platform-samples) | 1.7k | **平台 API 示例集**：展示不同 Android 平台 API 的使用方法（Notification、Shortcut、ShareSheet 等）。 |

### 🔗 架构相关 Codelabs
| 仓库 | ⭐ | 说明 |
|------|-----|------|
| [codelab-android-room-with-a-view](https://github.com/android/codelab-android-room-with-a-view) | 768 | Room 数据库 + ViewModel + LiveData/Flow 架构基础 |
| [codelab-android-lifecycles](https://github.com/android/codelab-android-lifecycles) | 624 | Lifecycle 感知组件 |
| [codelab-android-databinding](https://github.com/android/codelab-android-databinding) | 101 | Data Binding 库 |
| [codelab-android-dynamic-features](https://github.com/android/codelab-android-dynamic-features) | 128 | Dynamic Feature 模块 |

**适用场景**：应用架构设计、模块化、依赖注入（Hilt）、状态管理、完整应用参考。

---

## 🎨 Jetpack Compose & UI

Compose 是现代 Android UI 开发的推荐方式。

| 仓库 | ⭐ | 说明 |
|------|-----|------|
| [compose-samples](https://github.com/android/compose-samples) | 23.2k | **官方的 Jetpack Compose 示例集**：含 JetNews、Jetchat、JetSnack、Crane、Owl、Reply 等完整示例。 |
| [user-interface-samples](https://github.com/android/user-interface-samples) | 4.6k | **UI 最佳实践集**：涵盖 WindowInsets、手势、动画、自定义布局、可访问性等。 |
| [platform-samples](https://github.com/android/platform-samples) | 1.7k | 平台 UI API：通知、快捷方式、分屏、画中画等。 |
| [uamp](https://github.com/android/uamp) | 13.2k | 音频播放应用，展示 Material Design 和 MediaSession。 |
| [topeka](https://github.com/android/topeka) | 5.1k | Material Design 问答应用。 |
| [androidify](https://github.com/android/androidify) | 2.0k | 展示自定义 View 的应用。 |
| [large-screen-codelabs](https://github.com/android/large-screen-codelabs) | 60 | 大屏和折叠屏适配。 |
| [adaptive-apps-samples](https://github.com/android/adaptive-apps-samples) | 62 | 自适应布局。 |

### 🔗 Compose 相关 Codelabs
| 仓库 | ⭐ | 说明 |
|------|-----|------|
| [codelab-android-navigation](https://github.com/android/codelab-android-navigation) | 633 | Jetpack Navigation (View + Compose) |
| [codelab-constraint-layout](https://github.com/android/codelab-constraint-layout) | 471 | ConstraintLayout |
| [codelab-android-accessibility](https://github.com/android/codelab-android-accessibility) | 129 | 可访问性 |
| [nav3-recipes](https://github.com/android/nav3-recipes) | 1.3k | Navigation3 常见用例实现（类型安全导航）。 |

**适用场景**：Compose UI 实现、动画、手势、主题、导航、Material3、自适应布局。

---

## 💾 数据与存储 (Data & Storage)

| 仓库 | ⭐ | 说明 |
|------|-----|------|
| [storage-samples](https://github.com/android/storage-samples) | 1.7k | **存储最佳实践集**：Scoped Storage、MediaStore、SAF、DataStore 等。 |
| [databinding-samples](https://github.com/android/databinding-samples) | 1.7k | Data Binding 库示例（与 Room + ViewModel 结合）。 |

### 🔗 数据相关 Codelabs
| 仓库 | ⭐ | 说明 |
|------|-----|------|
| [codelab-android-room-with-a-view](https://github.com/android/codelab-android-room-with-a-view) | 768 | Room 数据库完整教程 |
| [codelab-android-paging](https://github.com/android/codelab-android-paging) | 512 | Jetpack Paging 3 分页加载 |

**适用场景**：Room 数据库、DataStore、文件存储、分页加载。

---

## 🧪 测试 (Testing)

| 仓库 | ⭐ | 说明 |
|------|-----|------|
| [testing-samples](https://github.com/android/testing-samples) | 9.3k | **测试示例集**：单元测试、UI 测试（Compose + Espresso）、集成测试、JUnit5、MockK、Hilt 测试。 |
| [android-test](https://github.com/android/android-test) | 1.2k | Android 测试框架源码。 |

**适用场景**：JUnit5 单元测试、Compose UI 测试、Hilt 测试、Espresso。

---

## ⚡ 性能 (Performance)

| 仓库 | ⭐ | 说明 |
|------|-----|------|
| [performance-samples](https://github.com/android/performance-samples) | 1.4k | **性能最佳实践集**：Baseline Profile、Startup、JankStats、Macrobenchmark、Memory。 |
| [codelab-android-performance](https://github.com/android/codelab-android-performance) | 41 | 性能优化 Codelab。 |

**适用场景**：启动优化、Jank 诊断、Baseline Profile、内存优化、Macrobenchmark。

---

## 📷 相机 (Camera)

| 仓库 | ⭐ | 说明 |
|------|-----|------|
| [camera-samples](https://github.com/android/camera-samples) | 5.4k | **CameraX/Camera2 最佳实践集**：预览、拍照、录像、图像分析、ML Kit 集成等。 |

**适用场景**：CameraX、Camera2、相机权限、图像分析。

---

## 🔔 通知 (Notifications)

通知示例分布在 `user-interface-samples` 和 `platform-samples` 中。

| 主题 | 参考位置 |
|------|---------|
| Notification Channels | [platform-samples](https://github.com/android/platform-samples) |
| Foreground Service 通知 | [workmanager codelab](https://github.com/android/codelab-android-workmanager) |
| MediaStyle 通知 | [uamp](https://github.com/android/uamp) |

**适用场景**：下载进度通知、新章节提醒、前台服务。

---

## 🔒 安全与隐私 (Security & Privacy)

| 仓库 | ⭐ | 说明 |
|------|-----|------|
| [security-samples](https://github.com/android/security-samples) | 1.0k | 加密、KeyStore、Biometric、签名。 |
| [privacy-sandbox-samples](https://github.com/android/privacy-sandbox-samples) | 209 | Privacy Sandbox on Android。 |

**适用场景**：数据加密、生物识别认证、隐私合规。

---

## 🌐 网络与连接 (Connectivity)

| 仓库 | ⭐ | 说明 |
|------|-----|------|
| [connectivity-samples](https://github.com/android/connectivity-samples) | 1.8k | **连接最佳实践集**：蓝牙、NFC、WiFi Direct、NetworkCallback。 |

**适用场景**：蓝牙通信、NFC、WiFi P2P、网络状态监听。

---

## 📍 位置 (Location)

| 仓库 | ⭐ | 说明 |
|------|-----|------|
| [location-samples](https://github.com/android/location-samples) | 2.7k | **位置 API 最佳实践集**：Fused Location Provider、Geofence、Activity Recognition。 |

**适用场景**：位置获取、地理围栏、活动识别。

---

## 🎵 媒体 (Media)

| 仓库 | ⭐ | 说明 |
|------|-----|------|
| [androidx/media](https://github.com/androidx/media) | **官方** | **ExoPlayer/Media3 主仓库**（⚠️ 旧 `google/ExoPlayer` 已归档，所有开发在此）。含 ExoPlayer 核心、PlayerView、MediaSession、OkHttp DataSource。 |
| [media-samples](https://github.com/android/media-samples) | 1.4k | **媒体 API 最佳实践集**：Media3/ExoPlayer、MediaSession、音频焦点管理。 |
| [uamp](https://github.com/android/uamp) | 13.2k | 音频播放完整示例。 |
| [midi-samples](https://github.com/android/midi-samples) | 53 | MIDI 最佳实践。 |

### 🔗 媒体相关 Codelabs
| 仓库 | ⭐ | 说明 |
|------|-----|------|
| [codelab-exoplayer-intro](https://github.com/android/codelab-exoplayer-intro) | 235 | Media Streaming with ExoPlayer |

**适用场景**：视频/音频播放、Media3/ExoPlayer、MediaSession、音频焦点。

> ⚠️ **ExoPlayer 迁移注意**：Google 已将 ExoPlayer 从独立的 `google/ExoPlayer` 仓库迁移至 `androidx/media`。旧仓库已归档，所有新 API 和 bug 修复都在 androidx/media。Kototoro 使用的 Media3/ExoPlayer 应参考此仓库。

---

## 🔒 安全 (Security)

| 仓库 | ⭐ | 说明 |
|------|-----|------|
| [security-samples](https://github.com/android/security-samples) | 1.0k | **安全最佳实践集**：加密、KeyStore、Biometric、App Bundles 签名。 |

**适用场景**：KeyStore、生物识别、数据加密、安全存储。

---

## 🎯 后台任务 (Background Tasks)

| 仓库 | ⭐ | 说明 |
|------|-----|------|
| [codelab-android-workmanager](https://github.com/android/codelab-android-workmanager) | 559 | **WorkManager 完整教程**：周期性任务、约束条件、链式任务。 |

**适用场景**：WorkManager 后台任务调度。

---

## ⌚ Wear OS

| 仓库 | ⭐ | 说明 |
|------|-----|------|
| [wear-os-samples](https://github.com/android/wear-os-samples) | 1.4k | **Wear OS 最佳实践集**：表盘、Complications、Tile、Health Services。 |

---

## 🧠 AI/ML

| 仓库 | ⭐ | 说明 |
|------|-----|------|
| [ai-samples](https://github.com/android/ai-samples) | 598 | **AI 应用示例**：Gemini API、ML Kit、On-device ML。 |
| [codelab-mlkit-android](https://github.com/android/codelab-mlkit-android) | 188 | ML Kit Codelab。 |
| [neural-networks-samples](https://github.com/android/neural-networks-samples) | 27 | Android Neural Networks API (NNAPI)。 |

---

## 🔧 Kotlin 语言与工具

| 仓库 | ⭐ | 说明 |
|------|-----|------|
| [kotlin-guides](https://github.com/android/kotlin-guides) | 1.7k | Kotlin for Android 编码指南。 |
| [codelab-kotlin-coroutines](https://github.com/android/codelab-kotlin-coroutines) | 555 | **Kotlin Coroutines 完整教程**。 |
| [kotlin-multiplatform-samples](https://github.com/android/kotlin-multiplatform-samples) | 1.3k | Kotlin Multiplatform (KMP) 示例。 |
| [snippets](https://github.com/android/snippets) | 1.1k | developer.android.com 上展示的代码片段集。 |

---

## 🎮 NDK / 原生开发 / XR

| 仓库 | ⭐ | 说明 |
|------|-----|------|
| [ndk-samples](https://github.com/android/ndk-samples) | 10.5k | NDK 示例（含 Android Studio 项目配置）。 |
| [xr-samples](https://github.com/android/xr-samples) | 448 | Android XR 示例。 |

---

## 🚀 如何使用这些官方仓库

### 1. 遇到问题时的工作流

```
1. 先查本索引 → 找到相关官方仓库
2. 在仓库中搜索关键词 → 找到具体代码
3. 阅读 README 和代码注释 → 理解上下文
4. 参照实现 → 适配到当前项目
```

### 2. 学习新技术时

```
1. 找到对应的 Codelab 仓库（如 codelab-android-xxx）
2. Clone 后运行 → 观察运行效果
3. 对比 starter/ 和 solution/ 目录 → 理解实现过程
```

### 3. 架构决策时

```
主参考：architecture-samples + nowinandroid
- 看 UDF 模式如何实现
- 看模块化如何划分
- 看测试如何编写
```

### 4. Kototoro 项目相关仓库优先级

本项目（Kototoro）使用 Compose + Room + Hilt + Navigation + Coil + ExoPlayer + Coroutines。
以下是按优先级排序的参考仓库：

**P0 — 必须参考（核心技术栈完全匹配）**：
| 仓库 | 原因 |
|------|------|
| [nowinandroid](https://github.com/android/nowinandroid) ⭐21.4k | **#1 参考源**：同一个技术栈（Compose+Room+Hilt），模块化架构，离线优先模式 |
| [architecture-samples](https://github.com/android/architecture-samples) ⭐45.7k | 基础 MVVM + Repository 模式、ViewModel 测试 |
| [compose-samples](https://github.com/android/compose-samples) ⭐23.2k | 所有 Compose UI 模式：主题、导航、动画、自适应布局 |

**P1 — 强烈推荐（特定功能参考）**：
| 仓库 | 原因 |
|------|------|
| [testing-samples](https://github.com/android/testing-samples) ⭐9.3k | Compose 测试、Hilt 测试、Room 测试 |
| [codelab-android-workmanager](https://github.com/android/codelab-android-workmanager) ⭐559 | 下载管理器后台任务模式 |
| [codelab-android-paging](https://github.com/android/codelab-android-paging) ⭐512 | 漫画目录无限滚动 |
| [storage-samples](https://github.com/android/storage-samples) ⭐1.7k | DataStore 偏好存储、Scoped Storage |
| [performance-samples](https://github.com/android/performance-samples) ⭐1.4k | 阅读器性能、启动优化 |

**P2 — 按需参考**：
| 仓库 | 原因 |
|------|------|
| [sunflower](https://github.com/android/sunflower) ⭐17.8k | Room + WorkManager 综合（但主要基于 View） |
| [user-interface-samples](https://github.com/android/user-interface-samples) ⭐4.6k | 手势、Insets、动画细节 |
| [androidx/media](https://github.com/androidx/media) | Media3/ExoPlayer ⚠️ 旧 google/ExoPlayer 已归档 |
| [architecture-templates](https://github.com/android/architecture-templates) ⭐3.1k | 项目结构验证、脚手架参考 |
| [codelab-kotlin-coroutines](https://github.com/android/codelab-kotlin-coroutines) ⭐555 | 协程最佳实践 |
| [large-screen-codelabs](https://github.com/android/large-screen-codelabs) | 平板/折叠屏阅读器布局 |

---

## 📚 补充资源

- **官方文档**：[developer.android.com](https://developer.android.com)
- **设计指南**：[Material Design 3](https://m3.material.io)
- **Compose 文档**：[developer.android.com/compose](https://developer.android.com/compose)
- **AndroidX 发布说明**：[developer.android.com/jetpack/androidx/versions](https://developer.android.com/jetpack/androidx/versions)
- **Google Codelabs**：[codelabs.developers.google.com](https://codelabs.developers.google.com/?cat=android)
- **完整仓库列表**：[github.com/orgs/android/repositories](https://github.com/orgs/android/repositories?type=public)

---

## ⚠️ 注意事项

1. **Google 正在整合示例仓库**：一些原本独立的仓库（如 `android-testing`、`android-architecture` 的某些子项目）已被合并到 `nowinandroid` 或 `architecture-samples` 中。如发现链接失效，在 GitHub 上搜索最新位置。

2. **ExoPlayer 迁移**：旧仓库 `google/ExoPlayer` 已归档，所有开发移至 [androidx/media](https://github.com/androidx/media)。

3. **构建系统差异**：`nowinandroid` 使用 Kotlin DSL (`.kts`)，而 Kototoro 的 `app/build.gradle` 使用 Groovy DSL。学习架构和代码模式，但不要直接复制构建脚本。

4. **版本差异**：官方示例可能使用较新的 AndroidX 版本或不同的依赖版本。始终参考 Kototoro 的 `libs.versions.toml` 确定当前使用的版本。
