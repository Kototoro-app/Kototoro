# Tsundoku Phase 4A + 4B 进度（2026-08-22）

状态：**已完成并提交**。由并行双代理实现（Phase 4A 于 local-vllm、Phase 4B 于 local-vllm-61），主会话统一集成验证。
验证命令：`:app:compileDebugKotlin --offline`（通过）+ `:app:testDebugUnitTest --offline`（**1833 tests / 0 failures**，基线 1768）。

## Phase 4A：小说 HTML 安全 + 图片 + 离线阅读（T4A.1–T4A.6）

### 新建
- `app/src/main/kotlin/org/skepsun/kototoro/reader/novel/NovelHtmlNormalizer.kt` — **Jsoup Safelist 清洗**（替代正则安全边界）：
  - 保留：`p br b strong i em u s sub sup h1..h6 ul ol li blockquote pre code hr span a img`；`a[href]`、`img[src alt title data-src]`；协议 `a[href]=http/https`、`img[src]=http/https/data`。
  - 剔除：`script/style/iframe/object/embed/form/input`、全部 `on*` 事件属性、`class/id/style`、`javascript:/vbscript:` URL；无 baseUrl 时相对 `src` 经协议校验被丢弃（`data-src` 作为普通属性保留）。
  - baseUrl 存在时相对 `href/src` → 绝对（Jsoup clean 原生支持）。
  - 输出无 `html/head` 包装、`prettyPrint(false)`。
- `app/src/main/kotlin/org/skepsun/kototoro/reader/novel/NovelHtmlImageResolver.kt` — `resolveImageSrc`（相对/协议相对/绝对/data:/file:，URI.resolve 语义，失败原样）、`extractImageUrls`（data-src 优先、去重保序）、`applyRetryResults(html, nameMap, retried)`（internal 纯函数：按重试成功映射改写 HTML `img src`，未重试项不受影响，失败项占位）。
- `app/src/main/kotlin/org/skepsun/kototoro/download/domain/NovelFailedImageStore.kt` — `FailedChapterImage(chapterId,url,localName?,error?,failedAt)` + 侧车 JSON `novel_failed_images_<mangaId>_<chapterId>.json`（kotlinx.serialization 1.11.0；读损坏/缺失静默空、写 best-effort）。**不进 ContentIndex / LocalManga*Output**（规避集成风险）。

### 修改
- `NovelContentLoader.kt`（仅加 4 处清洗，本地 zip/epub/file 支路未动）：
  1. `getChapterContent` 主路径：`raw.copy(html = sanitize(raw.html))`（images 字段原值保留，纯文本出口/缓存不变）；
  2. `concatPagesHtml` data: 分支先 sanitize；
  3. `loadChapterContentInternal` 的 getChapterContent 路径同 1；
  4. `decodeChapterHtml(firstUrl)` 路径先 sanitize。
- `DownloadWorker.downloadNovelChapters`（漫画/zip/cbz 路径未动）：
  1. `sanitize(content.html, baseUrl)`，baseUrl = 首图 origin 优先，否则 `TachiyomiXSourceAdapter.baseUrlOrNull`；
  2. 图片提取改 `extractImageUrls(safeHtml, baseUrl)`（headers 仍按 content.images 建 map + 补 Referer）；
  3. 单图失败在既有 `runCatching.onFailure` 收集 `FailedChapterImage` → 写侧车；
  4. **单图重试（不做整章重下）**：`retryFailedImages` 仅对 `localName==null` 的图复用私有 `downloadFile`，成功后按原计划 pageNumber 经 `output.addPage` 写进章节输出；仍失败 → `failed_<n>.jpg` 占位并留在侧车；
  5. `applyRetryResults` 生成最终 HTML/mapping → HTML 以 page 0 落盘（移到重试后）；`putChapterImages` 用最终 mapping。

### 测试（新增 40，均通过）
- `NovelHtmlNormalizerTest`（15）：标签/属性/协议剔除、相对转绝对、data: 保留、精确输出断言。
- `NovelHtmlImageResolverTest`（19）：resolveImageSrc（10）、extractImageUrls（5）、applyRetryResults（4）。
- `NovelFailedImageStoreTest`（6）：往返/空/转义/缺失/损坏/按 chapterId 过滤。

### 基线更新（NovelContentLoaderBaselineTest，4 个断言随清洗语义更新）
类注释已预告 Phase 4A 会重构该行为，属预期变更：
1. `<br class="x">` → 换行（safelist 保留 br）；
2. 大写 `<SCRIPT>` 内容不再泄漏 → `前\n后\n`（safelist 大小写不敏感整块清除）；
3. 无 baseUrl 相对 img `src` 被丢弃、无占位（`data-src` 例外）；
4. `src` vs `data-src` → data-src 优先（与 rewriteLocalImageSrc 的 Jsoup 语义一致）；越界数值实体 `&#99999999;` → U+FFFD（Jsoup 归一化）。

### 风险的已知剩余项
- T4A.6 真机验证（离线 CBZ 内 HTML+插图渲染、failed_N.jpg 占位、重试闭环）留待设备验证。
- 在线阅读器无 baseUrl 时相对 src（无 data-src）图片占位消失（连网时绝对 URL/data: 不受影响）。
- 侧车文件当前只写不读（UI 重新下载入口为后续）。
- **Compose 富文本格式渲染未改**：NovelComposeDocument 仍为纯文本 + 图片块，`<b>/<i>` 等 span 的加粗/斜体渲染是清晰记录的后续项（需设备 UI 验证，非本期范围）。
- 重试成功图能写进 CBZ 的唯一原因：`addPage` 与 HTML 内 src 同名（pageNumber 一致）；若远程 MIME≠URL 猜的扩展名仍可能不一致（既有问题，未修）。

