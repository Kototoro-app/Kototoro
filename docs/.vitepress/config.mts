import { defineConfig } from "vitepress";

const base = process.env.GITHUB_ACTIONS ? "/Kototoro/" : "/";
const editBranch = process.env.DOCS_EDIT_BRANCH || process.env.GITHUB_REF_NAME || "devel";

export default defineConfig({
  title: "Kototoro Docs",
  description: "Documentation for Kototoro: manga, novels, video, OCR translation, and source integrations.",
  base,
  // Citation links that point outside this docs site can never resolve as pages: local workstation
  // absolute paths and sibling-repository checkouts (see the komikku / mihon research notes). They
  // carry source evidence, not navigation, so they are whitelisted instead of being unlinked.
  ignoreDeadLinks: [
    /app\/src\//,
    /\/build\.gradle/,
    /(\.\.\/)+scripts\//,
    /^\/Users\//,
    /(\.\.\/)+komikku\//,
    // Source files cited from the iOS parity/audit notes. These relative links are correct — they
    // resolve on GitHub and in an IDE — but a .kt/.swift/.gradle file is not a site page, so the
    // VitePress build treats them as dead. Listed per module rather than as a blanket `(\.\./)+` so
    // a genuinely broken doc-to-doc link still fails the build.
    /(\.\.\/)+(iosApp|ios-shared|reader-core|core-local|core-ui|core-library|source-host|parser-host|mihon-ios-shims)\//,
  ],
  lastUpdated: true,
  cleanUrls: true,
  vite: {
    build: {
      chunkSizeWarningLimit: 5500,
    },
  },
  head: [
    ["link", { rel: "icon", href: `${base}icon.png` }],
    ["meta", { name: "theme-color", content: "#0f766e" }],
  ],
  locales: {
    root: {
      label: "English",
      lang: "en-US",
      description: "Documentation for Kototoro: manga, novels, video, OCR translation, and source integrations.",
      themeConfig: {
        nav: [
          { text: "Guides", link: "/getting-started" },
          { text: "Reference", link: "/reference/mihon-integration" },
          { text: "Development", link: "/development" },
          { text: "GitHub", link: "https://github.com/Kototoro-app/Kototoro" },
        ],
        sidebar: [
          {
            text: "Guides",
            items: [
              { text: "Getting Started", link: "/getting-started" },
              { text: "Windows & iOS (Preview)", link: "/platforms" },
              { text: "Reader Features", link: "/reader-features" },
              { text: "Local Import Guide", link: "/local-import" },
              { text: "Automatic Translation", link: "/automatic-translation" },
              { text: "Source Integrations", link: "/source-integrations" },
              { text: "Community Features", link: "/community" },
              { text: "Recommendations & Discovery", link: "/recommendations" },
              { text: "WebDAV Sync", link: "/webdav-sync" },
              { text: "FAQ", link: "/faq" },
              { text: "Troubleshooting", link: "/troubleshooting" },
            ],
          },
          {
            text: "Reference",
            items: [
              { text: "Architecture Review", link: "/architecture/architecture-review" },
              { text: "Architecture Roadmap", link: "/architecture/architecture-roadmap" },
              { text: "Community Architecture Plan", link: "/architecture/community-nostr-plan-2026-11" },
              { text: "KMP / iOS Plan", link: "/architecture/kmp-ios-plan-2026-10" },
              { text: "Windows Feature Parity", link: "/architecture/windows-feature-parity-2026-10" },
              { text: "Linux Native Capabilities", link: "/architecture/desktop-linux-native-2026-10" },
              { text: "iOS Feature Parity", link: "/architecture/ios-android-feature-parity-2026-10" },
              { text: "Reader Scene Closure Plan (2026-09)", link: "/architecture/reader-scene-closure-plan-2026-09" },
              { text: "Favourites Library Plan", link: "/architecture/favourites-komikku-alignment-implementation-plan-2026-09" },
              { text: "Unified Cloudflare Solver", link: "/architecture/unified-cloudflare-solver-plan-2026-08" },
              { text: "OCR Architecture Review", link: "/architecture/ocr-architecture-review" },
              { text: "OCR Pipeline", link: "/architecture/ocr-pipeline-v2" },
              { text: "UI Improvement", link: "/architecture/ui_improvement" },
              { text: "Compose Migration Roadmap", link: "/compose_migration/cmp-liquid-glass-migration" },
              { text: "Compose Migration Log", link: "/compose_migration/progress-log" },
              { text: "Mihon Integration", link: "/reference/mihon-integration" },
              { text: "TVBox Runtime Compatibility", link: "/reference/tvbox-runtime" },
              { text: "Legado Adaptation Gap Analysis", link: "/reference/legado-adaptation-gap-analysis" },
              { text: "External Extension Integration Guide", link: "/architecture/external-extension-integration-guide" },
            ],
          },
          {
            text: "Development",
            items: [
              { text: "Development", link: "/development" },
              { text: "Contributing", link: "/contributing" },
              { text: "Issue Submission Guide", link: "/issue-submission-guide" },
            ],
          },
          {
            text: "Archive",
            items: [
              { text: "Archive Overview", link: "/archive/" },
              { text: "OCR Roadmap Review (2026-03)", link: "/archive/ocr-roadmap-review-2026-03" },
              { text: "Entity System (removed 2026-09)", link: "/archive/entity-system/" },
              { text: "Archived Mihon Notes (ZH)", link: "/archive/zh/mihon-compatibility" },
            ],
          },
        ],
        editLink: {
          pattern: `https://github.com/Kototoro-app/Kototoro/edit/${editBranch}/docs/:path`,
          text: "Edit this page on GitHub",
        },
        docFooter: {
          prev: "Previous page",
          next: "Next page",
        },
      },
    },
    zh: {
      label: "简体中文",
      lang: "zh-CN",
      link: "/zh/",
      title: "Kototoro 文档",
      description: "Kototoro 中文使用与开发文档：聚合漫画、小说、视频、OCR 翻译及扩展源生态。",
      themeConfig: {
        nav: [
          { text: "指南", link: "/zh/getting-started" },
          { text: "参考", link: "/architecture/community-nostr-plan-2026-11" },
          { text: "开发", link: "/zh/development" },
          { text: "GitHub", link: "https://github.com/Kototoro-app/Kototoro" },
        ],
        sidebar: [
          {
            text: "使用指南",
            items: [
              { text: "快速入门", link: "/zh/getting-started" },
              { text: "Windows 与 iOS（预览版）", link: "/zh/platforms" },
              { text: "阅读器与播放器特性", link: "/zh/reader-features" },
              { text: "本地文件导入", link: "/zh/local-import" },
              { text: "自动翻译与 OCR", link: "/zh/automatic-translation" },
              { text: "内容源生态与扩展", link: "/zh/source-integrations" },
              { text: "社区功能", link: "/zh/community" },
              { text: "推荐与发现", link: "/zh/recommendations" },
              { text: "WebDAV 多端同步", link: "/zh/webdav-sync" },
              { text: "常见问题", link: "/zh/faq" },
              { text: "问题排查", link: "/zh/troubleshooting" },
            ],
          },
          {
            text: "技术参考",
            items: [
              { text: "社区 Nostr 方案 (2026-11)", link: "/architecture/community-nostr-plan-2026-11" },
              { text: "架构总览", link: "/architecture/architecture-review" },
              { text: "架构演进路线", link: "/architecture/architecture-roadmap" },
              { text: "KMP / iOS 方案", link: "/architecture/kmp-ios-plan-2026-10" },
              { text: "Windows 特性对齐", link: "/architecture/windows-feature-parity-2026-10" },
              { text: "Linux 原生能力", link: "/architecture/desktop-linux-native-2026-10" },
              { text: "iOS 特性对齐", link: "/architecture/ios-android-feature-parity-2026-10" },
              { text: "阅读器场景收敛方案", link: "/architecture/reader-scene-closure-plan-2026-09" },
              { text: "收藏书库方案", link: "/architecture/favourites-komikku-alignment-implementation-plan-2026-09" },
              { text: "统一 Cloudflare 解算器方案", link: "/architecture/unified-cloudflare-solver-plan-2026-08" },
              { text: "OCR 架构回顾", link: "/architecture/ocr-architecture-review" },
              { text: "OCR 渲染流水线", link: "/architecture/ocr-pipeline-v2" },
              { text: "UI 改版规划", link: "/architecture/ui_improvement" },
              { text: "Mihon 兼容层参考", link: "/reference/mihon-integration" },
              { text: "TVBox 运行时兼容性", link: "/reference/tvbox-runtime" },
              { text: "Legado 书源适配分析", link: "/reference/legado-adaptation-gap-analysis" },
              { text: "第三方扩展接入指南", link: "/architecture/external-extension-integration-guide" },
            ],
          },
          {
            text: "开发与贡献",
            items: [
              { text: "开发指南", link: "/zh/development" },
              { text: "贡献准则", link: "/zh/contributing" },
              { text: "Issue 反馈指引", link: "/zh/issue-submission-guide" },
            ],
          },
          {
            text: "归档文档",
            items: [
              { text: "归档索引", link: "/archive/" },
              { text: "OCR 路线回顾 (2026-03)", link: "/archive/ocr-roadmap-review-2026-03" },
              { text: "实体系统 (已废弃)", link: "/archive/entity-system/" },
              { text: "早期 Mihon 笔记 (中文)", link: "/archive/zh/mihon-compatibility" },
            ],
          },
        ],
        editLink: {
          pattern: `https://github.com/Kototoro-app/Kototoro/edit/${editBranch}/docs/:path`,
          text: "在 GitHub 上编辑此页",
        },
        docFooter: {
          prev: "上一页",
          next: "下一页",
        },
        outline: {
          label: "页面导航",
          level: [2, 3],
        },
        returnToTopLabel: "返回顶部",
        sidebarMenuLabel: "菜单",
        darkModeSwitchLabel: "主题",
        lightModeSwitchTitle: "切换到浅色模式",
        darkModeSwitchTitle: "切换到深色模式",
      },
    },
  },
  themeConfig: {
    logo: "/icon.png",
    siteTitle: "Kototoro Docs",
    search: {
      provider: "local",
      options: {
        locales: {
          zh: {
            translations: {
              button: {
                buttonText: "搜索文档",
                buttonAriaLabel: "搜索文档",
              },
              modal: {
                noResultsText: "无法找到相关结果",
                resetButtonTitle: "清除查询条件",
                footer: {
                  selectText: "选择",
                  navigateText: "切换",
                  closeText: "关闭",
                },
              },
            },
          },
        },
      },
    },
    socialLinks: [
      { icon: "github", link: "https://github.com/Kototoro-app/Kototoro" },
    ],
    footer: {
      message: "Documentation for Kototoro",
      copyright: "Kototoro contributors",
    },
    outline: {
      level: [2, 3],
    },
  },
});
