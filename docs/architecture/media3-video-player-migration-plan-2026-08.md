# Kototoro Media3 视频播放器迁移计划

## 文档信息

- 创建日期：2026-08-11
- 状态：实施中（Media3 主链完成，画质增强与真实源矩阵待验收）
- 决策方向：以 AndroidX Media3 / ExoPlayer 替换 mpv，作为唯一内置视频播放内核
- 适用范围：`app/src/main/kotlin/org/skepsun/kototoro/video`
- 关联文档：[`video-playback-performance-plan.md`](./video-playback-performance-plan.md)

> 2026-08-11 实施记录：Media3 唯一播放内核、请求规范化、异常 HLS 代理复用、单一持久缓存、
> 轨道/字幕、设置迁移及 mpv 清理已落地，并通过编译、全量单元测试、APK 构建和安装启动。
> Anime4K/FSR 已建立 OES Surface、GLES 输出和会话级首帧回退骨架；完整 Anime4K hook 多 Pass 与
> AMD FSR 1.0 EASU+RCAS 尚未达到本文验收门槛。连接设备禁止 ADB 输入注入，真实源播放矩阵仍需
> 人工操作确认。因此当前状态不得视为计划全部完成。

## 1. 决策摘要

Kototoro 应将 Media3 / ExoPlayer 作为视频播放主内核，并在迁移验证完成后移除 mpv。

这不是单纯的播放器库替换，而是将播放行为重新对齐 Kototoro 主要视频扩展生态的原生宿主：

- Cloudstream 的 Android 宿主使用 ExoPlayer 语义消费 `ExtractorLink`、HLS、HTTP Header 与字幕；
- Aniyomi 的视频扩展同样以 Android/ExoPlayer 播放行为为主要兼容目标；
- Kototoro 当前使用 mpv/FFmpeg 后，需要额外弥合 HLS 探测、异常后缀、Header 传播、失败事件和备用分流切换等差异；
- 近期对 Xhamster、AnimePahe、18EU、3XChina 等源的设备日志验证表明，部分问题并非扩展解析失败，而是 mpv/FFmpeg 与原宿主播放栈行为不同。

Media3 已经是项目依赖，版本为 1.10.0，并且项目已有 `VideoCache`、Media3 HLS 和 OkHttp DataSource 依赖。因此本次迁移不需要引入新的第三方播放器依赖。

异常 HLS 本地代理不随 mpv 一起删除。它属于输入规范化层，应继续负责 `.json` 等非标准 HLS 后缀、伪装响应和异常分片，再将规范化后的本地 URL 交给 Media3。

## 2. 背景与问题陈述

### 2.1 当前播放架构

当前播放链路以 `VideoPlayerActivity` 为编排中心：

```text
Cloudstream / Aniyomi / 其他 ContentRepository
                    │
                    ▼
         VideoCandidate / Video 列表
                    │
                    ▼
     Header 合并、异常 HLS 判断、代理选择
                    │
                    ▼
              MpvPlayer / libmpv
                    │
                    ▼
       CustomMpvView + Compose 控制层
```

相关现有组件包括：

- `video/ui/VideoPlayerActivity.kt`
- `video/player/MpvPlayer.kt`
- `video/player/CustomMpvView.kt`
- `video/player/MpvPlaybackOptions.kt`
- `video/player/MpvShaderManager.kt`
- `video/ui/compose/VideoPlayerRenderLayer.kt`
- `video/data/VideoLocalCacheProxy.kt`
- `video/data/VideoCache.kt`
- `video/domain/VideoCandidateResolver.kt`

### 2.2 已观察到的兼容性成本

近期设备日志暴露了以下重复问题：

