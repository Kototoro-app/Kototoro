# Kototoro 漫画精简版

![App Icon](metadata/en-US/icon.png)

[![Version](https://img.shields.io/badge/version-2.5.3-green)](https://github.com/Asd12-h/Kotoro-manga-only/releases/tag/2.5.3)
[![Platform](https://img.shields.io/badge/platform-Android%20arm64--v8a-blue)](https://github.com/Asd12-h/Kotoro-manga-only/releases/tag/2.5.3)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)

## 📖 项目简介

**Kototoro 漫画精简版** 是基于上游 [Kototoro](https://github.com/Kototoro-app/Kototoro) 深度定制的分支，专注于**纯漫画阅读体验**。

相比原版，本分支移除了翻译、OCR、视频、小说等全部非漫画功能及相关重型依赖，APK 体积由 **62.6MB 精简至 26MB**，并针对本地漫画库的排序、筛选、刷新与阅读器流畅度做了大量优化。

> [!NOTE]
> 本分支由上游 devel 派生，仅保留漫画相关内容。原版的小说 / 视频 / 翻译 / 超分等功能在本分支中均已被移除。

### 🚀 最近更新 (v2.5.3)

1. **详情页性能优化**：进入详情页改为静默加载，不再闪刷新圈；去掉重复的本地目录扫描，复用章节快照；非下拉刷新不联网更新。
2. **阅读器 UI 自动隐藏**：上下滑动、翻页、滚动时自动隐藏界面，中间点按仍可显示 / 隐藏 UI。
3. **进度条实时跳页**：拖动进度条时画面实时跟随，松手不再转圈；连续宿主加 140ms 转圈宽限。
4. **本地页刷新优化**：打开 App / 进入页面不再自动刷新，仅下拉刷新；首屏空白优化。
5. **本地库排序与筛选**：新增多种排序方式与阅读状态筛选。

### 🎯 主要特性

#### 🌟 核心功能

- ✅ **纯漫画阅读** - 移除小说 / 视频 / 翻译等非漫画功能，聚焦漫画阅读
- ✅ **轻量体积** - APK 仅 26MB，相比原版减少约 58%
- ✅ **本地库排序** - 支持最新 / 字母 / 评分 / 相关度排序，按文件创建时间
- ✅ **阅读状态筛选** - 全部 / 未读 / 在读 / 已读完，快速定位要看的漫画
- ✅ **智能刷新** - 本地页仅在下拉时刷新，打开不等待
- ✅ **流畅进度条** - 拖动实时跳页，画面跟手，松手不转圈
- ✅ **自动隐藏 UI** - 滑动 / 翻页自动隐藏界面，沉浸阅读
- ✅ **多格式支持** - 本地 CBZ / ZIP / WebP / JPG 等漫画格式
- ✅ **丰富图源** - 继承上游 Mihon、Legado 等扩展与解析器支持

## 📝 更新日志

<details>
<summary>点击展开完整改动记录</summary>

### 1. 移除翻译相关功能
- 删除翻译 / OCR / 画面超分（Anime4K、ncnn native 库）全部代码
- 移除 ML Kit、ONNX、OpenCV、DJL Tokenizer 等重型依赖
- 设置页、详情页、阅读器的翻译入口全部剥离

### 2. 仅漫画化
- 新增 `ContentScopePolicy` 统一判定可展示内容类型
- 仓库与导航层过滤非漫画来源 / 空间 / 标签页
- 阅读器一律走漫画模式

### 3. 本地库排序与筛选
- 新增排序：最新 / 字母 / 评分 / 相关度（按文件创建时间）
- 新增阅读状态筛选：全部 / 未读 / 在读 / 已读完
- 本地页工具栏加入排序与筛选控件

### 4. 本地页刷新行为
- 打开 App / 进入页面不再自动刷新，仅下拉才刷新
- 索引改用 `tryLock` + `hasEntries`，避免阻塞首屏
- 文件修改时间缓存到内存 + 磁盘

### 5. 首屏空白优化
- 修改时间查询并行化（8 并发）
- 首次筛选跳过 250ms 防抖

### 6. 阅读器进度条
- 拖动实时跳页（live scrubbing），画面跟手、松手不转圈
- 连续宿主加 140ms 转圈宽限
- 滑动 / 翻页 / 滚动 / 按键导航自动隐藏 UI，中间点按仍切换显示

### 7. 详情页性能
- 进入时静默加载，不再闪刷新圈
- 去掉重复的本地目录扫描，复用章节快照
- 非下拉刷新不联网更新关联源

</details>

## 📥 安装

从 [Releases 页面](https://github.com/Asd12-h/Kotoro-manga-only/releases/tag/2.5.3) 下载最新版本 APK：

- **kotoro-v2.7.1-arm64.apk** — arm64-v8a 架构，签名 `CN=kotoro`
- versionCode 1274，release 签名

> 本分支目前仅提供 arm64-v8a 版本，适用于绝大多数现代 Android 设备。

## 🛠️ 技术栈

- **Kotlin** - 主要开发语言
- **Jetpack Compose** - 全 Compose UI
- **Kotlin Multiplatform** - 共享核心代码
- **Hilt** - 依赖注入
- **Room** - 本地数据库
- **Coil** - 图片加载与缓存

## 🔧 开发

### 环境要求

- Android Studio 2022.3+
- JDK 17+
- Android SDK 33+
- Gradle 9.0+

### 构建命令

```bash
JAVA_HOME=/path/to/jdk-17 ./gradlew :app:assembleRelease -PmangaOnlyArm64
```

产物位于 `app/build/outputs/apk/release/app-arm64-v8a-release.apk`

## 📝 许可证

本项目基于上游 Kototoro 的许可证，采用 Apache License 2.0，详情请查看 [LICENSE](LICENSE) 文件。

## 🤝 致谢

- [**Kototoro**](https://github.com/Kototoro-app/Kototoro) - 上游项目，提供优秀的 Compose 阅读器基础
- [**Kotatsu**](https://github.com/KotatsuApp/Kotatsu) - 原始项目的开发者
- [**Mihon**](https://github.com/mihonapp/mihon) - 优秀的开源漫画阅读器，提供插件系统参考
- [**Yokai**](https://github.com/yokai-team/yokai) - 阅读器进度条交互参考

## 📞 联系方式

- **GitHub Issues**: [问题反馈](https://github.com/Asd12-h/Kotoro-manga-only/issues)

---

⭐ 如果这个项目对你有帮助，请给一个 Star！
