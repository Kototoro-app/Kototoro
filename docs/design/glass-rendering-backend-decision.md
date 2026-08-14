# 玻璃渲染后端决策：Backdrop vs Cloudy（2026-08-14）

## 决策

**保持 `io.github.kyant0:backdrop` 作为 iOS Glass 的唯一渲染器，不引入 Cloudy。**
Cloudy 作为"低版本设备模糊降级后端"的候选被记录，但需要同时满足 §4 的触发条件才会引入，
且只启用其 backdrop blur 能力，不启用其 Liquid Glass。

## 背景

2026-08-14 评审玻璃渲染方案时，评估了 Cloudy（https://github.com/skydoves/Cloudy）作为
Backdrop 替代或补充的可能性，并对照了第三方智能体的两库比较。以下事实均经源码 / Maven 元数据
逐一核实，核实时间 2026-08-14。

## 已核实事实

### Backdrop（现用）

| 项 | 事实 |
| :--- | :--- |
| 版本 | `2.0.0` stable（2026-05-28）；当前项目钉 `2.0.0-alpha03` |
| 依赖门槛 | AAR 无 minCompileSdk 硬门槛；kotlin-stdlib 2.3.21、compose 1.11.0（Kototoro 现状满足） |
| 运行门槛 | blur 需 Android 12+；lens/RuntimeShader 需 Android 13+；低于门槛退 Stable Surface |
| 能力 | 图形级 pipeline：Backdrop → colorFilter/blur/lens → Highlight/Shadow → composition；lens（SDF 折射 + 可选色散 + depthEffect）为一等公民 |
| 缺口 | Highlight/Shadow 为静态默认值；无内容感知阴影、边缘消隐、tint 自适应、frostier；无 API≤30 的模糊路径 |
| Kototoro 现状 | 已建 `GlassSurface`、root host（`LiquidGlassBackdropHost`）、25+ 调用点、AMOLED 守卫与降级策略；规范文档以其 API 事实为准 |

### Cloudy

| 项 | 事实 |
| :--- | :--- |
| 版本 | 最新 `1.0.0-alpha01`（2026-07-17），**无 stable 1.0**；0.6.1→0.7.0→0.7.1→1.0.0-alpha01 相隔数天到两周，API 高速变动期；1.0.0-alpha01 将 mirage+blur 合并到单一 EffectNode spine（内部大重构） |
| 依赖门槛 | AAR `minCompileSdk=33`（0.7.1 实测），Kototoro compileSdk 36 可消费；minSdk 23 |
| 构建基线 | AGP 9.2.1 / Kotlin 2.4.0 / compileSdk 37 / compose 1.11.4（发布方工具链，不构成消费门槛） |
| 能力 | `Modifier.cloudy`（self blur）、`Modifier.cloudy(sky=…)`（backdrop blur）、`Modifier.liquidGlass`（SDF 法线折射 + 色散 + 静态/陀螺仪高光）、`Modifier.mirage`（typed shader）；无高层组件，符合"非 UI Kit"定位 |
| 降级矩阵（KDoc 原文） | self blur：31+ RenderEffect GPU；30- bitmap 捕获 + CPU blur。backdrop：33+ AGSL progressive；31-32 RenderEffect uniform；30- CPU 或 scrim |
| 工程细节 | legacy 路径有 ANR 缓解（120ms 捕获 debounce、5 次空捕获重试）；0.6.1 已修 backdrop 滚动空闲循环与 cyclic-RenderNode 崩溃；0.7.1 修 PixelCopy RenderThread 崩溃 |
| 状态 | 活跃维护（pushed 2026-07-17），Apache-2.0，约 1.2k stars |

### 对第三方比较的修正

第三方比较总体准确（降级矩阵、四套 effect、source/overlay 模型、维护状态均核实无误），
但有三处需要修正或补充：

1. **"两者都还在维护"未点出版本成熟度差**：Backdrop 有 2.0.0 stable；Cloudy 停在
   `1.0.0-alpha01` 且正经历内部管线重构（EffectNode spine 合并）。对已把玻璃层写进设计规范的
   项目，绑定 alpha 库意味着每两周一次的 API 追随成本。
2. **"Cloudy ≤30 用 Native C++ CPU blur（NEON/SIMD）"与 0.7.1 源码不符**：0.7.1 的 legacy
   路径是 Kotlin 实现的 RenderScript Toolkit 风格 CPU blur（iterative passes + 降采样 ramp），
   源码树中无 .cpp。CPU 路径与 ANR 缓解确实存在，结论不受影响，但实现方式需更正。
3. **"按控件分库（FloatingNav 用 Cloudy、FAB 用 Backdrop）"不可采纳**：同一屏幕混用两套
   渲染器会让折射/色差/tint/圆角连续性特征混排，直接违反 [ios-glass.md](./ios-glass.md) §2.3
   的"同一屏幕不得混用 Regular 与 Clear"与 glass-on-glass 精神。后端只能按能力（frosted vs
   liquid）在 `GlassSurface` 内部分层，不能按控件硬编码。

## 否决 Cloudy 替代的理由

1. **版本风险不对称**：以 alpha 库替换 stable 库，且当前 Cloudy 正值内部大重构；
2. **切换成本 > 收益**：Kototoro 已围绕 Backdrop 建立宿主架构、25+ 调用点与规范文档；
   Cloudy 的 backdrop blur 语义与 Backdrop 高度重叠，替换不带来视觉能力增量；
3. **Cloudy 的独特价值（API≤30 的 CPU blur/scrim）对 Kototoro 是锦上添花而非刚需**：
   iOS Glass 是可选风格，Material 路径无需模糊；Android 12 以下退 Stable Surface 是设计系统
   已接受的既定降级策略（[ios-glass.md](./ios-glass.md) §7）；
4. **APK 体积纪律**：`CONTRIBUTING.md` 禁止不必要依赖，双渲染器并存违背该原则。

## 引入 Cloudy 的触发条件（满足全部才评估）

1. 产品明确要求 iOS 风格在 Android 8–11（API 26–30）设备上保留模糊效果（而非 Stable 降级）；
2. Cloudy 发布 `1.0.0` stable；
3. 体积增量实测可接受，且确认与现有 Compose/Hilt/R8 链无冲突。

## 引入路径（若触发）

- Cloudy 仅作为 `GlassSurface` 的**低版本降级后端**（`Frosted` 风格），业务层零感知；
- 不启用其 `liquidGlass`/`mirage`，避免与 Backdrop 的折射特征在同一屏幕混排；
- 后端选择封装在 `GlassSurface` 内部，按设备 API + 风格参数决策；Backdrop 路径保持现状；
- 引入后同步更新本文件、[ios-glass.md](./ios-glass.md) §7 与
  [official-guidelines-mapping.md](./official-guidelines-mapping.md) 的库事实记录。

## 关联

- [iOS Glass 规范](./ios-glass.md)
- [官方依据对照表](./official-guidelines-mapping.md)
- [工具链大升级计划](../architecture/toolchain-upgrade-plan-2026-08.md)（backdrop 2.0.0 为 Phase C）
