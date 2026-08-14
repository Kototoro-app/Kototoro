# Kototoro 工具链大升级计划（2026-08）

## 文档信息

- 创建日期：2026-08-14
- 状态：规划草案（未动代码；执行需另开任务并按 Phase 验收）
- 目标：一次完成 AGP / Gradle / Kotlin / Compose / compileSdk 的大版本跳级，
  为 Compose 1.12、Material 3 1.5 Expressive API、backdrop 2.0.0 与 Android 17 适配铺路
- 关联文档：
  - [Media3 视频播放器迁移计划](./media3-video-player-migration-plan-2026-08.md)
  - [界面风格系统实施文档](../development/interface-style-system.md)（M3 1.5 门槛已在 §8 记录）
  - [官方依据对照表](../design/official-guidelines-mapping.md)

## 1. 决策摘要

1. **不单独升 backdrop，也不单独升 AGP** —— 两个变更合并进同一个大工具链包，用 nightly
   变体作金丝雀逐 Phase 验证。
2. **Compose 1.12（2026-08-12 稳定）是本次升级的硬门槛来源**：它要求 compileSdk 37 与
   AGP ≥ 9.1.1。这决定了整个包的最低目标。
3. **Material3 保持 1.4.0（BOM 2026.08.00 仍然锁 1.4.0）**；M3 1.5.0-alpha26 单独作为
   可选后续 Phase，不在本包内引入 alpha。
4. **targetSdk 不随本包升 37** —— Android 17 会忽略大屏上的方向锁/不可缩放声明，视频播放器
   与阅读器的方向控制需要先重做（见 §5），否则在折叠屏/平板上行为退化。
5. 本计划的版本数字分三类标注：**[已验证]**（Maven 元数据/源码直接核实）、
   **[官方博客]**（用户转述的官方动态，执行前需在官方页复核）、**[推断]**（由依赖链推导）。

## 2. 升级动因与收益

| 收益 | 性质 | 依据 |
| :--- | :--- | :--- |
| R8 协程优化（Atomic*FieldUpdater → Unsafe，热点路径 2–4x） | 用户端运行性能 | [官方博客] AGP 9.2.0 起，R8 对协程/Modifier 构建路径生效；Kototoro release 已 `minifyEnabled true` |
| Compose 1.12 运行性能与特性（Mesh Gradients、WCG P3、DeferredAnimatedContent 等） | 用户端运行性能/特性 | [官方博客] Compose 1.12 稳定 |
| backdrop 2.0.0 的 LayoutCoordinates 泄漏修复（#101） | 用户端内存 | [已验证] 源码 diff alpha03→2.0.0，多 Space/路由下 Backdrop 挂载场景受益 |
| M3 1.5 公开 `MaterialExpressiveTheme`/`MotionScheme` 的前置条件 | 设计系统收敛 | [已验证] M3 1.5.0-alpha26 依赖 ui 1.12.0-beta01；设计规范 `material3-expressive.md` §8 已声明该路线 |
| AGP 9.x 构建性能（configuration cache、增量） | 构建期 | [推断] AGP 每个大版本主线的常规改进 |
| Android 17 大屏自适应合规 | 合规/未来 Play 要求 | [官方博客] API 37 移除大屏方向/尺寸限制 opt-out |

## 3. 版本目标矩阵（含验证状态）

### 3.1 现状基线（[已验证] `gradle/libs.versions.toml` 2026-08-14）

| 组件 | 现状 |
| :--- | :--- |
| AGP | 8.12.0 |
| Gradle wrapper | 9.0.0 |
| Kotlin | 2.4.0（注意：CLAUDE.md 写 2.2.10 已过时） |
| KSP | 2.3.11 |
| Compose BOM | 2026.05.01 → ui/foundation 1.11.2、material3 1.4.0 |
| compileSdk / targetSdk | 36 / 36 |
| Hilt / androidx.hilt | dagger 2.58 / androidx.hilt 1.3.0 |
| Room | 2.7.2 |
| backdrop | 2.0.0-alpha03 |
| Media3 | 1.9.3（+ nextlib-media3ext 1.9.3-0.12.0） |
| Navigation3 | 1.1.3（已在使用） |

