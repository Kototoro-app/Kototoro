# Kototoro 视频播放器低端设备适配与运行时回退方案

> [!IMPORTANT]
> 本文关于“长期保留 mpv、不引入 Media3 播放内核”的决策已被
> [`media3-video-player-migration-plan-2026-08.md`](./media3-video-player-migration-plan-2026-08.md) 取代。
> 本文继续作为设备性能分级、会话级回退和播放诊断的历史设计依据；后续实现应将这些策略映射到
> Media3 直接输出与 Anime4K 绕过，而不是继续扩展 mpv 专用兼容层。

## 文档版本
- 创建日期：2026-03-31
- 最后更新：2026-03-31
- 状态：部分已落地
- 适用范围：`Kototoro/app/src/main/kotlin/org/Kototoro-app/Kototoro/video`

---

## 1. 背景

Kototoro 当前内置视频播放器主链路基于 `mpv`，而不是 `Media3` 的完整播放实现。

当前事实：
- 播放入口在 `VideoPlayerActivity.startMpvPlayback()`
- 视频超分通过 `mpv` 的 `glsl-shaders` 管线实现
- `Media3` 在当前实现中主要承担控制条 UI、缓存等辅助职责，不是第二套完整播放器内核

因此，“低端设备适配”的正确方向不是切换到另一个播放器内核，而是在现有 `mpv` 架构上引入：
- 低端设备判定
- 播放前初始降级
- 播放中运行时回退
- 用户可感知但不打断的提示

---

## 2. 目标

### 2.1 主要目标
- 降低低端设备、弱 GPU 设备、低端 TV 盒子上的黑屏、掉帧、首帧慢、播放失败问题
- 在保留 `mpv` 播放能力的前提下，避免视频超分默认拖垮播放链路
- 在异常发生后自动回退到更保守的播放配置
- 让用户知道系统做了兼容性回退，但不使用阻断式交互

### 2.2 非目标
- 不在本阶段引入第二套完整 `Media3` 播放器实现
- 不在本阶段做复杂机型白名单/黑名单云控
- 不默认把运行时回退永久写回全局设置

---

## 3. 当前实现与问题

### 3.1 当前可调参数
- 解码模式：`VideoDecoderMode`
  - `HARDWARE`
  - `SOFTWARE`
- 渲染器模式：`VideoRendererMode`
  - `AUTO`
  - `GPU`
  - `GPU_NEXT`
  - `MEDIACODEC_EMBED`
- 视频超分模式：`VideoSuperResolutionMode`
  - `OFF`
  - `QUALITY`
  - `BALANCED`
  - `PERFORMANCE`
  - `ADVANCED`

### 3.2 当前风险点
- 超分开启后会强制改走 `mediacodec-copy` + shader 管线，低端设备风险高
- 当前没有统一的设备性能分层
- 当前没有播放失败后的自动降级链路
- 当前没有“本次会话已回退”的状态管理
- 当前没有统一的轻提示机制告诉用户发生了什么

### 3.3 典型失败场景
- 低端机 / 低端盒子开启超分后黑屏
- `gpu-next` 在部分设备上表现不稳定
- `mediacodec-copy` 在特定厂商设备上兼容性差
- 用户主动开启超分，但设备无法稳定运行

---

## 4. 设计原则

### 4.1 KISS
- 只在现有 `mpv` 播放链路上加一层策略，不重做播放器架构
- 优先复用现有设置枚举和 `VideoPlayerActivity` 播放流程

### 4.2 YAGNI
- 先实现静态能力判断和简单运行时回退
- 暂不做复杂遥测、自适应学习、多维机型知识库

### 4.3 DRY
- 所有“实际生效播放参数”统一由一个策略对象输出
- 不在多个位置重复判断“低端机 / 是否关闭超分 / 是否降级渲染器”

### 4.4 可维护性
- 用户设置与“本次会话实际生效配置”分离
- 运行时回退默认只影响当前会话，不偷偷覆盖用户设置

---

## 5. 方案总览

