# ADR 0002 — Decouple Reader Semantics, Image Resources, and Rendering Backends（阅读器语义、图像资源与渲染后端解耦）

- 状态：Accepted
- 日期：2026-09-16
- 实施状态：
  - WebGPU 分支隔离：已完成（`feat/webgpu-reader`，作为 `UPSTREAM-TRACKED` 资产）
  - Phase 0 性能与功耗基准：待开始（Pending）
  - PoC A (ReaderScene 几何抽象)：待开始（Pending）
  - PoC B (Webtoon 视口实验：Compose Scene vs. View Scene)：待开始（Pending）
- 关联分支：`feat/webgpu-reader`（WebGPU 成果隔离保存与上游追踪）、`devel`（基线主干）
- 核心准则：**ReaderCore owns semantics; ImagePipeline owns image policy; Renderer owns presentation.**（ReaderCore 掌管阅读语义；ImagePipeline 掌管图像策略；Renderer 掌管呈现）

---

## 一、 背景与动因

Kototoro 当前的漫画阅读器主要基于 Jetpack Compose 与 Telephoto（Zoomable/Subsampling）构建。近期在探索引入 Mihon 最新 WebGPU 渲染器（`ca.mpreg:webgpuviewer`）的过程中，团队深入触及了移动端漫画阅读器的深水区瓶颈与架构矛盾：

1. **WebGPU Continuous 的成熟化成本与上游状态**：
   - 上游 Mihon 的 High Quality Renderer（WebGPU）已覆盖分页与 Continuous 阅读模式，并提供更直接的 GPU 渲染路径与可编程着色器（3D 翻页动效、Catmull-Rom 缩放、LUT 色彩滤镜）。
   - 但截至 2026-09-16，其 Continuous viewer 仍存在惯性滚动微弱（Mihon #3780）与快速导航闪烁（Mihon #3779）等公开问题，且上游对超长条漫与 GPU 纹理管理的迭代仍非常频繁。
   - 因此，Kototoro 暂不承担在本地自行推进 Continuous WebGPU 成熟化的巨大成本，而是采取跟踪上游策略；在 Webtoon 场景下暂保持回退，待上游稳定后再行评估。
2. **移动端驱动与合成层兼容代价**：
   - 依赖 NDK/Dawn/Vulkan 的原生阅读视口在 Android 碎片化设备（Adreno/Mali 早期驱动、MIUI/HyperOS、Android 14 SurfaceControl 打洞与合成）上面临冷启动白屏、看门狗误判、生命周期回收等极其繁重的系统级兼容负担。
3. **通用 UI 列表虚拟化与二维漫画视口的抽象差异**：
   - Webtoon 本质是连续二维页面场景，而 `LazyColumn` 是通用的惰性列表抽象。虽然 `LazyColumn` 已对可见项进行虚拟化，并不会为整个章节建立完整 UI 树，但其 item composition、measurement、placement、prefetch 和逐项图片/缩放生命周期仍属于通用 UI 列表模型。
   - 对于只需要“视口与页面矩形求交 + 图像绘制”的漫画阅读场景，专用二维场景（2D Scene）存在进一步减少框架工作量、提高资源生命周期确定性的空间。
   - 巨幅图片（如 4000×20000 像素的条漫单页）若无视口自适应分级（LOD）与按需分块解码（Tiling），整张解码将造成数百兆显存突增、频繁 GC 卡顿甚至 OOM。
4. **后端与业务状态紧耦合**：
   - 既有实现中，页码进度、手势判定、页面排布与具体 UI 组件/渲染器深度绑定。若每引入一个新渲染后端（如 Compose、WebGPU、Canvas）都需要重新实现一套完整的状态与图片加载逻辑，系统维护成本将不可持续。

因此，亟需确立一套**高内聚、低耦合、渲染后端可插拔**的现代漫画阅读器核心架构。

---

## 二、 核心决策：三层主权模型（Three-Tier Sovereignty）

阅读器体系严格划分为三层，各层拥有独立主权，严禁越界：

