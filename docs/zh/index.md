---
layout: home

hero:
  name: Kototoro 文档
  text: 一款整合漫画、小说与视频的多媒体应用
  tagline: Android 优先，并提供 Windows 与 iOS 预览版。Kototoro 的安装指南、源生态集成参考、OCR 翻译、多端同步以及贡献者文档。
  image:
    src: /icon.png
    alt: Kototoro
  actions:
    - theme: brand
      text: 快速入门
      link: /zh/getting-started
    - theme: alt
      text: Windows 与 iOS
      link: /zh/platforms
    - theme: alt
      text: 配置内容源
      link: /zh/source-integrations
    - theme: alt
      text: 社区功能
      link: /zh/community
    - theme: alt
      text: GitHub
      link: https://github.com/Kototoro-app/Kototoro

features:
  - title: 多合一阅读与播放框架
    details: 在同一款 Android 应用中集中管理漫画、小说、视频、阅读历史、收藏夹与跨设备同步。
  - title: 本地 OCR 与自动翻译
    details: 在阅读器内直接完成离线文本检测识别、模型管理、在线 API 翻译以及原图/译文对照切换。
  - title: 本地文件导入
    details: 方便导入 CBZ、EPUB、TXT、MKV、MP4 等格式，支持强制指定内容类型以确保解析精准。
  - title: 多源生态无缝集成
    details: 在同一个应用界面中轻松接入 Mihon、Aniyomi、IReader、Legado（阅读）与 TVBox 源生态。
  - title: 进度追踪与全网发现
    details: 原生集成 MAL、Kitsu、AniList、Bangumi（番组计划）、Shikimori 及 MangaUpdates 发现与进度同步。
  - title: 强大内置播放器
    details: 支持本地与在线视频播放、DLNA 投屏、内嵌/外挂字幕与音轨切换、Anime4K / NCNN 超分辨率画质提升。
  - title: 可靠的 WebDAV 多端同步
    details: 通过任意标准 WebDAV 服务端（包括私有部署或云存储）无缝同步书库、进度与阅读历史。
  - title: Windows 与 iOS 预览版
    details: 基于 Kotlin Multiplatform 共享核心，在桌面端与苹果生态运行相同的书库格式与扩展插件。
  - title: 去中心化社区与推荐
    details: 基于 Nostr 与静态索引快照实现无服务器评分、讨论与本地隐私协同过滤推荐。
---

## 从最适合你的页面开始

> [!IMPORTANT]
> **免责声明：** Kototoro 本身不捆绑、不托管、亦不分发任何内容源、媒体资源或受版权保护的作品。应用仅作为通用的多媒体阅读器与播放器框架。所有内容源均由用户自行通过第三方扩展、本地文件导入或自定义 JSON 接口配置。开发者不对用户访问的任何第三方内容承担责任。

- 如果你是新用户，请先阅读 [快速入门指南](./getting-started.md)。
- 阅读 [Windows 与 iOS（预览版）](./platforms.md) 了解桌面端和苹果移动端的安装与功能覆盖。
- 阅读 [内容源生态集成](./source-integrations.md) 了解如何导入 Mihon、Aniyomi、Legado、TVBox 等扩展生态。
- 阅读 [社区功能](./community.md) 了解去中心化评分、评论讨论与身份密钥管理。
- 阅读 [推荐与发现](./recommendations.md) 了解本地品味画像建模与社区协同过滤算法。
- 阅读 [阅读器与播放器特性](./reader-features.md) 深入了解手势、双页阅读、超分辨率与投屏。
- 阅读 [自动翻译与 OCR](./automatic-translation.md) 配置本地离线检测识别与在线翻译。
- 阅读 [WebDAV 同步指南](./webdav-sync.md) 设置多设备备份与进度合并。
- 开发者或高级用户可查看 [架构总览](../architecture/architecture-review.md) 与 [架构演进路线](../architecture/architecture-roadmap.md)。
