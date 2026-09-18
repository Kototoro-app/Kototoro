# Reader Scene 收尾清单与 Phase 4 规划（2026-09 快照）

- 审计基线：commit `c6f80fe02`（2026-09-18）；本文档修订 r2 已纳入外部评审意见
- 架构依据：[ADR 0002 — Decouple Reader Semantics, Image Resources, and Rendering Backends](../adr/0002-reader-scene-decoupling.md)
- 范围：漫画阅读器（`app/src/main/kotlin/org/skepsun/kototoro/reader/`）。小说阅读器与视频播放器不在本次范围内。
- 本文档只做盘点与规划，**不含代码改动**；每个条目都给出可复核的 `文件:行` 证据。

> **阶段定性**：Scene Reader 的**引擎已经完成**，当前状态应描述为 **production cut-over（生产切换）**，
> 而不是"新 Scene Reader 还在开发"。这个区别很重要：剩下的工作不是把引擎做出来，而是让引擎成为唯一路径，
> 并且**不允许在切换过程中让任何既有语义静默退化**。

## 0. 结论摘要

场景阅读器的引擎已经闭环：Webtoon 与横向连续已是默认路径，纯 Draw Phase 渲染、瓦片 + 多 LOD、零跳动锚定、
双向资源状态机都有真机数据支撑（ADR 0002 Phase 0–3F）。距离"成为唯一路径"还差四类工作：

| 类别 | 条目 | 性质 |
| :--- | :--- | :--- |
| 收尾（切换前置） | CS-1A 分页真机验收、CS-2 翻页动画 parity、CS-3 连续横向的方向/门控、CS-1B 分页转正、CS-4 legacy 清账、CS-5 实验开关收口 | 不补就会静默功能回退或长期双路维护 |
| 工程护栏 | CS-6 I1 不变量强制、CS-7 基准门禁、CS-8 语义矩阵与可测性、CS-9 资源窗口/预测统一 | 决定长期演进是否漂移 |
| Scene Reader 2.0（**不阻塞清退**） | P4-1 增强管线场景化（翻译/超分）、P4-2 译文 overlay 层、P4-3 引擎跨模块复用 | 只有新架构才可能做，收益最大，但属于切换之后的能力 |

**核心判据**：**Benchmark 通过 ≠ 可以 promotion。Benchmark + 语义 parity 都通过，才是 promotion 条件。**
（见 §2.0 执行时序）

**证据强度标注**：`【已验证】` = 本次审计在仓库中直接读到的事实；`【待实测】` = 逻辑推理成立但缺实测数据；`【设计提案】` = 尚未实现的设计意见。

---

## 1. 当前生产事实（已闭环部分）

- **Webtoon**：`ComposeSceneWebtoonReader` 由 `isExperimentalSceneReaderEnabled`（默认 `true`）选择
  （`app/src/main/kotlin/org/skepsun/kototoro/reader/ui/config/ReaderSettings.kt:34`，
  `app/src/main/kotlin/org/skepsun/kototoro/reader/ui/compose/ComposeReaderScreenRoot.kt:256-303`）。【已验证】
- **横向连续**：`ReaderMode.CONTINUOUS_HORIZONTAL` 只有一个分支，**无条件**走 `ComposeSceneHorizontalReader`，
  没有 legacy 回退（`ComposeReaderScreenRoot.kt:354-398`）。【已验证】
- **分页 / 双页**：单页与双页**共用**同一个 `ComposeScenePagedReader`
  （`ComposeReaderScreenRoot.kt:120`（双页）与 `:401`（单页）），当前由第二个开关门控、默认关闭
  （`ReaderSettings.kt:35`、`app/src/main/kotlin/org/skepsun/kototoro/core/prefs/AppSettings.kt:1054-1056`）。【已验证】
- **手势与 camera 状态机**：分页宿主已不是"Pager 替代品"，而是自带 camera ——
  页面 fit 时 swipe 即翻页；overflow / zoomed 时 drag 为内容平移；平移到内容边界后残余位移交回翻页；
  可平移状态下松手走 canvas 惯性；切页时 outgoing slot 保留 zoom/pan，incoming slot 从自己的 transform 进入
  （ADR `:486-509` Phase 3F；`reader/core/PagedDragState.kt`、`reader/core/PagedPanBoundsResolver.kt`、
  设备侧 `app/src/androidTest/.../ScenePagedGestureTest.kt` 覆盖 LTR/RTL/TTB 溢出交接与 zoom 保留）。【已验证】
- **页面查找已是 O(1)**：三个宿主都用 `pages.associateBy { it.readerKey }` + `pageLookup` 映射，
  快速滑动时不再有 O(N) 线性查找
  （`ComposeSceneWebtoonReader.kt:142-145`、`ComposeSceneHorizontalReader.kt:142-145`、
  `ComposeScenePagedReader.kt:194-196`）。【已验证】
