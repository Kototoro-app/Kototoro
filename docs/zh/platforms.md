# Windows 与 iOS（预览版）

Kototoro 以 Android 为首发和核心平台。目前，借助 **Kotlin Multiplatform (KMP)** 跨平台架构，相同的数据库模型、备份格式、扩展源协议、阅读器几何排版以及 Compose 共享界面，也已延伸到了 **Windows 桌面端** 以及 **iOS / iPadOS** 平台。

这两者目前处于**预览阶段（Preview）**：每次 Nightly 与正式 Release 都会由 CI 自动构建并进行冒烟测试，但功能覆盖度较 Android 仍有剪裁，且尚未经历大规模各机型兼容性验证。

---

## 安装包下载

| 平台 | 正式版 (Release) | 测试版 (Nightly) |
| :--- | :--- | :--- |
| **Android** | [Releases](https://github.com/Kototoro-app/Kototoro/releases) (APK) | [Nightly](https://github.com/Kototoro-app/Kototoro-Nightly/releases) (APK) |
| **Windows x64** | Releases：`…-windows-x64-release.zip` 与 `.msi` | Nightly：`…-windows-x64-nightly.zip` |
| **iOS / iPadOS** | Releases：`…-ios-release-unsigned.ipa` | Nightly：`…-ios-nightly-unsigned.ipa` |

每个安装包同级目录下均附带对应的 `.sha256` 校验和文件。

---

## Windows 桌面端

### 安装方式
- **绿色便携版（Portable ZIP）**：解压整个压缩包，双击运行 `Kototoro.exe`。请保持目录结构完整，程序内嵌了精简 Java 运行环境（`runtime/`）以及播放与画质超分辨率库，**无需额外安装系统 JDK**。
- **安装包（MSI，仅正式版）**：安装至当前用户的本地应用目录 `%LOCALAPPDATA%\Kototoro-App`。

用户的个人书库和配置保存在 `%LOCALAPPDATA%\Kototoro` 中，更新程序或重新解压不会丢失数据。也可以通过 `Kototoro.exe --data-dir <目录路径>` 指定独立的数据存储目录。

### 系统要求
- Windows 10/11 64 位。
- 内置浏览器（网页调试与 Cloudflare 验证）依赖 [Microsoft Edge WebView2 Runtime](https://developer.microsoft.com/microsoft-edge/webview2/)（现代 Windows 均已自带）。
- 视频播放与超分辨率采用 Vulkan 接口，请确保显卡驱动支持。

### 已支持特性
- 与 Android 共享平板宽屏布局：首页、收藏夹、浏览、历史、更新及详情页。
- 扩展支持：**Mihon** 漫画扩展、**Tsundoku** 小说扩展、**Aniyomi** 视频扩展、**CloudStream** 插件以及各类内置 JAR 规则。APK 格式扩展会在本地自动转译为 JVM JAR。
- 漫画阅读器：单双页展开、RTL 翻页、平滑纵向卷轴、跨章连读、离线下载、RealCUGAN/Anime4K 画质提升。
- 小说阅读器：连续文本滚动、字体版式微调、目录与书签。
- 视频播放：基于内嵌 libmpv 引擎，支持外挂字幕、音轨切换与 DLNA 投屏。
- 完整的书库备份与 WebDAV 多端同步兼容。

---

## iOS / iPadOS 预览版

- 通过 Sideloadly、AltStore、TrollStore 等自签名工具安装 `.ipa` 文件。
- 提供原生的滑动返回与系统手势交互，书库数据与 Android 完全互通。

---

## 相关文档

- [文档首页](./index.md)
- [快速入门指南](./getting-started.md)
- [内容源生态集成](./source-integrations.md)
