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
- **场景 1 首次实测（2026-09-19，warsaw，Android 17，120Hz，`CompilationMode.Full`，5 轮取中位）**：

  | 指标（中位） | Scene（`ComposeScenePagedReader`） | Legacy（`ComposePagedReader`） | 结论 |
  | :--- | ---: | ---: | :--- |
  | `frameDurationCpuMs` P50 | 2.574 | 2.788 | −7.7% |
  | `frameDurationCpuMs` P90 / P95 | 4.286 / 4.714 | 4.035 / 4.243 | +6.2% / +11.1% |
  | `frameDurationCpuMs` **P99** | **7.709** | **5.200** | **+48.3%（回归）** |
  | `frameOverrunMs` P99 | −4.743 | −7.323 | 余量变小 |
  | `RssAnon` Max | **234,440 KB** | 270,560 KB | **−13.4%** |
  | `RssAnon` Last | **175,448 KB** | 270,560 KB | **−35.2%** |
  | `Gpu` Max | **86,776 KB** | 98,712 KB | −12.1% |
  | `HeapSize` Max | 132,194 KB | 132,376 KB | 持平 |
  | `ActivePresentationAssets` Max/Last | 2.0 / 1.0 | 0.0 / 0.0 | 见下方注 |

  **判读（不粉饰）**：
  - **内存是明确收益**：稳态 RSS Anon −35%、峰值 −13%、GPU −12%。
  - **CPU 尾部是明确回归**：P99 CPU 7.71ms vs 5.20ms。两者的 CPU P99 都仍在 120Hz CPU 预算（8.33ms）内
    （口径提醒：CPU P99 达标不能单独证明零掉帧，超时帧比例当时未记录，此处不作"没有丢帧"的断言），
    但按 ADR `:264-266` 的 Go 判据（"至少一项显著改善 **且其他关键指标不存在明显回归**"），
    **场景 1 单独不满足 Go**，P99 回归必须先定位。
  - Legacy 的 `ActivePresentationAssets` 恒为 0 是**仪表差异**而非收益：该指标读的是场景管线自身的
    presentation 状态机，legacy 路径不走它。
  - **测量条件警告**：设备当时内存紧张（15.4G 中 ZRAM 已用约 8G），两组同条件背靠背跑，但绝对数值
    应在清理设备后复测确认。
- **下一步（阻塞 Go/No-Go）**：把 P99 回归归因清楚。第一嫌疑是场景分页宿主 draw phase 的每帧分配/
  排序（CS-2 接线新增了逐帧 `map` + `sortedBy`、每 slot 一个 `Rect`、以及逐 slot 的过渡解析），
  第二嫌疑是宿主本身的逐 slot 裁剪与 `drawFrameNodes` 调用结构。归因手段：在 CS-2 之前的提交
  （`4130fc75b`）上叠加本 harness，做同场景的 A/B，再决定是优化还是接受。
- **归因结果（2026-09-19，同一旅程、同一夹具、逐次清理设备后测量）**：

  | 被测构建 | CPU P99 (ms) | Overrun P99 (ms) | RssAnon Last (KB) |
  | :--- | ---: | ---: | ---: |
  | CS-2 **之前**的宿主（`4130fc75b` 版文件 + 本 harness） | 7.774 | −4.727 | 174,472 |
  | CS-2（已提交状态） | 7.709 | −4.743 | 175,448 |
  | CS-2 + 两处热路径修正 | 7.563 | −4.657 | 175,184 |
  | Legacy 分页（对照） | 5.200 | −7.323 | 270,560 |

  **结论：P99 差距与 CS-2 无关**（7.774 → 7.709 在噪声内），也**不是**两处每帧开销造成的
  （7.709 → 7.563，同样在噪声内）。两处被排除的嫌疑是：
  ①以每帧变化的 offset 作 `LaunchedEffect` key 导致的逐帧协程重启（已改为 `snapshotFlow` + `debounce`，
     语义等价、去掉抖动，保留在代码中）；
  ②`loadingPlacements` 在组合期读取 offset 造成的逐帧重组（已在"无待加载页"时不再读取 offset，保留）。
  因此剩余差距是**结构性的**：场景宿主在翻页过程中逐帧重绘页面内容（`drawFrameNodes` + 每 slot 裁剪/变换），
  而 legacy 路径靠 Pager 的硬件层（每页一个 `graphicsLayer`）做过渡，逐帧只更新矩阵并合成。
- **给下一阶段的建议（未实施）**：这正是 ADR PoC B 里预留的 **Retained GraphicsLayer** 路线 ——
  静止时正常绘制，过渡期间把 outgoing/incoming 页面快照进各自的 `GraphicsLayer`，只动画层矩阵。
  若采纳，预期能把分页场景的翻页 CPU 拉回 legacy 水平，同时保留场景语义与内存收益。
  在此之前，**升格决策（CS-1B）应基于"两者都在 120Hz 预算内 + 场景内存显著更优"** 做出，
  并把"分页场景 P99 CPU 约为 legacy 的 1.5 倍"作为已知特性记录在 ADR，而不是当成回归处理。