- 编码决策 + 瓦片运行时 + 多态资产 + 影子校验器均已落地并有 JVM/真机覆盖（见 §5）。

---

## 2. 收尾清单

### 2.0 执行时序（Promotion Gate）

原计划把"分页真机验收 → 翻默认值 → legacy 清账"写成一个条目，同时又判断"动画 parity 才是真正 Blocker"，
这两者自相矛盾：**benchmark 通过不代表可以转正**。修正后的顺序是：

```text
CS-1A  Paged 性能 / 稳定性真机验收          ← 证明它够快、够稳
CS-2   Page transition parity               ← 证明它语义不退化（真正的 Blocker）
CS-3   Continuous Horizontal 方向与门控
CS-1B  Paged 默认开启
       ↓
       nightly 验证周期
       ↓
CS-4   legacy host 清退（分两步：host → oracle）
CS-5   experiment flag 删除 / 隐藏
```

判定规则：

1. **Promotion = Benchmark 通过 + Semantic parity 通过**，缺一不可；只满足前者不允许翻默认值。
2. 语义 parity 的清单以 §2「CS-2 / CS-3 / CS-8」为准，且必须能被测试或真机步骤复现，不靠人眼确认。
3. **删除 runtime host 与删除 reference oracle 不必同一个提交**（见 CS-4）。
4. CS-9（资源窗口统一）可以并行推进，但**不阻塞** CS-1B；P4 系列一律不阻塞任何收尾项。

---

### CS-1A 分页场景性能与稳定性真机验收

- **证据**：ADR 0002 `:486`（Phase 3F 功能已实现、真机基准待验收）、`:503`（4 组验收矩阵**待执行**，
  Paged 实验开关保持默认关闭）。【已验证】
- **前置缺口（2026-09-19 已补）**：原 `macrobenchmark` 夹具**只有 webtoon 后端**，
  分页矩阵没有可跑的 harness。现已扩展：
  - `ReaderProductionBenchmarkActivity` 新增 `legacy_paged` / `scene_paged` 两个后端与
    `animation` / `double_page` / `zoom_mode` 三个 intent extra，并新增两份夹具：
    `paged`（24 页 800×1200 纵向漫画页）与 `paged_large`（8 页 6000×9000 超大页）；
  - `ReaderProductionBenchmark` 新增 9 个分页旅程，覆盖 4 组验收场景：
    单页 1x（scene/legacy × Partial/Full）、超大页 1x（scene/legacy）、超高倍放大
    （`fit_height` = 原生像素级溢出平移）、双页跨章往复（scene/legacy）；指标沿用既有
    `FrameTimingMetric` + `MemoryUsageMetric(Max/Last)` + `ActivePresentationAssetsMetric`；
  - 夹具就绪超时从 30s 放宽到 180s（超大页首次生成较慢），就绪门控本身不变。
- **DoD**：执行 4 组矩阵，每组的 `frameDurationCpuMs`、`frameOverrunMs`、`RssAnon`、
  `ActivePresentationAssets` 记入 ADR，并满足 ADR `:264-266` 的 Go 判据。
- **注意**：本项只产出"性能与稳定性可接受"的结论，**不包含翻转默认值** —— 那是 CS-1B。
- **规模**：M（成本主要在真机时间，不在代码）

### CS-1B 分页场景转正（翻转默认开关）

- **前置**：CS-1A + CS-2 + CS-3 全部完成。
- **DoD**：`isExperimentalPagedSceneReaderEnabled` 默认值改为 `true`，经过一个 nightly 周期无回归后再进入 CS-4。
- **规模**：S

### CS-2 翻页动画 parity（**转正的真正 Blocker**）

- **证据**：场景分页宿主把三种动画样式塌缩成同一个滑动过渡 ——
  `app/src/main/kotlin/org/skepsun/kototoro/reader/ui/compose/ComposeScenePagedReader.kt:244`
  `val shouldAnimate = isAnimationEnabled && pageAnimation != ReaderAnimation.NONE`，
  `DEFAULT` / `ADVANCED` / `SIMULATION` 走的是同一条路径。【已验证】
- 对照组：legacy 有完整分派与实现
  （`reader/ui/compose/ComposeReaderPageAnimation.kt:53-72` 分派 `DEFAULT` / `NONE` / `ADVANCED`（cover 变换）/
  `SIMULATION`（仿真 + curl），curl 状态机与几何在 `:171-247`，
  测试 `app/src/test/kotlin/.../ComposeReaderPageAnimationTest.kt`（542 行））。【已验证】