1. 标准 HLS 主列表在 mpv/FFmpeg 中会探测多个变体，任一轨道网络超时都可能显著拖慢首帧。
2. Cloudstream 返回成功并不代表 mpv 能按原宿主方式消费该链接。
3. `EVENT_END_FILE`、`EVENT_IDLE`、`EVENT_SHUTDOWN` 与实际加载失败之间需要额外状态修正。
4. Header、Referer、Cookie 需要在主请求、子播放列表和分片之间保持一致。
5. 为兼容非标准 HLS，播放器层逐渐混入代理、内容识别和响应改写逻辑。
6. mpv 的磁盘缓存与项目已有 Media3 `SimpleCache` 并存，增加设置、统计和清理复杂度。
7. Cloudstream/Aniyomi 原宿主的行为不能直接作为 Kototoro 的可靠基线，因为播放内核不同。

### 2.3 Xhamster 日志样本

一次 Xhamster HLS 播放中：

- `14:09:59.504`：mpv 开始加载；
- `14:10:01.814`：主播放列表完成；
- 随后依次读取 144p、240p、480p、720p 子列表和分片；
- 720p 分片连接 `video-nss.xhcdn.com:443` 卡满 `network-timeout=30`；
- `14:10:51.804`：文件加载完成；
- `14:10:57.461`：显示首帧。

总首帧时间约 58 秒。本地代理统计为 `hit=0 miss=0`，说明主要延迟发生在 mpv/FFmpeg 的 HLS 网络和轨道探测阶段，而不是 Kototoro 本地代理。

该样本不能证明所有 Media3 播放都必然更快，但足以证明继续围绕 FFmpeg HLS 行为打补丁不能稳定复现原宿主体验。

## 3. 目标与非目标

### 3.1 目标

- 让 Cloudstream 和 Aniyomi 链接尽量遵循其原宿主的播放语义。
- 以 Media3 统一 HLS、DASH、MP4、Header、轨道、缓存和错误事件。
- 缩短正常网络条件下的首帧时间，避免无必要的全变体探测。
- 保留异常 HLS 规范化能力，包括非 `.m3u8` 后缀和异常分片处理。
- 保留现有 Compose 播放器 UI、手势、进度、弹幕、分流和 DLNA 能力。
- 独立实现 Media3 + Anime4K 输出管线。
- 迁移完成后删除 mpv 依赖和 mpv 专用配置，避免长期维护双内核。
- 建立可重复的源兼容性回归矩阵和首帧诊断指标。

### 3.2 非目标

- 不重写 Cloudstream、Aniyomi 或其他插件解析器。
- 不用播放器兜底修复原宿主同样无法播放的失效链接。
- 不在迁移中新增远程机型黑名单或复杂自学习策略。
- 不直接复制 GPLv3 项目的实现代码。
- 不同时重做播放器 Compose 视觉设计。
- 不永久保留“Media3 + mpv 自动回退”双引擎架构。

## 4. 设计原则

### 4.1 原宿主语义优先

插件提供的 URL、MIME 类型、Header、字幕和分流顺序应尽可能按原宿主方式传入 Media3。只有已确认的输入格式异常才进入规范化代理。

### 4.2 输入规范化与播放内核分离

播放器不应知道 `.json` 响应如何改写，也不应通过反射访问代理内部的 OkHttpClient。规范化层返回一个可播放请求，播放内核只负责消费。

### 4.3 单一播放器抽象

UI 和业务编排依赖项目自有的播放器接口，而不是直接依赖 `ExoPlayer` 或 mpv 属性字符串。Media3 是唯一生产实现。

### 4.4 单一缓存所有者

媒体缓存统一由 Media3 `SimpleCache` 管理。异常 HLS 代理默认只做流式转换，不再额外维护一套与 Media3 重叠的持久分片缓存。

### 4.5 渐进迁移、最终收敛

实施期间允许短期保留 mpv 代码用于对照和回滚，但不能形成长期运行时双栈。达到验收门槛后必须删除旧实现。

## 5. 目标架构

