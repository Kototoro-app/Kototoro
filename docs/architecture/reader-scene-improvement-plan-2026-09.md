# Scene Reader 改进与验收计划（2026-09）

- 日期：2026-09-20。
- 代码审阅基线：`48083e0b4`（`fix(reader): prevent cover transition page flicker`）。
- 状态：部分执行（2026-09-20 已交付：§4.3 口径修正、§5.2 semantics、§6.1 范围查询、§6.2 I1 护栏、§8.1 资源窗口 planner 契约、§8.2 `:reader-core` 模块抽取、§8.3 两处依赖反转与路线图记录、§4.1 场景 1 A/B 基线、§4.2 场景 2/3/4 分页矩阵基线、2.5× 超时帧 trace 提取（交付 10）、zoom 长卡顿根因修复——session 关闭移出主线程（交付 11，700ms 停顿消除）、7 场景超时帧证据归档与 §4.2.1 分组 SLO 制定（交付 12，高倍率组由「待验收」转为有数值门禁）；长章节组 fixture、B 回归矩阵其余项、残差 tile 到达调度优化、§8.3 后续模块轮次与阶段 D 未启动，见 §10.1 交付记录）。本文其余未执行部分仍为设计提案。
- 范围：漫画 Scene Reader；不包含小说阅读器、视频播放器或依赖升级。
- 关联：[Scene Reader 收尾计划](reader-scene-closure-plan-2026-09.md)、[ADR 0002](../adr/0002-reader-scene-decoupling.md)。

## 1. 目标与决策原则

沿用 Scene 几何、阅读语义、图片资源与 Compose 渲染分离的架构，优先补齐可复现的正确性验收，
再通过受控实验降低分页绘制成本。架构成熟度以行为、性能与维护成本为依据。

本文补充现有 CS 清单，不替代其历史实验记录，不自动修改默认开关或放宽既有门槛。
如果采用本文提出的分场景性能策略，应先同步 CS-7 和验收脚本，再据此作出 promotion 决策。

必须保持以下边界：

1. ReaderCore 不依赖 Android、Compose 或 renderer 类型（I1）。
2. 页码、进度、排布、缩放归属和手势语义由 core/阅读状态持有，GraphicsLayer 不成为真相之源（I3）。
3. 替换 renderer 不要求重写阅读语义（I6）。
4. Benchmark 与语义 parity 均通过，才允许分页 promotion。
5. Retained layer、资源窗口统一和模块化是独立工作项，不因列入计划自动成为发布阻塞项。

## 2. 事实、假设与证据限制

| 项目 | 当前依据 | 判定 |
| --- | --- | --- |
| Paged 全量扫描 | `PagedReaderScene.slotIndexOf`、`resolveActiveSlot`、`resolve` 遍历 slots；宿主 draw 使用 `filter/map/sortedBy` | 代码已核实，可优化；耗时占比未测量 |
| Paged CPU 尾部差距 | 收尾计划记录 Scene 7.563ms、legacy 5.200ms 的 CPU P99 | 历史结果，不能视为本 HEAD 的复测结果 |
| Retained layer 收益 | 已排除两处热路径嫌疑；官方支持重放 layer 绘制指令 | 有机制依据，收益与完整归因仍待 A/B |
| Semantics 缺口 | 三个 `ComposeScene*` 宿主未显式提供 semantics/testTag 等能力 | 宿主级缺口成立；完整无障碍体验需设备验证 |
| Core 隔离 | 当前为 app 内 package；`PagedSpreadResolver` 引用应用层 `ZoomMode` | 没有独立模块边界，需先处理反向依赖 |
| 高倍率性能 | 收尾计划记录 2×/2.5× 的正 overrun 尾部 | 仍有截止期超时，不能称为全面达到 120Hz |

历史 A/B 排除若干嫌疑，并不能单独证明剩余开销全部来自 display list 重新录制。
Retained layer 是否有效，必须由同条件实验验证。

统计解释统一为：

- `frameOverrunMs > 0` 表示该帧超过 deadline。
- P99 overrun 为负不等于零掉帧，最慢约 1% 的帧仍可能超时。
- CPU P99 小于 8.33ms 不能单独证明 120Hz 达标；CPU 工作耗时与显示 deadline 分开评价。
- 单台设备、单个旅程或多轮 P99 的中位数，不能证明所有设备、所有帧均达标。

## 3. 执行顺序与依赖

| 阶段 | 工作 | 对应 CS | 完成条件 |
| --- | --- | --- | --- |
| A | 固定基线、修正指标口径、建立结果记录 | CS-1A / CS-7 | 同条件可复测，产物可追溯 |
| B | Cover/Curl 回归、恢复矩阵、基础 semantics | CS-2 / CS-3 / CS-8 | 必需行为逐项有结论和证据 |
| C | Paged 范围查询/索引、I1 构建护栏 | CS-6，分页热路径 | 语义不变、检查可执行、长章节成本收敛 |
| D | SLIDE/COVER retained layer PoC | ADR PoC B | 得出采用、调整或放弃的实测结论 |
| E | 资源窗口统一、抽取 `:reader-core` | CS-9 / CS-6 | 输出契约明确，依赖边界由构建约束 |
| F | 可选输入/ARR 适配、Scene 2.0 | P4 | 有独立需求和收益证据后启动 |

B 的语义验收是 promotion 前置；C/D/E 可独立交付。
若性能门禁未通过，应定位具体瓶颈，而不是预先规定必须采用 retained layer 才能转正。
每阶段完成后记录证据再启动依赖工作，不把所有改动打包成一次重构。

## 4. 阶段 A：基线与性能门禁

### 4.1 固定实验条件

每轮至少记录：commit 与未提交 diff、APK 哈希、包名/variant、设备与系统版本、分辨率、
刷新率/ARR 配置、温度状态、编译模式、夹具、旅程、冷暖缓存策略、迭代次数。
安装后核对实际运行包，避免 benchmark 使用旧 APK。

保留现有 `CompilationMode.Full` 作为历史对照；面向发布的验证另记录实际 Baseline Profile 配置，
两类结果分别展示，不直接混合比较。A/B 顺序应交错，减少温度和时间漂移影响。

### 4.2 场景分组

| 组别 | 旅程 | 评价要求 |
| --- | --- | --- |
| 常规阅读 | 普通单页、双页跨章、SLIDE/COVER/CURL 往返 | 保留 P99 overrun ≤ 0 的既有目标；同时记录超时帧比例与连续超时 |
| 大图常用倍率 | 6000×9000，1×/1.5×，平移与翻页 | 独立检查尾延迟、纹理上传和峰值/稳态内存 |
| 高倍率压力 | 同夹具 2×/2.5×，快速往返与缩放切换 | 独立 SLO；具体上限未制定前不得宣布通过或自动豁免 |
| 长章节 | 50/500/5000 页元数据，固定可见窗口 | 检查查询耗时与分配是否随总页数增长 |

高倍率分级仅为提案。若接受正 overrun 尾部，必须在测量改动收益前固定：
P95/P99 上限、超时帧比例、连续超时帧限制、内存上限及相对基线退化容忍度。
没有足够数据时保持“待验收”，不能用“可控尾部”代替数值。

#### 4.2.1 分组 SLO（2026-09-20 制定，交付 12）

上段的前置条件已满足：下列数值在测量任何优化收益之前，依据 `67a243fc6` 上 7 个场景的基线分布
（§10.1 交付 12；每场景 5 迭代，trace 与 harness JSON 逐样本核对）固定。后续 A/B 与优化轮次
不再改动；需要改动时按本节末尾的「重设条件」重新立项并留证据。

**判定口径（三组共用）**

- overrun = `max(actual.end, RT.end) − expected.end`；本配置（1280×2772、请求 120Hz）的平台
  expected 预算为**固定 13.6666ms**，不是裸 vsync 周期，也不是 8.33ms。