- **为什么是 Blocker**：现在转正 = 已经选中 `ADVANCED` / `SIMULATION` 的用户设置仍然存在，
  但视觉语义**静默退化**为普通 slide。这比 crash 更难被发现，属于典型的"设置了但没生效"。
  （`ReaderAnimation` 是持久化设置：`core/prefs/ReaderAnimation.kt:6`。）【已验证】
- **实现方向**【设计提案】：**不要把 legacy Pager 的状态组织搬进来**。legacy 里真正值得复用的是纯函数
  `resolveComposeReaderPageTransform(animation, pageOffset, isVertical, isReversed, navigationProgress,
  isSettledPage, isIncomingPage, isCurlUnfolding) → ComposeReaderPageTransform`
  （`:32-74`，输入输出完全纯）。Scene 侧更合理的结构是：

  ```text
  PagedReaderScene + PagedDragState
          ↓
  PagedTransitionSnapshot(fromSlot, toSlot, progress, direction, gesturePosition)
          ↓
  ScenePageTransitionRenderer
          ├─ Slide
          ├─ Cover
          └─ Curl
  ```

  即 **Scene geometry ≠ Transition visual**：以后换掉 curl 实现不需要动 `PagedReaderScene`（符合 ADR I3）。
  `SIMULATION` 用到的 `clipPath` / `rotateRad` / `frontPath` / `backPath` / 阴影全部可在 Draw Phase 完成，
  没有任何理由为了 curl 退回 Pager 或 Composition。【设计提案】
- **DoD**：三种样式在 `ComposeScenePagedReader` 各有独立表现；参数化测试直接比对
  `resolveComposeReaderPageTransform` 的输出（同输入同输出）；真机确认无 P99 回归。
- **规模**：M–L（工作量偏大，但**没有架构风险**）
- **实施状态（2026-09-18）**：
  - Seam 1：`reader/core/PagedTransitionResolver.kt`（复用既有 `PagedMotionSnapshot`），6 例纯 JVM；
  - Seam 2：`reader/render/compose/ScenePageTransitionRenderer.kt`（SLIDE / COVER / CURL 三样式 + curl 几何接线），
    13 例纯 JVM，其中 COVER 对 legacy 做 240 组、CURL 做 72 组同输入同输出 parity；
  - Seam 3：`ComposeScenePagedReader` draw phase 接线（zIndex 分层、alpha 分层、卷曲前后页与阴影、斜切路径屏幕化），
    加载/错误浮层随动画平移；
  - `SLIDE` 解析为单位变换，因此 `DEFAULT` 与 `NONE` 的**渲染路径与今日完全一致**（回归风险为零，
    `NONE` 与 `DEFAULT` 的差别仍只在释放时是否补间）；`COVER` / `CURL` 只在用户主动选择相应动画时生效。
  - **仍未完成**：真机视觉确认与 P99 基准（属 CS-1A 的真机口径），以及把纯 curl 几何从
    `reader/ui/compose/ComposeReaderPageAnimation.kt` 下移到 render 层 —— 当前 `render/compose` 反向
    import 了 `ui/compose` 的三个纯函数（`calculatePageCurlGeometry` / `resolvePageCurlFromStart` /
    `resolvePageCurlStartFraction`），属本次新增的分层债，需在 CS-4 清账时一并了结。
  - **真机验证（2026-09-19，Redmi Note 12 Turbo / warsaw，Android 17，120Hz）**：本机没有可用视觉模型，
    因此改用像素度量而非肉眼判断（见 §5「真机验证方法」）。四种动画在同一拖拽位置各抓一帧后两两比对：
    - `NONE` vs `DEFAULT`：仅 **0.05%** 像素不同 → 两者共用 SLIDE 渲染路径，与设计一致；
    - `DEFAULT` vs `ADVANCED`：**9.8%** 不同；`DEFAULT` vs `SIMULATION`：**15.8%** 不同；
      `ADVANCED` vs `SIMULATION`：**21.7%** 不同 → 三种样式在真机上确实渲染不同。
      （CS-2 之前四者会完全一致，这正是被修掉的静默塌缩。）
    - 差异区域的内边界（逐行最左侧变化像素）区分平移与折页：SLIDE `x∈[1090,1114]`（std 8.0）、
      COVER 恰好垂直（std **0.0**，新页在静止页下方滑入）、CURL `x∈[900,1114]`（std **93.6**，
      随行漂移的折痕边界，平移不可能产生）。

### CS-3 CONTINUOUS_HORIZONTAL 的方向与门控一致性