- **四组场景全矩阵（2026-09-19，warsaw，Android 17，120Hz，`CompilationMode.Full`，各 5 轮取中位；
  每次运行前 force-stop + drop_caches）**：

  | 场景 | 宿主 | CPU P50 | CPU P99 | Overrun P99 | RssAnon Max | RssAnon Last | GPU Max | Assets Max/Last |
  | :--- | :--- | ---: | ---: | ---: | ---: | ---: | ---: | :--- |
  | 1. 普通单页 1x | Scene | 2.643 | 7.563 | −4.657 | — | 175,184 | 86,776 | 2.0 / 1.0 |
  | 1. 普通单页 1x | Legacy | 2.788 | 5.200 | −7.323 | 270,560 | 270,560 | 98,712 | 0.0 / 0.0 |
  | 2. 超大页 6000×9000 1x | Scene | 2.087 | 8.934 | −2.995 | **523,324** | **244,536** | **172,292** | 2.0 / 1.0 |
  | 2. 超大页 6000×9000 1x | Legacy | 2.636 | 5.351 | −7.039 | 449,812 | 449,812 | 122,360 | 0.0 / 0.0 |
  | 3. 超大页高倍放大（fit_height） | Scene | 2.538 | 7.724 | −4.146 | 434,056 | 390,672 | 172,136 | 2.0 / 2.0 |
  | 4. 双页跨章往复 | Scene | 2.040 | 7.840 | −4.891 | **240,776** | **221,824** | 98,840 | 3.0 / 2.0 |
  | 4. 双页跨章往复 | Legacy | 2.804 | 5.044 | −7.272 | 271,328 | 271,328 | 98,712 | 0.0 / 0.0 |

  （单位为 ms / ms / ms / KB / KB / KB。场景 3 无 legacy 对照：legacy 宿主的缩放实现不同，
  该场景按"场景专属"记录。）

  **判读（结论未粉饰）**：
  - **没有任何场景错过截止期**：四个场景的 `frameOverrunMs` P99 全为负（余量 −2.995 ~ −4.891ms），
    即至少 99% 的帧未超时且留有约 3–5ms 余量。**口径修正（2026-09-20，见
    [改进计划 §2/§4.3](reader-scene-improvement-plan-2026-09.md)）：P99 overrun 为负不等于零掉帧** ——
    最慢约 1% 的帧不在该分位数描述内，是否超时需以超时帧比例或最差帧 trace 核对；当时未保留该口径，
    故此处只记录分位数本身，不作"120Hz 下没有丢帧"的结论。场景 2 的 CPU P99 8.934ms 高于 8.33ms 的
    "CPU 预算"属 CPU 口径，与显示截止期分开评价。
  - **稳态内存全面占优**：RssAnon Last 相对 legacy 为 −35%（场景 1）、−46%（场景 2）、−18%（场景 4）。
  - **两个真实回归**：
    (a) **CPU P99 一致偏高**：约为 legacy 的 1.5–1.7 倍（已归因为结构性，见上）；
    (b) **超大页场景的峰值内存与 GPU 更差**：场景 2 的 RssAnon Max +16%（+73MB）、GPU Max +41%（+50MB），
        与 ADR Phase 1D 在 12k–40k px 条漫上"GPU −62%"的结论方向相反 —— 说明瓦片/overview 的预算
        在 6000×9000 这个量级上没有调好（overview LOD 与相邻页预取同时驻留），需要单独定位。
  - 因此**当前证据是"有条件 Go"，不是无条件 Go**：需要在"接受两个回归并在 ADR 记录"
    与"先修其中至少一个（建议先修 (b)，因为它最可能是预算参数问题）"之间做决策。
- **回归 (b) 已定位并修复（2026-09-19）**：根因不在瓦片预算，而在**适配器丢弃了 planner 的 LOD** ——
  `KototoroImagePipelineAdapter` 的非瓦片路径构造 Coil 请求时没有设置尺寸
  （`ImageRequest.Builder(context).data(uri)`），于是 6000×9000 被按**原始分辨率解码 = 216MB**，
  而 planner 明明算出了 `SampledSingle(≈1500×2250, 13.5MB)`。修复：把 plan 的目标尺寸传给请求
  （`DecodePlan?.requestedDecodeSize()`，仅 `SampledSingle` 生效）。
  真机前后对比（同场景、同夹具、逐次清理）：

  | 场景 2（6000×9000，Scene） | 修复前 | 修复后 | Legacy 对照 |
  | :--- | ---: | ---: | ---: |
  | CPU P99 (ms) | 8.934 | **7.597** | 5.351 |
  | Overrun P99 (ms) | −2.995 | **−4.890** | −7.039 |
  | RssAnon Max (KB) | 523,324 | **247,228（−52.8%）** | 449,812 |
  | GPU Max (KB) | 172,292 | **108,752（−36.9%）** | 122,360 |

  场景 3 同样改善（Max 434,056 → 246,372；GPU 172,136 → 108,764），场景 1（小页，不走该路径）**完全不变**
  （175,184 → 175,416 / 86,776 → 86,764）。**回归 (b) 消除，且超大页场景现在在内存与 GPU 上都优于 legacy。**
  新增 `Reader.PresentationWidthPx` 指标（`PresentationWidthMetric`）用于验证解码宽度：
  1× 实测 **1500px**，与 planner 的预测一致。
- **修复时发现的第二个缺口（已修）**：`onCameraSettled` 只处理 Tiled 资产，且 planner 调用未传
  `cameraScale`，因此限制解码尺寸后，用户**捏合放大**时采样页不会被重解码（会变糊）。
  已补：`cameraScale` 进入 plan，并新增 `shouldReacquireForZoom`（缺额超过 25% 才重解码，避免抖动）。
- **新旅程暴露的既有严重问题（未修，列为下一优先项）**：`pagedLargeZoomedSceneFull`
  （6000×9000 + `default_scale=2.5`）实测 **RssAnon Max 855,884 KB、GPU 306,512 KB、CPU P99 458ms**，
  而 `PresentationWidthPx` 为 0 —— 即在 2.5× 下 planner 直接给出 **Tiled**（`sampleSize=1`、1024² 瓦片），
  瓦片机制在这个量级的放大下失控。**该路径与本轮修复无关**（Tiled 计划时 `requestedDecodeSize()` 返回 null，
  请求与修复前逐字一致），属于既有问题；legacy 宿主靠 Telephoto 的 SubSampling 不会这样。
  这是"场景阅读器真正成熟"的当前最大阻碍，优先级高于升格：需要在 4K 级放大下重新审视
  瓦片尺寸/LOD 策略（例如放大时用采样瓦片而不是 level-0 瓦片）与在飞解码的取消/回收。