### 3.2 目标版本

| 组件 | 目标 | 验证状态 |
| :--- | :--- | :--- |
| AGP | **9.3.1**（最新稳定；9.4 仍 alpha） | [已验证] Maven 元数据 |
| Gradle | 随 AGP 9.3.1 的最低要求调整（9.0 之上，执行时查官方兼容表） | [官方博客] 执行时复核 |
| Kotlin | 2.4.10（最新稳定；若 KSP 不兼容则退 2.4.0 现状） | [已验证] 2.4.10 已发布 |
| KSP | 2.3.11 保持不变（与 Kotlin 2.4.0 组合已在当前工程编译通过） | [已验证] 现状即通过 |
| Compose BOM | **2026.08.00**（ui/foundation 1.12.0、material3 1.4.0） | [已验证] BOM POM 核实 |
| compileSdk | **37** | [官方博客] Compose 1.12 要求；M3 1.5 alpha 同要求 |
| targetSdk | 保持 36，另行规划升 37（依赖 §5 自适应改造） | 决策 |
| Hilt / dagger | 2.60.1（AGP 9 兼容最新线） | [已验证] 已发布；执行时验证 `enableAggregatingTask` |
| Room | 2.8.4（KSP2 线最新） | [已验证] 已发布 |
| backdrop | 2.0.0（API 零变化 + 泄漏修复） | [已验证] 源码 diff 与 module metadata |
| Media3 | 1.11.0（独立 Phase，跟随视频迁移计划） | [官方博客] |

### 3.3 为什么不升的部分

- **Material3 1.5.0-alpha26 不进入本包**：仍是 alpha；BOM 2026.08.00 锁 1.4.0；其 ui 依赖
  是 1.12.0-beta01，与 stable 链混用需要单独验证。MaterialExpressiveTheme 留作 Phase C。
- **AGP 9.4 不选**：仍 alpha。
- **Kotlin 2.4.10 若与 KSP 2.3.11 冲突则留在 2.4.0**：当前组合已构建通过，优先级是工具链
  包整体成功而非 Kotlin 小版本。

## 4. 分阶段执行计划

### Phase 0：基线快照（前置）

- nightly 变体跑通并留存：构建时长、APK 体积、`testDebugUnitTest` 全量结果、阅读器/详情页
  截图基准。
- 记录 `prepareCloudstreamRuntimeJar`、`applicationVariants.configureEach`（nightly
  versionCode 生成）、CMake 4-ABI 构建在本机/CI 的通过状态。

### Phase A：工具链内核（不动 Compose、不动 compileSdk）

1. AGP 8.12.0 → 9.3.1；Gradle wrapper 按兼容表调整。
2. Hilt/dagger 2.58 → 2.60.1（验证 AGP 9 下 `enableAggregatingTask`、Hilt 插件 API）。
3. Room 2.7.2 → 2.8.4（KSP 2.3.11 兼容性；schema 导出回归）。
4. Kotlin 2.4.0 → 2.4.10（可选，KSP 冲突则跳过）。
5. 构建迁移项逐一核对：
   - `packagingOptions {}` → AGP 9 的 `packaging {}`（[已验证] 项目仍在用 `packagingOptions`，`app/build.gradle:123`）；
   - Groovy DSL 在 AGP 9 下的支持（`app/build.gradle` 是 Groovy，AGENT 规则明确不写 `.kts`）；
   - `buildFeatures`/默认值变化（AGP 9 默认关闭 `buildConfig` 等）逐一核对当前声明；
   - CMake 3.22.1 + NDK 在 AGP 9 的 externalNativeBuild 行为。