- **证据**：该模式的 `readingDirection` 硬编码 `SceneReadingDirection.LEFT_TO_RIGHT`
  （`ComposeReaderScreenRoot.kt:361`），且该分支无 `isExperimentalSceneReaderEnabled` 判定、无 legacy 回退（`:354-398`）；
  模式已对用户暴露（`reader/ui/compose/ComposeReaderOptionsSheet.kt:1011`、`core/prefs/ReaderMode.kt:12,15`）。【已验证】
- **这其实不是引擎缺功能**：`HorizontalReaderScene` 已完整支持 `LEFT_TO_RIGHT` / `RIGHT_TO_LEFT`
  并做过镜像对称性测试（ADR `:420-424`）。缺的是偏好模型。【已验证】
- **根因**【设计提案】：`ReaderMode` 把"布局模式"和"阅读方向"混在一个 enum 里
  （`STANDARD` / `REVERSED` / `VERTICAL` / `WEBTOON` / `CONTINUOUS_HORIZONTAL`）。
  这是 legacy 时代的自然设计，但 Scene 架构下应拆成
  `LayoutMode(PAGED / CONTINUOUS_VERTICAL / CONTINUOUS_HORIZONTAL) × ReadingDirection(LTR / RTL / TTB)`。
  **本次不重构 settings schema**，但 ADR 应显式记录一句：

  > `ReaderMode` 当前仍是 legacy preference projection；
  > Scene core 不得复制这种布局与方向耦合。

  否则将来有人整理 settings 时，可能又把这个耦合带回 Scene。
- **DoD**：短期只补 Continuous Horizontal RTL（选项面板 + 组合语义 + 单测）；附带给该模式补上与其他场景一致的
  门控，或在 ADR 明确记录为何不需要。
- **规模**：S（RTL 本身）+ S（ADR 记录）
- **实施状态（2026-09-19）**：
  - 方式：**不新增 `ReaderMode` 枚举值**，而是独立偏好 `isContinuousHorizontalReversed`
    （`AppSettings.KEY_READER_CONTINUOUS_HORIZONTAL_REVERSED`），把"布局 × 方向"的折算收敛到
    `reader/ui/config/SceneReadingDirectionResolver.kt` 一个纯函数；分页分支原先内联的 `when (mode)` 也改为走同一函数。
  - 界面：阅读选项面板在 `CONTINUOUS_HORIZONTAL` 被选中时显示"从右向左阅读"开关（4 份语言字符串）。
  - 测试：`SceneReadingDirectionResolverTest`（4 例，覆盖全部模式的映射矩阵与 totality）、
    `AppSettingsReadingDirectionTest`（3 例，默认值 / 显式值 / 写入往返）。
  - ADR 已记录 `ReaderMode` 属 legacy 投影、Scene core 不得复制该耦合。
  - **门控结论**：不给该模式新增实验开关 —— 该模式本身即新入口且无 legacy 回退，其渲染宿主与其他场景同源，
    单独再加一层门控只会制造不一致；此结论已写入 ADR。

### CS-4 legacy 宿主与对照组件清账

- **证据**（行数为实测）：`ComposePagedReader.kt` 436 行、`ComposeDoublePageReader.kt` 758 行、
  `ComposeWebtoonReader.kt` 1243 行，合计 **2437 行** legacy 宿主；另有
  `reader/render/canvas/AndroidViewSceneView.kt`（ADR `:276-278` 已判定出局、不再扩张）
  与 `reader/ui/compose/PagedShadowValidator.kt` + `app/src/test/kotlin/.../PagedShadowParityTest.kt`（影子模式已完成使命）。【已验证】
- **为什么必须做，而不是代码洁癖**：长期双引擎会导致 `fix Scene 忘记 legacy` 或
  `fix legacy 导致 Scene 行为不同`，最终重新长出"两套 page list / spread semantics / gesture / resource lifecycle"。
- **分阶段删除策略**（关键调整：**host 与 oracle 分开删**）：

  ```text
  Paged 默认开启
      ↓
  legacy route 降级为 developer fallback（保留一个 nightly/stable 周期）
      ↓
  删除 runtime legacy hosts + View 对照组 + 影子采样点
      ↓
  保留纯函数 / parity 测试作为 oracle（例如 resolveComposeReaderPageTransform）
      ↓
  等 Scene transition 稳定后再清参考测试
  ```

- **注意**：`PagedShadowValidator` 的运行时采样目前挂在 legacy `ComposeDoublePageReader` 里（ADR `:443-447`），
  删除 host 时需一并移除采样点，避免留下死引用。
- **DoD**：按上述阶段推进，每一步都有独立的夜间验证；仅覆盖 legacy 的测试用例逐个判定迁移或删除
  （例如 `ReaderModeAndOverlayTest` 等），不批量删。
- **规模**：M（代码删除本身小，验证成本是主要工作量）

### CS-5 实验开关收口

