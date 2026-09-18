# Reader Scene 收尾清单与 Phase 4 规划（2026-09 快照）

- 审计基线：commit `c6f80fe02`（2026-09-18）
- 架构依据：[ADR 0002 — Decouple Reader Semantics, Image Resources, and Rendering Backends](../adr/0002-reader-scene-decoupling.md)
- 范围：漫画阅读器（`app/src/main/kotlin/org/skepsun/kototoro/reader/`）。小说阅读器与视频播放器不在本次范围内。
- 本文档只做盘点与规划，**不含代码改动**；每个条目都给出可复核的 `文件:行` 证据。

## 0. 结论摘要

场景阅读器的**引擎**已经闭环：Webtoon 与横向连续已是默认路径，纯 Draw Phase 渲染、瓦片 + 多 LOD、零跳动锚定、双向资源状态机都有真机数据支撑（ADR 0002 Phase 0–3F）。
但它距离"成为唯一路径"还差三类工作：

| 类别 | 条目 | 性质 |
| :--- | :--- | :--- |
| 收尾（转正前置） | CS-1 分页真机验收与转正、CS-2 翻页动画 parity、CS-3 连续横向的方向/门控、CS-4 legacy 清账、CS-5 实验开关收口 | 不补就会静默功能回退或长期双路维护 |
| 架构红利（Phase 4） | P4-1 增强管线场景化（翻译/超分）、P4-2 译文 overlay 层、P4-3 引擎跨模块复用 | 只有新架构才可能做，收益最大 |
| 工程护栏 | CS-6 I1 不变量测试、CS-7 基准门禁、CS-8 语义与可测性、CS-9 预取统一与文档 | 决定长期演进是否漂移 |

**证据强度标注**：`【已验证】` = 本次审计在仓库中直接读到的事实；`【待实测】` = 逻辑推理成立但缺实测数据；`【设计提案】` = 尚未实现的设计意见。

---

## 1. 当前生产事实（已闭环部分）

- **Webtoon**：`ComposeSceneWebtoonReader` 由 `isExperimentalSceneReaderEnabled`（默认 `true`）选择
  （`app/src/main/kotlin/org/skepsun/kototoro/reader/ui/config/ReaderSettings.kt:34`，
  `app/src/main/kotlin/org/skepsun/kototoro/reader/ui/compose/ComposeReaderScreenRoot.kt:256-303`）。【已验证】
- **横向连续**：`ReaderMode.CONTINUOUS_HORIZONTAL` 只有一个分支，**无条件**走 `ComposeSceneHorizontalReader`，
  没有 legacy 回退（`ComposeReaderScreenRoot.kt:354-398`）。【已验证】
- **分页 / 双页**：仍由第二个开关门控，默认关闭
  （`ReaderSettings.kt:35`、`app/src/main/kotlin/org/skepsun/kototoro/core/prefs/AppSettings.kt:1054-1056`、
  路由双门控 `ComposeReaderScreenRoot.kt:119`（双页）与 `:400`（单页））。【已验证】
- 编码决策 + 瓦片运行时 + 多态资产 + 影子校验器均已落地并有 JVM/真机覆盖（见 §5）。

---

## 2. 收尾清单

### CS-1 分页场景真机验收 → 翻转默认开关

- **证据**：ADR 0002 `:486`（Phase 3F 功能已实现、真机基准待验收）、`:503`（4 组验收矩阵**待执行**，Paged 实验开关保持默认关闭）；
  代码侧 `ReaderSettings.kt:35`、`ComposeReaderScreenRoot.kt:119/400`。【已验证】
- **为什么现在**：分页是最后一个没转正的场景，它同时挡着 CS-4（清账）与 CS-5（开关收口）。
- **DoD**：
  1. 执行 ADR `:504-508` 的 4 组矩阵（普通单页 1x / 6000×9000 大图 1x / 2.5x–5x 放大切片 / 双页跨章往复），
     每组的 `frameDurationCpuMs`、`frameOverrunMs`、`RssAnon`、`ActivePresentationAssets` 记入 ADR；
  2. 结果满足 ADR `:264-266` 的 Go 判据（至少一项显著改善且无关键指标回归）；
  3. 翻默认值为 `true`，观察一个 nightly 周期无回归后再进入 CS-4。
- **规模**：M（成本主要在真机时间，不在代码）

### CS-2 翻页动画 parity（**转正的真正 Blocker**）

- **证据**：场景分页宿主把三种动画样式塌缩成同一个滑动过渡 ——
  `app/src/main/kotlin/org/skepsun/kototoro/reader/ui/compose/ComposeScenePagedReader.kt:244`
  `val shouldAnimate = isAnimationEnabled && pageAnimation != ReaderAnimation.NONE`，
  `DEFAULT` / `ADVANCED` / `SIMULATION` 走的是同一条路径。【已验证】
