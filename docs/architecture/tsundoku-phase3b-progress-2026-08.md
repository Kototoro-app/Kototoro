# Tsundoku Phase 3B 进度 — Novel Repository / RefreshContext / 刷新状态 / 偏好隔离 / 诊断

日期：2026-08 · 依据：`docs/architecture/tsundoku-extension-integration-plan-2026-08.md` §540-555（T3B.1..T3B.6，门控见 §554）

## 完成态（Phase 3B 门控达成，单测 1768/0）

> 门控：1.4/1.6 扩展均可浏览、搜索、打开详情、刷新章节；强制刷新与增量刷新语义可测试。

| 条目 | 交付 | 验证 |
|---|---|---|
| T3B.1 仓库 | `tsundoku/TsundokuNovelRepository.kt`（893 行，镜像 `MihonMangaRepository` 适配 `TsundokuNovelSource`） | 17 单测 |
| T3B.2 网络复用 | 全部 ABI 调用经 `MihonRequestContext.withSourceBlocking(tsundokuSource){...}`（Cookie/CF/WebView 按宿主 keyed 复用，走 `TachiyomiXSourceAdapter` 接缝） | `SourceRequestContextTest` 既有 + 本轮测试 |
| T3B.3 RefreshContext | `getDetailsImpl`：已有章节 → `getMangaDetails` + `getChapterList(manga, RefreshContext)`（真实 context：mangaId=Content.id、existingChapters、lastFetchTime=上次成功刷新(lastSuccessAt)、forceRefresh）；全新内容 → `getMangaUpdate`；公开 `refreshChapters(content, forceRefresh)` 暴露强制语义 | slot 捕获验证 mangaId/existingChapters/forceRefresh；强制/增量两态 |
| T3B.4 刷新状态 | `extensions/recovery/SourceRefreshReporter.kt`：接口 + `NoOpSourceRefreshReporter` + `RoomSourceRefreshReporter`（DAO 读合写 upsert；成功才推进 lastSuccessAt，失败只写 lastError/lastAttemptAt，取消不记录）；`RecoveryModule` provides 绑定；仓库 getDetailsImpl 记账 | 6 单测（attempt 新建/保留、failure 不动 success、success 清 error、NoOp） |
| T3B.5 偏好隔离 | `TachiyomiPreferenceNamespace.kt` 纯函数 remap（仅 TSUNDOKU：`source_<id>` → `source_tsundoku_<pkg尾段>_<id>`；Mihon/其余原样不动）；`KotoInjektBridge` 注册 `NamespacedApplication` 委托（覆写 `getSharedPreferences` 按 `MihonRequestContext.currentSource()` 重映射后转发） | 13 单测（remap 7 + 委托代理 7，真实 TsundokuNovelSource/MihonMangaSource + mockk 上游） |
| T3B.5a 设置页 | `SourceSettingsRoute` 四分支：LaunchedEffect when、`resolveSourcePreferencesName`（绑定扩展真实读写的托管 key）、`buildExternalPreferenceScreen(TsundokuNovelRepository)`（`setupPreferenceScreen` 包在 `withSourceBlocking` 内）、`hasDynamicSettings()` | 随套件编译 |
| T3B.6 诊断 | `SourceRefreshDiagnostics`（纯 Kotlin）：classify 四分类 + 单行可复制 summary + query 敏感参数/userinfo 脱敏 | 10 单测 |
| DI 装配 | `TsundokuContentRepositoryProvider`（@Inject contentCache + refreshReporter）注册进 `ContentRepositoryProviderRegistry` 构造器与列表 | 4 单测 |

## 关键架构决策

