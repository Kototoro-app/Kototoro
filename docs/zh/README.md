# Kototoro 官方文档导航

## 界面开发相关

- [Kototoro 设计语言](../design/README.md)
- [界面样式系统实现](../development/interface-style-system.md)

本目录按任务场景组织，而非按内部实现代码堆叠。请从最符合你当前需求的页面开始。

> [!IMPORTANT]
> **Kototoro 不包含、不托管、亦不分发任何内容源、媒体资源或受版权保护的作品。** 应用仅作为通用的多媒体阅读器与播放器框架。所有内容源均由用户自行通过第三方扩展、本地文件导入或自定义 JSON 接口配置。开发者对用户通过安装内容源访问到的任何资源概不负责。

> **加入社区讨论：** [Discord](https://discord.gg/xBXvPz7tr7)

---

## 快速导航

| 如果你想... | 请阅读 |
| :--- | :--- |
| 安装应用并快速上手 | [快速入门指南](./getting-started.md) |
| 了解应用的核心视听与阅读特性 | [阅读器与播放器特性](./reader-features.md) |
| 导入本地文件（CBZ, EPUB, 视频） | [本地文件导入指南](./local-import.md) |
| 配置本地离线 OCR 与自动翻译 | [自动翻译与 OCR](./automatic-translation.md) |
| 接入外部源生态（Mihon/Legado/TVBox） | [内容源生态集成](./source-integrations.md) |
| 开启去中心化社区评分与讨论 | [社区功能指南](./community.md) |
| 探索与定制个性化推荐 | [推荐与发现](./recommendations.md) |
| 使用 WebDAV 在多台设备间同步 | [WebDAV 多端同步指南](./webdav-sync.md) |
| 查阅常见疑问解答 | [常见问题解答 (FAQ)](./faq.md) |
| 解决常见运行与配置故障 | [问题排查与修复](./troubleshooting.md) |
| 参与开发、测试与贡献 | [开发指南](./development.md) 与 [贡献准则](./contributing.md) |

---

## 文档地图

### 面向普通用户

- [快速入门指南](./getting-started.md)
- [Windows 与 iOS（预览版）](./platforms.md)
- [阅读器与播放器特性](./reader-features.md) — 漫画、小说、视频、全网追踪、DLNA 投屏与字幕
- [本地文件导入指南](./local-import.md) — CBZ、EPUB、TXT、MKV、MP4 等
- [自动翻译与 OCR](./automatic-translation.md)
- [内容源生态集成](./source-integrations.md) — 内置源、Kotatsu-Redo、Mihon、Aniyomi、IReader、Legado、TVBox
- [社区功能指南](./community.md) — 基于 Nostr 的评分、讨论、榜单与恢复密钥保管
- [推荐与发现](./recommendations.md) — 本地品味画像、协同过滤与纯本地隐私计算
- [WebDAV 多端同步指南](./webdav-sync.md)
- [常见问题解答 (FAQ)](./faq.md)
- [问题排查与修复](./troubleshooting.md)

### 面向进阶用户与架构参考

- [架构总览](../architecture/architecture-review.md)
- [架构演进路线](../architecture/architecture-roadmap.md)
- [社区 Nostr 方案设计](../architecture/community-nostr-plan-2026-11.md)
- [KMP 与 iOS 迁移规划](../architecture/kmp-ios-plan-2026-10.md)
- [Windows 特性对齐](../architecture/windows-feature-parity-2026-10.md)
- [Linux 原生能力](../architecture/desktop-linux-native-2026-10.md)
- [iOS 特性对齐](../architecture/ios-android-feature-parity-2026-10.md)
- [阅读器场景收敛方案](../architecture/reader-scene-closure-plan-2026-09.md)
- [收藏书库方案](../architecture/favourites-komikku-alignment-implementation-plan-2026-09.md)
- [统一 Cloudflare 解算器方案](../architecture/unified-cloudflare-solver-plan-2026-08.md)
- [Mihon 兼容层参考](../reference/mihon-integration.md)
- [TVBox 运行时兼容性](../reference/tvbox-runtime.md)
- [Legado 书源适配分析](../reference/legado-adaptation-gap-analysis.md)

### 面向开发者与贡献者

- [开发指南](./development.md)
- [贡献准则](./contributing.md)
- [Issue 反馈指引](./issue-submission-guide.md)
- [工具链升级规划](../architecture/toolchain-upgrade-plan-2026-08.md)
- [第三方扩展接入指南](../architecture/external-extension-integration-guide.md)

---

## 文档撰写准则

- 根目录 `README.md` 保持简练，突出产品定位。
- `docs/` 保持任务导向，注重实际操作步骤与排错建议。
- 用户端功能变更时，请同步更新中英文对应的文档页面。