- 分位数用合并样本的 `(N−1)×p` 线性插值；比例 = 合并超时帧数／合并帧数；最大连续超时按单轮
  有序帧序列计数，不跨迭代连接。
- 超时比例、最大连续超时与最差帧必须来自**同一次运行归档的 trace**，并与 `benchmarkData.json`
  的 `frameCount`／`frameDurationCpuMs`／`frameOverrunMs` 逐样本一致（`scripts/analyze_reader_frames.py`
  在不一致时直接报错）。只有 JSON、没有 trace 时不得判定通过。
- 判定前必须记录：commit 与未提交 diff、APK sha256、安装核对（`lastUpdateTime` 变化）、
  refresh 前置（≥119Hz）、每场景运行前后的电池温度。

| 维度 | 常规阅读组（单页 / 双页） | 大图常用倍率组（大图 1× / 1.5×） | 高倍率压力组（2.0× / 2.5×） |
| --- | --- | --- | --- |
| overrun P95 | ≤ 0 | ≤ 0 | ≤ 0（≥95% 帧在预算内） |
| overrun P99 | ≤ 0 | ≤ 0 | ≤ +6ms |
| 超时帧比例 | ≤ 0.5% | ≤ 0.5% | ≤ 2.5% |
| 最大连续超时 | ≤ 1 帧 | ≤ 1 帧 | ≤ 3 帧 |
| 最大单帧 overrun | ≤ 20ms | ≤ 20ms | ≤ 50ms |
| 最大主线程帧 | ≤ 50ms | ≤ 50ms | ≤ 50ms |
| RssAnon Max | ≤ 400MB | ≤ 550MB | ≤ 650MB |
| 硬性失败类（三组一致） | 任一主线程帧 ≥100ms 或单帧 overrun ≥100ms | 同左 | 同左 |

**基线实测（上表数值的来源；各场景 5 迭代合并）**

| 场景 | 帧数 | 超时比例 | 最大连续 | overrun P95/P99 | 最大单帧 | 最大主线程帧 | RssAnon Max |
| --- | ---: | ---: | ---: | --- | ---: | ---: | ---: |
| 单页 1× | 2,992 | 0.067% | 1 | −7.95 / −5.49 | +2.7 | 13.6 | 323MB |
| 双页往返 | 4,910 | 0.020% | 1 | −7.94 / −5.36 | +0.1 | 11.7 | 229MB |
| 大图 1× | 3,043 | 0.066% | 1 | −7.97 / −5.36 | +0.8 | 11.8 | 242MB |
| 大图 1.5× | 3,101 | 0.097% | 1 | −7.92 / −4.48 | +3.3 | 11.2 | 436MB |
| 2.0× | 4,118 | 1.918% | 3 | −8.09 / **+5.70** | +21.9 | 28.6 | 532MB |
| 2.5× held（fit-height，开 1×） | 3,125 | 0.416% | 3 | −7.76 / −4.10 | +21.0 | 27.8 | 423MB |
| 2.5× 开即 2.5× | 3,984 | 1.632% | 2 | −8.25 / **+3.16** | +16.2 | 27.1 | 528MB |

判读要点：

1. **接受正 overrun 尾部的前提被显式固定**：P99 允许为正的前提是同时受「比例 ≤2.5%、
   连续 ≤3 帧、单帧 ≤50ms、主线程帧 ≤50ms」四项约束；**P99 为负不能代替掉帧结论** ——
   2.5× held 的 P99 为 −4.10ms，却仍有 0.416% 超时与 3 帧连续超时，正是 §4.3 口径警告的情形。
2. **硬性失败类来自交付 10/11 的证据**：主线程同步关闭 region decoder 曾造成 723–753ms
   单段睡眠；任何 ≥100ms 的主线程帧都按该类回归处理，不再看比例。
3. **跨轮可复现**：2.5× 开即 1.632% / P99 +3.156ms（本轮）与交付 11 B 侧 1.628% / +3.116ms
   几乎逐项相同；其余六个场景与交付 8/9 的数字同量级（详见交付 12），因此该组数值可作为门禁。
4. 三组 SLO 只对本轮固定条件成立：M332BF / 1280×2772 / 请求 120Hz（预算 13.6666ms）/
   `CompilationMode.Full` / 5 迭代 / `pagedTurns` 旅程 / 现有 fixture。

**相对基线退化容忍度（后续 A/B 与优化轮次）**：CPU P99 ≤ 基线 +0.5ms；overrun P99 ≤
基线 +1.5ms；超时帧比例 ≤ 基线 +0.5pp；最大单帧 overrun ≤ 基线 ×2 且不越过组上限；
RssAnon Max ≤ 基线 ×1.10；GPU Max ≤ 基线 ×1.10。

**重设条件**：设备、分辨率/刷新率、夹具、旅程、编译模式或迭代数任一变化，必须重设基线；
出现设备温度 >38°C、残留 root perfetto 进程、可用内存异常等状态偏差时（真机验证方法第 6 条），
不得跨状态比较 P99/overrun，须在同一状态下重测。

### 4.3 必须输出的指标

- CPU 与 overrun 的 P50/P95/P99，各轮值及汇总方法。
- 超时帧比例、最大连续超时帧数和最差帧 trace；汇总工具不提供时从 trace 提取。
- RSS/GPU 峰值与结束值，多轮遍历后的趋势。
- presentation assets、tile 驻留/解码/驱逐计数与分配/GC 证据。
- 场景失败、空白页、闪烁、错误进度等正确性结果。

新增自动判定脚本时必须先用通过/失败样本验证其判断；缺少必需字段应判为证据不足。
修正现有计划中“P99 为负即零掉帧”的表述，同时保留原始历史数字。

## 5. 阶段 B：语义与视觉正确性

### 5.1 最小回归矩阵

| 维度 | 必测行为 |
| --- | --- |
| 翻页样式 | SLIDE/COVER/CURL，前进/后退、拖动取消、快速反向、动画被新输入打断 |
| 方向与排布 | LTR/RTL/TTB；单页/双页、封面偏移、宽页独占、跨章双页阻断 |
| 缩放归属 | 平移到边界后残余位移翻页；切页保留、回翻恢复；切模式后旧手势取消 |
| 生命周期 | 旋转、分屏尺寸变化、后台进程死亡后恢复；区分 Activity 重建与真正进程重建 |
| 异步内容 | 冷加载、tile 延迟到达、失败重试、动图；翻页时不冻结或显示上一页资源 |
| 输入 | 触摸、TalkBack 动作、DPAD、音量键；边界动作不产生错误进度 |
| 连续模式 | Webtoon 与连续横向的方向、章节边界和已有门控要求 |

恢复检查使用章节/PageId 与归一化页内锚点，不以旋转前后的绝对像素相等作为判据。
组合矩阵覆盖风险交叉点；不用无差别穷举所有组合，但每个维度必须有证据。

### 5.2 Semantics 实现范围

先为 viewport 提供稳定的语义节点：页码/总页数、章节、必要状态描述和上一页/下一页动作。
状态更新跟随阅读语义变化，不把每帧 offset 暴露为播报状态。

- 复用现有导航入口，避免新增一套无障碍翻页逻辑。
- 使用稳定 viewport testTag，页身份用语义属性表达，避免 tag 随帧变化。
- 双页描述要反映实际阅读顺序；首尾页不可执行的动作应正确禁用或不提供。
- 章节跳转、缩放动作按已支持能力添加，不虚构产品行为。
- 错误重试等独立控件保留自己的可访问节点；viewport 节点不能吞掉它们。
- TalkBack 焦点、键盘/DPAD 焦点分别验证；testTag 不能替代可访问性。

验收：至少有经 semantics 定位并执行翻页的 instrumentation 测试，以及 TalkBack 真机记录。
视觉问题保留截图或视频；语义/恢复问题保留可自动断言的状态。

