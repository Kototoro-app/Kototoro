import { defineConfig } from "vitepress";

const base = process.env.GITHUB_ACTIONS ? "/Kototoro/" : "/";
const editBranch = process.env.DOCS_EDIT_BRANCH || process.env.GITHUB_REF_NAME || "devel";

export default defineConfig({
  title: "Kototoro Docs",
  description: "Documentation for Kototoro: manga, novels, video, OCR translation, and source integrations.",
  lang: "en-US",
  base,
  ignoreDeadLinks: [/app\/src\//, /\/build\.gradle/],
  lastUpdated: true,
  cleanUrls: true,
  head: [
    ["link", { rel: "icon", href: `${base}icon.png` }],
    ["meta", { name: "theme-color", content: "#0f766e" }],
  ],
  themeConfig: {
    logo: "/icon.png",
    siteTitle: "Kototoro Docs",
    nav: [
      { text: "Guides", link: "/getting-started" },
      { text: "Reference", link: "/reference/mihon-integration" },
      { text: "Development", link: "/development" },
      { text: "GitHub", link: "https://github.com/Kototoro-app/Kototoro" },
    ],
    search: {
      provider: "local",
    },
    socialLinks: [
      { icon: "github", link: "https://github.com/Kototoro-app/Kototoro" },
    ],
    editLink: {
      pattern: `https://github.com/Kototoro-app/Kototoro/edit/${editBranch}/docs/:path`,
      text: "Edit this page on GitHub",
    },
    sidebar: [
      {
        text: "Guides",
        items: [
          { text: "Getting Started", link: "/getting-started" },
          { text: "Reader Features", link: "/reader-features" },
          { text: "Entity System", link: "/entity-system" },
          { text: "Automatic Translation", link: "/automatic-translation" },
          { text: "Source Integrations", link: "/source-integrations" },
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
          { text: "Entity Graph Plan", link: "/architecture/entity-graph-implementation-plan" },
          { text: "Entity Identity Migration", link: "/architecture/entity-identity-migration-consolidation-plan-2026-06" },
          { text: "Entity Space Plan", link: "/architecture/entity-space-implementation-plan-2026-07" },
          { text: "Entity Source Governance", link: "/architecture/entity-source-governance-plan" },
          { text: "Entity Source Boundary Audit", link: "/architecture/entity-graph-source-boundary-audit-2026-06" },
          { text: "Entity Content-Type Merge Bug", link: "/architecture/entity-content-type-merge-bug-analysis-2026-07" },
          { text: "Work Migration Status Audit", link: "/architecture/work-migration-status-audit-2026-06" },
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
          { text: "Archived Mihon Notes (ZH)", link: "/archive/zh/mihon-compatibility" },
        ],
      },
    ],
    footer: {
      message: "Documentation for Kototoro",
      copyright: "Kototoro contributors",
    },
    outline: {
      level: [2, 3],
    },
    docFooter: {
      prev: "Previous page",
      next: "Next page",
    },
  },
});