```text
Cloudstream / Aniyomi / 其他 Repository
                    │
                    ▼
              VideoCandidate
                    │
                    ▼
       PlaybackRequestNormalizer
       ├─ 标准 URL：直接返回
       ├─ 非标准 HLS：本地动态代理
       ├─ Header/Cookie：标准化
       └─ MIME：显式标注
                    │
                    ▼
          Media3VideoPlayerEngine
       ├─ OkHttpDataSource
       ├─ CacheDataSource / SimpleCache
       ├─ DefaultMediaSourceFactory
       ├─ DefaultTrackSelector
       └─ LoadErrorHandlingPolicy
                    │
          ┌─────────┴─────────┐
          ▼                   ▼
    PlayerView 直接输出    Anime4K GL 输出
                          SurfaceTexture/OES
                                │
                                ▼
                         GLSurfaceView
```

## 6. 核心模型与边界

### 6.1 `PlaybackRequest`

新增播放器无关的不可变请求模型：

```kotlin
data class PlaybackRequest(
    val uri: Uri,
    val mimeType: String?,
    val headers: Map<String, String>,
    val subtitles: List<ExternalSubtitle>,
    val externalAudio: List<ExternalAudioTrack>,
    val startPositionMs: Long,
    val cacheKey: String?,
    val sourceIdentity: String?,
)
```

约束：

- `mimeType` 由扩展声明、URL 与响应识别共同决定；
- Cloudstream 声明为 HLS 时，即使 URI 后缀为 `.json`，最终请求也必须标注 HLS；
- `headers` 是该媒体会话的不可变快照，不依赖全局可变播放器属性；
- 日志不得输出 Cookie、签名 Token 或完整敏感 URL。

### 6.2 `VideoPlayerEngine`

建议定义小而稳定的接口：

```kotlin
interface VideoPlayerEngine {
    val state: StateFlow<VideoPlaybackState>
    val tracks: StateFlow<VideoTrackSnapshot>

    fun prepare(request: PlaybackRequest)
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun setPlaybackSpeed(speed: Float)
    fun setVolume(volume: Float)
    fun selectAudioTrack(id: String?)
    fun selectSubtitleTrack(id: String?)
    fun setVideoOutput(output: VideoOutput)
    fun release()
}
```

接口不应包含：

- mpv 属性名；
- Media3 `PlayerView`；
- Cloudstream 类型；
- 本地代理实现细节；
- Activity 或 Compose 状态。

### 6.3 播放状态

统一映射 Media3 状态：

| Kototoro 状态 | Media3 信号 |
|---|---|
| Idle | `Player.STATE_IDLE` |
| Preparing | 设置媒体后到 `STATE_READY` 前 |
| Buffering | `Player.STATE_BUFFERING` |
| Ready | `Player.STATE_READY` |
| Playing | `isPlaying == true` |
| Ended | `Player.STATE_ENDED` |
| Failed | `onPlayerError()` |

`VideoPlayerActivity` 不再根据底层日志字符串判断一次文件是否真正加载。分流回退以结构化错误和启动超时为输入。

## 7. Media3 播放链路设计

### 7.1 网络 DataSource

使用项目现有 OkHttpClient 构造 `OkHttpDataSource.Factory`，确保：

- 遵循应用 VPN/代理和 DNS 配置；
- 复用 Cookie、Cloudflare clearance 与现有网络策略；
- 每个媒体请求设置独立 Header；
- 主列表、子列表、密钥和分片继承同一请求属性；
- 允许按源要求设置 `Referer`、`Origin`、`User-Agent`、`Cookie`；
- 不通过全局 Header 状态污染下一条分流。

需要明确验证 OkHttp 拦截器是否适合长时间媒体分片请求。浏览器挑战解决器不得在每个媒体分片上启动 WebView。

### 7.2 MediaSource 选择

通过 `DefaultMediaSourceFactory` 处理标准输入，并在 MIME 已知时显式写入 `MediaItem.LocalConfiguration`：

- HLS：`MimeTypes.APPLICATION_M3U8`
- DASH：`MimeTypes.APPLICATION_MPD`
- 普通文件：由 Media3 推断或使用扩展提供的 MIME
- 本地代理 HLS：无论代理 URL 后缀如何，显式指定 HLS MIME

不依赖修改 URL 后缀来诱导播放器识别格式。

### 7.3 自适应轨道

使用 `DefaultTrackSelector`：