## 6. 阶段 C：Paged 查询与依赖护栏

### 6.1 Paged 范围查询

利用固定 slot primary extent 直接定位候选范围，然后枚举实际相交 slots：目标复杂度为
O(1) 范围定位 + O(k) 候选处理。保留精确相交判断，不假定所有 viewport 永远只覆盖两页。

`resolveActiveSlot` 的最近中心语义和 `resolve` 的最大相交面积语义分别保留，尤其要保持
等距/等面积时的选择规则、页序和 progress anchor 不变。

建立 PageId 到 page index/slot index 的索引，并在重建 slots 时更新。
几何提示更新、双页重新分组及章节变化后，索引不得沿用旧位置。

绘制、transition 固定页、资源预取可以共用基础范围计算，但分别构造各自集合：
预取集合通常大于可见集合，固定页还涉及变换后的屏幕可见区域。
不要为减少扫描而把三者强制设为同一范围。

优先局部函数与现有类方法；出现明确复用需求后再提取 resolver。

验收：

- 用原线性算法作为测试 oracle，对随机及边界 viewport 比较 nodes、顺序和 progress。
- 覆盖空场景、首尾越界、slot 边界、等距、宽 viewport、双页、方向和重建索引。
- 50/500/5000 页固定窗口测量证明查询成本不再线性增长；重建成本单独统计。
- 普通章节 benchmark 无明显回归，不预先承诺 P99 收益。

### 6.2 I1 护栏

先增加可被 `check`/CI 执行的依赖检查，禁止 core 引入 Android、Compose 和 renderer 类型。
若采用文本扫描，应覆盖全限定名与 import alias，并明确它不是完整的依赖图证明。
用故意违规的测试夹具验证护栏确实失败，避免仅测试现有目录恰好通过。

同步列出 core 对 app 类型的引用；优先处理 `ZoomMode` 的归属或边界映射，为模块化准备。
不为抽模块创建空壳接口或大量一对一转发层。

## 7. 阶段 D：Retained GraphicsLayer 实验

### 7.1 范围与所有权

首轮仅支持 SLIDE/COVER，保留 immediate 路径作为对照与不支持内容的回退。
图层属于 Compose renderer；scene、分页状态、zoom/pan 及进度所有权保持不变。

图层记录 slot 局部坐标的页面内容，transition 变换与裁剪在其外部应用。
相同内容的位移变化不应触发重新 record；是否在静止后立即释放由测量决定。
GraphicsLayer 保存绘制指令，不等于自动生成或永久缓存整页位图。

### 7.2 失效与生命周期契约

| 变化 | 要求 |
| --- | --- |
| 翻页进度、外部位移 | 只更新必要变换/裁剪，不重新录制静态内容 |
| 图片/tile 到达、重试成功、LOD 替换 | 内容版本变化，重新录制受影响图层 |
| 动图换帧 | 更新相应图层，或明确回退 immediate；不得冻结动画 |
| slot 身份、排布、viewport 尺寸变化 | 重新计算内容与图层尺寸，废弃不兼容记录 |
| zoom/pan 变化 | 可分离变换单独更新；可见内容或 LOD 变化仍需失效 |
| 取消、跳页、反向拖动、宿主销毁 | 正确复用或释放，不能遗留旧页引用 |
| 资源驱逐 | 图层仍使用的资源不得被提前回收；资源固定必须计入预算 |

内容版本使用现有可观察资源状态扩展，避免每帧扫描全部 tile 来计算签名。
特别验证“初次只录到窄可见带，随后固定页显示整屏”的 COVER 情况，防止重新引入闪烁。

### 7.3 实验方法与退出条件

在同一版本、夹具和设备上切换 immediate/retained，除渲染策略外保持条件一致。
分别测量热资源、冷资源、异步 tile 到达和快速往返；增加 record 次数、首个过渡帧耗时、
存活图层数及其资源引用指标。预期目标是内容不变时 record 次数不随动画帧数增长。

采用条件：正确性矩阵通过；预先固定的性能目标有可重复改善；首帧、尾延迟和内存没有超出
已制定的回归容忍度；所有资源在取消/结束/销毁后按策略释放。

若收益落在噪声内、频繁内容失效抵消收益、或内存/首帧明显退化，应调整或放弃，记录负结果。
不得为了保留方案而扩大缓存预算或放宽门槛。CURL 仅在首轮证据成立后单独实验，
届时可评估保留页面内容、即时计算折叠路径与阴影的组合。

## 8. 阶段 E：资源契约与模块化

### 8.1 资源窗口统一

由 scene/阅读策略计算可见、固定、预取需求，统一输出资源窗口契约；连续与分页保留各自策略。
ImagePipeline 根据资源需求执行加载与预算管理，不接收用于决定阅读行为的模式分支。

能从需求分布猜出阅读模式不等于依赖泄漏；验收重点是管线是否依赖模式枚举或阅读规则。
契约需明确页身份、优先级、所需区域/LOD 信息、保留期限及取消/替换规则，具体字段按现有消费者确定。

验证范围：不同方向的前瞻、快速反向、章节切换、transition 固定页、缩放 LOD 与预算压力。
要求不缺失屏幕实际所需资源，窗口/资产数量有界，旧请求不会覆盖新状态。

### 8.2 抽取 `:reader-core`

先完成 app 类型依赖审计，再抽纯 Kotlin/JVM 模块：geometry、scene、camera、progress、drag 和
纯 transition 语义模型。将对应单测迁入，app 单向依赖 core。

模块不添加 Android/Compose 编译依赖；保持 I1 护栏，防止以后通过新增依赖重新破坏边界。
首轮不拆 `:reader-image` / `:reader-render-compose`，不顺带引入 KMP；JVM 模块化不等于 commonMain 兼容。

验收：core 可独立测试，app Kotlin 编译通过，相关集成/手势测试通过，依赖图无回指 app。

### 8.3 后续模块化路线图（外部评审输入，2026-09-20 记录）

外部评审建议提出四层拆分目标，与 §8.2 的「首轮只抽 `:reader-core`」兼容，作为后续轮次的
结构记录（每轮独立交付、独立证据，不打包执行）：

```text
reader-core（已完成，2026-09-20 交付 6）
        ↓
scene-image        // reader/image 的 tile/LOD/residency 引擎，隔离 KototoroImagePipelineAdapter
        ↓
scene-compose      // reader/render/compose 的 Draw-phase renderer + 输入 + semantics
        ↓
kototoro-reader-adapter  // Kototoro 自有：ReaderPage→ScenePage、枚举映射、Coil/PageLoader 桥接
```

已采纳的判断：