- **证据**：两个持久化 key `KEY_READER_EXPERIMENTAL_SCENE_ENGINE` /
  `KEY_READER_EXPERIMENTAL_PAGED_SCENE_ENGINE`（`core/prefs/AppSettings.kt:3207-3208`），
  用户可见开关见 `app/src/main/kotlin/org/skepsun/kototoro/settings/compose/ReaderSettingsScreen.kt:1050-1070`。【已验证】
- **DoD**：转正后合并为单一（或零）开关；若保留用于灰度，只留在开发者选项，并修正设置项标题/摘要文案与中英字符串。
- **规模**：S

### CS-6 强制 I1 不变量（`reader/core` 零 Android 依赖）

- **证据**：ADR `:529-534` 把 I1–I6 定义为刚性约束，但当前**没有任何测试或构建约束强制它** ——
  全仓检索不存在扫描 `reader/core/**` 依赖的断言；`reader/core` 目前也只是一个包，
  不是 Gradle 模块（ADR §四当时明确"Package 先行，暂缓拆 Module"，见 `docs/adr/0002-...md:182-218`）。【已验证】
- **DoD**：
  1. 短期：JVM 测试断言 `reader/core/**` 不出现 `android.*` / `androidx.compose.*` / `android.graphics.*` /
     `Bitmap` / Renderer 类型（文本或 PSI 扫描均可）；
  2. 中期：评估抽出 `:reader-core` Gradle 模块，把 I1 从"测试约束"升级为**编译期约束**；
     同类断言可覆盖 I2（`reader/image/**` 不得依赖 `reader/ui`）与 I3。
- **规模**：S（测试）/ M（模块化）

### CS-7 基准回归门禁

- **证据**：`macrobenchmark` 模块已提交完整 harness
  （`ReaderProductionBenchmark.kt`：`FrameTimingMetric`、`MemoryUsageMetric(Max/Last: HeapSize, RssAnon, RssShmem, Gpu)`、
  `PowerMetric`、自定义 `ActivePresentationAssetsMetric`；另有 `ReaderRendererBenchmark.kt`）。【已验证】
- **缺口**：harness 只能人工在真机 `connectedCheck` 触发，**没有任何阈值判定或 CI 门禁**，
  ADR 里的数字靠手抄；`macrobenchmark/README.md:23-26` 仍称"排除真实章节与持续滑动旅程"，
  而 `ReaderProductionBenchmark.kt` 已包含 Burst / Sustained 生产旅程（文档债）。【已验证】
- **DoD**：把关键 journey 脚本化并加阈值判定，重点是**尾部与常驻指标**：
  `frameOverrunMs` P99 不得越过 0 余量、`RssAnon` Max 不得随遍历轮次增长、
  `ActivePresentationAssets` 必须有界（不超过保留窗口）。**不要只检查均值** —— 均值会掩盖尾部尖峰。
  同时更新 README 说明生产基准与判定口径。
- **规模**：M

### CS-8 语义矩阵与可测性

- **证据（已有覆盖）**：设备侧已覆盖 LTR/RTL/TTB 溢出平移→翻页交接、`FIT_HEIGHT` / `KEEP_START` 切换后的
  手势归属重置、`ORIGINAL` 尺寸原生像素平移、双击缩放、翻页后 zoom 保留与回翻恢复
  （`app/src/androidTest/.../ScenePagedGestureTest.kt:49,52,55,58,64,70,73,76,181,264,392`）；
  失败页重试与本地兜底绘制见 `SceneReaderRecoveryTest.kt:45`。【已验证】
- **证据（缺口）**：三个 `ComposeScene*` 宿主文件内检索 `semantics` / `testTag` / `focusRequester` /
  `contentDescription` 均无命中 → TalkBack 无法获知页码章节，instrumented 测试也只能直接实例化 composable；
  另外**进程重建、旋转、章节切换、DPAD / 音量键**在 scene 宿主下没有明确的验收项。【已验证】
- **DoD**：建立可复现的语义矩阵并逐格有结论（通过 / 已知差异 / 待修）：

  | 场景 | 期望不变量 |
  | :--- | :--- |
  | 进程重建 / 后台回收后恢复 | 页面 + 章节 + 页内 scroll/zoom 锚点一致 |
  | 屏幕旋转 / 分屏 / 折叠展开 | 语义锚点归一化，不跳到别页 |
  | 章节切换（含跨章双页阻断） | 跨章不并页、进度连续 |
  | RTL / 双页 / 封面偏移 | 排布与阅读顺序保序 |
  | zoom restore（切页 / 回翻 / 切模式） | zoom/pan 状态与归属正确 |
  | DPAD / 音量键 / TV 呈现 | 翻页可用，不依赖渲染宿主 |

  同时补页面级 `testTag` 与阅读器级 `semantics`（页码/章节/自定义动作 上一页·下一页），
  并加一条经 Tag 驱动的 instrumentation 用例。