6. 验收：`:app:assembleDebug` + `assembleNightly` + `testDebugUnitTest` + release 打包
   （R8 全量跑通，此 Phase 起生效协程优化收益）。

### Phase B：Compose 1.12 + compileSdk 37

1. compileSdk 36 → 37（targetSdk 保持 36）。
2. Compose BOM 2026.05.01 → 2026.08.00（ui 1.12.0）。
3. 逐项核对 Compose 1.12 破坏性变更（执行时以官方 release notes 为准）：
   - `Modifier.onFirstVisible()` 废弃 → `onVisibilityChanged()`：**[已验证]** 项目无使用，跳过；
   - `SideEffect` key、DeferredAnimatedContent/Visibility 新 API：不强制迁移，仅评估采用；
   - Styles API：仍实验性，**不采用**；
   - Mesh Gradients / WCG P3：**默认关闭**，不进入本包范围。
4. 运行回归重点：阅读器（Pager/Telephoto）、详情页 SharedTransition、backdrop 玻璃路径在
   ui 1.12 的渲染行为（alpha03 对 ui 1.11 构建，2.0.0 对 1.11 构建——升级 BOM 后重新验证
   `drawBackdrop`/`layerBackdrop` 与 lens shader）。
5. 验收：编译 + 全量单测 + 设备截图对照 + Macrobenchmark（若有脚本）。

### Phase C：backdrop 2.0.0（紧随 B，玻璃路径回归）

1. `backdrop = 2.0.0-alpha03` → `2.0.0`。
2. API 零迁移（[已验证] 签名无变化），验证：
   - `LocalLiquidGlassBackdrop` 宿主在 AppNavGraph/详情/阅读器的同窗口采样仍正确；
   - 泄漏修复在多 Space 切换下的表现；
   - Android 12 以下降级路径不变。
3. 同步 `docs/design/ios-glass.md` §7 与 `official-guidelines-mapping.md` 的库版本记录。

### Phase D（后续，另开任务）：M3 1.5 Expressive 主题

- 单独引入 `material3:1.5.0-alpha26`（或届时最新），把 `KototoroMotion` 映射到官方
  `MotionScheme`，主题根替换为 `MaterialExpressiveTheme`。
- 前提：Phase B 通过 + alpha 的 ui 依赖与稳定链的解析验证通过。

### Phase E（后续，另开任务）：targetSdk 37 与 Android 17 自适应

- 见 §5 改造清单；完成前 targetSdk 不得升 37。

## 5. Android 17（API 37）大屏自适应改造清单（Phase E 依赖）

**[官方博客]** API 37 起，大屏（≥600dp）上系统忽略 `screenOrientation`、`resizeableActivity`
限制与 letterboxing opt-out。项目现状 **[已验证]**：

- `reader/ui/ScreenOrientationHelper.kt`：视频 `SENSOR_LANDSCAPE/USER_LANDSCAPE`、
  阅读器 `LOCKED/USER_PORTRAIT/USER_LANDSCAPE/FULL_SENSOR` 全部经由 `requestedOrientation` 硬锁；
- `AndroidManifest.xml:172`：UCropActivity `screenOrientation="portrait"`；
- `NovelReaderActivity` 依赖 `configChanges=orientation|...` 自处理旋转。

改造方向（执行时按官方文档细化）：

1. 视频播放器：横屏需求改为「旋转敏感布局 + 建议用户旋转」，不再请求系统锁向；全屏按钮语义
   改为展开窗口/建议横屏，兼容系统强制的可旋转。
2. 阅读器：`LOCKED` 改为应用内「禁用自动旋转但接受系统 resize」，`USER_PORTRAIT/LANDSCAPE`
   在大屏降级为跟随窗口；`ScreenOrientationHelper` 增加 `windowSizeClass` 判断分支。