- **放大场景的定位进展（2026-09-19，同轮内多次迭代，全部有真机数据）**：
  1. **先测量再修**：加了瓦片驻留遥测（`Reader.TileResidentBytes` / `TileResidentCount` /
     `TileDecodeRequests` / `CachedAssetCount` / `RegionSourceCount`，由 `DecodeResidencyMetric` 读出）。
     首测结果定性：**Java 堆 133MB 与 1× 完全相同，而 RssAnon 843MB** —— 增长全在 native（Android 8+
     位图像素在 native 堆）；同时**瓦片账本自报 406MB / 83 块（≈4.9MB/块 = level-0 瓦片）、
     累计 2104 次解码、4~5 个页面持有资产、4 个 region source**。可见区域实际只需约 11.5MB/页。
  2. **已修**：`PagedSlot.visibleContentNodes` 回答的是"该 slot 自己的视口可见什么"，对**邻页**
     （`resolveSlotTransform` 返回其保存的 scale，通常 1.0）就等于"整页" → 整页瓦片被当作 VISIBLE 钉住。
     新增 `PagedSlot.screenVisibleContentNodes(screenViewportBounds, …)`，把结果与**屏幕视口**求交，
     宿主两处（资源窗口与 draw phase）都改用它。
     效果：瓦片驻留 **406MB → 283MB（−30%）**、RSS **843MB → 706MB（−137MB）**。
  3. **另有两次尝试为负结果，已如实记录**：①给 LOD 策略加"绘制密度容差"（避免只是差 6% 就退回 level-0）
     —— 对放大场景无影响（2.5× 实际相机 3.61×，此时 level-0 是合理的锐度选择）；②限制单页位图
     不得占用整个工作集预算（`TilePolicy.maxSinglePageCostBytes` = 16MB）并把放大重解码改为
     "同一目标宽度只尝试一次" —— 同样未改变放大场景的数字（两处改动本身仍有价值，保留）。
  4. **仍未解决**：修复后仍有 **283MB 瓦片 / 53 块 / 1951 次解码 / CPU P99 429ms**，说明仍在按
     level-0 请求远超可见范围的瓦片（预期约 6 块/页、24MB/页）。下一轮应从两处继续：
     ①`onCameraSettled` 的 target 层与 base 层可能同时驻留（`requestTiles` 同时驱动两者）；
     ②大量解码请求（1951）表明存在按页级粒度的重复请求，需要按 `TileKey` 粒度核对请求来源。
  5. **请求来源已定案（2026-09-19，异常探针 + 调用栈）**：在 `ReaderTileManager.requestTiles`
     加了"异常即记录"探针（`specs > 12` 或区域 > 6M 逻辑像素时打日志，前 5 条附调用栈）。
     一次放大旅程共 **4012 次异常请求**，形态高度一致：
     `region=IntRect(0, 0, ~1031, 9000)`、`sampleSize=1`、`pageSize=6000×9000`、`specs=18/27/36/45`
     —— 即**整页高度 × 约一个瓦片宽的竖条**（正确形态应是约 1153×2494、specs≈6）。
     调用栈显示来源**正是上一轮已经改过的两处**（`updateResourceWindow` 第 4 步与 settle 效果里的
     `coordinateVisibleTiles`），因此问题不在调用点，而在**传进去的几何**。
  6. **已排除 helper 本身**：新增两个纯几何测试（放大后区域必须在 y 方向也收缩；pan 之后区域不得
     超过放大视口），在 scale=2.5 与带 pan 偏移下均通过（4/4）。所以 host 传参是下一步的落点：
     下一轮应在两处调用点打印 `resolveSlotTransform(slotIndex)` 的结果（scale/offsetX/offsetY）、
     `zoomMode` 推导出的 `layoutScale` 与 `canvasOffsetX/Y`，确认是否出现"该 slot 的 scale 被当成 1"
     （即 `zoomedSlotIndex` 与当前可见 slot 不一致）导致整页高度进入请求。
  7. **现场取证完成，根因确定（2026-09-19）**：在 `SceneImagePresentationCoordinator.coordinateVisibleTiles`
     里对"逻辑区域 > 6M 像素"的节点打印几何后，一次运行拿到 24 条现场记录，形态统一为：
     ```text
     sceneBounds   = (996, 0, 2844, 2772)        // 页高 2772 = 视口高
     visibleRegion = (996, 0, 1315.6, 2772)      // 全高，宽仅 319.6
     sampleSize    = 1 → logical = (0, 0, 1038, 9000)
     ```
     即：该页**当前以 scale=1 绘制**（`resolveSlotTransform` 对它返回默认值——相机属于另一个 slot），
     所以"整页高度都在屏内"成立；但它持有的**网格仍是放大期间按 camera≈3.6 建成的 level-0**。
     密度差 3.6 倍 = 像素多约 13 倍，这才是 30 倍过度请求的真正来源：**网格的 LOD 没有跟随页面
     当前的绘制密度**（页面转身后相机重置为 1×，网格却没有降级）。
  8. **已实施的对称修复**：`KototoroImagePipelineAdapter.coarsenOverDetailedTiles(scale)` —— 每次相机沉降
     时为每个 Tiled 页重算 plan，若 plan 的取样率至少粗一档（`coarsenedBaseSampleSize`，≥2×）就用
     更粗的 grid 重建 base 并释放旧瓦片；瓦片尺寸与 overview 沿用（密度才是变量）。这与既有
     "放大时加 target 层"完全对称。测试：`TileBaseCoarseningPolicyTest` 4 例。
     效果：放大场景瓦片驻留 **283MB → 187MB**、RSS **706MB → 494MB**（相对最初 843MB 共降 349MB），
     1× 对照不变。
  9. **仍未解决且已改写判据**：修复后瓦片块数仍 54、解码请求仍 1973、**CPU P99 仍 431ms** —— 说明
     主导成本已不是解码密度而是**解码/上传的绝对次数**（1973 次解码 + 306MB 纹理）。下一轮的判据：
     ①统计"每帧实际绘制的瓦片数"（新增计数器）以区分"解码线程抢占"与"纹理上传/绘制抢占"；
     ②核对为何请求量是期望值的 ~3 倍（期望约 6 块/页/次，实测 18 块）——即请求是否每帧重复下发；
     ③若确认为上传/绘制瓶颈，考虑对 level-0 瓦片启用 `RGB_565` 或降低放大上限（产品级护栏）。
  10. **瓶颈已定性为"每帧纹理绘制量"（2026-09-19）**：新增两个计数器（`Reader.DrawnTilesPerLayer`
      与 `Reader.DrawnTileBytesPerLayer`，在 `drawTileLayer` 内统计）后实测：
      **单次绘制 44 块瓦片 / 138.7MB 位图**，而同期驱逐仅 481 次、常驻 52 块/200MB。
      即 120Hz 下每帧要上传约 139MB 纹理（16GB/s，物理不可能）→ **帧线程被纹理上传阻塞**，
      与 431ms 的 P99 完全吻合。这才是放大场景的真正瓶颈（不是解码线程抢占）。
  11. **一次失败的修复（负结果，已回滚并记录原因）**：针对第 10 条尝试"按页上报绘制倍率"
      （`ensureTileDensity(pageId, drawnScale)`，在宿主资源窗口里逐页调用）：内存确实进一步下降
      （绘制位图 139→98MB、常驻 200→112MB、RSS 610→434MB），但 **解码请求从 1933 暴涨到 26,533**，
      且 CPU P99 毫无改善（454ms）。加 400ms 冷却后数字不变 —— 说明抖动来自**重建本身**
      （每页每 400ms 释放 18 块并重解码），而不是重复触发。因此该改动**已回滚**：
      留在代码里的是第 8 条的沉降期降级（residency 187MB），而非每帧重建。
  12. **下一轮的落点（已明确）**：`drawTiledPage` 目前**先画 base 层再画 target 层**，
      两层覆盖同一区域 → 已驻留 target 瓦片之下的 base 瓦片是**冗余上传**。修复方向：
      先画 target 层，再只为"target 尚未驻留"的区域补画 base 层。该改动只减少绘制量、
      不触发任何重建，是纯收益；预期把每帧 139MB 的纹理上传压到约一半以内。

### Telephoto 对照研究（2026-09-19，`me.saket.telephoto 0.19.0`）