- **规模**：M（矩阵偏验证成本）+ S（semantics/testTag）

### CS-9 资源窗口 / 预取策略统一

- **证据**：Webtoon 与横向连续使用 `ReaderPrediction` + `ViewportMotion` 的速度驱动前瞻
  （`ComposeSceneWebtoonReader.kt:177-191`、`ComposeSceneHorizontalReader.kt:177-191`）；
  分页宿主则是手写的占位优先级列表（`ComposeScenePagedReader.kt:461-486`：IMMEDIATE / HIGH / MEDIUM，
  current slot ±1 / ±2）。【已验证】
- **为什么是债**：这是 ADR I2 的最后一公里 —— 现在 ImagePipeline 仍能从需求形态反推出
  "这是分页还是连续"，说明模式知识泄漏到了资源层。
- **目标形态**【设计提案】：

  ```text
  Scene + ViewportMotion + SceneReadingDirection
          ↓
  SceneResourceWindowPlanner（连续 / 分页各有 strategy）
          ↓
  ReaderResourceWindow（统一输出契约）
  ```

  连续与分页可以保留各自的 strategy，但**输出契约必须相同**，这样 ImagePipeline 才真正不知道
  Webtoon / Horizontal / Paged / Double Page 的区别。
- **DoD**：抽出统一 planner 契约 + 分页侧改为经 planner 产出需求；JVM 测试覆盖两种 strategy 的输出契约一致性。
- **规模**：M（可与 CS-1B 并行，但不阻塞它）

---

## 3. Scene Reader 2.0（Phase 4，架构红利，**不阻塞清退**）

> 明确标注：以下三项属于 Scene Reader 2.0 能力。它们收益很大，但**不得**成为 CS-1B ~ CS-5 的前置条件，
> 否则"收尾"会重新变成无限扩 scope。

### P4-1 增强管线场景化：翻译 / 超分各自采用正确的粒度

- **证据**：增强入口在 `reader/domain/PageLoader.kt:433-459` —— 先 `enhancementController.preparePage`，
  再对整页文件做超分（`:442-459`，注释 `:440` 直言 *"Super-resolution runs outside the download permit pool and
  remains legacy-reader behavior."*）；翻译侧在
  `reader/translate/domain/ReaderPageTranslationProcessor.kt:373-380`，路径是
  整页 `decode` → `copy(Bitmap.Config.ARGB_8888, true)`（第二次全页拷贝）→ 全页 `Canvas` 渲染 → 缓存文件。【已验证】
- **影响**【待实测】：ADR `:362` 记录 Phase 1D 夹具单张原始 bitmap 展开最高 **172.8 MB**（12k–40k px 条漫）。
  上述路径会把瓦片引擎刚刚消除的内存峰值重新引入 —— 呈现阶段仍是 tiled（超分产物是文件 URI，
  照旧走 `DecodePlanner`，`reader/image/KototoroImagePipelineAdapter.kt:301`），问题出在**处理阶段**。
- **相关空白**：`PixelUsage.CPU_READ_REQUIRED` 定义了"CPU 可读"像素用途
  （`reader/image/PixelUsage.kt:20`），但全仓仅被 `DecodeAllocatorPolicy.kt:35` 映射，
  **没有任何调用者** —— 即瓦片引擎目前没有给 OCR/色彩分析的 CPU 可读通道。【已验证】
- **粒度口径（关键修正）**：**不能**把整条增强链一律改成"按可见条带处理"。
  - **Detection 必须是 page-aware**：漫画对话框会被视口边界切开，
    纯 viewport-driven 的 detection 会损失 bubble grouping、阅读顺序与跨边界 text region。
    应使用**低分辨率整页**或**带重叠的条带**做检测。
  - **Recognition 是 region-driven**：按检测出的 text region 做区域解码即可。
  - **Rendering 是 viewport-aware**：只渲染可见部分（这也是 overlay 方案的前提，见 P4-2）。
  - **Super-resolution 可以真正做到 viewport + LOD + tile aware**，与 OCR 的策略不必相同。

  即：**Detection page-aware，Rendering viewport-aware**；超分与 OCR 两条策略不要再绑成一种。
- **建议路径**：
  1. **先测量再改造**：在 `ReaderProductionBenchmark` 夹具上加"翻译/超分开启"变体，
     采集 `RssAnon`、`memoryGpuKb` 与耗时，把"疑似内存炸弹"变成结论或排除；这一步成本低且不改变行为。
  2. 若成立：按上面的粒度口径拆分增强链；为增强阶段引入 scene-aware 契约（明确像素用途与瓦片归属），
     并遵守 ADR I2（不持有阅读语义）。
