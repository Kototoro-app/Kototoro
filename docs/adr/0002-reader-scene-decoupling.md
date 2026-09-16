# ADR 0002 — Decouple Reader Semantics, Image Resources, and Rendering Backends（阅读器语义、图像资源与渲染后端解耦）

- 状态：Proposed
- 日期：2026-09-16
- 关联分支：`feat/webgpu-reader`（WebGPU 成果隔离保存与上游追踪）、`devel`（基线主干）
- 核心准则：**ReaderCore owns semantics; ImagePipeline owns image policy; Renderer owns presentation.**（ReaderCore 掌管阅读语义，ImagePipeline 掌管图像策略，Renderer 仅负责呈现绘制）

---

## 一、 背景与动因

Kototoro 当前的漫画阅读器主要基于 Jetpack Compose 与 Telephoto（Zoomable/Subsampling）构建。近期在探索引入 Mihon 最新 WebGPU 渲染器（`ca.mpreg:webgpuviewer`）的过程中，团队深入触及了移动端漫画阅读器的深水区瓶颈与架构矛盾：

1. **WebGPU 在长条漫（Webtoon）上的局限性**：
   - 上游 Mihon 引入 WebGPU 的主要收益在于跨平台一致性与可编程着色器（3D 翻页动效、Catmull-Rom 缩放、LUT 色彩滤镜），但在无限连续滚动的 Webtoon 场景下，其流式分块管理与惯性滑动（Mihon #3780）仍处于演进初期。
   - 在 Kototoro 内部，Webtoon 模式目前不得不强制回退到 Telephoto，无法直接享受 GPU 后端带来的理论优势。
2. **移动端驱动与合成层兼容代价**：
   - 依赖 NDK/Dawn/Vulkan 的原生阅读视口在 Android 碎片化设备（Adreno/Mali 早期驱动、MIUI/HyperOS、Android 14 SurfaceControl 打洞与合成）上面临冷启动白屏、看门狗误判、生命周期回收等极其繁重的系统级兼容负担。