legacy 分页宿主在同样的 6000×9000 页面上不会出现纹理爆炸，因为它用的就是 Telephoto 的瓦片降采样器
（仓库自己实现了三种 `SubSamplingImageSource` / `ImageRegionDecoder`：`NativeSubSamplingImageSource`、
`ZipSubSamplingImageSource`、`RegionSubSamplingImageSource`，宿主侧用 `SubSamplingImage` +
`rememberZoomableImageState`）。读上游源码后，它的做法与我们的场景瓦片路径有 5 处本质差异
（源码：`sub-sampling-image/src/**`：`internal/tileGridGenerator.kt`、`internal/ImageCache.kt`、
`RealSubSamplingImageState.kt`）：

| # | Telephoto 的做法 | 我们当前的做法 | 造成的后果 |
| :--- | :--- | :--- | :--- |
| 1 | **一次预生成整个 LOD 阶梯**：`foreground[sampleSize]` 对所有可能的 sampleSize 预先算好（注释直言"避免缩放手势期间的分配"） | 每次沉降/重规划重建 grid（`coarsenOverDetailedTiles`、acquire 时按当时相机） | 网格标识随缩放变化 → 释放再解码，churn（实测 1.9k–26k 次解码） |
| 2 | **层由当前缩放逐帧决定**：`currentSampleSize = calculateFor(scale).coerceAtMost(base)`，再无状态需要调和 | 层的 sampleSize 烘焙进 grid，之后只能靠重建去改 | 同上；且"该用哪一层"与"网格是什么"耦合 |
| 3 | **驻留 = 可见集合**：`ImageCache` 只在视口变化时加载缺失瓦片、**卸载不在可见列表里的一切**并取消在飞任务，另有 `throttleLatest(100ms)` 防止缩放动画狂发解码；没有预算、没有 LRU、没有"可见永不驱逐"的逃生口 | `TileMemoryBudget` 四级保留 + VISIBLE 钉住 + 64MB 预算，且重建时批量释放 | 常驻 53 块/182–406MB，且预算被"可见豁免"绕过 |
| 4 | **瓦片几何由层与视口推导**：`tileSize = imageSize × (sampleSize / baseSampleSize)`，并按"不超过视口一半"取整 → **每块瓦片解码后≈视口大小**，层越深块数越少（首层 2×2） | 固定 1024 逻辑像素瓦片 | level-0 时一块 = 1024²=4MB，6000×9000 页要 54 块，可见区就要 18–45 块 → 单帧 85–139MB 纹理 |
| 5 | **base 层只作补缝**：`canDrawBaseTile = hasNoForeground \|\| hasGapsInForeground()`，前景层补齐后 base 不再绘制 | base 与 target 都画（第 12 条） | 多余上传 |

**结论（对场景阅读器的启示，按收益排序）**：
1. **瓦片几何改为"每块≈视口大小"**（第 4 条）：可见区从 18–45 块降到约 4–6 块、单帧纹理从 85–139MB
   降到约 25–30MB。这是**单项收益最大**的改动，且是纯几何策略改动（`DecodePlanner.resolveTileDimension`
   需要拿到视口尺寸与层）。
2. **层由缩放逐帧推导 + 预生成阶梯**（第 1、2 条）：彻底消除重建/churn（本轮两次重建类实验的失败根因）。
3. **驻留改为"可见集合"语义**（第 3 条）：去掉"VISIBLE 永不驱逐"的逃生口，让内存有界且可预测。
4. **base 只作补缝**（第 5 条）。
5. **可直接复用现成资产**：我们的 `SubSamplingImageSource` 系实现已经封好了 `ImageRegionDecoder`
   的三种取流方式，场景管线可以复用它们，而不是各自维护 `AndroidRegionDecoderFactory`。

### 放大病理的根因与修复（2026-09-19，提交见下）

对照 Telephoto 后回看我们的 plan 决策，发现真正的触发点既不是缓存也不是缩放，而是一个**过保守的
单张位图上限**：

- `TilePolicy.safetyDimensionLimitPx` 默认 **4096**，且 `RendererCapabilities` 在生产代码里**从未被解析**
  （只有测试构造过 `Resolved`），所以 `Unknown` 分支还用了**另一个** 4096 常量作为 `fallbackDefaultPx`。
- 后果：6000×9000 页只有在 fit LOD（sampleSize 4 → 1500×2250）时才能是单张位图；**任何中等缩放**
  （1.5× 需要 sampleSize 2 → 3000×4500，高度 4500 > 4096）都被强制推入瓦片路径，而瓦片路径每帧要画
  几十张纹理 → 单帧 85–139MB 上传。这与 Telephoto"让每张解码位图不超过约一个视口"的做法正好相反。

修复（两处，单一真相源）：
1. `TilePolicy.DEFAULT_SAFETY_DIMENSION_LIMIT_PX` 4096 → **8192**（API 26+ 设备 8K 纹理是普遍能力；
   ADR Phase 1D 实测硬上限为 16384）；
2. `DecodePlanner` 把 `tilePolicy.safetyDimensionLimitPx` **同时**作为 `safetyLimitPx` 与 `fallbackDefaultPx`
   传入 `effectiveLimits`，避免"未解析能力"静默使用另一个更保守的值。

真机实测（同夹具、逐次 force-stop + drop_caches）：

| 旅程 | CPU P99 修复前 | CPU P99 修复后 | 绘制瓦片 | 解码请求 | RssAnon Max |
| :--- | ---: | ---: | ---: | ---: | ---: |
| fit_height 1× | 394 ms | **8.0 ms** | 0 | 0 | 434 MB |
| 1.5× | 452 ms | **7.96 ms** | 0 | 0 | 448 MB |
| 2.0× | 359 ms | **12.1 ms** | 43 | 282 | 649 MB |
| 2.5× | 453 ms | **9.77 ms** | 41 | 273 | 632 MB |
| 1× 普通页（对照） | 7.75 ms | 7.75 ms | 0 | 0 | 248 MB |

**判读**：
- **1× 与 1.5× 已彻底修复**：完全回到单张采样位图（零瓦片、零解码请求），P99 ≈ 8ms、overrun 为负。
- **2.0×/2.5× 好了一个数量级但仍未完美**：P99 9.8–12.1ms，且 overrun 仍为正（+26.7ms / +52.9ms），
  即偶发错过 120Hz 截止期。这两档仍走瓦片路径（level 0 = 216MB 超出 64MB 预算，分块本身是正确的），
  剩下的优化空间正是上面第 1 条（Telephoto 式瓦片几何）与第 3 条（可见集合驻留）。
- 因此**放大场景不再是"不可用"**：常用倍率（≤1.5×）已达标，2× 以上为可用但有偶发掉帧，
  按 Telephoto 对照清单继续收敛即可。