- 对照组：legacy 有完整分派与实现
  （`reader/ui/compose/ComposeReaderPageAnimation.kt:53-72` 分派 `DEFAULT` / `NONE` / `ADVANCED`（cover 变换）/ `SIMULATION`（仿真 + curl），
  curl 状态机与几何在 `:171-247`，测试 `app/src/test/kotlin/.../ComposeReaderPageAnimationTest.kt`（542 行））。【已验证】
- **为什么现在**：现在转正 = 静默砍掉用户已经选中的翻页动画（`ReaderAnimation` 是持久化设置，
  `app/src/main/kotlin/org/skepsun/kototoro/core/prefs/ReaderAnimation.kt:6`，取值 `NONE / DEFAULT / ADVANCED / SIMULATION`）。【已验证】
- **方案要点**【设计提案】：curl 依赖的 `clipPath` + `rotateRad` + 阴影全部可在 Draw Phase 完成，
  不需要退回 Composition；需要让 `PagedReaderScene` / `PagedDragState` 向渲染层暴露"当前插槽 → 下一插槽"的过渡进度，
  以承接 legacy 已有的 `isCurlUnfolding` 语义。
- **DoD**：三种样式在 `ComposeScenePagedReader` 各有独立表现；新增参数化测试直接比对 legacy
  `resolveComposeReaderPageTransform` 的输出（同输入同输出），并在真机确认无 P99 回归。
- **规模**：M–L

### CS-3 CONTINUOUS_HORIZONTAL 的方向与门控一致性

- **证据**：该模式的 `readingDirection` 硬编码 `SceneReadingDirection.LEFT_TO_RIGHT`
  （`ComposeReaderScreenRoot.kt:361`），且该分支无 `isExperimentalSceneReaderEnabled` 判定、无 legacy 回退（`:354-398`）；
  模式已对用户暴露（`reader/ui/compose/ComposeReaderOptionsSheet.kt:1011`、`core/prefs/ReaderMode.kt:12,15`）。【已验证】
- 引擎侧能力已具备：`HorizontalReaderScene` 支持 `LEFT_TO_RIGHT` / `RIGHT_TO_LEFT`（ADR `:420-424` Phase 2C 对称性描述）。【已验证】
- **待决**：日漫横向连续（RTL）是否需要？如果要，现在取不到。
- **DoD**：二选一并写进 ADR —— (a) 补 RTL 连续（选项面板 + `ReaderMode.REVERSED` 组合语义 + 单测）；
  (b) 显式声明该模式仅 LTR，并补一条说明文案。附带：给该模式补上与其他场景一致的实验门控或明确记录为何不需要。
- **规模**：S

### CS-4 legacy 宿主与对照组件清账

- **证据**（行数为实测）：`ComposePagedReader.kt` 436 行、`ComposeDoublePageReader.kt` 758 行、
  `ComposeWebtoonReader.kt` 1243 行，合计 **2437 行** legacy 宿主；另有
  `reader/render/canvas/AndroidViewSceneView.kt`（ADR `:276-278` 已判定出局、不再扩张）
  与 `reader/ui/compose/PagedShadowValidator.kt` + `app/src/test/kotlin/.../PagedShadowParityTest.kt`（影子模式已完成使命）。【已验证】
- **注意**：`PagedShadowValidator` 的运行时采样目前挂在 legacy `ComposeDoublePageReader` 里（ADR `:443-447`），
  删除前需确认采样点一并移除，避免留下死引用。
- **DoD**：CS-1 转正后经过一个 nightly 周期无回归 → 删除 legacy 宿主与 View 对照组 →
  仅覆盖 legacy 的测试用例迁移或删除（例如 `ReaderModeAndOverlayTest` 等需逐个判定，不批量删）。
- **规模**：M（代码删除本身小，验证成本是主要工作量）

### CS-5 实验开关收口

- **证据**：两个持久化 key `KEY_READER_EXPERIMENTAL_SCENE_ENGINE` /
  `KEY_READER_EXPERIMENTAL_PAGED_SCENE_ENGINE`（`core/prefs/AppSettings.kt:3207-3208`），
  用户可见开关见 `app/src/main/kotlin/org/skepsun/kototoro/settings/compose/ReaderSettingsScreen.kt:1050-1070`。【已验证】
- **DoD**：转正后合并为单一（或零）开关；若保留用于灰度，只留在开发者选项，并修正设置项标题/摘要文案与中英字符串。
- **规模**：S

---

## 3. Phase 4 候选（架构红利）

### P4-1 增强管线场景化：把翻译 / 超分从"整页栅格"搬进瓦片世界