1. **Repository 本体**：结构完全镜像 `MihonMangaRepository`，复用 `MihonDataConverters`（toKotoChapter/toMihonChapter/toMihonManga/toKotoPage/getPublicContentUrl）与 `MihonFilterMapper`（ABI 相同）。`SManga.toKotoContent` 硬引用 MihonMangaSource，故复制私有 `toTsundokuContent` 孪生（映射与 ID 方案一致）。
2. **小说正文双通道**：`Page.text`/`fetchPageText` → `data:text/html;base64` 页（`NovelContentLoader.decodeChapterHtml` 可解，编码与 LNReader 同线格式）；`getChapterContent` 返回 `<p>` 转义 HTML + 内嵌 `NovelImage`。全部为空时返回 null 让上层回退 pages 路径。图片页走 Mihon 式 `tsundoku://image|resolve` 管道。
3. **强制/增量语义**：`getDetailsImpl` 无法感知 CachePolicy，普通详情刷新固定 `forceRefresh=false`；强制路径由 `refreshChapters(content, forceRefresh=true)` 提供（RefreshContext.lastFetchTime 来自 refresh state DAO，可选注入，缺省 0）。
4. **代理 vs 子类（T3B.5）**：`java.lang.reflect.Proxy` 只能代理接口，Application 是具体类 → 用最小委托子类 `NamespacedApplication`（`javap` 证实 compileSdk37 `ContextWrapper.getSharedPreferences(String,int)` 非 final，可合法覆写）。未转发方法在未 attach 包装上会 NPE（mBase=null）——扩展消费路径只走 `getSharedPreferences`，另转发 4 个高频访问器。
5. **设置页 key 绑定**：用 `remapTachiyomiPreferenceKey("source_${id}", tsundokuSource)` 而非 `preferenceNamespace`（`tsundoku:pkg:id`），保证页面与扩展读写同一份托管文件。
6. **T3B.4 记账范围**：仅 `getDetailsImpl`（含章节刷新的详情路径）记 attempt/success/failure；`getPagesImpl` 页面错误不记账（无稳定 contentId；行语义 = 详情+章节刷新）。取消一律不记。

## 单测要点（本轮 50 新增）

- `TsundokuNovelRepositoryTest`（17）：getList 映射/最新路由/非 Catalogue 空列表；RefreshContext slot 捕获（已有章节 + refreshChapters 强制）；getMangaUpdate 新内容路径；data:URL 编码与 fetchPageText 兜底；getChapterContent 转义+图片；IOException → recordFailure+重抛、CancellationException → 不记录；成功 → attempt+success；getImageClient/getPageUrl。
- `SourceRefreshReporterTest`（6）：新建/保留/成功推进/失败不动 success /NoOp。
- `SourceRefreshDiagnosticsTest`（10）：四分类前缀、脱敏（token/password/api_key/key 大小写）、userinfo 剥除、summary 折叠/内嵌 URL 脱敏/包名保留。
- `TachiyomiPreferenceNamespaceTest`（13）：remap 纯函数 7 例 + NamespacedApplication 转发/重映射 7 例。
- `TsundokuContentRepositoryProviderTest`（4）：supports/create 语义。

## 集成期修复（主会话）

1. `SChapter` 无 `copy()`（ABI 用 `SChapter.create()+copyFrom`）→ `chapterSnapshots` 读侧改 `?.snapshot()`（与 Mihon 一致）。
2. `ContentRepositoryFactoryTest` 补 `tsundokuContentRepositoryProvider` 构造参数（registry 新增必填参数）。
3. Provider 测试 `mockk<Source>`（ABI）→ `mockk<ContentSource>`（Kototoro）。
4. **MockK 大坑**：`TsundokuNovelSource` 是 data class 且 equals/hashCode 走 `name`（= `TSUNDOKU_${upstreamSource.id}`），MockK relaxed 缓存 mock 的 `anyValue` 生成会对调用参数做 `EqMatcher.hashCode`，进而触碰被 mock 的 `HttpSource.getId()` → “No other calls allowed in stdObjectAnswer”。修复：测试 helper 改用**真实 `MemoryContentCache(mockk<Application>(relaxed=true))`**，hash 落在正常上下文（桩生效）；reporter 测试给 `dao.get` 默认 `returns null`（relaxed mock 的实体返回非 null 造成 0 覆盖）；多调 upsert 改用 `capture(mutableList)`；`recordAttempt` 验证补 `any()` 防时钟漂移。

## 后续（Phase 4A/4B）

- 4A：Tsundoku 内容 safelist / 离线下载语义（依赖本轮的 `getChapterContent` 与 `refreshChapters`）。
- 4B：（owner B）备份/恢复对 Tsundoku 源的覆盖（AppBackupAgent 已有双 DI 图要同步扩）。
- Phase 5 recovery：`SourceRefreshStateDao` 的 lastSuccessAt/lastError 将作为重试/标记依据（本轮已灌数据）。