**能力上限改为实测（同日补充）**：把上限提到 8192 之后暴露了一个新风险 —— 在一台真实画布上限更低的
设备上，planner 会乐观地假设 8192。为此 paged 宿主在首帧绘制时从**正在绘制的 canvas** 读
`maximumBitmapWidth/Height` 并回传给管线（`KototoroImagePipelineAdapter.setRendererCapabilities`），
planner 用真实值参与 `effectiveLimits` 的夹取（策略上限仍为硬顶）。真机复测：1.5× 仍为
**7.48 ms、零瓦片**，说明该设备实测上限 ≥ 4500，新上限在本机成立；2.5× 13.2 ms
（overrun 抖动，属该档已知的瓦片路径抖动）。
**遗留**：webtoon 与横向宿主也应同样回报能力（当前只有 paged 宿主做了），已列入清单。

### Telephoto 瓦片几何实验：负结果与回滚（2026-09-19）

按上文对照清单第 1 条实现了 Telephoto 的瓦片尺寸规则
（`tileDimension = imageSize × sampleSize / baseSampleSize`，地板取"图像折半至两边 ≤ 视口"，
落在 `DecodePlanner.resolveTileDimension`），JVM 单测（TDD，先红后绿）与真机单点验证都符合预期：

- 真机证据（warsaw / 6000×9000 夹具 / vp=1280×2772 / 采集瞬间相机 scale=3.609）：
  重规划得到 `tile=1500×2250 grid=4×4 sample=1`；同一可视带 `IntRect(1727,2700,3390,6300)`（1663×3601）
  的请求由 **15 specs 降到 4 specs**（旧 1024 格为 3 列 × 5 行）。

但**整轮真机基准证明这个方向是错的**（同夹具、同旅程、`CompilationMode.Full`、5 次迭代）：

| 旅程 | 指标 | 基线（1024 格） | Telephoto 格（1500×2250） | 判读 |
| :--- | :--- | ---: | ---: | :--- |
| 2.0× | 绘制瓦片/层（最坏帧） | 36–43 | **12** | 少 3.4× |
| 2.0× | 解码请求 | 270–402 | **190–239** | 少 1.5× |
| 2.0× | 绘制位图/层 | 102–127 MB | **201 MB** | 变差 |
| 2.0× | 常驻瓦片 | 37–45 块 / 279–293 MB | 19–21 块 / **391–424 MB** | 变差 |
| 2.0× | memoryGpu | 385–418 MB | **554–584 MB** | 变差 |
| 2.0× | CPU P99 / overrun P99 | **12.1 ms** / +26.7 ms | 29.8 ms / +30.2 ms | 变差 |
| 2.5× | 绘制瓦片/层 | 39–45 | **8–12** | 少 4× |
| 2.5× | 绘制位图/层 | 94–149 MB | 137–201 MB | 变差 |
| 2.5× | CPU P99 / overrun P99 | 9.77 ms / +52.9 ms | 31.2 ms / +39.5 ms | 变差 |
| 1×、1.5× | 全部瓦片指标 | 0 | 0 | 不变（走单张采样位图） |

**机制**：帧线程的纹理上传尖峰随**单块位图大小**增长，而不是随块数增长。1024² 时一块 4MB，
1500×2250（外加缝合 gutter 约 1700×2450）一块约 16.8MB；块数少了 3–4 倍，但一帧要连续上传十几块
16.8MB 的纹理，P99 因此翻倍，常驻与 GPU 随之上浮。**在这个场景里纹理要"小"，不是"少"。**

另一个更重要的读数：两种格子的**绘制总面积几乎相同**（基线 43 块 × 1M px ≈ 45M px；
实验 12 块 × 3.4M px ≈ 41M px）。屏幕只有 1280×2772 = 3.55M px，而 45M px 恰好等于
`3.55M ÷ 0.31²`——**0.31 px/图像 px 正是"页面按 fit 高度显示"时的绘制密度**。
也就是说最坏那一帧是「页面以 fit 比例显示（0.31），却仍从 level 0（1 px/图像 px）取瓦片」：
线性过采样 3.2×、面积过采样 10×。同一算术也解释了对照读数：手动验证时相机在 3.609×
（页面对屏幕 1.11 px/图像 px），同样 3.55M px 的屏幕只需 `3.55M ÷ 1.11² ≈ 2.9M px` 的 level-0 数据
≈ 4 块（实测 `specs=4 drawn=4` ✓）。**所以瓶颈是"层级"，既不是格子形状，也不是屏外裁剪**：
按正确层级（sampleSize 4，整页 1500×2250 ≈ 3.4M px ≈ 一屏）画一帧只需 1 块 13.5MB，
比最坏帧的 160–200MB 少 12–15 倍。

**结论与动作**：

1. 该改动**已回滚**：`resolveTileDimension` 恢复"固定 1024 方形格 / 全宽条带"两种几何，
   `coarsenOverDetailedTiles` 恢复沿用采集期 lattice。回滚后在设备上行为与基线一致。
2. 唯一保留的修正：`estimatedTileBytes` 改为按**解码尺寸**（`tileDimension / sampleSize`）计费，
   而不是逻辑覆盖面积——粗层条带瓦片覆盖 2048×512 却只解码 1024×256，旧算法会把它记成 4 倍。
   它只喂给 `estimatedResidentCostBytes`，而该字段全仓无消费者，属纯记账修正、零行为影响。
3. 回归守卫：`DecodePlannerTest` 用两个用例钉住"2D 页格子保持固定边长"与"按解码尺寸计费"，
   注释里写明本次实测数字，避免以后有人再按算术把格子放大。
4. **下一步落点改为 Telephoto 对照清单第 1、2 条（瓦片阶梯）**：预生成"fit 层 + 相机层"两个 grid，
   绘制与请求都按**该页当前的绘制密度**（`绘制屏幕宽 / 页逻辑宽`）选层，而不是按全局相机 scale。
   预期把"页面按 fit 显示"的那些帧从 160–200MB 压到 ~13.5MB（12–15×），这正是 2×/2.5× 档
   残余 overrun 的来源。**不要再走"改格子几何"或"缩格子"这两条路**：前者的实测结论已如上，
   后者在面积不变时只会把同样的上传量切得更碎。

**过程教训**：第一次跑基准得到的结论是"毫无变化"，原因是
`:macrobenchmark:connectedDebugAndroidTest` 安装的 `app-arm64-v8a-benchmark.apk`
（`applicationId` 无 `.debug` 后缀、宿主 Activity 在 `app/src/benchmark` 源集）**是上一轮的旧包**，
整轮 18 分钟测的是改动前的代码。判定方法已写入 §5。

### 瓦片阶梯：base = fit 层、target = 相机层（2026-09-19）

上文的读数（最坏帧 43 块 ≈ 45M px ≈ 屏幕 3.55M px ÷ 0.31²）指向的不是格子几何而是**层级**：
那一帧的页面按 fit 比例显示（0.31 px/图像 px），却仍然从 level 0 取图。修复就是 Telephoto 对照清单
第 1、2 条，且**不做每帧重建**：