- 默认允许 Media3 自适应选择 HLS 清晰度；
- 用户选择“自动”时不固定具体轨道；
- 用户选择清晰度时使用 TrackSelectionOverride；
- 切换分流与切换同一主列表内的质量轨道必须在 UI 上明确区分；
- 不为首帧提前下载所有变体分片。

### 7.4 外挂字幕和音轨

- Cloudstream 字幕使用 `MediaItem.SubtitleConfiguration`；
- Aniyomi 外挂字幕同样映射为 SubtitleConfiguration；
- 分离音频根据来源能力使用 `MergingMediaSource`；
- 字幕样式优先由 Compose 字幕覆盖层统一呈现；若使用 Media3 `SubtitleView`，必须避免与现有字幕层重复显示；
- 保留系统语言自动选择、关闭字幕、手动选择记忆等现有行为。

### 7.5 缓存

复用并完善现有 `VideoCache`：

- `SimpleCache` 是唯一持久媒体缓存；
- 使用 `CacheDataSource.Factory` 包装上游 OkHttp DataSource；
- 根据规范化后的源 URL 与必要身份信息生成稳定 cache key；
- 签名参数会频繁变化的 CDN URL不能直接作为唯一缓存身份；
- 不缓存明确一次性、DRM 或敏感会话资源；
- 缓存大小设置、统计、清理入口统一指向 `video_cache`；
- 删除 mpv 的 `mpv_cache` 配置和清理逻辑前，提供一次旧缓存清理或迁移说明。

## 8. 异常 HLS 本地代理保留策略

### 8.1 继续代理的场景

- 扩展声明 `M3U8`，但 URL 后缀为 `.json` 或其他非标准形式；
- 返回体 Content-Type 错误，但内容实际是 HLS；
- 播放列表或分片使用图片类型伪装；
- 分片需要现有 PNG 包装剥离或响应改写；
- DLNA 需要将带 Header 的远程资源暴露为局域网可访问 URL。

### 8.2 不代理的场景

- 标准 `.m3u8` 且 Media3 可直接携带所需 Header；
- 标准 MP4/WebM 等直链；
- 仅为了预探测可用性；
- 仅为了重复实现 Media3 已有缓存；
- 原宿主也无法访问的 403、地区封锁或失效签名。

### 8.3 结构调整

将 `VideoPlayerActivity` 中的异常 HLS 判断和动态响应改写移出 Activity，形成：

```text
PlaybackRequestNormalizer
        │
        ├── DirectPlaybackRequest
        └── ProxiedPlaybackRequest
                 │
                 ▼
          VideoLocalCacheProxy
```

消除当前通过反射获取 `VideoLocalCacheProxy.okHttpClient` 的实现。代理所需依赖应通过明确构造参数或专用接口提供。

## 9. Anime4K 迁移方案

### 9.1 输出结构

关闭 Anime4K：

```text
ExoPlayer → PlayerView Surface
```

开启 Anime4K：

```text
ExoPlayer
   │ setVideoSurface
   ▼
SurfaceTexture（GL_TEXTURE_EXTERNAL_OES）
   │
   ▼
Anime4K 多 Pass Shader / FBO
   │
   ▼
GLSurfaceView 可见输出
```

### 9.2 建议组件

- `VideoOutputRouter`
  - 保证直接输出和 Anime4K 输出互斥；
  - 负责 Surface 切换、清理与重绑。
- `Anime4KInputSurface`
  - 在 GL 线程创建和释放 OES 纹理、SurfaceTexture 与 Surface。
- `Anime4KSurfaceView`
  - 承载 GLSurfaceView 生命周期和输入 Surface 回调。
- `Anime4KPipelineRenderer`
  - 加载现有 shader 链；
  - 管理 FBO、多 Pass 渲染和画面比例；
  - 只在帧可用时渲染。
- `Anime4KOutputPolicy`
  - 统一决定是否启用、绕过或回退。

### 9.3 必须处理的边界