建议新增四层能力：

1. `DevicePerformanceClassifier`
   - 负责判断设备性能档位

2. `VideoPlaybackPolicy`
   - 负责根据用户设置 + 设备档位生成“实际生效配置”

3. `PlaybackFallbackController`
   - 负责播放失败/异常后的降级链路

4. `PlaybackFallbackNotifier`
   - 负责 Snackbar 级别轻提示，并做会话级去重

### 5.1 当前落地状态

已落地：
- `DevicePerformanceClassifier`
- `VideoPlaybackPolicy`
- `PlaybackFallbackController`
- `VideoPlayerActivity` 中的会话级回退提示与配置 override
- 视频信息面板中的本地播放诊断聚合

尚未落地：
- 独立的 `PlaybackFallbackNotifier` 类
- 更精细的失败信号识别（当前主要基于启动超时）
- 回退统计与多次失败后的默认兼容模式推荐

---

## 6. 设备性能分级方案

### 6.1 为什么不能只看一个指标
Android 设备差异很大，仅靠 RAM、CPU 或 Android 版本都不可靠。最实用做法是多信号打分。

### 6.2 推荐信号
- `ActivityManager.isLowRamDevice`
- 总内存 `totalMem`
- CPU 核心数 `availableProcessors()`
- 是否 32 位进程 / 仅 32 位 ABI
- 是否 Android TV / TV 盒子设备

### 6.3 推荐档位

```kotlin
enum class DevicePerformanceTier {
    LOW,
    MID,
    HIGH,
}
```

### 6.4 推荐打分规则
- `isLowRamDevice == true`：`+3`
- RAM `<= 3GB`：`+2`
- CPU 核心数 `<= 4`：`+1`
- 32 位进程：`+1`
- TV 设备：`+1`

分档建议：
- `score >= 4`：`LOW`
- `score in 2..3`：`MID`
- `score <= 1`：`HIGH`

### 6.5 建议数据结构

```kotlin
data class DevicePerformanceInfo(
    val tier: DevicePerformanceTier,
    val score: Int,
    val totalRamMb: Long,
    val cpuCores: Int,
    val isLowRamDevice: Boolean,
    val isTv: Boolean,
    val is32Bit: Boolean,
)
```

---

## 7. 播放前初始策略

### 7.1 目标
在真正开始播放前，先用尽量保守但仍可接受的参数启动，降低首帧失败和超分拖垮播放的概率。

### 7.2 关键原则
- 用户设置是“期望值”
- 策略层输出“实际生效值”
- 低端设备优先关闭超分，而不是优先切软件解码

### 7.3 建议的实际生效配置

```kotlin
data class EffectiveVideoPlaybackConfig(
    val rendererMode: VideoRendererMode,
    val decoderMode: VideoDecoderMode,
    val superResolutionMode: VideoSuperResolutionMode,
    val allowShaderPipeline: Boolean,
)
```

### 7.4 分档默认策略

#### LOW
- `superResolutionMode = OFF`
- `rendererMode = MEDIACODEC_EMBED`
- `decoderMode = HARDWARE`
- `allowShaderPipeline = false`

#### MID
- `superResolutionMode = OFF` 或最多 `PERFORMANCE`
- `rendererMode = GPU`
- `decoderMode = HARDWARE`
- `allowShaderPipeline = false` 或仅在用户显式开启时允许

#### HIGH
- 保持用户选择
- `rendererMode = AUTO`
- 超分允许按用户设置启用

### 7.5 特别说明
- 不建议低端设备默认改 `SOFTWARE`
- 多数情况下，关闭超分和切更保守渲染器，比软解更合理

---

## 8. 运行时回退方案

### 8.1 目标
当播放已经开始但出现异常、首帧超时或兼容性问题时，自动降级当前会话参数，尽量保住播放。

### 8.2 回退触发条件

当前已实现的硬条件：
- 首帧超时
- `MPV_EVENT_END_FILE` 早于 `FILE_LOADED`
- 回退日志会附带最近一次 `mpv` 错误日志上下文（若可捕获）
- 明显的网络 / 源错误会跳过自动降级，避免误判为播放器兼容问题