- **规模**：L（但第 1 步是 S）

### P4-2 译文 overlay 层（向量化呈现）

- **提案**【设计提案】：把译文从"烤进 bitmap"改为 Draw Phase 的文本覆盖层。
  收益：原/译切换退化为 overlay alpha 0 ↔ 1（而不是重新 decode / render / cache 一张图）；
  字号与排版样式可调且不触发重 OCR（现在 `ReaderTranslationRenderStyle` 的 `REPLACE` / `COMPACT_OVERLAY`
  是渲染期烘焙进像素的）；省掉一份全页栅格与缓存文件。
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

以下五项在本次审计中**被排除**，不必再当作缺口：

1. **自动滚动**：`ScrollTimer` 经 `ReaderControlDelegate` 下发，两套 webtoon 宿主都有对应实现
   （`ComposeSceneWebtoonReader.kt:322 dispatchWebtoonScroll` 与 `ComposeWebtoonReader.kt:261,567`）。【已验证】
2. **译文/增强完成后的刷新**：重载通过 `reloadNonce` 进入页面身份
   （`reader/ui/pager/ReaderPage.kt:27,31`，`readerKey` 含 `reloadNonce`），
   因此 `PageId` 会变化，`KototoroImagePipelineAdapter` 的 `cachedAssets`
   （`KototoroImagePipelineAdapter.kt:78`）自然失效，不会命中旧资产 —— 与 Phase 0C 修复的
   "warm backend switch 首屏模糊"是不同机制。【已验证】
3. **`pageId → ReaderPage` 查找开销**：三个宿主都已是 `associateBy { it.readerKey }` + `pageLookup` 的 O(1) 映射，
   快速滑动不会退化为 O(N)（证据见 §1 末条）。【已验证】
4. **TV / 音量键翻页**：在 Activity 层分发（`reader/ui/ReaderActivity.kt:960-996`），不依赖渲染宿主选择。
   但 scene 宿主下的 DPAD 实际行为仍必须纳入 CS-8 的语义矩阵真机确认，不要仅凭代码路径推断。【部分已验证】
5. **超分产物与瓦片共存**：超分输出是文件 URI，仍会被 `DecodePlan` 判定，
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
> README 第 23-26 行的描述已过时，属于 CS-7 的文档债。

**现有覆盖（行数为实测，节选）**

| 层 | 测试 |
| :--- | :--- |
| 纯几何 / 语义（JVM） | `PagedReaderSceneTest` 220、`PagedSpreadResolverTest` 396、`PagedSnapResolverTest`、`HorizontalReaderSceneTest` 399、`VerticalReaderSceneTest` 359、`PagedPanBoundsResolverTest` 142、`PagedDragStateTest` 90、`SceneAxisProjectionTest` 77 |
| 图像管线（JVM） | `ReaderTileManagerTest` 408、`TileGridTest` 244、`TileMemoryBudgetTest` 185、`ReaderImageAssetTest` 609、`KototoroImagePipelineAdapterTiledTest` 335、`ReaderLodPolicyTest` 83、`SceneParityRegressionTest` 324 |
| 呈现 / 交互（JVM） | `ComposeScenePagedInteractionTest` 452、`ComposeSceneHorizontalInteractionTest` 279、`ComposeSceneInteractionsTest` 239、`SceneImagePresentationCoordinatorTest` 89、`ComposeSceneRendererTiledTest` 129、`TileDrawModifierNodeTest` 91 |
| 设备侧（androidTest） | `ScenePagedGestureTest` 698、`SceneReaderRecoveryTest` 157 |

**基准既有 metric（`ReaderProductionBenchmark.kt:121-170`）**【已验证】：
`FrameTimingMetric`、`MemoryUsageMetric(Mode.Max: HeapSize / RssAnon / RssShmem / Gpu)`、
`MemoryUsageMetric(Mode.Last)`、`ActivePresentationAssetsMetric`、`PowerMetric`（设备支持时）。

**真机验收口径**：任何"转正 / 删除 / 替换"类变更，都必须记录 4 组指标
（`frameDurationCpuMs` P50/P99、`frameOverrunMs` P99、`RssAnon` Max/Last、`ActivePresentationAssets` Max/Last），
并同时跑 Primary（`CompilationMode.Partial`）与 Diagnostic（`CompilationMode.Full`）两种模式，
沿用 ADR Phase 0B/1D 的表格格式，便于跨阶段对比。阈值判定归属 CS-7。

### 真机验证方法（2026-09-19 建立）

本机环境**没有可用的视觉模型**（候选模型均不声明图像输入），因此"看一眼动画对不对"不可用，
视觉类结论一律改由像素度量给出。三个必须记住的操作要点：