3. **通用 UI 列表虚拟化与二维漫画视口的错配**：
   - 现存的条漫阅读器基于 Compose `LazyColumn`。快速滑动（Fling）时，逐像素的位移驱动 Snapshot State 频繁变更，触发重组（Recomposition）、测量（Measurement）、布局（Layout）以及大量的子项挂载/卸载（Item subcomposition & disposal）。
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
│   ┌────────────────────┬────────────────────┬────────────────┐   │
│   │      Compose       │       Canvas       │     WebGPU     │   │
│   │      [STABLE]      │   [EXPERIMENTAL]   │[UPSTREAM-TRACK]│   │
│   │   现有 Telephoto   │  新 Webtoon 单视口 │  Mihon 翻页/特效│   │
│   └────────────────────┴────────────────────┴────────────────┘   │
└──────────────────────────────────────────────────────────────────┘
```

### 1. 第一层：Reader Semantics 主权 (`ReaderCore`)
- **职责**：维护二维漫画虚拟世界坐标系，负责页面几何排列、视口相交计算、阅读进度结算。
- **输出**：纯几何与语义帧 `ReaderFrame`，告知下游“当前视口内应当在哪些矩形位置绘制哪些页面的哪些区域”。
- **绝对禁忌**：**绝不知晓图片文件的下载、解码、路径与失败状态**。

### 2. 第二层：Image Resource 主权 (`ImagePipeline`)
- **职责**：决定“如何准备像素”。根据视口物理尺寸、设备 RAM 等级、缩放倍率计算动态 LOD 采样率，管理分块切片（Tiling）、预取队列与内存缓存。
- **输出**：多态呈现资产 `ReaderImageAsset`。
- **绝对禁忌**：不持有任何 Android UI 组件或阅读器章节/进度状态。

### 3. 第三层：Rendering 主权 (`ReaderRenderer`)
- **职责**：决定“如何把像素画在屏幕上”。
- **形态**：各 Backend（Compose、Canvas、WebGPU）作为插件式适配器接入。
- **绝对禁忌**：**不得成为阅读进度、页面布局和章节语义的 Source of Truth**。

---

## 三、 关键架构约束

为确保长期演进不发生设计漂移，确立以下 4 项刚性约束：

### 约束 1：ReaderCore 绝不知晓图片加载状态（排版稳定性与 Zero CLS）
- `ReaderCore` 仅基于页面固有尺寸元数据（`intrinsicWidth`、`intrinsicHeight` 或初始预估宽高比）构建连续虚拟几何体。
- 图片正在下载、等待解码或解码失败，Scene 几何布局完全不受影响。
- 若资源不可用，Renderer 仅负责在对应位置绘制占位块（Placeholder），**排版骨架永远不塌陷、不跳动**。

### 约束 2：ImagePipeline 职责细分，Prefetch 归属清晰
- 严禁将 `ImagePipeline` 做成巨型上帝对象。内部明确拆分为：
  - `ImageSource`：原始字节流输入；
  - `DecodePlanner`：决定“解多少、怎么解”；
  - `ResourceStore`：缓存生命周期；
  - `DecodeExecutor`：执行解码产出目标资产。
- **`PrefetchScheduler` 归属于阅读场景驱动**：由 `ReaderCore` 依据运动速度计算出 `PrefetchRequest` 提交给 `ImagePipeline` 执行。

### 约束 3：Renderer 拥有“呈现状态”，而非“语义状态”
- Renderer 允许且应当拥有后端特有的绘制资源状态（如 Canvas 的 `Paint`、`Matrix`，WebGPU 的 `TextureCache`、`BindGroup`）。
- 但严禁 Renderer 内部私自维护 `currentChapter`、`currentPageProgress`、`isDoublePage` 等阅读语义字段。

### 约束 4：多态呈现资产，拒绝为统一而二次拷贝
- `ReaderImageAsset` 采用密封接口（Sealed Interface）：
  - `BitmapAsset`：供 Hardware Canvas 与 Compose 消费；
  - `TileSet`：供超长图局部高精 Tile 消费；
  - `Encoded`：直接向 WebGPU 或底层 NDK 传递原始数据流。
- 各 Renderer 可向 Pipeline 声明其 `preferredRepresentation`。**绝不强迫 WebGPU 必须先经过 Java Bitmap 再转纹理**。

---

## 四、 代码组织规划（Package 先行，暂缓拆 Module）

初期避免 Gradle 模块爆炸，直接在 `app/src/main/kotlin/org/skepsun/kototoro/reader/` 下建立清晰的目录隔离：

```text
reader/
├── core/                  // 纯 Kotlin，无 Android UI 依赖，100% JVM 单测覆盖
│   ├── PageId.kt
│   ├── PageGeometry.kt
│   ├── ReaderViewport.kt
│   ├── ReaderScene.kt
│   ├── VisibleNode.kt
│   ├── ReaderFrame.kt
│   ├── VisibleRegionResolver.kt
│   └── ReaderProgressResolver.kt
│
├── image/                 // 图像资源决策与流水线
│   ├── ReaderImageAsset.kt
│   ├── ReaderImagePipeline.kt
│   ├── ReaderLodPolicy.kt
│   ├── ReaderTileManager.kt
│   └── DecodePlanner.kt
│
├── render/                // 可插拔渲染器实现
│   ├── ReaderRenderer.kt
│   ├── compose/           // 现有稳定 Telephoto 实现
│   ├── canvas/            // 新建 CanvasWebtoon 原型
│   └── webgpu/            // 隔离的 WebGPU 适配器
│
└── ui/                    // Activity, ViewModel, Chrome, Menu, Settings
```

---

## 五、 验证路线图与 Go/No-Go 评判准则

### 阶段规划

1. **分支隔离（已完成）**：
   - 将现有 13 个 WebGPU 提交完整封存在 `feat/webgpu-reader` 分支，状态标记为 `UPSTREAM-TRACKED`。
   - `devel` 恢复纯净，消除 NDK 依赖与冷启动监控对主干的干扰。
2. **Phase 0：基准建立（Benchmark Baseline）**：
   - 使用 `androidx.benchmark.macro` 在 120Hz 测试机上对现有 Compose/Telephoto 运行固定数据集（100 页普通、100 页 Webtoon、极端超长图）的快速滚动性能基准。
3. **PoC A：Scene 几何抽象**：
   - 实现纯几何的 `ReaderScene`，让现有阅读器与新 Scene 并行计算，验证位置、可见区域与当前页计算 100% 一致。
4. **PoC B：Webtoon 视口实验（A/B 对照）**：
   - 保持功能极简（仅垂直滚动、无缩放、无 OCR、固定图集），同时构建两个极小渲染器：
     - `ComposeCanvasRenderer`（基于 Compose `Canvas(Modifier.fillMaxSize())`）
     - `AndroidViewCanvasRenderer`（基于自定义 `View.onDraw(canvas)`）
   - 探究性能收益的根源究竟是“Canvas 本身”还是“脱离了 LazyColumn 虚拟列表抽象”。

### Go / No-Go 决策矩阵

不以模糊的“平均帧率提升百分比”为唯一标准，重点考量尾部延迟与内存平稳度：

| 评估维度 | 核心指标 | 达到标准（GO） | 放弃或退回（NO-GO） |
| :--- | :--- | :--- | :--- |
| **滑动卡顿 (Jank)** | **P99 / P99.9 帧耗时** | 从 Compose 的 25~35ms 降至 8~12ms（彻底消除掉帧尖峰） | 依然高频出现 >16.6ms 尖峰，无质变改善 |
| **垃圾回收 (GC)** | **滚动期间 GC 次数** | 快速 Fling 期间垃圾回收暂停次数趋近于 **0** | 依然频繁触发高额内存分配与 GC 暂停 |
| **内存稳定性** | **长图 Peak RSS** | 内存平稳有界，无突发暴涨 | 内存持续堆积，存在泄漏或 OOM 隐患 |
| **系统复杂度** | **维护成本 & 兼容性** | 逻辑清晰，无特定厂商驱动黑洞与穿孔 Bug | 代码量膨胀严重，引入新的系统级黑盒缺陷 |

---

## 六、 Non-Goals（第一阶段明确不做的事项）

为了控制工程范围，第一阶段严格禁止发散：
1. **不**重写现有阅读器的全部交互与手势；
2. **不**重写物理惯性引擎（`Physics`）；
3. **不**在第一阶段直接开发完整的 Tile 动态切片与 LOD 高级引擎；
4. **不**在第一阶段废弃或替换现有的 Telephoto 单页/双页阅读模式；
5. **不**自行实现或重构 WebGPU 的 Continuous 条漫模式（交由上游 Mihon 推进）。