```text
┌──────────────────────────────────────────────────────────────────┐
│                      Kototoro Reader UI                          │
│        (Compose Chrome: TopBar, BottomBar, Sheets, OCR UI)       │
└────────────────────────────────┬─────────────────────────────────┘
                                 │ 低频语义通知 (PageChanged, Zoom)
                                 ▼
┌──────────────────────────────────────────────────────────────────┐
│ 1. Reader Semantics 主权 (ReaderCore)                           │
│ ────────────────────────────────────────────────────────────     │
│   ReaderScene / ReaderLayout / ReaderViewport / ReaderCamera     │
│   ReaderProgressResolver / VisibleRegionResolver                 │
│                                                                  │
│   输出纯几何/状态帧: ReaderFrame(viewport, List<VisibleNode>)     │
└────────────────────────────────┬─────────────────────────────────┘
                                 │ 预测请求 (PrefetchRequest) & 视口帧
                                 ▼
┌──────────────────────────────────────────────────────────────────┐
│ 2. Image Resource 主权 (ImagePipeline)                          │
│ ────────────────────────────────────────────────────────────     │
│   DecodePlanner / LodPolicy / TileManager / ResourceStore        │
│   资源预算 (Memory/Disk Cache) & 解码调度                         │
│                                                                  │
│   输出多态资源: ReaderImageAsset (BitmapAsset | TileSet | Encoded) │
└────────────────────────────────┬─────────────────────────────────┘
                                 │ 针对各后端特化的呈现资产
                                 ▼
┌──────────────────────────────────────────────────────────────────┐
│ 3. Rendering 主权 (ReaderRenderer)                              │
│ ────────────────────────────────────────────────────────────     │
│   ┌──────────────────────────┬────────────────┬──────────────┐   │
│   │   ComposeSceneRenderer   │ ViewScene      │    WebGPU    │   │
│   │   [PRIMARY CANDIDATE]    │ [CONTROL]      │[UPSTREAM-TRK]│   │
│   │ DrawModifierNode/Graphics│ Custom View    │ Mihon 翻页/  │   │
│   │ Layer (跳过 Composition) │ onDraw(Canvas) │ 着色器特效   │   │
│   └──────────────────────────┴────────────────┴──────────────┘   │
└──────────────────────────────────────────────────────────────────┘
```

### 1. 第一层：Reader Semantics 主权 (`ReaderCore`)
- **职责**：维护二维漫画虚拟世界坐标系，负责页面几何排列、视口相交计算、阅读进度结算与运动预测。
- **输出**：纯几何与语义帧 `ReaderFrame`，告知下游“当前视口内应当在哪些矩形位置绘制哪些页面的哪些区域”。
- **绝对禁忌**：**绝不知晓图片文件的下载、解码、路径与失败状态**。

### 2. 第二层：Image Resource 主权 (`ImagePipeline`)
- **职责**：决定“如何准备像素”。根据视口物理尺寸、设备 RAM 等级、缩放倍率计算动态 LOD 采样率，管理分块切片（Tiling）、执行预取请求及资源调度队列与内存缓存。
- **输出**：多态呈现资产 `ReaderImageAsset`。
- **绝对禁忌**：不持有任何 Android UI 组件或阅读器章节/进度状态。

### 3. 第三层：Rendering 主权 (`ReaderRenderer`)
- **职责**：决定“如何把像素画在屏幕上”，掌管具体的视觉呈现。
- **战略重心更新**：
  - **首选主攻候选：`ComposeSceneRenderer`**。利用 Compose 现代高性能特性（`DrawModifierNode`、`GraphicsLayer` 保留显示列表、Draw phase 状态读取），将高频变换限制在 Draw 阶段，彻底跳过 Composition 与 Layout。
  - **对照参考候选：`AndroidViewSceneRenderer`**。作为标准单 View `Canvas.onDraw` 的性能基准对照组。
  - **决策决胜原则（Tie-Breaker）**：若 Compose Scene 与 View Scene 在关键帧指标与内存上无显著质差，**坚决优先采纳 Compose Scene**，以消除 `AndroidView` 互操作、生命周期桥接、手势穿透与合成层黑盒问题。
- **绝对禁忌**：**不得成为阅读进度、页面布局和章节语义的 Source of Truth**。

---

## 三、 关键架构约束

为确保长期演进不发生设计漂移，确立以下 4 项刚性约束：