- **证据**：增强入口在 `reader/domain/PageLoader.kt:433-459` —— 先 `enhancementController.preparePage`，
  再对整页文件做超分（`:442-459`，注释 `:440` 直言 *"Super-resolution runs outside the download permit pool and
  remains legacy-reader behavior."*）；翻译侧在
  `reader/translate/domain/ReaderPageTranslationProcessor.kt:373-380`，路径是
  整页 `decode` → `copy(Bitmap.Config.ARGB_8888, true)`（第二次全页拷贝）→ 全页 `Canvas` 渲染 → 缓存文件。【已验证】
- **影响**【待实测】：ADR `:362` 记录 Phase 1D 夹具单张原始 bitmap 展开最高 **172.8 MB**（12k–40k px 条漫）。
  上述路径会把瓦片引擎刚刚消除的内存峰值重新引入 —— 呈现阶段仍是 tiled（超分产物是文件 URI，
  照旧走 `DecodePlanner`，`reader/image/KototoroImagePipelineAdapter.kt:301`），问题出在**处理阶段**。【推理，需实测】
- **相关空白**：`PixelUsage.CPU_READ_REQUIRED` 定义了"CPU 可读"像素用途
  （`reader/image/PixelUsage.kt:20`），但全仓仅被 `DecodeAllocatorPolicy.kt:35` 映射，
  **没有任何调用者** —— 即瓦片引擎目前没有给 OCR/色彩分析的 CPU 可读通道。【已验证】
- **建议路径**：
  1. **先测量再改造**：在 `macrobenchmark` 的 `ReaderProductionBenchmark` 夹具上加"翻译/超分开启"变体，
     采集 `RssAnon`、`memoryGpuKb` 与耗时，把"疑似内存炸弹"变成结论或排除；这一步成本低且不改变行为。
  2. 若成立：OCR 与译文渲染按可见条带进行；超分从"整页文件"改为"可见 LOD 目标区域"处理；
     为增强阶段引入 scene-aware 契约（明确像素用途与瓦片归属），并让它遵守 ADR 的 I2（不持有阅读语义）。
- **规模**：L（但第 1 步是 S）

### P4-2 译文 overlay 层（向量化呈现）

- **提案**【设计提案】：把译文从"烤进 bitmap"改为 Draw Phase 的文本覆盖层。
  收益：原/译切换零成本；字号与排版样式可调且不触发重 OCR（现在 `ReaderTranslationRenderStyle`
  的 `REPLACE` / `COMPACT_OVERLAY` 是渲染期烘焙进像素的）；省掉一份全页栅格与缓存文件。
- **坐标已现成**：`reader/render/compose/SceneImagePresentationCoordinator.kt:25 coordinateVisibleTiles`、
  `:77 computeVisibleBounds`、`:115 createCameraSnapshot` 已完成视口正/逆变换。【已验证】
- **约束**：必须遵守 ADR I4 与 I3 —— overlay 层可以持有绘制状态，但"哪页、哪个气泡、什么译文"必须来自
  阅读语义层与增强域，不得在 Renderer 内自持。
- **规模**：L

### P4-3 场景引擎跨模块复用（低优先）

- 有"二维视口 + 大图"特征的场景（图片查看器 `image/`、视频缩略图/故事板）理论上可复用 camera 与几何语义。
  建议在动手前先评估这些模块的既有实现是否已经够用，避免为复用而复用。
- **规模**：待评估

---

## 4. 已核实的疑点（避免重复排查）

以下四项在本次审计中**被排除**，不必再当作缺口：

1. **自动滚动**：`ScrollTimer` 经 `ReaderControlDelegate` 下发，两套 webtoon 宿主都有对应实现
   （`ComposeSceneWebtoonReader.kt:322 dispatchWebtoonScroll` 与 `ComposeWebtoonReader.kt:261,567`）。【已验证】
2. **译文/增强完成后的刷新**：重载通过 `reloadNonce` 进入页面身份
   （`reader/ui/pager/ReaderPage.kt:27,31`，`readerKey` 含 `reloadNonce`），
   因此 `PageId` 会变化，`KototoroImagePipelineAdapter` 的 `cachedAssets`
   （`KototoroImagePipelineAdapter.kt:78`）自然失效，不会命中旧资产 —— 与 Phase 0C 修复的
   "warm backend switch 首屏模糊"是不同机制。【已验证】
3. **TV / 音量键翻页**：在 Activity 层分发（`reader/ui/ReaderActivity.kt:960-996`），不依赖渲染宿主选择。
   但 scene paged 下的 DPAD 实际行为仍建议纳入 CS-8 的真机验收项，不要仅凭代码路径推断。【部分已验证】