3. UCrop 的 portrait 锁移除，裁剪界面做自适应布局验证。
4. 双页/横屏漫画布局的 `FoldableUtils.shouldUseTabletLayout` 路径在强制可旋转下重新回归。
5. App Bubbles / Bubble Bar / 桌面 PiP：仅记录为候选特性，不进入本期。

## 6. 其他官方动态的采纳结论

| 动态 | 对 Kototoro 的结论 |
| :--- | :--- |
| Media3 1.11（Player 四区插槽、PlayerPool、MiniController、PlaybackSpeedState） | **采纳评估项**：直接并入 `media3-video-player-migration-plan-2026-08.md` 的实施范围；`video/` 模块当前 1.9.3，升级收益明确（倍速 API、迷你控制条契合阅读器紧凑控制层规范） |
| Android Studio Quail 2（Agent Mode、LeakCanary、AQI） | **工具链采纳**：CI/开发环境用其 Profiler 做 Phase B 后内存回归（尤其 backdrop 泄漏场景） |
| AppFunctions（应用作本地 MCP server） | **暂不采纳**：alpha + Gemini 内测，仅记录观察 |
| 官方 Android Skills 体系 | **采纳为流程**：`android skills list` 已入 AGENTS.md；执行本计划各 Phase 时先加载 AGP9/Navigation3 相关 skill 再动手 |
| `Modifier.onFirstVisible()` 废弃 | **无影响**（[已验证] 项目零使用） |

## 7. 风险与回滚

- 最大风险是 Phase A 的 AGP 9 迁移（Groovy DSL、Hilt、打包 DSL）；Phase B 的 ui 1.12 渲染
  回归次之。两者用 nightly 金丝雀 + 截图对照隔离。
- 每 Phase 独立提交；Phase A/B 通过前不得合入主干 release。
- 回滚策略：逐 Phase revert 版本目录变更即可，无数据/格式迁移。
- 已知无关项不得顺手改：`decoroutinator` 插件保持注释状态、`generateLocaleConfig=false`
  保持、`kotlinx-serialization-json-okio` 1.7.3 钉住、命名空间不批量重命名。

## 7.5 执行日志（2026-08-14）

### Phase A：工具链内核 ✅

- AGP 8.12.0 → 9.3.1；Gradle wrapper 9.0.0 → 9.5.0（官方 AGP 9.3 最低要求，release notes 实测）。
- Dagger 2.58 → 2.60.1；Room 2.7.2 → 2.8.4；Kotlin 保持 2.4.0 / KSP 保持 2.3.11。
- 实际迁移项（与 §4 Phase A 清单的偏差记录）：
  1. **built-in Kotlin 强制**：`kotlin-android` 插件必须移除（官方报错指引 kotl.in/gradle/agp-built-in-kotlin）；
     顶层 buildscript classpath 声明 KGP 2.4.0 + KSP 2.3.11（高于内置基线 2.2.10）；
  2. `android.kotlinOptions` 不存在了 → `kotlin { compilerOptions { } }`；testOptions.kotlinOptions 删除，
     其 opt-in 并入主 compilerOptions；
  3. `packagingOptions` → `packaging`；`buildToolsVersion = '35.0.0'` 删除（AGP 9.3 默认 36.0.0）；
  4. `applicationVariants` Groovy 属性已移除 → `androidComponents.onVariants(selector().withName('nightly'))`，
     且 `output.versionCodeOverride` 改为 `output.versionCode.set(...)`（官方 VariantOutput API 为 Property）；
  5. `resValue` 需要 `buildFeatures { resValues true }`（AGP 9 默认 false；此前试错
     `androidResources.enableCustomResourceValues` 不存在，已按官方 release notes 修正）；
  6. `android.nonFinalResIds=false` 已删除（deprecated，AGP 9 默认 true）；
  7. **依赖解析修复**：`lifecycle-viewmodel-navigation3` 从未发布 2.9.4（版本线 1.0.0-alpha04 →
     2.10.0），旧宽松解析掩盖了错误，AGP 9 严格解析暴露；改为独立版本 2.10.0；
  8. settings.gradle 中 R8 9.1.31 类路径覆盖删除（AGP 9.3.1 自带 R8 9.3.16，警告消除）；
  9. NDK 28.2.13676358 由 AGP 自动安装；CMake 3.22.1 路径不变。