- **规则**（新纯函数 `reader/image/TileLadder.kt`）：`fit = resolveLod(页宽, 视口宽, cameraScale = 1)`，
  `camera = resolveLod(..., cameraScale)`，然后 `base = max(fit, camera)`、`target = camera`（仅当 `camera < fit`）。
  即 **base 是"这一页按 fit 显示时需要的层级"**（翻页过程中/旁边的页看的就是它），**target 才是相机那一层**。
  迟滞只在"仍处于放大区"时生效：一开始把迟滞套在整条阶梯上，回到 fit 后 target 会粘住不放，
  `SceneParityRegressionTest` 当场抓到（`assertNull(zoomOutAsset.target)` 失败）；改成"不放大就用无迟滞的需求值"。
- **一次建好，之后只做建/换/弃**：`acquireAsset` 里同时建好 base 与 target 两个 grid；
  `onCameraSettled` 变成 `updateTileLadders(scale)`，只在层级真的变化时换 grid 并释放该层瓦片。
  旧的 `coarsenOverDetailedTiles`（每次沉降逐页重跑 `DecodePlanner`、重建 base grid）与
  `coarsenedBaseSampleSize` 一并删除——那条"每帧重规划"的路径正是上文第 11 条实验里 26k 次解码的来源。
- **请求与绘制侧不用改**：`requestTiles` 本来就同时请求 base 与 target 的可见带（base 补缝、target 清晰），
  `drawTiledPage` 本来就"先 base、再按 target 已驻留区域跳过 base"。
- **测试**：`TileLadderTest` 8 例（fit 无 target／放大加对应层／base 不随相机／缩到 fit 以下 base 变粗／
  边界迟滞／回到 fit 立即撤 target／webtoon 单层）；`KototoroImagePipelineAdapterTiledTest` 新增 1 例
  （放大状态下采集 → `base=4`、`target=1`；回到 fit → target 撤除）；删除 `TileBaseCoarseningPolicyTest`
  （其意图被阶梯规则覆盖）。全量 JVM 2751 例 0 失败。

真机（warsaw，6000×9000 夹具，vp=1280×2772，`CompilationMode.Full`，5 迭代，设备侧已核对 APK 含本次代码）：

| 旅程 | 指标 | 基线（单层 level-0 + 每次沉降重建） | 阶梯（base=fit / target=相机） |
| :--- | :--- | ---: | ---: |
| 2.0× | 绘制瓦片/层（最坏帧） | 36–43 | 16–37 |
| 2.0× | **绘制位图/层** | 102–172 MB | **98.3 MB（5 次迭代完全一致）** |
| 2.0× | 常驻瓦片 | 37–45 块 / 279–293 MB | 43–47 块 / **215–234 MB** |
| 2.0× | **解码请求** | 270–402 | **243–611**（首迭代 602 为冷启动，其后 242–297） |
| 2.0× | 驱逐次数 | 56–98 | 53–160 |
| 2.0× | memoryGpu | 385–418 MB | **372–387 MB** |
| 2.0× | RssAnon Max | 540–656 MB | 580–629 MB |
| 2.5× | 绘制瓦片/层 | 39–45 | **15–17** |
| 2.5× | 绘制位图/层 | 94–149 MB | **98.3 MB** |
| 2.5× | 常驻瓦片 | 41–46 块 / 250–334 MB | 36–41 块 / **204–216 MB** |
| 2.5× | 解码请求 | 284–465 | **230–256** |
| 2.5× | memoryGpu | 379–419 MB | **376–389 MB** |
| 1×、1.5×、fit 大页 | 全部瓦片指标 | 0 | 0（不变，仍走单张采样位图） |

**读法**：最坏帧的绘制量由「页面按 fit 显示却取 level 0」的那些帧（≈172MB）**转移到了真正放大的那些帧**
（target 层 15 块 × 6.55MB = 98.3MB）；而 fit 显示的帧从 172MB 降到 base 层的 ~15 块 × 1MB ≈ 15MB（约 11×）。
常驻字节、GPU、解码请求三项同时下降或持平，说明这次不是"把成本搬了个地方"，而是净减。
`TileDecodeRequests` **同时包含两层的请求**，因此与单层基线同量级即代表没有抖动（见下）。

**过程中的一次真回归与其修复**：首次接入阶梯后解码请求从基线 282 暴涨到 **10,304**（常驻卡在 16 块）。
根因不在阶梯本身，而在 `ReaderTileManager.demoteAbsentLatticeTiles`：它只按 `pageId` 过滤 LATTICE
瓦片与在飞任务，不看 `sampleSize`，于是"先请求 base 再请求 target"时，每次请求都会把另一层刚请求的瓦片
降级（VISIBLE→STANDBY→CACHE）并 cancel 其在飞解码，下一帧再重建 —— 永久抖动。
修复：把降级作用域按 `grid.sampleSize` 收窄；回归守卫 `ReaderTileManagerTest`
`requesting one layer does not demote the other layer's tiles`（先红后绿）。修复后解码请求回到 243–611。

**帧时序口径（同日补齐）**：上表之外的 `FrameTimingMetric` 一度整批缺失，根因是设备侧
`/data/misc/perfetto-traces/trace_output.pb` 属主为 `root:root 0600`，而 Gradle 驱动的 instrumentation
以 app 身份 `stat` 不到它（报 `Cannot check size of ...`，内层 `NumberFormatException: For input string: ""`），
于是**所有 trace 派生指标静默消失、内存类指标照常上报**（webtoon 旅程同样缺失，确认与分页路径无关）。
以 root 直接跑 instrumentation 后指标恢复，四档全部补齐：

| 旅程 | 基线 CPU P99 / overrun P99 | 阶梯 CPU P99 / overrun P99 | 判读 |
| :--- | ---: | ---: | :--- |
| fit 大页 1× | 8.0 ms / 负 | **7.9 ms / −4.6 ms** | 持平 |
| 1.5× | 7.96 ms / 负 | **7.9 ms / −4.5 ms** | 持平 |
| 2.0× | 12.1 ms / **+26.7 ms** | **8.2 ms / −0.8 ms** | **不再错过 120Hz 截止期** |
| 2.5× | 9.77 ms / **+52.9 ms** | 9.3 ms / **+8.1 ms** | 残余仅剩 +8.1ms（改善 6.5×） |

同时刻的计数中位数与 Gradle 驱动轮次一致（解码请求中位数 241–252、常驻 209–246MB），
说明两种驱动方式测得的是同一件事。**结论：2.0× 及以下的 overrun 已转为负余量，2.5× 从 +52.9ms 收到 +8.1ms**，
即"1×/1.5× 达标、2× 达标、2.5× 可用"。