### 约束 1：ReaderCore 绝不知晓图片加载状态（几何稳定性与锚定修正）
- `ReaderCore` 仅消费 `PageGeometryHint` 构建连续虚拟几何体，不感知几何尺寸来自于网络 Header、本地元数据、历史缓存还是算法估算：
  ```kotlin
  sealed interface PageGeometryHint {
      data class Exact(val width: Int, val height: Int) : PageGeometryHint
      data class AspectRatio(val ratio: Float) : PageGeometryHint
      data class Estimated(val ratio: Float) : PageGeometryHint
  }
  ```
- 图片下载中、等待解码或解码失败，Scene 几何排版骨架完全独立；若资源不可用，Renderer 仅负责在对应几何区域绘制占位块。
- **锚定修正（Anchored Correction）**：当估算尺寸（`Estimated`）被精确尺寸（`Exact`）修正引起总高度变化时，通过 Viewport Anchor Compensation（保持当前主要可见页面及其内部相对偏移比例不变）避免用户可感知的位置跳动。

### 约束 2：ImagePipeline 职责细分，运动遥测与 ARR 协作
- 严禁将 `ImagePipeline` 做成巨型上帝对象。内部明确拆分为：
  - `ImageSource`：原始字节流与资源句柄；
  - `DecodePlanner`：按像素用途（`PixelUsage: DISPLAY_ONLY | CPU_READ_REQUIRED | REGION_TILE`）精细化解码规格；
  - `ResourceStore`：缓存生命周期；
  - `DecodeExecutor`：执行解码产出目标资产。
- **运动遥测（ViewportMotion）的双重用途**：
  ```kotlin
  data class ViewportMotion(
      val velocityX: Float,
      val velocityY: Float,
      val isDragging: Boolean,
      val timestampNanos: Long,
  )
  ```
  数据流分支为：
  1. `ViewportMotion -> ReaderCore/Prediction -> PrefetchRequest -> ImagePipeline`（驱动预测预加载）；
  2. `ViewportMotion -> PlatformPresentationAdapter`（协作 Android 15/16 自适应刷新率 ARR：Compose 映射至 `preferredFrameRate`，View 映射至 `frameContentVelocity`），在高速滑动时拉升 120Hz，静止时降频降功耗。

### 约束 3：Renderer 拥有“呈现状态”，而非“语义状态”
- Renderer 允许且应当拥有后端特有的绘制与保留资源状态（如 Compose 的 `GraphicsLayer`、`DrawModifierNode`，Canvas 的 `Paint`、`Matrix`，WebGPU 的 `TextureCache`）。
- 高频 Camera 变换（位移/缩放）严格约束在 Draw Phase（或 `GraphicsLayer.matrix`）消费，严禁逆向触发父级重组或布局。
- 严禁 Renderer 内部私自维护 `currentChapter`、`currentPageProgress`、`isDoublePage` 等阅读语义字段。

### 约束 4：多态呈现资产（句柄化），拒绝为统一而二次拷贝
- `ReaderImageAsset` 采用密封接口（Sealed Interface），描述资源的**访问句柄**而非强行具象化到堆内存：
  ```kotlin
  sealed interface ReaderImageAsset {
      data class EncodedAsset(
          val source: ImageSourceHandle, // FileSource, BufferSource, StreamSource
          val metadata: ImageMetadata,
      ) : ReaderImageAsset

      data class BitmapAsset(
          val bitmap: Bitmap,
          val level: Int,
          val sourceRegion: IntRect?,
      ) : ReaderImageAsset

      data class TileSet(
          val overview: Bitmap?,
          val tiles: TileProvider,
      ) : ReaderImageAsset
  }
  ```
- 各 Renderer 可向 Pipeline 声明其 `preferredRepresentation`（如 WebGPU 声明 `ENCODED`，Canvas/Compose 声明 `BITMAP` 或 `TILE_SET`）。严禁将 EncodedAsset 设计为单纯的 `ByteArray`，以避免大图在 Java Heap 与 JNI 之间发生数十兆的冗余拷贝。

---

## 四、 代码组织规划（Package 先行，暂缓拆 Module）