4. **超分产物与瓦片共存**：超分输出是文件 URI，仍会被 `DecodePlan` 判定，
   超长图仍可走 tiled。P4-1 要解决的是处理阶段的峰值，而非呈现阶段的可行性。【已验证】

---

## 5. 验收与回归基线

**命令**

```bash
./gradlew :app:compileDebugKotlin              # 最快的编译验证
./gradlew :app:testDebugUnitTest               # JVM 单元测试（几何/管线/策略）
./gradlew :app:connectedDebugAndroidTest       # 设备侧场景交互与恢复测试
./gradlew :macrobenchmark:connectedCheck       # 真机基准（API 31+；MIUI 需开启 USB 调试(安全设置)）
```

> `:macrobenchmark:connectedCheck` 的具体用法与 OEM 注意事项见 `macrobenchmark/README.md`；
> 注意该 README 第 23-26 行仍称"排除真实章节与持续滑动旅程"，而 `ReaderProductionBenchmark.kt`
> 已经包含 Burst / Sustained 生产旅程 —— 属于 CS-7 的文档债。【已验证】

**现有覆盖（行数为实测，节选）**

| 层 | 测试 |
| :--- | :--- |
| 纯几何 / 语义（JVM） | `PagedReaderSceneTest` 220、`PagedSpreadResolverTest` 396、`PagedSnapResolverTest`、`HorizontalReaderSceneTest` 399、`VerticalReaderSceneTest` 359、`PagedPanBoundsResolverTest` 142、`PagedDragStateTest` 90、`SceneAxisProjectionTest` 77 |
| 图像管线（JVM） | `ReaderTileManagerTest` 408、`TileGridTest` 244、`TileMemoryBudgetTest` 185、`ReaderImageAssetTest` 609、`KototoroImagePipelineAdapterTiledTest` 335、`ReaderLodPolicyTest` 83、`SceneParityRegressionTest` 324 |
| 呈现 / 交互（JVM） | `ComposeScenePagedInteractionTest` 452、`ComposeSceneHorizontalInteractionTest` 279、`ComposeSceneInteractionsTest` 239、`SceneImagePresentationCoordinatorTest` 89、`ComposeSceneRendererTiledTest` 129、`TileDrawModifierNodeTest` 91 |
| 设备侧（androidTest） | `ScenePagedGestureTest` 698、`SceneReaderRecoveryTest` 157 |

**真机验收口径**：任何"转正 / 删除 / 替换"类变更，都必须记录 4 组指标
（`frameDurationCpuMs` P50/P99、`frameOverrunMs` P99、`RssAnon` Max/Last、`ActivePresentationAssets` Max/Last），
并同时跑 Primary（`CompilationMode.Partial`）与 Diagnostic（`CompilationMode.Full`）两种模式，
沿用 ADR Phase 0B/1D 的表格格式，便于跨阶段对比。

---

## 附录：证据索引

| 结论 | 证据位置 |
| :--- | :--- |
| 分页场景默认关闭 | `ReaderSettings.kt:35`、`AppSettings.kt:1054-1056`、`ComposeReaderScreenRoot.kt:119,400` |
| Webtoon 场景默认开启 | `ReaderSettings.kt:34`、`ComposeReaderScreenRoot.kt:256-303` |
| 横向连续无回退、硬编码 LTR | `ComposeReaderScreenRoot.kt:354-398`（方向见 `:361`） |
| 分页动画样式被塌缩 | `ComposeScenePagedReader.kt:244` |
| legacy 动画分派与 curl | `ComposeReaderPageAnimation.kt:53-72,171-247` |
| 增强阶段整页栅格 | `PageLoader.kt:433-459`、`ReaderPageTranslationProcessor.kt:373-380` |
| CPU 可读像素用途无调用者 | `PixelUsage.kt:20`、`DecodeAllocatorPolicy.kt:35`（全仓唯一引用） |
| 视口逆变换工具 | `SceneImagePresentationCoordinator.kt:25,77,115` |
| 页面身份含 reloadNonce | `reader/ui/pager/ReaderPage.kt:27,31` |
| 资产缓存按 PageId | `KototoroImagePipelineAdapter.kt:78,162-182,236-248` |
| 预取策略不统一 | `ComposeSceneWebtoonReader.kt:177-191`、`ComposeSceneHorizontalReader.kt:177-191` vs `ComposeScenePagedReader.kt:461-486` |
| 基准 harness 位置 | `macrobenchmark/src/main/kotlin/.../ReaderProductionBenchmark.kt`、`ReaderRendererBenchmark.kt`、`ActivePresentationAssetsMetric.kt` |
| 架构不变量 I1–I6 | `docs/adr/0002-reader-scene-decoupling.md:527-534` |
| Phase 3F 未验收项与动画债 | `docs/adr/0002-reader-scene-decoupling.md:486-509` |