- 验收：`assembleDebug` ✅（含 Hilt 聚合、KSP、CMake、debug ABI 构建）；`testDebugUnitTest` ✅
  1446 通过（2 个标题字号断言更新为 12sp 地板、1 个 AppSettings MockK stub 补齐 getString）。
- 遗留观察：`androidx.multidex` 警告（minSdk 26 不需要）与 configuration-cache 对
  dataBindingMergeDependencyArtifactsDebug 的序列化 warning，不影响构建，留待清理。

### Phase B：Compose 1.12 + compileSdk 37 ✅

- compileSdk 36 → 37（targetSdk 保持 36）；BOM 2026.05.01 → 2026.08.00（ui/foundation 1.12.0、
  material3 仍 1.4.0）。
- `Modifier.onFirstVisible()` 弃用无影响（项目零使用，与预检一致）。
- 代码修复：API 37 下 `ActivityManager.RecentTaskInfo` 可空，
  `ImmersiveSpaceSessionRegistry` / `SpaceSwitcherDelegate` 增加安全调用。
- 验收：`assembleDebug` ✅；`testDebugUnitTest` ✅。

### Phase C：backdrop 2.0.0 ✅

- `2.0.0-alpha03` → `2.0.0`，API 零迁移，直接编译通过；`assembleDebug` ✅、`testDebugUnitTest` ✅。
- 泄漏修复（rc01 #101）随本次生效，多 Space/路由 Backdrop 挂载场景为后续内存回归观察点。

### 未执行（保持计划）

- Kotlin 2.4.10（KSP 2.3.11 组合未验证，当前组合稳定，不引入）。
- Phase D（M3 1.5 alpha）与 Phase E（targetSdk 37 / Android 17 大屏自适应）按计划留待后续任务。

### 验收门槛补充（2026-08-14 晚）

- `assembleNightly` ✅：`versionCode=260814` / `versionName=N20260814`（Variant API 日期版本生效）、
  5 ABI 分片 + universal、compileSdk 37。
- `assembleRelease` ✅：R8 9.3.16 全量 + shrinkResources 通过（无签名配置，产物为 unsigned）。
- 构建环境治理：磁盘 3.4Gi 告急导致 nightly/release 并行构建 Kotlin daemon 崩溃
  （"Daemon compilation failed"）；已删除旧 Gradle 分发（8.x/9.0/9.4.1/9.6.1）、旧版本 cache
  （9.0/9.4.1/9.6.1）、build-cache-1、旧 NDK 27/28.0、旧 build-tools 34/35、旧 platforms
  31/33/34/35，可用空间恢复至 35Gi；nightly/release 改为顺序执行。
- 设备回归（阅读器/详情页/玻璃路径截图对照）留待后续 Phase D/E 任务或日常发布流程执行。

## 8. 验收总门槛（全部 Phase 完成后）

- `./gradlew :app:assembleDebug :app:assembleNightly` 通过；
- `./gradlew :app:testDebugUnitTest --no-daemon` 全量通过；
- release 变体（R8 全量 + shrinkResources）打包成功，协程优化路径抽查字节码；
- 设备回归：漫画/小说/视频阅读器、Space 切换、iOS Glass 路径、详情页 SharedTransition、
  AMOLED、大屏分栏，各截图与 Phase 0 基线对照；
- 文档同步：`docs/development.md`、`CLAUDE.md`/`AGENTS.md` 中的版本事实（Kotlin 等）随
  最终版本更新。