初期避免 Gradle 模块爆炸，直接在 `app/src/main/kotlin/org/skepsun/kototoro/reader/` 下建立清晰的目录隔离：

```text
reader/
├── core/                  // 纯 Kotlin，零 Android 依赖（禁用 android.graphics.*、androidx.compose.* 等几何与 UI 类）
│   ├── FloatRect.kt       // 跨平台自包含几何基础类型
│   ├── IntSize.kt
│   ├── PageId.kt
│   ├── PageGeometryHint.kt
│   ├── ReaderViewport.kt
│   ├── ReaderScene.kt
│   ├── VisibleNode.kt
│   ├── ReaderFrame.kt
│   ├── ViewportMotion.kt
│   ├── VisibleRegionResolver.kt
│   └── ReaderProgressResolver.kt
│
├── image/                 // 图像资源决策与流水线
│   ├── ReaderImageAsset.kt
│   ├── ReaderImagePipeline.kt
│   ├── ReaderLodPolicy.kt
│   ├── ReaderTileManager.kt
│   ├── PixelUsage.kt
│   └── DecodePlanner.kt
│
├── render/                // 可插拔渲染器实现
│   ├── ReaderRenderer.kt
│   ├── compose/           // Telephoto 现有实现 + 新 ComposeSceneRenderer (DrawModifierNode/GraphicsLayer)
│   ├── canvas/            // 对照组 AndroidViewSceneRenderer (View onDraw)
│   └── webgpu/            // 隔离的 WebGPU 适配器
│
└── ui/                    // Activity, ViewModel, Chrome, Menu, Settings
```

> **测试原则**：`core/` 下的几何相交计算（`VisibleRegionResolver`）、锚定修正（`AnchoredCorrection`）、页面排布与进度结算必须具备高分支覆盖度与边界值单元测试，不追求全包盲目的 100% 形式主义覆盖率。

---

## 五、 验证路线图与评判准则

### 阶段规划

1. **分支隔离（已完成）**：
   - 将现有 13 个 WebGPU 提交完整封存在 `feat/webgpu-reader` 分支，状态标记为 `UPSTREAM-TRACKED`。
   - `devel` 恢复纯净，消除 NDK 依赖与冷启动监控对主干的干扰。
2. **Phase 0：多维基准建立（Benchmark Baseline）**：
   - 升级至 AndroidX Benchmark 1.5+，在 ARR / 120Hz 测试机上运行固定数据集（100 页普通、100 页 Webtoon、极端超长图）；
   - **执行两套模式**：
     - *Primary Mode*：`CompilationMode.Partial(BaselineProfileMode.Require)`，拟合真实用户环境；
     - *Diagnostic Mode*：`CompilationMode.Full`，消除 JIT 噪音，观察纯渲染器理论差距；
   - **涵盖突发与持续测试**：
     - *Burst Benchmark*（10~20s 快速连续 Fling）：考察 P99 逾期、GC 尖峰、纹理上传 Stall；
     - *Sustained Benchmark*（5~10min 滚动）：记录 GPU 显存增长、功耗趋势（PowerMetric）与热节流。
3. **PoC A：Scene 几何抽象**：
   - 实现纯几何的 `ReaderScene`，让现有阅读器与新 Scene 并行计算，验证页面几何、可见集合及阅读语义与现有行为等价（浮点几何允许定义明确的 epsilon 容差；若发现旧实现缺陷，以显式行为变更记录处理而非机械迁就旧 bug）。
4. **PoC B：Webtoon 视口实验（战略 A/B 对照）**：
   - 保持功能极简（仅垂直滚动、无缩放、无 OCR、固定图集），构建两个极小渲染器：
     - **Candidate A（首选主力）**：`ComposeSceneRenderer`
       - 测试 Immediate（`DrawModifierNode` / `drawBehind`）与 Retained（单页独立 `GraphicsLayer`）两种机制；
     - **Candidate B（参考对照）**：`AndroidViewSceneRenderer`（单 View `onDraw(Canvas)`）；
   - 归因分析：验证性能红利是否本质源自“脱离通用 LazyColumn 抽象与建立专属 2D Scene”，而非“必须退回 Android View”。

### 评估指标与 Go/No-Go 准则