- Anime4K 与 PlayerView 不能同时绑定同一播放器输出；
- 切换增强模式不能重建 MediaItem 或丢失进度；
- GL 输入 Surface 未准备好时保留直接输出；
- Anime4K 接管后规定时间内没有可见首帧，应回退直接输出；
- Activity 暂停、恢复、旋转、画中画和返回详情页时正确重绑；
- HDR、HLG、Dolby Vision 默认绕过 Anime4K，避免破坏色彩信息；
- 音频播放不依赖视频 Surface 生命周期；
- 低端设备策略继续生效，但从“mpv 渲染器降级”改为“关闭 Anime4K、直接 MediaCodec 输出”。

建议第一版首帧回退阈值为 3 秒，但计时起点必须是输入 Surface 已交给播放器且播放器进入可输出状态之后，不能把纯网络缓冲误判为 GL 故障。

### 9.4 BiliPai 参考边界

`../BiliPai` 已验证 Media3 + SurfaceTexture + OpenGL Anime4K 的可行性，并记录了 Surface 争用、重绑、首帧回退、HDR 绕过和画面比例等问题。

Kototoro 使用 Apache-2.0，BiliPai 使用 GPLv3。实施时只能参考其公开行为、问题结论和通用架构，不能直接复制 GPLv3 源码。Kototoro 应基于 Android、OpenGL ES 和 Media3 API 独立实现，并保留现有 Anime4K shader 的许可声明。

## 10. 现有功能迁移矩阵

| 现有能力 | Media3 对应实现 | 验收重点 |
|---|---|---|
| 播放/暂停 | `Player.play/pause` | 生命周期恢复一致 |
| 进度/时长 | `currentPosition/duration` | 未知时长和直播流 |
| 精确跳转 | `seekTo` + SeekParameters | 片头跳过和手势跳转 |
| 倍速 | `PlaybackParameters` | 音调与记忆设置 |
| 音量 | `Player.volume` | 与系统音量手势边界 |
| 画面比例 | `PlayerView.resizeMode` 或 GL 输出矩阵 | FIT/FILL/CROP 等模式 |
| 硬件解码 | MediaCodec renderer | 设备失败回退 |
| 软件解码 | 非默认能力 | 明确产品取舍，不伪造支持 |
| HLS 自适应 | Media3 HLS + TrackSelector | 首帧与切档稳定性 |
| 分流切换 | 重建 PlaybackRequest | 保留进度、避免旧回调污染 |
| 音轨选择 | TrackSelectionOverride | 内嵌和外挂音频 |
| 字幕选择 | SubtitleConfiguration + TrackSelector | 系统语言自动选择 |
| 字幕样式 | Compose 或 SubtitleView | 避免双字幕 |
| 缓存 | SimpleCache/CacheDataSource | 无双重缓存 |
| 播放错误 | PlaybackException | 分类、日志和分流回退 |
| 播放结束 | `STATE_ENDED` | 自动下一集和短广告判断 |
| 弹幕同步 | Media3 position/isPlaying | seek、暂停、倍速 |
| DLNA | 保留 URL/局域网代理输出 | Header 与代理生命周期 |
| Anime4K | 自建 GL Surface 管线 | Surface 与首帧回退 |
| NCNN | 保持现有独立能力边界 | 不与视频 Anime4K 混淆 |

## 11. 分流失败与错误处理

### 11.1 结构化错误分类

根据 `PlaybackException.errorCode`、HTTP 响应与底层原因映射为：

- NetworkTimeout
- NetworkConnectionFailed
- HttpForbidden
- HttpNotFound
- ExpiredUrl
- ManifestMalformed
- SegmentMalformed
- DecoderUnsupported
- DecoderInitializationFailed
- BehindLiveWindow
- SourceUnavailable
- Unknown

错误分类用于日志、提示和有限分流切换，不用于无限重试。

### 11.2 分流切换原则

- 只在实际播放失败或明确启动超时时切换；
- 不恢复已删除的播放前探测请求；
- 同一分流只尝试一次，防止循环；
- 切换时保留合理的播放位置；
- 403 若来自节点/IP 封锁，可切换不同宿主分流，但不能反复请求同一 URL；
- 旧播放器实例或旧 MediaItem 回调必须带 generation/session id，不能影响新分流。