### 缝合 padding 与查询光环分离 + 按工作集设驻留上限（2026-09-19）

阶梯之后剩下的成本在"每块位图有多大"。`TileGrid` 原本让 `outputGutterPx`（128 屏幕 px 的**查询光环**）
兼任**解码 padding**（`128 × sampleSize` 编码像素），于是每块都按 level 0 的余量解码：
level 0 一块 1024 内容解成 1280（56% 是 padding），level 2 一块 256 内容解成 512（**75% 是 padding**）。
缝合只需要几个解码像素，两者是不同的事，拆开：

- `outputGutterPx` 只用于 `tilesIntersecting`（拉相邻瓦片保持温热）；
- 新增 `seamPaddingPx = 8`（**解码**像素/边），`decodeRegion` 用它 × `sampleSize` 展开 ⇒ **任何层级的解码余量都是常数 8 像素**。

拆分后暴露出第二个问题：小瓦片让**按字节设上限**的驻留账本"削得更碎"——上限是 planner 的单图预算
（64MB），而钉住的可见工作集有 207MB，于是账本不断把光环瓦片驱逐、下一帧又重建：实测 2.0× 下
**驱逐 50 → 140、解码请求 239 → 624**。把 `TileMemoryBudget` 的默认上限改为**按工作集定的 256MB**
（与 planner 的 64MB 单图预算解耦）后驱逐直接归零。

真机（warsaw，6000×9000，`CompilationMode.Full`，5 迭代，**干净设备**，详见下一条）：

| 2.0× | 基线（单层 level-0） | 阶梯（padding 128 / cap 64MB） | 拆分 + cap 256MB |
| :--- | ---: | ---: | ---: |
| CPU P99 | 12.1 ms | 8.2 ms | **7.5 ms** |
| overrun P99 | +26.7 ms | −0.8 ms | +4.6 ms |
| 绘制位图/层 | 102–172 MB | 98.3 MB | **64.9 MB** |
| 解码请求 | 270–402 | 239 | **239** |
| 驱逐次数 | 56–98 | 50 | **0** |
| memoryGpu | 385–418 MB | 372–387 MB | **325 MB** |
| RssAnon Last | 540–656 MB | 580–629 MB | **402 MB** |
| 2.5× CPU P99 / overrun | 9.77 ms / +52.9 ms | 9.3 ms / +8.1 ms | **7.8 ms / +13.6 ms** |
| 2.5× memoryGpu / RssAnon | 379–419 / 612–684 MB | 376–389 / 511–608 MB | **317 / 403 MB** |

**判读**：绘制字节 −34%、GPU −14%、RSS −32%、驱逐归零、CPU P99 为三档最好（7.5/7.8ms）；
overrun 尾部在两次测量间有 ±5–13ms 的漂移（基线为 +26.7/+52.9），需要同状态背靠背复测才能归因，
已列入待办。**保留该改动**：内存与解码工作量的收益是单向的、且 overrun 仍显著优于基线。

### 同状态 A/B/A 归因：真正的尾部元凶是"已驻留瓦片被重复解码"（2026-09-19）

为了在同一设备状态下比较 padding 128 与 8，加了一个**临时运行时开关**（instrumentation 参数
`-e seamPadding N` → intent extra → `TileGrid` 覆盖值），同一构建内交错跑 A8 → B128 → A8 → B128：

| 2.5×（未修 re-decode 前） | pad=8 | pad=128 | pad=8 | pad=128 |
| :--- | ---: | ---: | ---: | ---: |
| overrun P99 | +114.2 ms | +5.4 ms | +113.4 ms | +5.3 ms |
| 解码请求 | 596 | 238 | 582 | 239 |

可复现且方向明确：**小 padding 反而差 100ms**。定位到 `ReaderTileManager.launchDecode` 只在
`tileJobs` 里查"是否已在飞"，**不查"是否已驻留"** —— 而任务完成后会从 `tileJobs` 移除，于是下一帧
同一块瓦片被重新解码一次。解码越快（padding 越小）这个守卫失效得越快，冗余解码就越多，
尾部因此被拖长。这与上一节"驱逐 50 → 140"是两件不同的事：即使驱逐为 0，重复解码依然存在。

修复：`launchDecode` 增加 `if (budget.contains(spec.key)) return`；回归守卫
`ReaderTileManagerTest` 「a resident tile is not decoded again by a repeated request」（先红后绿）。
修完后同一套交错序列（**同一会话同一构建**）：

| 2.5×（修复后） | pad=8 | pad=128 | pad=8 |
| :--- | ---: | ---: | ---: |
| CPU P99 | 8.8 ms | 9.9 ms | **8.6 ms** |
| overrun P99 | **+6.5 ms** | +15.1 ms | **+7.8 ms** |
| 绘制位图/层 | **64.9 MB** | 98.3 MB | **64.9 MB** |
| 解码请求 | 392 | 401 | 405 |

即：守卫修好后 padding 8 **两项都更好**，尾部差异不再是 padding 造成的；`seamPaddingPx = 8` 保留。

### 最终构建的四档实测（2026-09-19，干净设备，root 直跑，5 迭代）

| 旅程 | CPU P99 | overrun P99 | 绘制位图/层 | 解码请求 | 驱逐 | memoryGpu | RssAnon Last |
| :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| fit 大页 1× | 8.1 ms | −4.4 ms | 0 | 0 | 0 | 109 MB | 262 MB |
| 1.5× | 8.4 ms | −3.5 ms | 0 | 0 | 0 | 202 MB | 404 MB |
| **2.0×** | **9.4 ms** | **+9.6 ms** | **64.9 MB** | 392 | **0** | **312 MB** | **447 MB** |
| **2.5×** | **8.6 ms** | **+8.1 ms** | **64.9 MB** | 401 | **0** | **312 MB** | **446 MB** |

对照基线（本轮工作开始前）：2.0× CPU P99 12.1ms / overrun +26.7ms / 绘制 102–172MB / GPU 385–418MB / RSS 540–656MB；
2.5× 9.77ms / +52.9ms / 94–149MB / 379–419MB / 612–684MB。⇒ **CPU P99 −22%/−12%、overrun −64%/−85%、
绘制字节 −37%…−62%、GPU −19%/−17%、RSS −17%…−35%**，1×/1.5× 保持零瓦片与负余量不变。

**遗留**：2.0×/2.5× 的 overrun 仍为正（+8…+10ms，即偶尔错过 120Hz 截止期），本机同一配置的
run-to-run 漂移为几毫秒且跨设备状态不可比；继续压这一档需要新的假设（可见集合驻留、或按绘制密度选层的进一步细化），
不在本轮范围。

### 整体回归（2026-09-19，本轮瓦片工作收口）