评判标准**相对于 Phase 0 建立的 Baseline 定义**，统一纳入刷新率意图（`preferredFrameRate` 协同）：

| 评估维度 | 核心主指标 (Primary) | 辅助指标 (Secondary) | 达到标准（GO） | 放弃或退回（NO-GO） |
| :--- | :--- | :--- | :--- | :--- |
| **帧稳定性** | `frameOverrunMs` P95/P99 | `frameDurationCpuMs` P95/P99 | 尾部逾期时间（P99 Overrun）显著缩短，消除极端掉帧尖峰 | Overrun 无明显改善，依然频发错过 Hardware Deadline |
| **卡顿率** | Deadline Miss Rate | Worst-frame Trace | 严重丢帧率明显下降 | 掉帧率持平或恶化 |
| **Java 内存** | Heap Max | Allocations / GC Count | Fling 期间垃圾回收暂停次数与瞬时分配量趋近于零 | 内存抖动未减，GC 依然打断渲染管线 |
| **Native 内存** | RSS Anon Max | RSS Shmem | 内存峰值有界平稳 | 存在非托管内存堆积或泄漏风险 |
| **GPU 显存** | `memoryGpuKb` Max | Texture Upload Trace | 纹理显存平稳有界，无主线程 Stall | 突发显存占用过大导致 OOM |
| **功耗与持续性** | Energy / Battery Drain | Thermal / Headroom Drift | 长时间滚动能耗平稳，无剧烈热降频 | 持续功耗异常飙升，迅速触发系统温控降频 |
| **系统复杂度** | 代码增量 (LOC) / 异常率 | OEM-specific Workarounds | 逻辑清晰，无特定厂商驱动黑洞与穿孔 Bug | 代码量膨胀严重，引入新的系统级黑盒缺陷 |

**Go/No-Go 判定**：
- 优先要求在 P99 `frameOverrunMs`、GC/Allocation 次数或内存峰值中**至少一项出现显著改善**，且其他关键指标不存在明显回归；
- 若 `ComposeSceneRenderer` 表现与 `AndroidViewSceneRenderer` 相当，**直接采纳 Compose 方案**，终止 View 方案的进一步扩张。

---

## 六、 Non-Goals（第一阶段明确不做的事项）

为了控制工程范围，第一阶段严格禁止发散：
1. **不**重写现有阅读器的全部交互与手势；
2. **不**重写物理惯性引擎（`Physics`，通过 `ViewportMotion` 承接）；
3. **不**在第一阶段直接开发完整的 Tile 动态切片与 LOD 高级引擎；
4. **不**在第一阶段废弃或替换现有的 Telephoto 单页/双页阅读模式；
5. **不**自行实现或重构 WebGPU 的 Continuous 条漫模式（交由上游 Mihon 推进）；
6. **不**将 `LowLatencyCanvasView`（前台双缓冲/画笔向）纳入 Webtoon 实验。

---

## 七、 架构不变量（Architectural Invariants）

未来所有与阅读器相关的代码提交与代码审查（PR Review），必须严格核对是否违背以下 6 条不变量：

- **I1**: `ReaderCore` MUST NOT depend on Android UI toolkit or renderer APIs, nor Android platform geometry types (`android.graphics.Rect/RectF/Matrix`, `androidx.compose.ui.geometry.*`).（ReaderCore 绝不得依赖 Android UI 工具包、渲染器 API 或 Android 平台几何类，必须使用自包含的跨平台几何抽象）
- **I2**: `ImagePipeline` MUST NOT own chapter progress, reading direction, spread semantics, or viewport UI state.（ImagePipeline 绝不得持有章节进度、阅读方向、双页语义或视口 UI 状态）
- **I3**: `Renderer` MUST NOT be the source of truth for reader semantics.（Renderer 绝不得成为阅读语义的真相之源）
- **I4**: Renderer-specific resource representations ARE allowed.（允许 Renderer 拥有特化的资源表征形态）
- **I5**: Scene geometry MUST remain computable when image pixels are unavailable.（在图片像素不可用时，Scene 几何结构必须依然保持可计算）
- **I6**: Backend replacement MUST NOT require rewriting progress/layout semantics.（替换渲染后端绝不得要求重写进度或布局语义）