### 11.3 原宿主一致性

若原始 Cloudstream/Aniyomi 宿主对相同 URL、Header 和网络出口也失败，Kototoro 不新增不可解释的兜底。兼容层只解决宿主已支持、Kototoro 未正确消费的情况。

## 12. 生命周期与 Compose 边界

`VideoPlayerRenderLayer` 继续作为 Android View 互操作边界，但从 `CustomMpvView` 改为：

- 普通模式：无原生控制器的 `PlayerView`；
- Anime4K 模式：`Anime4KSurfaceView`；
- 顶层继续叠加 `DanmakuView`；
- 控制条、手势、弹窗、字幕覆盖和状态保持 Compose 实现。

播放器实例生命周期建议由 Activity 级持有，不在 Compose 重组时创建：

- `onCreate`：创建 engine；
- render layer 建立后：绑定输出；
- `onStart/onResume`：根据设置恢复播放与 Surface；
- `onPause/onStop`：保存进度并按现有策略暂停；
- `onDestroy`：释放 engine、GL、代理会话和缓存引用。

所有高频播放器状态进入单向状态流，Compose 只渲染状态并发送用户事件。

## 13. 实施阶段

### 阶段 0：基线冻结与诊断

任务：

- 固化当前可播放源测试样本及日志字段；
- 记录首帧、首次缓冲结束、错误、分流切换和代理使用情况；
- 建立至少包含 Xhamster、AnimePahe、18EU、3XChina、CamCaps 的设备验证清单；
- 记录普通 Aniyomi MP4、Aniyomi HLS、Cloudstream MP4、Cloudstream HLS 样本；
- 标记现有工作树中与播放兼容相关的未提交修改，避免迁移时丢失。

完成条件：每类输入至少有一个可复现样本，并能区分解析耗时、网络耗时、准备耗时和首帧耗时。

### 阶段 1：建立播放器抽象

任务：

- 新增 `PlaybackRequest`、`VideoPlaybackState`、`VideoTrackSnapshot`；
- 新增 `VideoPlayerEngine`；
- 将 `VideoPlayerActivity` 对 mpv 的业务调用迁移到接口；
- 将底层属性字符串读取替换为结构化状态；
- 保持现有 mpv 作为短期适配实现，仅用于降低重构跨度。

完成条件：Activity 和 Compose 不再直接引用 `MPVLib`、`MpvPlayer.TrackInfo` 或 mpv property 名称。

### 阶段 2：实现 Media3 基础播放

任务：

- 构造 OkHttpDataSource、CacheDataSource 和 DefaultMediaSourceFactory；
- 实现标准 MP4、WebM、HLS 播放；
- 实现 Header、Cookie、Referer、Origin、User-Agent；
- 实现准备、播放、暂停、seek、倍速、音量、结束和错误事件；
- 实现自适应清晰度与手动轨道选择；
- 接入 PlayerView 直接输出；
- 保留播放 session generation，隔离旧回调。

完成条件：不启用 Anime4K 时，标准源可通过 Media3 完成全流程播放。

### 阶段 3：迁移插件输入和异常 HLS

任务：

- 将 Cloudstream/Aniyomi 输出统一转换为 PlaybackRequest；
- 将异常 HLS 逻辑移入 PlaybackRequestNormalizer；
- Media3 直接播放标准 HLS；
- `.json` HLS、异常 Content-Type 和包装分片继续走动态代理；
- 删除播放器前探测；
- 校验字幕和外部音频；
- 校验分流失败切换与进度保留。

完成条件：兼容性测试矩阵中的插件源均走预期的直连或代理路径，没有无意义的双重请求。

### 阶段 4：实现 Anime4K 输出管线

任务：

- 独立实现 OES 输入 Surface、FBO 与 shader 管线；
- 复用现有 Anime4K shader 资产和用户预设语义；
- 实现 VideoOutputRouter；
- 实现首帧回退、HDR/Dolby Vision 绕过、画中画绕过；
- 验证 FIT/FILL/CROP、旋转、横竖屏和 Surface 重绑；
- 将低端设备回退策略映射为 Media3 直接输出。