1. **MIUI/HyperOS 必须先解封输入注入**：否则 `input swipe/tap/motionevent` 静默无效，
   而截图完全正常 —— 极易误判成"功能没生效"。root 下执行
   `adb shell su -c "setprop persist.security.adbinput 1"` 即可；随后一次 swipe 应能改变
   >90% 的像素（本机实测 `moved_share(>12)=0.953`）。
2. **截图必须走 raw + cmd 重定向**：PowerShell 的 `>` 会破坏二进制（PNG 头都不对）。
   用 `cmd /c "adb exec-out screencap > shot.raw"`，得到 16 字节头（w/h/fmt/colorspace）+ RGBA8888，
   本机为 1280×2772 → 14,192,656 字节。
3. **过渡样式的判据是"差异区域的内边界"**：同一拖拽位置抓帧后，比较"拖拽帧 vs 静止帧"的逐行差异，
   取每行最左侧发生变化的 x。纯滑动内边界固定（实测 std 8.0），cover 恰好垂直（std 0.0），
   curl 随行漂移（std 93.6，x 从 900 漂到 1114）—— 平移不可能产生随行变化的边界，故该指标可直接
   区分折页与滑动。

像素度量脚本 `transition_probe.py`（zlib + numpy 解 PNG，或直读 raw；提供 `stats` / `diff` / `seam` /
`extent` 四个命令）当前位于工作区外的 `E:\kototoro_demo\device-evidence\`。它在同一台设备上可复用，
**待稳定后应移入仓库**（可仿照 `artwork_probe.py` 随 skill 分发），以免下次又从零写一遍。

---

## 附录：证据索引

| 结论 | 证据位置 |
| :--- | :--- |
| 分页场景默认关闭 | `ReaderSettings.kt:35`、`AppSettings.kt:1054-1056`、`ComposeReaderScreenRoot.kt:119,400` |
| 单页 / 双页共用同一场景宿主 | `ComposeReaderScreenRoot.kt:120,401` |
| Webtoon 场景默认开启 | `ReaderSettings.kt:34`、`ComposeReaderScreenRoot.kt:256-303` |
| 横向连续无回退、硬编码 LTR | `ComposeReaderScreenRoot.kt:354-398`（方向见 `:361`） |
| 分页动画样式被塌缩 | `ComposeScenePagedReader.kt:244` |
| 场景过渡三样式实现 | `reader/render/compose/ScenePageTransitionRenderer.kt`、`reader/core/PagedTransitionResolver.kt` |
| curl 几何纯函数（待下移分层） | `reader/ui/compose/ComposeReaderPageAnimation.kt`（`calculatePageCurlGeometry` 等） |
| legacy 动画分派与 curl | `ComposeReaderPageAnimation.kt:53-72,171-247` |
| 动画纯函数 oracle（可复用为对照） | `ComposeReaderPageAnimation.kt:32-74` |
| O(1) 页面查找（三个宿主） | `ComposeSceneWebtoonReader.kt:142-145`、`ComposeSceneHorizontalReader.kt:142-145`、`ComposeScenePagedReader.kt:194-196` |
| 分页占位预取为手写列表 | `ComposeScenePagedReader.kt:461-486` |
| 连续模式使用 ReaderPrediction | `ComposeSceneWebtoonReader.kt:177-191`、`ComposeSceneHorizontalReader.kt:177-191` |
| 增强阶段整页栅格 | `PageLoader.kt:433-459`、`ReaderPageTranslationProcessor.kt:373-380` |
| CPU 可读像素用途无调用者 | `PixelUsage.kt:20`、`DecodeAllocatorPolicy.kt:35`（全仓唯一引用） |
| 视口逆变换工具 | `SceneImagePresentationCoordinator.kt:25,77,115` |
| 页面身份含 reloadNonce | `reader/ui/pager/ReaderPage.kt:27,31` |
| 资产缓存按 PageId | `KototoroImagePipelineAdapter.kt:78,162-182,236-248` |
| 基准 harness 与既有 metric | `macrobenchmark/.../ReaderProductionBenchmark.kt:121-170`、`ReaderRendererBenchmark.kt`、`ActivePresentationAssetsMetric.kt` |
| 设备侧手势覆盖 | `app/src/androidTest/.../ScenePagedGestureTest.kt:49,52,55,58,64,70,73,76,181,264,392` |
| 架构不变量 I1–I6 | `docs/adr/0002-reader-scene-decoupling.md:527-534` |
| 暂缓拆 Gradle 模块的原始决定 | `docs/adr/0002-reader-scene-decoupling.md:182-218` |
| Phase 3F 未验收项与动画债 | `docs/adr/0002-reader-scene-decoupling.md:486-509` |
