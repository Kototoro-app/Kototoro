# Compose 迁移：后续计划

> 2026-08-21 全量校线重写：逐条核对上一版（4/18–5/1 时代）checklist 与当前代码。
> 结论：**绝大多数 legacy 壳清理项已被后续开发执行完毕**，证据见下「已核销」清单。
> 本文件只保留**仍在当前代码中成立、可执行**的条目。

---

## 已核销（2026-08-21 以代码为准）

### Phase 1 详情页
- [x] `DetailsActivity` 已去壳：`BaseComposeActivity` + `setComposeContent`（DetailsActivity.kt:46/91），无 `ActivityDetailsBinding`
- [x] `TrackingSiteDetailsActivity` / `TrackingSiteDetailsScreen` 已不存在；tracking 详情已改由 `TrackingDiscoverActivity` + 面板（`TrackingBottomBar` / `TrackingLocalSearchSheet` / `TrackingAlternativeTrackersPanel`）承载
- [x] Download / Stats 交互已由 `DetailsScreen` / `DetailsScreenScrollable` 承载，`DetailsActivity` 不再中转

### Phase 2 设置系统
- [x] `SettingsTabbedFragmentsScreen` / `JsonSourcesRootFragment` / `ExtensionsRootFragment` 已不存在
- [x] `SourceSettingsFragment` / `SourceComposeSettingsFragment` / `SourceSettingsHostFragment` / `SourceSettingsExt` 已不存在
- [x] `pref_source.xml` / `pref_source_parser.xml` / `pref_root.xml` 已删除
- [x] `UnifiedSourcesActivity` 已去壳：`BaseComposeActivity` + `setComposeContent`（UnifiedSourcesActivity.kt:52/54）
- [x] `LegacySourceRedirects` 已不存在
- [x] 当前形态：`SettingsActivity` 纯 Compose host（`setComposeContent`，含 `SettingsAdaptiveShell`）+ route 层（`SettingsDestination` + 各 `*Route.kt`）+ `compose/` 几十个 screen + `sources/`（`SourceSettingsRoute` + `ComposePreferenceAdapter` + `unified/`）
- [x] `pref_sync_header.xml` 是 AccountManager 认证器资源（`authenticator_sync.xml` 的 `accountPreferences`），非 legacy UI，**保留**

### Phase 3 Dialog / Sheet
- [x] `DownloadDialogFragment` / `ContentStatsSheet` / `ChaptersPagesSheet` / `AlternativesSheet` 已不存在（Compose dialog/sheet 取代，`AppRouter` 走 Compose 路径）
- [x] `ScrobblingSelectorSheet` → `ScrobblingSelectorSheetRoute`：Material3 `ModalBottomSheet` + `ScrobblingSelectorDialog`（纯 Compose）

### Phase 4 主壳（2026-08 会话）
- [x] MainActivity 去壳：`BaseComposeActivity` + `setComposeContent`
- [x] `MainChromeController` 收敛顶栏/过滤器/insets 状态（含 `LocalMainChromeController` CompositionLocal）
- [x] `MainAppState` 参数聚合 + controller 边界

### Phase 5
- [x] `collectAsState` → `collectAsStateWithLifecycle`（全仓 0 处裸 `collectAsState(`）
- [x] `DiscoverViewModel.isPageLoading` AtomicBoolean CAS（f4c6d630d）
- [x] `KototoroApp` 参数聚合为 `MainAppState`
- [x] `as? MainActivity` → `LocalMainChromeController`（454b8baca）
- [x] `AppNavGraph` 内联路由（**已过时**：nav3 重构后不存在 `AppNavGraph.kt`，路由在 `navigation3/` + MainShellScene 8 个 `TopLevelRouteContent` 按 key 分发）
- [x] `ReadButtonDelegate` vs `ReadDock`（**已过时**：无 ViewBinding 残留，单一 `ReadDock`）
- [x] 滚动条组件升级 / 章节标签中文化（5/1 已落地）

---

## 当前开放项

- [ ] 备份兼容：为设置备份建立显式 schema/versioned migration（目前仍为 `BackupRestoreFormat.sanitize` 键级兜底）
- [ ] 应用直接升级的分段 versioned migration（避免停在「版本变化即全量 sanitize」）
- [ ] 旧备份高风险偏好回归用例（nav/grid/panorama/popup/search suggestions/list badges）
- [ ] Compose 首屏读取阶段的旧 key / 旧值域偏好审计
- [ ] 主页恢复备份 UI 回归：高亮三合一卡片渲染无崩溃（当前 `GlassSurface` 为自绘模糊、未接 haze；若 haze 落地需重验）
- [~] `ContentHeroBackdropCarousel`：Discover hero 内联 panorama 动画对齐共享 `AnimatedPanoramaBackdrop`（Home/Details 已复用）——视觉重构，需有截图验证手段再做（prefs 部分已去重，49592af7a）
- [ ] `GlassSurface` 接入 `dev.chrisbanes.haze`：**新依赖**，需产品拍板 + CONTRIBUTING 例外
- [ ] `SettingsActivity` 死 import 清理（`androidx.preference.Preference` / `PreferenceFragmentCompat` / `PreferenceManager`，未使用）
- [ ] `androidx.preference` 依赖评估：`ComposePreferenceAdapter`（源专属偏好 XML 桥）为刻意保留；Reader / Novel / TTS / Video 中的 Preference 用法需逐屏评估
- [~] 详情页系统级共享元素锚点方案去留（设计开放项）

---

## 暂缓项

- [ ] Reader / Video 全量架构重写
- [ ] 全仓 ViewModel 模式重构
- [ ] 全局设计系统大拆分
- [ ] 共享层 / CMP 的工程化落地

---

## 中长期方向

- [ ] 评估 `shared/designsystem` 或等价模块拆分时机
- [ ] 在 Source Settings 与主壳去壳完成后，再评估首批 `commonMain` 友好组件（前两条已达成，可启动评估）
- [ ] 为未来 `expect/actual` 桥接保留边界，但当前不提前实现

---

## 持续校验要求

- [ ] 后续继续实现前，先跑 `./gradlew :app:compileDebugKotlin --no-daemon`
- [ ] 每次更新文档时，只记录代码能直接证明的状态
- [ ] `status-snapshot.md` / `decision-log.md` / `task.md` 继续保持「状态 / 历史 / 计划」分工