完成条件：Anime4K 开关不会重载媒体或丢失进度；异常时能自动回到直接输出并继续播放。

### 阶段 5：功能齐套和设备回归

任务：

- 完成字幕、外挂音频、弹幕、DLNA、自动下一集、片头片尾和历史记录回归；
- 检查详情页返回、Activity 重建、后台恢复和画中画；
- 对比 Media3 与旧 mpv 的首帧和失败率；
- 检查缓存占用、重复下载和清理行为；
- 检查低端设备与四 ABI 构建。

完成条件：功能矩阵无阻断缺口，核心源在相同节点下不劣于原宿主的可播放性。

### 阶段 6：移除 mpv

任务：

- 删除 `MpvPlayer`、`CustomMpvView`、`MpvPlaybackOptions` 和 `MpvShaderManager`；
- 删除 `libs.mpv.android.lib` 和对应版本项；
- 删除 mpv 初始化、配置文件、缓存目录和设置入口；
- 删除仅服务于 mpv 的错误解析和生命周期补丁；
- 更新问题提交指南、播放器文档和设置文案；
- 对旧 `mpv_cache` 提供可恢复的清理入口或版本迁移清理。

完成条件：生产代码、构建依赖、资源和文档均不再依赖 mpv，Media3 是唯一内置视频内核。

## 14. 测试策略

### 14.1 单元测试

- PlaybackRequest MIME 推断与 Header 归一化；
- 标准 HLS/异常 HLS 直连与代理决策；
- 分流排序、去重和失败推进；
- PlaybackException 分类；
- session generation 过滤旧事件；
- cache key 稳定性和敏感参数排除；
- Anime4K 输出决策、HDR 绕过和首帧回退；
- Media3 轨道模型到 UI 模型的映射。

### 14.2 MockWebServer 集成测试

- 主列表、子列表、分片均收到正确 Header；
- 302/307 重定向后的 Header 行为；
- Range 请求和 206 响应；
- 403、404、超时和断连错误分类；
- `.json` HLS 通过代理后可被 Media3 识别；
- 伪装 Content-Type 与包装分片转换；
- 签名 URL 刷新后缓存身份符合预期。

### 14.3 设备测试矩阵

| 生态/源 | 输入类型 | 重点 |
|---|---|---|
| Xhamster | 自适应 HLS | 首帧、轨道探测、Referer |
| 另一个 Xhamster 插件 | MP4 多清晰度 | 直链、Range、节点限制 |
| AnimePahe | HLS + MP4 多分流 | 403、分流切换、kwik Header |
| 18EU | `.json` HLS | 强制 MIME、本地代理 |
| 3XChina | 多 HLS 分流 | Cookie、Referer、自动回退 |
| CamCaps | 带签名 HLS | Token 过期、Referer |
| Aniyomi 普通扩展 | MP4/HLS | 原宿主一致性 |
| 本地视频 | 文件 URI | seek、字幕、结束 |
| DLNA | 局域网代理 | Header 与可达性 |

每个样本至少验证：首次播放、重新播放、切换节点后播放、切换分流、后台恢复、返回详情页后重进。

### 14.4 性能指标

- 链接解析完成到 `prepare()` 的耗时；
- `prepare()` 到 `STATE_READY`；
- `prepare()` 到首帧；
- 首帧前请求数量；
- 首帧前下载字节数；
- 初次缓冲和二次缓冲次数；
- 分流切换耗时；
- Anime4K 每帧 GPU 时间和首帧回退次数；
- 同一媒体重复播放的缓存命中率。

性能结论必须区分播放器差异与 VPN 节点/CDN 故障，不能把网络不可达归因于解码器。

## 15. 可观测性与隐私

建议统一日志事件：

```text
playback_prepare_start
playback_request_normalized
playback_proxy_selected
playback_manifest_loaded
playback_ready
playback_first_frame
playback_rebuffer
playback_error
playback_fallback
anime4k_surface_attached
anime4k_first_frame
anime4k_fallback_direct
```