- **多模块优先，暂不独立仓库、暂不 KMP**：与 §8.2 约束一致（JVM 模块化 ≠ commonMain 兼容；
  无真实多平台消费者之前不为假想未来付 KMP 工具链复杂度）。发布级命名（scene-reader-* 或其他）
  留到真的拆仓库时再定，当前 `:reader-core` 名称与本仓库 reader/* 词汇一致。
- **不新造已有抽象**：`ReaderImagePipeline` / `TileStore` / `ComposeReaderImagePipeline` 契约
  已存在；后续轮次是让消费者依赖既有抽象，而不是引入平行的 `SceneImagePipeline` /
  `SceneImageSource` 新接口（避免一对一转发层）。
- **Phase C（`ScenePage`/`SceneReaderConfig` 替代 `ReaderPage`/`ReaderAnimation`/`ZoomMode` 等
  宿主模型）是宿主公共 API 重构**：须按仓库工作流（brainstorm → 设计 → TDD）单独一轮，验收含
  三宿主迁移与手势/语义/恢复测试全绿；chapterId 语义推广为「不能跨组配对的连续内容组」（group）
  的想法在该轮设计时一并评估。
- Phase D（三宿主封装为单一 public `SceneReader` API）在 C 之后；不为「更库化」提前收窄 API。

对该评审输入的当日执行与修正记录：

1. 其指出的两处耦合属实且当日已修复（交付 7）：`SceneImagePresentationCoordinator` 改依赖
   `ReaderImagePipeline`（`requestTiles` 上提为契约方法）；`Listener` 从具体类 `ReaderTileManager`
   移入 `TileStore` 接口。
2. 其「进一步直接 KMP（androidTarget/iosArm64/jvm）」与本计划 §8.2 明确约束冲突，按本计划执行：
   KMP 等真实多平台消费者出现后再立项。

## 9. Promotion 与后续工作

分页 promotion 检查表：

- [ ] 当前候选 APK 与 commit 可追溯。
- [ ] CS-1A 性能验收通过；所有例外都有明确范围、数值与决策记录。
- [ ] CS-2/CS-3 和阶段 B 必需语义矩阵通过，无未处理的功能退化。
- [ ] 无资源泄漏、持续内存增长、错误进度或可复现空白/闪烁。
- [ ] nightly 观察与回退路径明确；翻默认值不同时清除所有对照与回退能力。
- [ ] 默认开启后的证据充分，再按原 CS-4/CS-5 清退 legacy host 与实验开关。

不以完成 retained layer 或模块化作为成功的替代指标。
若既有 immediate renderer 已满足门禁，其性能优化可继续独立迭代。

后置工作包括 `scrollable2D` 输入适配、Compose `preferredFrameRate` adapter、译文 overlay、
scene-aware OCR/SR。只有在独立需求明确且能简化实现或改善实测体验时启动；不为“更 Compose”重写成熟手势。

## 10. 验证命令与交付记录

沿用仓库 wrapper，并按改动范围选择验证：

```bash
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest :reader-core:test --no-daemon
./gradlew :reader-core:test --no-daemon   # 模块独立测试（§8.2 验收：core 可独立测试）
./gradlew :app:connectedDebugAndroidTest
```

设备测试与 macrobenchmark 按实际测试类筛选；运行前检查安装包与测试 variant，
不因本文列出命令就宣称它们已经通过。模块抽出后的独立测试命令在实施时补充。

每项交付记录：修改范围、关联 CS、测试结果、设备/构建身份、原始结果路径、已知限制和下一步决策。
性能结果至少使用以下表格，不只给出平均值或单个最优轮次：

| 候选/基线 | 场景 | CPU P99 | overrun P99 | 超时比例/最长连续超时 | RSS/GPU Max/Last | 正确性 | 结论 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 待测 | 待测 | — | — | — | — | 待验收 | 不作通过判断 |

### 10.1 交付记录（2026-09-20，devel @ 48083e0b4 + 本次工作树改动）

设备/构建身份：M332BF - 17（ecd4369c，arm64-v8a），debug variant；JVM 测试在 macOS/Temurin 24。
真机 instrumented 结果 XML：`app/build/outputs/androidTest-results/connected/debug/`（Gradle 运行后即被卸载，
原始文件随运行覆盖）；JVM 结果：`app/build/test-results/testDebugUnitTest/`。

#### 交付 1 — 阶段 A：统计口径修正（§4.3）

- 修改范围：closure plan 两处（场景 1 判读"没有丢帧"、全矩阵判读"即 120Hz 下没有丢帧"）与
  ADR 0002 两处（"零掉帧"、"无掉帧"）改为分位数口径：P99 为负仅说明 ≥99% 帧未超时且有余量，
  最慢约 1% 帧是否超时需超时帧比例/最差帧 trace 核对。历史数字全部保留，修正处标注本文出处。
- 关联 CS：CS-7（文档债）。
- 测试：文档改动，无。
- 已知限制：固定实验条件（§4.1）与结果记录流程未建立，属后续真机 benchmark 工作。

#### 交付 2 — 阶段 C：PagedReaderScene 范围查询与索引（§6.1）

- 修改范围：`reader/core/PagedReaderScene.kt`
  - `slotIndexRangeFor(bounds)`：固定 slot primary extent 上的 O(1) 索引算术候选范围（低位一档余量），
    布局非均匀 stride 时自动回退全量线性扫描（rebuild 时校验，容差 min(0.5px, 5% stride)）。
  - `slotsIntersecting(bounds)`：候选范围内保留逐 slot 精确正面积相交过滤，即 draw 可见集。
  - `resolve()` 候选枚举 + `resolveActiveSlot()` O(1) 最近中心（V 形距离两候选 + 实际 bounds 计距），
    严格小于的 tie 规则与线性扫描逐字节一致（等距取低 slot、等面积取早 slot）。
  - `pageIndexById` / `slotIndexByPageId` HashMap 在 `rebuildSlots()` 内重建；几何提示更新、
    双页重分组、章节前后增删后索引随位置更新（重复 PageId 取首个，与线性一致）。
- 宿主热路径（`ComposeScenePagedReader`）：draw 循环、`updateResourceWindow` 的 contentNodes、
  loading placements 三处 `allSlots.filter/for` 全量扫描改用 `slotsIntersecting`；
  预取（active slot ± lookahead）与 COVER 固定页保持各自索引算术，未合并为同一范围。
- 测试（先红后绿）：`PagedReaderSceneRangeQueryTest` 17 用例。oracle = 原线性算法逐字复刻；
  fuzz 80 场景 × 30 viewport（随机方向/双页/封面偏移/4 种 zoomMode/宽页/切半/跨章/零尺寸/越界 viewport）
  + 边界用例（空场景、首尾越界、slot 边界对齐、等距中心、等面积、宽 viewport 三方向、重建索引、
  scroll position/origin）。已存在的 `PagedReaderSceneTest`/`PagedSpreadResolverTest`/
  `PagedSlotScreenVisibilityTest` 全部保持绿。
- 成本证据（JUnit system-out，同上 XML）：固定窗口 1000 次查询（200 次预热）：
  50 页 1665ns、500 页 1243ns、5000 页 236ns per query —— 不随页数增长（线性扫描量级应 ~100×）；
  rebuild 单独统计 0.12/0.51/4.57ms（O(n) 按设计）。
- 关联 CS：CS-6、分页热路径。
- 已知限制/下一步："普通章节 benchmark 无明显回归"（§6.1 验收第 4 条）需 CS-7 真机门禁流程与固定
  基线（阶段 A 余项），本文不据此宣称 P99 收益。

#### 交付 3 — 阶段 C：I1 护栏与 ZoomMode 归属（§6.2）

- `ReaderCoreIsolationGuardTest`（随 `check` 执行）：扫描 `reader/core/**`（真实树 >20 文件）
  必须零违规 —— 禁止 Android/Compose/renderer/app 层引用，覆盖 import（含 alias）与全限定名，
  注释剥离避免误报；KDoc 明示文本扫描不是完整依赖图证明（完整边界需 §8.2 模块抽取）。
  5 个故意违规夹具（`app/src/test/resources/reader-core-isolation-fixtures/`：Android import、
  Compose alias import、renderer FQN、inline android 类、注释提及 Android 的干净夹具）逐一验证
  护栏确实会失败 / 不误报。
- ZoomMode 归属：`core/model/ZoomMode.kt` → `reader/core/ZoomMode.kt`（git mv + 包名），
  17 个引用点更新 import；`reader/core` 对 app 类型引用审计后清零（此前唯一引用即 ZoomMode）。
  未创建空壳接口或转发层。
- 测试：`compileDebugKotlin`、`compileDebugUnitTestKotlin`、`compileDebugAndroidTestKotlin`、
  `compileBenchmarkKotlin` 全部通过；`testDebugUnitTest` 全绿。
- 关联 CS：CS-6。
- 下一步：§8.2 `:reader-core` Gradle 模块抽取（把 I1 从文本护栏升级为编译期约束）。

#### 交付 4 — 阶段 B：viewport semantics（§5.2）

- 修改范围：三个宿主（`ComposeScenePagedReader` / `ComposeSceneWebtoonReader` /
  `ComposeSceneHorizontalReader`）+ 共享 `SceneReaderViewportSemantics.kt` + 6 条中英字符串
  （`reader_a11y_*`）。
  - 稳定 testTag：`kototoro.reader.scene.pagedViewport` / `webtoonViewport` / `horizontalViewport`
    （不随帧/页变化）；页身份经语义属性表达。
  - contentDescription 用 settled 页窗口（lower–upper，双页反映实际阅读顺序），不把每帧 offset
    暴露为播报状态。
  - 上一页/下一页/上一章/下一章 custom actions 复用既有导航入口（从 requestedPage effect 提取的
    `navigateToSlot` / `navigateToPagePosition` 与 `onPullChapter`），未新增无障碍专用翻页逻辑；
    首尾页不提供不可能动作。
  - 不 merge descendants：错误重试、加载占位等子控件保留自身可访问节点。
- 测试（先红后绿，真机 ecd4369c）：`SceneReaderViewportSemanticsTest` 经 framework
  AccessibilityNodeInfo（TalkBack 同路径）按 contentDescription 定位 viewport、执行 "Next page"
  动作真翻页、断言第 1 页无 "Previous page"/第 4 页无 "Next page"。红验证：临时禁用 semantics
  后同测试失败（"viewport description did not settle"），恢复后通过。
- 真机回归：`ScenePagedGestureTest` 11 用例 10 过；唯一失败 `originalSizeCanPanVerticallyWithoutUserZoom`
  在未改动 HEAD（48083e0b4，同日基线）同样失败（同断言黑像素），属既有设备问题；连续满负荷运行时
  失败集随热状态漂移（HEAD 亦然，A/B 交错对比过）。`SceneReaderRecoveryTest` 1/1 过。
- 关联 CS：CS-8。
- 已知限制/下一步：TalkBack 真机手动记录、DPAD/键盘焦点、§5.1 回归矩阵其余逐格证据未完成；
  属阶段 B 余项。

#### 交付 5 — 阶段 E：资源窗口 planner 契约（§8.1）

- 修改范围：
  - 新增 `reader/core/SceneResourceWindowPlanner.kt`：统一 planner 契约
    （`plan(SceneResourceWindowRequest) → ReaderResourceWindow?`，null=快照未变化、保持现窗口）。
    输出契约写入 KDoc 并由测试强制：完整快照（缺页即可驱逐）、可见页覆盖且唯一、
    IMMEDIATE⇒PRESENTATION_READY、SOURCE_READY 仅限非可见页、模式不透明（窗口只含页身份/优先级/
    就绪度/区域，管线无需阅读模式分支）、有界（随视口与 lookahead 而非场景规模增长）。
  - 新增 `reader/core/PagedSceneResourceWindowStrategy.kt`：从分页宿主逐字提取的请求列表
    （活动槽+COVER 固定槽 IMMEDIATE、其余可见页 HIGH PRESENTATION、固定槽 lookahead 后方 MEDIUM /
    前方 HIGH SOURCE_READY、先到先得去重；活动槽索引由 frame viewport 沿主轴 `round(offset/extent)`
    推导，无需宿主状态）。
  - `ReaderPrediction` 实现同一接口（连续 strategy，含窗口键抑制语义）。
  - 三个宿主改经 planner 产出窗口：分页宿主删除手写请求列表（保留进度上报、transition 固定槽解析
    与 tile 协调在宿主侧——后者依赖宿主 zoom 变换）；连续两宿主改调 `plan(SceneResourceWindowRequest)`。
    管线/adapter 侧零改动（本就只消费 ReaderResourceWindow）。
- 测试（先红后绿）：`SceneResourceWindowPlannerContractTest` 11 用例 —— 分页提取 oracle
  （活动/固定槽、部分可见邻槽、lookahead 1 vs 2、越界钳制、双页槽、垂直分页、空场景、零视口、
  非 PagedReaderScene 拒绝）、连续 strategy 委托等价（含 null 抑制）、两 strategy 共享契约扫掠
  （分页 -500..5500 offset×3 pinned + 连续 -500..7500×3 motion）、有界性（≤6 请求/快照）。
  两个测试预期先错（末槽 ahead 越界全为 MEDIUM、offset 500 时 lookahead+2 含 page 4），按提取的
  实际行为修正——实现本身为逐字提取未改动。
- 验证：`:app:testDebugUnitTest` 全绿；`compileDebugKotlin` / `compileDebugUnitTestKotlin` /
  `compileDebugAndroidTestKotlin` / `compileBenchmarkKotlin` 全过。
  真机 ecd4369c（单类分跑）：`SceneReaderViewportSemanticsTest` 1/1、`SceneReaderRecoveryTest` 1/1、
  `ScenePagedGestureTest` 10/11（唯一失败为交付 4 记录的既有设备问题，HEAD 同败）。
- 关联 CS：CS-9。
- 已知限制：契约的「保留期限」仍是隐式的（窗口=完整期望集合，缺页可驱逐）——按 §8.1「具体字段按现有
  消费者确定」暂不加显式字段；下一步 §8.2 模块抽取时随 `:reader-core` 一并固化。

#### 交付 6 — 阶段 E：`:reader-core` 模块抽取（§8.2）

- 修改范围：
  - 新 Gradle 模块 `reader-core/`（纯 Kotlin/JVM，`org.jetbrains.kotlin.jvm`，JVM_11 字节码，
    Groovy DSL）：`git mv` 迁入 `reader/core` 全部 32 个主源文件（geometry、scene、camera/
    progress、drag、transition、prediction、planner 契约）+ 19 个测试类 + I1 违规夹具资源。
    未创建空壳接口或转发层；未引入 KMP 结构（JVM 模块化 ≠ commonMain 兼容）。
  - `settings.gradle` 纳入 `:reader-core`；`app` 单向 `implementation project(':reader-core')`。
  - `ReaderCoreIsolationGuardTest` 随迁并改为扫描模块自身源码/夹具路径：编译期类路径成为
    I1 主边界（模块 build.gradle 无任何 Android/Compose/renderer 依赖），文本扫描降级为第二
    道防线（仍捕捉方法体内的 FQN 引用），KDoc 更新如实说明两层关系。
  - `WebtoonViewportPolicyParityTest` 留在 app 测试源集（桥接 core 与旧 UI 测量函数，依赖
    app 类，非纯模块测试）。
  - §10 验证命令补充模块独立测试命令。
- 验证（全部实际运行）：
  - `:reader-core:test` 独立运行：**143 用例 0 失败**（含护栏 6 用例 + planner 契约 11 用例）；
  - `:reader-core` compileClasspath 仅 `kotlin-stdlib` —— 依赖图零回指（编译期保证）；
  - `:app:compileDebugKotlin` / `compileDebugUnitTestKotlin` / `compileDebugAndroidTestKotlin` /
    `compileBenchmarkKotlin` 全过；`:app:testDebugUnitTest` 全绿（含迁回的 parity 测试）；
  - 真机 ecd4369c：semantics 1/1、recovery 1/1、gesture 10/11（唯一失败为 HEAD 同败的既有
    设备问题，见交付 4）。
- 构建细节记录：KGP 一致性检查要求 compileJava/compileKotlin 同 JVM target（模块无 Java 源，
  显式对齐 11）；`kotest-runner-junit5` 只带 jupiter api 不带 engine —— app 侧由其他测试依赖
  传递补齐，纯模块需显式声明 `org.junit.jupiter:junit-jupiter:5.8.2`（与 kotest 的 platform
  1.8.2 / jupiter-api 5.8.2 版本对齐）。
- 已知限制：本仓库 CI（debug/nightly/release workflow）只执行 assemble 不跑单测，护栏与全部
  单测仍依赖本地/手动执行 `./gradlew :app:testDebugUnitTest :reader-core:test`（已在 §10 记录）。

#### 交付 7 — 阶段 E：两处依赖反转（§8.3 评审输入当日修复）

来源：外部评审指出的两处耦合，经代码核实属实后当日修复（均为小反转且有既有契约可依，
不新增平行抽象，符合 §8.2「不创建空壳接口或一对一转发层」）。

1. `requestTiles` 上提为 `ReaderImagePipeline` 契约方法（§8.1「契约需明确…所需区域/LOD 信息」
   的补全；接口方法为抽象 —— 无 tile 能力的实现必须显式 no-op，调用方永不按管线类型分支）；
   `SceneImagePresentationCoordinator.coordinateVisibleTiles` 参数类型从具体
   `KototoroImagePipelineAdapter` 改为 `ReaderImagePipeline`，renderer 层不再知道具体管线类。
2. `Listener` 从具体类 `ReaderTileManager` 移入 `TileStore` 接口（声明逐字迁移）；
   `TileDrawModifierNode` 与两个测试文件的 7 处 `ReaderTileManager.Listener` 引用改为
   `TileStore.Listener` —— Compose renderer 不再为监听器类型引用具体解码引擎。

- 测试（先红后绿）：`SceneImagePresentationCoordinatorTest` 先以纯 JVM `RecordingPipeline`
  （实现 `ReaderImagePipeline`）新增 3 用例 —— tiled 可见节点经契约路由（pageId+映射区域）、
  非 tiled 资产不请求、零尺寸/未保留节点跳过；编译红（接口缺方法 + 参数要具体类型）后实现转绿。
  该协调器的 `coordinateVisibleTiles` 首次获得 JVM 级测试（此前依赖具体 adapter 无法在 JVM 构造）。
- 验证：`:app:testDebugUnitTest` 全绿；`compileDebugKotlin` / `UnitTest` / `AndroidTest` /
  `Benchmark` / `:reader-core:test`（143）全过；真机 ecd4369c：semantics 1/1、recovery 1/1、
  gesture 10/11（唯一失败为 HEAD 同败既有设备问题）。
- 关联：§8.1（契约区域信息）、§8.3（scene-image / scene-compose 拆分的前置反转）。

#### 交付 8 — 阶段 A：场景 1（普通单页）A/B 交错基线（§4.1 首项）

目的：验证 7 项交付（范围查询、planner 契约、`:reader-core` 抽取、两处反转等）对普通章节
翻页性能「无明显回归」（§6.1 验收 4），并建立 §4.1 固定条件记录流程。

固定条件（§4.1 全项）：
- A = HEAD `48083e0b4`（干净树，APK sha256 `b684edd1…`）；B = 工作树 7 项交付
  （APK sha256 `332d461f…`）。每轮安装前核对 sha256、安装后核对 `dumpsys` lastUpdateTime。
- 设备 Redmi M332BF（warsaw），Android 17（SDK 37，CP2A.260605.016，OS4.0.0.9），
  1280×2772 @ 60Hz 固定模式（presDeadline 16.7ms），电池 75% 充电中。
- 温度趋势 33.8→36.8°C（电池温度）；A 轮 35.8–36.2，B 轮 36.4–36.8（B 平均略暖，已记录）。
- CompilationMode.Full，5 迭代/轮，A/B 交错 ×3 轮（顺序 A1 B1 A2 B2 A3 B3）。
- 场景：`pagedSingleSceneFull`（普通单页 1×，fixture `paged`，scene_paged 后端）。

结果（各侧 3 轮中位数）：

| 指标 | A (HEAD) | B (工作树) | Δ |
| --- | --- | --- | --- |
| frameCount | 620 | 625 | 旅程一致 |
| CPU P50 (ms) | 2.5 | 2.5 | 0 |
| CPU P90 (ms) | 4.2 | 4.4 | +0.2 |
| CPU P95 (ms) | 4.6 | 4.8 | +0.2 |
| CPU P99 (ms) | 7.0 | 7.4 | +0.4 |
| overrun P50 (ms) | -10.3 | -10.3 | 0 |
| overrun P95 (ms) | -8.1 | -7.9 | +0.2 |
| overrun P99 (ms) | -5.6 | -5.5 | +0.1 |
| RssAnon Max (KB) | 228,532 | 228,568 | +36（≈0） |
| RssAnon Last (KB) | 175,156 | 176,664 | +1.5MB |
| HeapSize Max (KB) | 125,188 | 124,881 | -0.3MB |
| Gpu Max (KB) | 86,776 | 86,776 | 0 |

判读（按 §4.2 常规阅读组要求「P99 overrun ≤ 0」）：
- **门禁通过**：B 侧 P99 overrun 全轮 ≤ -4.9ms（6 轮全部为负），远在 0 之下。
- **记录在案的小幅上移**：B 侧 P90/P95 一致 +0.2ms、P99 +0.4ms；P99 增量与 A 侧自身
  轮间散布（6.7→7.3，0.6ms）同量级，不能仅凭此判为回归；候选归因：R8/dex 布局因模块
  抽取改变（局部性）、B 轮温度略高。若后续轮次复现同向偏移，再从 trace 定位。
- 超时帧比例：P99 overrun ≤ -4.9 → 比例上界 <1%（§4.3 完整比例需 trace 提取，
  迭代 trace 每轮被覆盖，仅末轮 5 份留存；后续按需保存）。
- 内存、GPU、frameCount 均等 —— 无泄漏迹象。

基线建立：A 侧三轮（P50 2.5 / P95 4.6 / P99 7.0 / overrun P99 -5.6）即「改动前」锚点，
供后续轮次（阶段 A 其余场景、阶段 D 实验前对照）引用。

方法论教训（后续 benchmark 轮次必读）：
1. **陈旧 APK 陷阱**：`:macrobenchmark:connectedDebugAndroidTest` 的 gradle 任务配对的是
   app 的 **debug** 变体（装到 `.debug`），而 `TARGET_PACKAGE=org.skepsun.kototoro` 实际
   测的是设备上已装的包 —— 首三轮测的是昨天装的旧 APK（versionCode 1227），数据全部作废
   重测。**必须**：每侧显式 `:app:assembleBenchmark` → `adb install -r -t` → 核对
   lastUpdateTime 变化。本记录中所有数字均来自修正后流程（每轮 sha + lastUpdateTime 已核）。
2. **perfetto trace 权限**：MIUI 上 daemon 以 root:600 写
   `/data/misc/perfetto-traces/trace_output.pb`，benchmark 库（shell 身份）读不到 →
   `IllegalStateException: Cannot check size`。解决：root 看护循环
   `su -c 'while true; do chmod a+r …; sleep 0.3; done'` 在跑分期间放开读权限。
3. 工作树切换用 `git stash push -u`；发现并清除了一处残留：reader-core/src/test 里
   `WebtoonViewportPolicyParityTest` 的杂散副本（依赖 app 代码，无法在纯 JVM 模块编译；
   canonical 副本在 app/src/test）。

#### 交付 9 — 阶段 A：场景 2/3/4 基线（§4.2 分页矩阵补全）

条件：commit `7db11a1d5`（benchmark 变体 APK sha `332d461f…`，安装核对）；同设备同显示配置；
CompilationMode.Full × 5 迭代/场景；场景按序运行，电池温度 36.8→38.6°C 逐场景爬升（zoom
阶梯最热，记录在案）；每场景 5 份迭代 trace 仅末场景（zoomed 2.5×）留存并已归档。

各场景汇总（CPU/overrun 为五轮合并样本的分位数，非各轮 P99 中位数；
内存及计数为各轮结果的中位数，RssAnon 单位 KB；交付 10 已核对 2.5× 原始 JSON）：

| 场景 | 后端 | CPU P99 (ms) | overrun P99 (ms) | RssAnon Max | tile 解码/驻留 | 判定 |
| --- | --- | --- | --- | --- | --- | --- |
| 2 大图 1× (6000×9000) | scene | 7.9 | **-4.9** | 248,192 | 0 / 0 | 通过（常规门禁） |
| 2 大图 1× | legacy | 5.1 | -7.5 | 447,628 | – | scene 内存仅 legacy 55% |
| 4 双页往返 | scene | 7.8 | **-4.8** | 232,924 | 0 / 0 | 通过 |
| 4 双页往返 | legacy | 4.9 | -7.8 | 269,900 | – | legacy CPU 尾更低但内存 +37MB |
| 3 zoom 1.5× | scene | 7.7 | **-4.7** | 446,968 | 0 / 0 | 通过（常用倍率组） |
| 3 zoom 2.0× | scene | 8.6 | **+9.1** | 571,876 | 392 / 90 | 待验收（见下） |
| 3 zoom 2.5×(fit-height,开1×) | scene | 6.5 | -5.7 | 433,608 | 0 / 0 | 通过 |
| 3 zoom 2.5×(开即2.5×) | scene | 7.7 | **+13.1** | 577,372 | 392 / 80 | 待验收（见下） |

发现与判读：

1. **zoom 成本悬崖实测定位**：1.5×（无 tile 活动，-4.7ms）→ 2.0×（tile 激活：392 次解码
   请求、90 驻留，+9.1ms）之间。打开即 2.5× 的 +13.1 是本矩阵最高 **P99**，不是最差单帧。
   原先根据低 CPU P99 推断「CPU 之外的上传／栅格化主导」证据不足：交付 10 发现
   2.5× 实际最大 overrun +752.860ms，主线程睡眠等待主导长尾，详见后文。
   更热的 held 场景未激活 tile，支持路径相关的嫌疑，但并非隔离温度变量的对照实验。
2. **高倍率组无 SLO（§4.2 明确约束）**：+9.1/+13.1 记为待验收数据，不宣布通过也不豁免。
   制定 SLO 需要超时帧比例与最大连续超时（trace 提取，见下）。
3. **内存证据**：zoom 场景 RssAnon Max 545-577MB、Last 383-427MB —— 结束值比峰值低
   150-195MB；Max→Last 回落支持资源释放，不能单独证明 tile 预算驱逐。
   TileEvictions 全场景为 0，需区分预算驱逐和 `releasePage` 等释放路径。
4. **scene vs legacy 旅程不完全等帧**（双页 1045 vs 505 帧、大图 606 vs 522 帧）：
   CPU 分位直接横比有偏，内存与门禁判定不受影响；已按各自原始数字记录。
5. **场景 2 内存优势**：scene 大图 1× 用 248MB vs legacy 448MB —— LOD/概览路径的
   实测收益（-45%）。

后续（阶段 A 收尾清单）：
- 超时帧比例 + 最大连续超时：**已完成** —— 2.5× 见交付 10，其余 6 个场景见交付 12
  （7 场景 × 5 迭代 trace 全部归档并逐场景核对）；历史 `/tmp/reader-bench/scenarios/zoomed2_5-traces/`
  与 `/tmp/reader-bench/fix-ab/` 为本机产物，交付 12 的归档在
  `E:\kototoro_demo\reader-bench\stageA-closure-20260920\`。
- §4.2 长章节组（50/500/5000 页元数据，固定窗口查询耗时/分配）**仍无 fixture 与
  benchmark 方法**（现有 FIXTURE_MODE：standard/ultra_long/paged/paged_large）——
  需新增夹具与测量方法，独立一轮设计。
- webtoon burst/sustained 套件重跑（可选，历史数据在 closure plan）。

#### 交付 10 — 阶段 A：2.5× 超时帧证据（§4.3）

安装用户级 Perfetto `trace_processor` v58.2，并以 AndroidX Macrobenchmark 1.5.0 的取帧规则
复核留存的 5 份 trace。帧数及全部 CPU/overrun 样本与同次 benchmark JSON 完全一致。
复现脚本：`scripts/analyze_reader_frames.py`；完整逐轮表、最差帧定位、哈希与命令见
[2.5× trace 分析报告](reader-scene-zoom-trace-analysis-2026-09.md)。

- 合并 **3,163 帧，41 帧超时（1.296%）**；各轮比例 **1.133%–1.575%**。
- 每轮最大连续超时 **2 帧**；合并 P99 **+13.060ms**，真正最大 overrun **+752.860ms**。
- 每轮 3 个、共 15 个 UI 长帧，主线程 `doFrame` **127.198–764.516ms**。
  最差帧主线程睡眠 **724–753ms**，实际 running **10.6–14.5ms**，RT 仅 **7.5–9.2ms**。
  连续 2 帧不能解释为仅卡顿约 33ms；低 CPU P99 也不能排除稀疏主线程长阻塞。
- 首要候选：`closeSession()` 沿用宿主主线程 scope，同步 `close()` 写锁等待后台 region decode
  读者排空。trace 可见后台 `BitmapRegionDecoder` monitor 竞争，但无主线程等待调用栈；
  **候选因果需定点 trace／单变量 A/B 验证**，本轮不修改渲染或资源实现。
- iter 1、4 各有一次 packet-loss 标记；iter 0、2、3 同样复现长等待且无该标记。
  全部超时位于 measureBlock 内；工具成功复核不等于采集无缺失或场景通过 SLO。

结论：本轮补全该压力场景的超时帧证据；优先验证主线程关闭 decoder 的阻塞候选。
高倍率维持待验收；其余场景 trace、长章节组和阶段 D 仍需独立证据。

#### 交付 11 — zoom 长卡顿根因修复：session 关闭移出主线程

来源：交付 10 的 trace 证据链 + [zoom trace 分析](reader-scene-zoom-trace-analysis-2026-09.md)
首选候选（主线程 `closeSession` 等待读者排空写锁）经单变量 A/B 验证成立。

改动：
- `ReaderTileManager.closeSession()`：关闭协程改跑 `decodeDispatcher`（不再落在宿主
  Main 线程），`withContext(NonCancellable)` 包裹 `await()+close()` —— scope 中途死亡时
  关闭仍完成（优于现状：同场景下现状直接泄漏 decoder）。
- 窄 trace section：`Reader.TileSessionClose`（关闭协程整段）与
  `Reader.SessionCloseWriteLock`（写锁获取 = 读者排空等待）。
- TDD：`ReaderTileManagerTest` 新增先红后绿的调度测试 —— `releasePage` 的 session
  close 必须发生在 decode dispatcher 线程（红：`Test worker @coroutine#45`，
  绿：`close-dispatcher`）。

验证（A/B 交错，zoomed 2.5× 两轮 + 2.0× 与普通页各一轮，各侧独立 APK、
安装核对、每轮归档 trace+JSON 并用交付 10 脚本核实）：

| 指标（zoomed 2.5× 合并） | A 基线 `332d461f` | B 修复 `a9221207` |
| --- | ---: | ---: |
| 最大单帧 overrun | +729.254ms（各轮 710–729ms） | **+50.518ms（-93%）** |
| overrun P99 | +11.577ms | +3.116ms |
| 超时帧比例 | 1.257% | 1.628% |
| 总帧数 | 3,183 | 3,931（+23%） |
| 普通单页对照 overrun P99 | -5.1ms | -5.0ms（无回归） |

判读：700ms 级主线程长帧消失，关闭路径因果成立。超时比例 1.26%→1.63% 伴随帧数
+23%：被长停顿吞掉的 vsync 恢复，剩余超时为 tile 到达的普通尾延迟（P99 +3.1ms、
最大 50ms），不再由关闭路径主导。`:app:testDebugUnitTest` 全绿。

高倍率组仍**待验收**：残差尾延迟的 SLO 制定与可能的 tile 到达调度优化留待后续轮次。

#### 交付 12 — 阶段 A 收尾：全场景超时帧证据归档 + 高倍率组 SLO 制定

- 目的：补全 §4.2 各组的超时帧证据（此前只有交付 10 的 2.5× 一批），并按 §4.2 的前置要求
  在测量任何优化收益之前固定 SLO 数值（已写入 §4.2.1）。
- 代码改动：**无** —— 本轮只采集证据并制定门禁，工作树为干净 `67a243fc6`。
- 设备/构建身份：HEAD `67a243fc6`；`:app:assembleBenchmark` 产物
  `app-arm64-v8a-benchmark.apk` sha256 `35b2746a32631f312f4d6d1bad73d0f56216e581f12cdecd5205cc97a2d8f773`
  （versionCode 1227 / versionName 2.1.3 / applicationId `org.skepsun.kototoro`）；
  `adb install -r -t` 后 `lastUpdateTime` 17:02:37 → 18:13:27（设备时钟核对），
  并按真机验证方法第 4 条把**设备上已安装的包拉回校验**：`pm path` → `su -c cp` → `adb pull`
  后 sha256 与构建产物**逐字节相同**（同 `35b2746a…`）。
  设备 M332BF（warsaw）、Android 17（SDK 37，CP2A.260605.016）、1280×2772、`CompilationMode.Full`、
  5 迭代/场景、`pagedTurns` 旅程；trace 计数器 `Reader.ActualRefreshRateHz` 记录 60 → 120，
  expected 预算固定 13.6666ms。
- 采集与提取：7 场景各 5 份 trace **全部归档**（35 份）+ 各场景 JSON，根目录
  `E:\kototoro_demo\reader-bench\stageA-closure-20260920\<场景>\`；提取用仓库
  `scripts/analyze_reader_frames.py` + Perfetto v58.2（Windows prebuilt），每场景输出
  「Verified all original samples」。逐 trace SHA-256、JSON SHA-256、逐帧 CSV、最差帧探针位于
  各自 `verified/report.json`、`verified/frames-*.csv`；补充探针与汇总脚本在同目录
  （`sql/scenario_probes.sql`、`summarize_campaign.py`、`extract_worst_frames.py`）。
  归档属本机产物（同交付 10 的 `/tmp` 定位），仓库保存数字、流程与脚本。
- 结果：7 个场景全部落在 §4.2.1 的新 SLO 内（基线表见该节）。
- 与历史比对（跨轮复现性）：单页 overrun P99 −5.49 / CPU P99 7.08（交付 8 A/B：−5.6/−5.5、7.0/7.4）；
  大图 1× P99 −5.36、RssAnon 242MB（交付 9：−4.9、248MB）；双页 −5.36、229MB（交付 9：−4.8、232MB）；
  1.5× −4.48、436MB（交付 9：−4.7、447MB）；2.5× 开即 1.632% / P99 +3.156ms（交付 11 B 侧：
  1.628% / +3.116ms）。2.0× 本轮 P99 +5.70；交付 9 曾记录 +9.1，该轮在 36.8→38.6°C 的更热状态下按序运行，
  两轮不构成同条件对照。
- 残差归因（关闭路径修复之后，2.5× 开即与 2.0×）：
  - `Reader.TileSessionClose` 共 70 / 61 次，**全部不在主线程**（`tile_session_close_on_ui = 0`）；
    `Reader.SessionCloseWriteLock` 与之同量（最大单次 710.7 / 308.9ms，off-UI）。
  - 最差帧两类：① 主线程 `postAndWait` 主导 —— UI 墙钟 18.6–27.8ms，其中 sleeping 8.4–26.9ms、
    running 0.2–13.0ms；② RenderThread 纹理上传主导 —— `rt_upload` 23.3–28.4ms 而同一帧 UI 仅 1.6–2.0ms。
  - 后台 `monitor contention*BitmapRegionDecoder*` 每轮 1,191–1,252 次、跨线程累计 410–436s、
    最大单次 1.08–1.35s：解码串行是 tile 到达延迟的来源，但 off-UI，不直接等于掉帧。
  - 2.5× held 场景**无 tile 解码**（解码/驻留/驱逐 0/0/0）却仍有 13 次超时、连续 3 帧 ——
    该场景尾部来自 LOD/上传路径而非 tile 解码。
  - 全部 7 场景 `ui_frames_over_50ms = 0`，最差主线程帧 11.2–28.6ms；交付 10 的 723–753ms
    主线程睡眠类未复现。
- 失败与偏差记录：
  - 2.0× 首轮失败：`IllegalArgumentException: Expected ~120Hz display refresh rate, actual=60.000004 Hz`
    —— MIUI 对窗口 high-refresh 请求的应用有延迟，不是代码问题。处理：运行前预热并校验
    （启动 benchmark activity → 读 `mActiveSfDisplayMode.peakRefreshRate`，<119 则重试），
    随后 2.0× 与 2.5× 开即均记录 `refresh_precondition_hz=120.00001`。
  - 每场景开始前冷却至 ≤35.0°C（上限 480s；本机稳态约 35.2–35.9°C，触顶后按上限放行），
    运行前后温度逐场景留档（31.1–37.5°C）。2.0× 重跑为 35.5→37.5°C，略暖于其余场景，已记录。
  - 双页第 3 迭代 731 帧（其余 1,040–1,050）：脚本已与 JSON 核对一致，属该轮采集长度差异，
    不影响合并口径。
- 关联：§4.2（SLO 前置）、§4.3（超时帧与最差帧证据）、交付 10/11（关闭路径因果）。
- 已知限制/下一步：残差归因目前是相关性证据（无单变量 A/B）——「tile 到达/上传调度优化」
  应作为独立轮次，用 §4.2.1 的退化容忍度做 A/B；§4.2 长章节组仍无夹具；CS-7 的阈值脚本尚未落地，
  但 §4.2.1 的口径可直接作为脚本输入；本轮未改动任何默认开关，不构成 promotion 决定。

#### 未启动

- 阶段 D（retained GraphicsLayer PoC）—— 计划中唯一完全未启动的阶段。
- §4.2 长章节组夹具与测量方法（需新增 FIXTURE_MODE 与 benchmark 方法）。
- 高倍率残差归因的进一步实验：tile 到达/上传调度优化（用 §4.2.1 容忍度做 A/B）。
- §5.1 回归矩阵其余项、TalkBack 真机记录与 DPAD/键盘焦点（阶段 B 余项）。
- §8.3 后续模块轮次（scene-image → scene-compose → kototoro-reader-adapter）与 Phase C 宿主 API 重构。
- §9 promotion 检查表 —— 按计划要求在具备独立证据后另行交付。

（已交付项对应的清单项在此移除：`§8.2 :reader-core` 抽取见交付 6，阶段 A 基线固定与结果记录
见交付 8/9/12，SLO 制定见 §4.2.1。）

## 11. 官方依据

- [Compose 图形修饰符](https://developer.android.com/develop/ui/compose/graphics/draw/modifiers)：图层可重放绘制指令并独立应用变换；不承诺本项目的具体收益。
- [Macrobenchmark 指标](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-metrics)：区分 CPU 帧耗时与 deadline overrun。
- [Compose Semantics](https://developer.android.com/develop/ui/compose/testing/semantics)：自定义 UI 的语义与测试基础。
- [二维滚动](https://developer.android.com/develop/ui/compose/touch-input/scroll/two-dimensional-scrolling)：二维输入、fling 与已消费 delta 的契约。
- [Adaptive refresh rate](https://developer.android.com/develop/ui/views/animations/adaptive-refresh-rate)：Compose 帧率偏好与平台速度上报能力。