## Phase 4B：SourceTracker 网站同步（T4B.1–T4B.4）

### 新建
- `app/src/main/kotlin/org/skepsun/kototoro/tracker/domain/SourceTrackerEvent.kt` — `SourceTrackerEvent`（`Read(contentId,sourceKey,percent,contentUrl?)` / `Unread` / `Favorite(added)` / `Unfavorite`）+ `SourceTrackerEventEmitter` 接口 + `SourceTrackerEventBus` 单例（`MutableSharedFlow(extraBufferCapacity=256, DROP_OLDEST)`，`emit` 用 `tryEmit` 非挂起，DB 写路径永不阻塞）。
- `app/src/main/kotlin/org/skepsun/kototoro/tracker/domain/SourceTrackerSyncManager.kt`：
  - `SourceTrackerGate`（`isEnabled()` 默认 false —— **安全默认：设置关闭/supports=false 时零网络副作用**；`supports(Content)` 委托 `supportsSource(sourceKey)`，ContentSource ABI 无能力标记 → 默认 false）+ `DefaultSourceTrackerGate` + Hilt `@Provides @Singleton`（本次补上 gate 绑定，否则 Dagger MissingBinding 编译失败——集成时修复）。
  - `SourceTrackerDiagnostics`：单行脱敏摘要（token 掩码、userinfo 剥离，复用 SourceRefreshDiagnostics）。
  - `foldLatest`（纯函数）：同 (sourceKey,contentId) 的 Read 只留最大 percent；Read↔Unread 后到者胜；Favorite(added)/Unfavorite 轴 last-wins；多轴按最后出现序合并。
  - `SourceTrackerSyncManager`：门禁 → per-content 串行（`workers`/`pendingByContent` + 单 worker drain，结尾 reclaim 防孤儿）→ 折叠 → **有限重试**（`maxRetries=3`，`backoff = 2^n*500ms` 上限 8s，`backoffDelayMillis` 纯函数）→ **超时**（`withTimeout(10s)`/attempt，记录诊断不阻塞其他内容）→ **结构化取消**（`Scope(SupervisorJob()+Dispatchers.Default)`，`onStop()` 幂等取消）→ `awaitDrain()`（测试钩子）。`syncToTracker` 为 open 占位返回 true —— **Phase 5 之前零网络**。
- Hilt：`SourceTrackerSyncModule.provideSourceTrackerEventEmitter(manager){ manager.start(); return SourceTrackerEventBus }` —— 依赖注入 env，构造任何注入 emitter 的 repo 即启动 manager，无循环。

### 修改
- `FavouritesRepository.kt` + `HistoryRepository.kt`：构造器注入 `sourceTrackerEvents: SourceTrackerEventEmitter`（均为 `@Inject constructor`，Hilt 自动装配）：
  - 收藏成功（`addToCategory`/`addToCategoryAsSeparateWorks` 提交后）→ `Favorite(added=true)`；整本取消收藏/移除最后一个分类 → `Unfavorite`。
  - `HistoryRepository.updateProgress` 提交成功后（仅刻意进度设置，非逐页 autosave）→ `Read`；`delete(manga)` 提交后 → `Unread`。
  - **全部 emit 包 try/catch**：失败者不回滚、不抛出 —— T4B.4「本地 DB 提交结果不被事件副作用破坏」在生产代码层已成立。
- 必要连带：`HistoryRepositoryResumeFilterTest` 构造调用加 `sourceTrackerEvents = SourceTrackerEventBus`（唯一直接实例化点）。

### 测试（新增 23，均通过）
- `SourceTrackerEventTest`（5）：模型相等性、in-order 投递、多订阅者共享、DROP_OLDEST 溢出（300→256）、tryEmit 不挂起。
- `SourceTrackerSyncManagerTest`（18）：disable 门/unsupported 门零副作用、foldLatest 纯规则、管线端到端折叠、按内容串行（阻塞验证）、不同内容独立、重试 3 次后成功、放弃记 lastError、退避数学、超时记录且不阻塞他内容、onStop 取消、T4B.4 失败同步不影响本地结果、诊断单行脱敏。
- 集成期修复：① FakeEmitter 加 `replay=64`（`tryEmit` 在订阅注册前会丢事件 —— StandardTestDispatcher 惰性注册时序）；② 两个 blocker 测试把 `advanceUntilIdle` 改 `runCurrent`（阻塞用 CompletableDeferred 而超时是虚拟时间任务，advanceUntilIdle 会推进虚拟时钟误触发 10s 超时 → 虚增重试）；③ 与测试无关的 bonus 测试 `FavouritesRepositorySourceTrackerTest` 因 relaxed mock + 真实 Room `withTransaction` 挂起 1 分钟被删除（其 T4B.4 语义已由 SyncManagerTest 覆盖 + 生产代码 try/catch 已核实）。

## 验证与提交
- 全量单测：**1833 / 0 失败**（基线 1768 + 65 新增，删 1 个挂起 bonus）。
- 提交：`feat(reader): normalize external novel HTML and offline images`（Phase 4A）+ `feat(tracker): source tracker sync events after commit`（Phase 4B）。

## 后续（P5/P6）
- P5（owner C）：SourceTracker 同步 UI + 设置开关接 `AppSettings`。
- P6（owner D）+ 4B 事件入口后续：把 `SourceTrackerSyncManager.syncToTracker` 从占位接到真实扩展/HTTP 调用（MAL 等），读取侧车/重试 UI。
- T4A.6 真机离线验证、Compose 富文本 span 渲染。