日志包含：

- source、媒体类型、分流索引；
- 是否代理、MIME、Header 名称；
- Media3 errorCode；
- 各阶段耗时；
- 选中的视频/音频轨道摘要。

日志不得包含：

- Cookie 值；
- Authorization；
- 完整签名 Token；
- Cloudflare clearance；
- 可用于复用会话的完整媒体 URL。

## 16. 风险与应对

### 16.1 Media3 编解码覆盖小于 mpv

Media3 默认依赖平台 MediaCodec，少数非常规容器或编码可能不如 mpv。

应对：以项目真实源矩阵决定是否需要额外 decoder extension，不因理论兼容性预先引入 FFmpeg 依赖。Cloudstream/Aniyomi 主流输出通常是 H.264/H.265/AAC/HLS/MP4。

### 16.2 Anime4K Surface 生命周期复杂

Surface 争用或重建可能造成黑屏、只有声音或进度重置。

应对：输出切换集中到单一 Router；先直接输出，再由已就绪的 GL Surface 接管；始终提供会话级直接输出回退。

### 16.3 Activity 责任继续膨胀

若只把 mpv 调用替换为 ExoPlayer 调用，`VideoPlayerActivity` 仍会混合网络、代理、播放、UI 和回退逻辑。

应对：先建立 PlaybackRequestNormalizer 和 VideoPlayerEngine 边界，再接入 Media3，不把 DataSource 构造散落在 Activity 中。

### 16.4 永久双播放器

长期保留 mpv 回退会让每个功能都维护两套状态、轨道、Surface 和错误处理。

应对：双栈只允许存在于迁移分支和明确阶段；阶段 5 达标后进入强制删除清单。

### 16.5 外部参考许可证

直接复制 GPLv3 实现会与 Kototoro Apache-2.0 发布方式产生许可证冲突。

应对：只借鉴通用架构和问题清单，独立编写实现；提交中记录设计来源与独立实现边界。

## 17. 回滚策略

- 阶段 1 至阶段 4 按独立、可编译的小提交推进；
- Media3 接管生产路径前保留内部开发开关用于 A/B 验证；
- 开关不作为长期用户设置发布；
- 发生阻断回归时回滚到上一阶段，而不是在生产环境自动切换 mpv；
- 阶段 6 删除 mpv 前打下明确的兼容性基线和可回滚提交点。

## 18. 总体验收标准

满足以下条件后方可宣布迁移完成：

1. Media3 是唯一生产播放内核。
2. 标准 HLS 不经过本地代理，异常 HLS 仅在必要时代理。
3. Cloudstream 与 Aniyomi 核心样本在相同网络条件下达到或接近原宿主行为。
4. Xhamster 不再因 FFmpeg 全变体探测产生同类 30 秒串行阻塞。
5. AnimePahe、18EU、3XChina 和 CamCaps 的已知播放路径通过设备验证。
6. Anime4K 可启用、切换、回退，且不会导致黑屏或重新加载媒体。
7. 字幕、音轨、弹幕、DLNA、历史记录、自动下一集和手势功能无阻断回归。
8. 只存在一套持久视频缓存。
9. 日志可区分解析、规范化、网络、播放器和 GL 管线故障。
10. mpv 依赖、代码、缓存和文档入口均已移除或迁移。

## 19. 最终建议

按“播放器抽象 → Media3 基础播放 → 插件输入规范化 → Anime4K → 设备回归 → 删除 mpv”的顺序实施。

不要先移植 Anime4K，也不要直接在 `VideoPlayerActivity` 中大规模替换 API。首先收敛播放请求和播放器状态边界，能显著降低后续 Media3、代理和 GL 输出调试互相干扰的风险。

本计划取代旧方案中“保持 mpv 作为长期播放内核”的结论；旧方案已有的设备性能分级、会话级回退和诊断思想可以继续复用，但底层配置目标应改为 Media3 直接输出与 Anime4K 绕过策略。