| 层次 | 命令 | 结果 |
| :--- | :--- | :--- |
| JVM 单测 | `./gradlew :app:testDebugUnitTest` | **2755 例 0 失败** |
| 设备 instrumented | `./gradlew :app:connectedDebugAndroidTest` | 221 例 / **12 失败**，逐条核对后**全部与阅读器无关**：`MangaDatabaseTest`(4，Room 迁移)、`AppShortcutManagerTest`(1)、`AppBackupAgentTest`(1)、`DirectoryConsistencyPropertyTest`(1)、3 个集成用例与 2 个 novel 用例的 `initializationError`（JUnit runner 初始化） |
| 设备 instrumented（阅读器） | 同上 | `ScenePagedGestureTest` 11 例中 10 例通过；唯一失败 `originalSizeCanPanVerticallyWithoutUserZoom` 已证**基线同样失败**（见下） |
| 真机冒烟：webtoon 连续滚动 | `sustainedSceneFull` | OK；CPU P99 4.5ms、overrun P99 −8.1ms、RSS 236MB（条带瓦片路径无回归） |
| 真机冒烟：双页 | `pagedDoublePageSceneFull` | OK；CPU P99 8.4ms、overrun P99 −4.3ms、0 瓦片、RSS 235MB（split 路径无回归） |
| 真机四档分页 | 见上两节表格 | 见上 |

**`originalSizeCanPanVerticallyWithoutUserZoom` 是既有失败（有对照证据）**：把本轮全部改动
`git stash push -u` 后在**基线树**上跑同一条用例，结果同样 FAILED，断言一致
（`Native image must fill exactly 240 physical pixels expected:<RED> but was:<BLACK>`，
即 240×6000 夹具在 `KEEP_START` 1:1 下第 240 列不是图像内容）。因此它不是本轮引入的回归；
它同时说明"高图在 ORIGINAL/KEEP_START 下的 1:1 几何"存在既有缺陷（另开任务跟踪，不混进瓦片工作）。
注意该用例的夹具走**单张位图**路径（240×6000 在预算与纹理上限内），与瓦片管线无关。

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
4. **跑基准前必须确认"设备上装的就是这一版"**：macrobenchmark 模块是 `self-instrumenting`
   （`macrobenchmark/build.gradle`），`targetProjectPath = ':app'` 只提供**构建**目标，跑
   `:macrobenchmark:connectedDebugAndroidTest` **不会重新安装 target app** —— 它启动的是设备上
   已经装好的 `org.skepsun.kototoro`。实测代价：两轮各 19 分钟的基准测的都是遗留的旧包，
   而且第二次的指标与上一轮**逐字节相同**（`DrawnTileBytesPerLayer_Max = 200,830,080`）才暴露出来。
   规程（缺一不可）：
   1. `./gradlew :app:assembleBenchmark` 显式构建 benchmark 变体
      （产物 `app/build/outputs/apk/benchmark/app-arm64-v8a-benchmark.apk`，`applicationId` 无 `.debug` 后缀、
      `ReaderProductionBenchmarkActivity` 在 `app/src/benchmark` 源集里）；
   2. `adb install -r` 该 APK；
   3. **从设备拉回已安装的包再验**：`pm path org.skepsun.kototoro` → `su -c cp` 到 `/data/local/tmp` → `adb pull` →
      在所有 `classes*.dex` 里搜本次新增的标识字符串（注意是**多 dex**，只查 `classes.dex` 会误判为 0）；
   4. 才跑基准。手动快验（比整轮基准快一个数量级）：`am force-stop org.skepsun.kototoro` →
      `am start -n org.skepsun.kototoro/.reader.benchmark.ReaderProductionBenchmarkActivity --es backend scene_paged
      --es fixture_mode paged_large --es zoom_mode fit_height --ef default_scale 2.5` → 读 logcat。
      注意：**静态页不会重绘**，必须 swipe 触发一次或等瓦片到达，否则只看到第一帧。
   5. 也可以直接 `./gradlew :app:installBenchmark`（AGP 会同时装 `.dm` 基线剖面，比裸 `adb install -r` 更完整）。
5. **trace 文件属主会让"帧时序指标"整批静默消失，用 root 跑 instrument 即可绕过**：若
   `/data/misc/perfetto-traces/trace_output.pb` 是 `root:root 0600`，而以 app 身份运行的 instrumentation
   `stat` 不到它，harness 直接报 `IllegalStateException: Cannot check size of ...`
   （内层 `NumberFormatException: For input string: ""`），于是 `frameDurationCpuMs` / `frameOverrunMs`
   **在所有旅程上一起消失**，而内存类与自定义计数指标照常上报 —— 极易被误读成"这版变快了/没变化"。
   判据：基准 JSON 的 metric 名单里有没有 `frameDurationCpuMs`。
   已试无效：`chown shell:shell` + `chmod 666`、设备重启、`:app:installBenchmark` 重装。
   **有效做法（root 设备）**：直接以 root 跑 instrumentation，使 harness 与 perfetto 同 uid：
   ```bash
   ./gradlew :app:assembleBenchmark :app:installBenchmark        # 目标 app（必须显式装，见第 4 条）
   adb install -r macrobenchmark/build/outputs/apk/debug/macrobenchmark-debug.apk
   adb shell su -c "am instrument -w -e class \
     org.skepsun.kototoro.macrobenchmark.ReaderProductionBenchmark#pagedLargeZoom2_0SceneFull \
     org.skepsun.kototoro.macrobenchmark/androidx.test.runner.AndroidJUnitRunner"
   ```
   输出里即含各指标与 `P50/P90/P95/P99`；单条旅程约 2 分钟，比 Gradle 轮次快得多。
   实测计数中位数与 Gradle 轮次一致（同一份帧数据来源是该 app 自身的 FrameTimeline），两种方式可互相印证。
6. **跑基准前先确认设备是"干净的"，否则会凭空造出 +100ms 尾延迟甚至超时**：本轮实测一次
   "padding 变小后 overrun 从 −0.8ms 涨到 +95/+106ms、2.5× 旅程 600s 超时"的假回归，
   真因是设备当时 `free` 只剩 300–690MB（15.1GB 中 14.4GB 被占）且残留了一个 **root 的 perfetto 进程**
   （`ps -A | grep perfetto`）—— 重启并确认残留进程消失后，同一构建测出 CPU P99 7.5/7.8ms、overrun +4.6/+13.6ms。
   规程：跑前 `adb shell free -m` 与 `adb shell "ps -A | grep -E 'perfetto|kototoro'"`，
   有残留就 `su -c kill -9`、必要时重启；**跨状态比较的 P99/overrun 不可信**，
   改动前后若要归因尾部，必须在同一设备状态下背靠背各测一次。

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