后续建议补充：
- 播放失败 / load 失败
- 开启超分后黑屏或明显无法开始播放
- 更明确的 `mpv` 错误事件桥接

第二阶段可扩展：
- 持续掉帧
- 短时间内重复卡顿
- 特定 `mpv` 错误码或日志关键字

### 8.3 推荐回退顺序

#### 场景 A：已开启超分
1. 关闭超分
2. 保持硬解
3. 如仍失败，渲染器降级到 `MEDIACODEC_EMBED`

#### 场景 B：`gpu-next` 播放异常
1. `GPU_NEXT -> GPU`
2. 如仍失败：`GPU -> MEDIACODEC_EMBED`

#### 场景 C：`mediacodec-copy` 播放异常
1. 关闭 shader
2. 回退到普通硬解 `auto`
3. 必要时切 `MEDIACODEC_EMBED`

#### 场景 D：仍然失败
1. 保守模式重试一次
2. 仍失败则交给用户手动处理，不做无限回退

### 8.4 回退边界
- 每个播放会话限制最大回退次数
- 避免在多个模式之间来回震荡
- 同一媒体 URL 不要在短时间内重复执行相同回退

### 8.5 会话级状态

```kotlin
data class PlaybackFallbackSession(
    val attemptedFallbacks: MutableSet<String>,
    var hasShownSuperResHint: Boolean = false,
    var hasShownRendererHint: Boolean = false,
    var hasShownDecoderHint: Boolean = false,
)
```

---

## 9. 用户提示方案

### 9.1 是否需要提醒
需要，但不能使用阻断式弹窗。

原因：
- 用户主动开启的功能被系统临时关闭时，应给出解释
- 用户需要知道“为什么画质/渲染表现变了”
- 但播放恢复优先级高于解释，因此不应弹对话框打断

### 9.2 交互形式
- 使用 `Snackbar`
- 同一播放会话内对同类回退只提示一次
- 默认只影响当前会话，不自动写回全局设置

### 9.3 建议提示文案
- 超分关闭：
  - `已临时关闭视频超分，以提升播放稳定性`
- 渲染器降级：
  - `已切换到兼容渲染模式，以改善播放表现`
- 解码策略调整：
  - `已调整解码策略，以提升播放兼容性`
- 最终保守模式：
  - `当前设备性能或兼容性有限，已使用保守播放模式`

### 9.4 操作按钮
可附带 `设置` 按钮：
- 打开视频设置面板
- 或直接打开超分设置面板

### 9.5 提示触发原则
- 用户主动开启超分，系统又临时关闭：必须提示
- 自动降级渲染器：提示一次
- 同类回退重复发生：不重复提示

---

## 10. 建议新增的核心结构

### 10.1 设备分类器

建议位置：
- `video/performance/DevicePerformanceClassifier.kt`

职责：
- 读取系统信息
- 输出 `DevicePerformanceInfo`

### 10.2 播放策略

建议位置：
- `video/performance/VideoPlaybackPolicy.kt`

职责：
- 输入：用户设置 + 设备性能档位
- 输出：`EffectiveVideoPlaybackConfig`

### 10.3 回退控制器

建议位置：
- `video/performance/PlaybackFallbackController.kt`

职责：
- 维护回退顺序
- 判断某一步是否已尝试
- 输出下一步降级后的实际配置

### 10.4 提示器

建议位置：
- `video/performance/PlaybackFallbackNotifier.kt`

职责：
- 会话内去重
- 统一显示 Snackbar 文案

---

## 11. 与现有代码的接入点

### 11.1 `VideoPlayerActivity.startMpvPlayback()`
当前这里会直接读取：
- `videoRendererMode`
- `videoDecoderMode`

建议改为：
- 先通过 `VideoPlaybackPolicy` 生成 `EffectiveVideoPlaybackConfig`
- 再根据“实际生效配置”设置 `mpv`

### 11.2 `VideoPlayerActivity.applySuperResolutionFromSettings()`
当前这里会直接根据设置切 shader。

建议改为：
- 改成 `applyEffectiveSuperResolution(config)`
- 如果 `allowShaderPipeline == false`，则直接清 shader 并返回

### 11.3 播放异常处理点
建议新增统一入口，例如：
- `handlePlaybackFailure(reason: PlaybackFailureReason)`

由该入口驱动：
- 请求下一步回退配置
- 应用新配置
- 触发一次轻提示

### 11.4 设置页
本阶段不强制新增 UI，但建议预留：
- “低性能设备自动兼容模式”开关
- “播放兼容性提示”开关

---

## 12. 分阶段实施计划

### 阶段 1：最小可用版本
目标：先解决低端设备默认超分过重的问题。

当前状态：已完成

实施项：
- 新增 `DevicePerformanceClassifier`
- 新增 `VideoPlaybackPolicy`
- 低端设备默认关闭视频超分
- 低端设备默认改用 `MEDIACODEC_EMBED`
- 保留硬解，不默认切软解

验收标准：
- 低端设备默认不会进入 shader 超分链路
- 已有高端设备行为不明显退化

### 阶段 2：运行时回退
目标：在首次启动失败时自动救场。

当前状态：已完成最小版本

实施项：
- 新增 `PlaybackFallbackController`
- 增加首帧超时 / 播放失败回退
- 实现会话级回退去重

当前已实现：
- 启动超时后按固定顺序回退
- 当前会话 override 配置
- 同类回退只提示一次

尚未实现：
- 明确的播放失败 / `load` 失败信号接入
- 更细粒度的错误分类

验收标准：
- 同一会话内可以自动从激进配置回退到兼容配置
- 不会出现无限重试

### 阶段 3：用户提示
目标：让用户感知回退，但不打断播放。

当前状态：已完成最小版本

实施项：
- 新增 `PlaybackFallbackNotifier`
- Snackbar 提示
- 同类提示会话内只出现一次

当前已实现：
- `VideoPlayerActivity` 内联 Snackbar 提示
- `设置` 按钮直接打开视频设置面板
- 同类回退会话内只提示一次

后续可选优化：
- 将提示逻辑拆分到独立 `PlaybackFallbackNotifier`
- 支持关闭兼容性提示

验收标准：
- 用户能理解“为什么超分没生效 / 画面策略变化”
- 不会出现频繁弹提示

### 阶段 4：可选增强
目标：提升后续维护性。

实施项：
- 增加日志埋点
- 增加机型/平台特殊兼容表
- 增加“以后默认使用兼容模式”用户选项

---

## 13. 测试建议

### 13.1 设备维度
- 低端 Android 手机
- 中端 Android 手机
- 高端 Android 手机
- Android TV / 低端盒子

### 13.2 功能维度
- 普通播放
- 开启超分播放
- 超分切换模式
- 渲染器切换
- 播放失败自动回退
- 回退提示是否只弹一次

### 13.3 重点验证
- 低端设备启动成功率是否提升
- 首帧时间是否改善
- 黑屏概率是否下降
- 用户设置是否未被静默永久改写

---

## 14. 风险与取舍

### 14.1 风险
- 设备性能打分过于粗糙，可能误判
- 过度保守会让部分中端设备失去可用超分能力
- 回退链路处理不当可能导致逻辑复杂化

### 14.2 取舍
- 第一阶段宁可保守，也不要默认激进
- 优先保证“能播、稳定播”，其次才是“画质增强”

---

## 15. 最终建议

在 Kototoro 当前架构下，最佳路径是：

1. 保持 `mpv` 作为视频播放内核
2. 对低端设备默认关闭超分并采用更保守渲染模式
3. 在播放异常时自动执行有限次运行时回退
4. 用 Snackbar 做一次性轻提示
5. 不在本阶段引入第二套完整播放器实现

这是最符合当前工程现实、改动最小、收益最大的方案。
