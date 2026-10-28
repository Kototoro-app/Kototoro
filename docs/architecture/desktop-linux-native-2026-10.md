# Linux 桌面原生能力（不能共享的部分）

范围：桌面三系统（Windows/macOS/Linux）共用 `desktop-app`、`desktop-runtime`、`desktop-player`，共享呈现已经收敛到
`core-ui`/`reader-core`/`reader-novel`（见 [CMP 共享 UI 收敛计划](cmp-ui-convergence-plan-2026-10.md)）。
本文只记录**不能共享**的那一层：必须由宿主操作系统提供、各平台做法不同的能力，逐项写清 Linux 上的实现位置、
默认行为、验证方式与已知缺口。Linux 预览包的构建与运行说明见
[desktop-app/README.md](../../desktop-app/README.md)。

## 能力清单

| 能力 | 为什么不能共享 | Linux 的做法 | 现状 |
| --- | --- | --- | --- |
| 视频解码与呈现 | 需要平台原生播放内核（Windows 侧预置 libmpv DLL，Android 侧 Media3） | JNA 加载发行版提供的 libmpv（`libmpv.so.2`），AWT 画布的 X11 窗口号作为 mpv `wid` 内嵌输出 | 发现与解码已在装 `libmpv2` 的机器上跑通（见「两个 Linux 专属的 libmpv 坑」） |
| 视频增强（Anime4K） | 需要 libmpv 着色器管线 | 同一 libmpv 离屏渲染 + 随包 GLSL（与 Android 同一批着色器文件） | 着色器列表与离屏出图已在 Linux 上验证 |
| 页面超分（NCNN 程序） | 预置程序是各平台的本地可执行文件 | 官方 `ncnn-vulkan` 程序按平台资产下载安装 | Windows/Linux 双平台资产已钉死，Linux 上真实出图已验证 |
| 浏览器 / WebView | 系统 WebView 完全不同（Windows 用 WebView2 桥） | 无系统 WebView 桥 | `browser=unsupported`，来源挑战只报告 |
| 图片解码与分页 | 解码器与内存预算在宿主 | ImageIO（`FileImageInputStream` + `ImageReader`）+ Skia 位图，分块与并发预算在 `desktop-app` | Linux 与 Windows 同一套 JVM 实现 |
| 文件选择 / 外部打开 | 系统文件对话框与默认程序 | AWT `JFileChooser`/`Desktop.open` | 跨平台可用 |
| 打包与安装 | 系统安装器格式不同 | Compose jpackage：`Msi`/`Dmg`/`Deb`，Linux 另跑镜像搬迁与内容检查 | `.deb` 在与构建主机相同的发行版上验证；`Depends` 不含 `libmpv2` |
| 应用更新 | 安装器与签名机制不同 | 只对 Windows 包做下载/静默安装 | Linux 仅提示，无自更新 |
| DLNA 投屏 | 需要组播与网络接口枚举 | JDK `MulticastSocket`/`NetworkInterface` + 本机 HTTP 代理 | 与 Windows 同一实现 |
| 托盘 / 通知 / 媒体键 | 系统集成 API | 未实现 | Linux 与 Windows 都没有 |

## 播放器：libmpv 的发现与嵌入

`desktop-player` 只做两件事：把 libmpv 找到（`MpvLocator`），把它绑到一块原生窗口上（`MpvPlayer` + JNA）。
共享层只提供播放界面与状态（`core-ui` 的播放器控件、`VideoSeekFeedback`、`formatPlaybackTime`），
解码、渲染、着色器、轨道切换都留在这里。

查找顺序（`MpvLocator.candidates`，取第一个真实存在的文件）：

1. `-Dkototoro.libmpv=<file>` 或环境变量 `KOTOTORO_LIBMPV`；
2. 随包资源目录的 `mpv/`（`compose.application.resources.dir`）；
3. 用户数据目录的 `mpv/`；
4. 平台库目录：Linux 为 `/usr/lib/<multiarch>`（按 `os.arch` 映射，如 `amd64` → `x86_64-linux-gnu`）与
   `/lib/<multiarch>`，再加 `/usr/lib64`、`/usr/lib`、`/usr/local/lib64`、`/usr/local/lib`、`/lib64`、`/lib`；
   macOS 为 Homebrew 的 `/opt/homebrew/lib`、`/usr/local/lib`；Windows 没有这一层；
5. `LD_LIBRARY_PATH`（macOS 为 `DYLD_LIBRARY_PATH`）；
6. `PATH`；
7. Linux 上再读一次加载器缓存（`ldconfig -p` 的 `=>` 目标），每个进程只读一次。

库名按平台取：Windows `libmpv-2.dll`/`mpv-2.dll`，macOS `libmpv.2.dylib`/`libmpv.dylib`，
Linux `libmpv.so.2`/`libmpv.so`/`libmpv.so.1`（`.so` 是开发包给的软链，`.so.1` 照顾老发行版）。

第 4 步与第 7 步是 Linux 的关键：`apt install libmpv2` 把库装进 `/usr/lib/x86_64-linux-gnu`，那个目录既不在
`PATH` 也不是用户会手动挑选的文件夹；只查 `PATH` 的实现在装好 libmpv 的机器上仍会报「需要 libmpv」。

嵌入判定：`MpvLocator.supportsEmbeddedPlayback` 在 Linux 为真（AWT 画布在 Linux 上就是 X11 窗口，Wayland 会话经
XWayland 也仍然有 X11 窗口号，JNA 的 `Native.getComponentID` 给出的正是 mpv `wid` 需要的 drawable），
macOS 已使用 CGL／OpenGL render API（不接 AWT 指针，见 [macOS 原生能力](desktop-macos-native-2026-10.md)）。找不到库时视频页不消失，而是给出安装提示、外部 `mpv`
handoff（`MpvLocator.findExecutable`，Linux 额外查 `/usr/bin`、`/usr/local/bin`、`/snap/bin`、
`/var/lib/flatpak/exports/bin`）、复制直链与「重新检测」。

## 两个 Linux 专属的 libmpv 坑

装上 Ubuntu 24.04 仓库里的 `libmpv2`（0.37.0，ffmpeg 6.1.1）之后，库能加载但播放器一个也建不起来、建立起来也放不出来。
两个原因都是「Windows 上不会遇到」，所以只在这里记：

1. **`mpv_create` 要求 C 数字区域**。进程 `LC_NUMERIC` 不是 `C` 时 mpv 直接在 stderr 打印
   `Non-C locale detected. This is not supported.` 并让 `mpv_create` 返回 `NULL`；Windows 的 C 运行时默认就是
   `C`，而 Linux 桌面从环境继承 `LANG`/`LC_NUMERIC`（本机 `en_US.UTF-8`）。修法是在建实例前调
   `setlocale(LC_NUMERIC, "C")`：`MpvLocale`（`desktop-player`）用 JNA 直接叫 libc，Windows 跳过，
   macOS 的类别号是 4、glibc 是 1，并在切换后用 `setlocale(LC_NUMERIC, NULL)` 读回确认。只改 C 的数字格式化，
   UI 仍走 `java.util.Locale`，用户看到的数字不变；切换失败的原因存在 `MpvLocale.failure`，`mpv_create` 返回
   `NULL` 时的异常会带上它（没失败就带当时的 `LC_NUMERIC`），`MpvLocaleTest` 断言切换成功、读回是 `C`、
   且 JVM 的 `Locale.getDefault()` 没被动过。
   因为 `MpvNative.load` 统一调用它，播放器和 Anime4K 离屏渲染两个入口一起修好。
2. **`loadfile` 的参数个数随 mpv 版本变**。0.37 只接受 `loadfile <url> [<flags>]`，多传 `<index>`/`<options>`
   返回 `MPV_ERROR_INVALID_PARAMETER`（`mpv loadfile: invalid parameter`），而 Windows 侧预置的较新 libmpv 接受
   `loadfile <url> <flags> <index> <options>`。`MpvPlayer.load` 因此读一次 `mpv-version` 判断：0.38 及以上走带
   `start=<秒>` 的形式，更老的把 `start` 当播放选项设好再 `loadfile`，文件打开（`EVENT_FILE_LOADED`）后复位为 0。

顺带一个读回差异：`glsl-shaders` 这类路径列表写入时用平台分隔符（POSIX `:`、Windows `;`），
从属性读回来是逗号分隔，回归断言按两种都接受。

## 页面超分：两套原生路径

- **Anime4K（着色器）**：`MpvImageEnhancer` 用同一个 libmpv 离屏渲染，把 Android 那批 `Anime4K_*.glsl` 喂给 mpv；
  只在找得到 libmpv 时可用（`DesktopSuperResolution.available` 里 `tool == null` 的模型看 `shaderUpscaler`）。
  所以 Linux 上的可用性完全取决于上面的库发现与那两个坑。离屏渲染要 `vo=gpu`/`vf=gpu` 创建一个 GL/Vulkan 上下文，
  也就是**必须有显示**（真实桌面会话、或测试里的 Xvfb）；没有显示时 mpv 一帧都不写，回归用例按 `GraphicsEnvironment.isHeadless()` 跳过而不是报错——纯 headless 的
  CI 里这就是唯一被跳过的播放器用例。
- **NCNN 程序（RealCUGAN / Real-ESRGAN）**：`DesktopUpscaleTool` 现在是「平台 → 资产」表，同一 release tag 下
  Windows 与 Linux 各一份，模型目录名（`models-se`/`models`）与 `run()` 的参数一致：

  | 程序 | release | Windows 资产 / 可执行 | Linux 资产 / 可执行 |
  | --- | --- | --- | --- |
  | RealCUGAN（nihui） | `20220728` | `realcugan-ncnn-vulkan-20220728-windows.zip`（`c6e08d46…`）/ `realcugan-ncnn-vulkan.exe` | `realcugan-ncnn-vulkan-20220728-ubuntu.zip`（`d745174b…`）/ `realcugan-ncnn-vulkan` |
  | Real-ESRGAN（xinntao） | `v0.2.5.0` | `realesrgan-ncnn-vulkan-20220424-windows.zip`（`abc02804…`）/ `realesrgan-ncnn-vulkan.exe` | `realesrgan-ncnn-vulkan-20220424-ubuntu.zip`（`e5aa6eb1…`）/ `realesrgan-ncnn-vulkan` |

  Linux 的 ubuntu 包不带 `.so`（ncnn 静态链接），只是少了 `.exe` 后缀，且 zip 条目不带 POSIX 权限位，所以
  `unpackVerified` 解包后在非 Windows 上补一次 `rwxr-xr-x`，否则装好了也执行不了。`supportsNativeUpscaling`
  对 Windows、Linux 与 macOS 均为真；macOS 同样有官方包，具体资产与校验见 macOS 原生能力文档。
  Linux 的程序按需下载（不随包分发），安装入口就是阅读器超分那一行的「下载并安装 / 选择压缩包」。

## 阅读器：哪些是原生的、哪些不是

分页/连续/横向条带的场景、可见页计算、相机缩放与锚点、进度锚点都在 `reader-core`；chrome、目录、选项、书签卡在
`core-ui`。留在宿主的只有：

- 图片解码：`DesktopImageDecoder`（ImageIO 读头部尺寸、按 `ImageDecodeSize` 限幅、两个并发名额、Skia 位图）；
- 长页分块：`DesktopTiledImage`（按可见区域分块绘制，单块 2 MiB 输出预算）；
- 页索引与磁盘缓存：`DesktopReaderImages`（按扩展哈希与完整请求上下文隔离，读取核对大小/哈希）；
- 内存预算：`DesktopImageBudget`；
- 窗口、指针、滚轮、键盘：Compose Desktop + AWT。

这些都是纯 JVM 实现，Linux 与 Windows 走同一份代码；Linux 特有的差异只在窗口系统（X11/XWayland）与字体可用性。

## 运行检查

`desktop-app --check-runtime <report>` 在安装包内报告本机装配（`DesktopRuntimeCheck`）。Linux 上它现在会：

- 报告 `platform=Linux`、`browser=unsupported`、`player.embedded=x11-window`、`upscale.native=on-demand`；
- **真正去探测 libmpv**：找到就无窗口解码一段 lavfi 测试流并写 `player=ok`，找不到写 `player=unavailable` 与
  `player.hint`（发行版包名或数据目录的 `mpv/` 文件夹）；
- **真正去探测超分**：`upscale.realcugan`/`upscale.realesrgan` 各写程序路径或 `unavailable`，两个都装好就用 32×48
  的样张跑一次真实放大并核对宽高，写 `upscale=ok`；没装则写 `upscale=unavailable`（Linux 本就按需下载，
  这不是失败）。这段探测是 `DesktopRuntimeCheck.probeUpscaling(root, superResolution, required)`，只依赖 `DesktopSuperResolution`
   本身（不碰 Session），所以能直接对着装好程序的真实数据目录回归；Windows 分支走同一个函数、`required = true`，
   那里缺程序仍算打包 bug。

之前 Linux 分支在探测之前就 `return` 了，报告里只有静态的 `player.embedded=unbundled`、`upscale.native=unsupported`；
现在缺库缺程序都不再是失败（libmpv 与 ncnn 都从不随包分发），但报告不会再把「不知道」写成「不支持」。

## 验证与限制

在本机（Ubuntu 24.04、x86_64、NVIDIA A800 + llvmpipe/lavapipe、`apt install libmpv2` 后）实跑：

- `:desktop-player:test`（Xvfb）：`MpvPlayerTest` 4 项全部通过（2026-10-27 之前这 4 项在 Linux 上是跳过/失败的）——
  自编码音频片段的载入/暂停/跳转、着色器列表、HTTP 头与起始位置、Anime4K 离屏出图；`MpvLocaleTest` 1 项（区域切换与 JVM locale 不变）、`MpvLocatorTest` 7 项、
  `DlnaTest` 2 项也全过；去掉 Xvfb 重跑则只有 Anime4K 那一项被跳过，其余 13 项照旧通过。
- `:desktop-runtime:test -PncnnDirectory=<两个 ubuntu zip>`：`DesktopSuperResolutionTest` 2 项通过，其中
  「官方程序按模型倍数放大并缓存」一项用 Linux 二进制真实跑完 RealCUGAN 2x、Real-ESRGAN animevideo 2x 与
  4x anime 三种模型（12.3 s，含缓存复用断言）。
- `:desktop-app:test -PncnnDirectory=<两个 ubuntu zip>`：`DesktopAppTest.reader pages are upscaled…` 用真实 UI
  走完「下载并安装 → 放大 → 记住选择 → 重进恢复」（29.1 s）；新增的 `DesktopRuntimeCheckUpscaleTest` 直接调
  `probeUpscaling`，对装好两个程序的数据目录断言 `upscale=ok`、对空目录断言 `unavailable`，且 `required = true`
  时会失败。
- `:desktop-app:verifyDesktopStrings` 通过（新增 `reader.upscaleAnime4kMissingLinux`，Windows 文案不再写死
  「Windows 程序」）。
- 打包镜像实跑：Xvfb 下 `-PwithLinuxDistribution` 的 `createDistributable` + `:desktop-app:linuxDistributionTest`
  通过，报告里 `player` 与 `upscale` 都按本机真实装配取值（详见测试断言的三种取值）：本机是
  `player.library=/usr/lib/x86_64-linux-gnu/libmpv.so.2`、`player=ok`、`upscale.native=on-demand`、
  `upscale.realcugan=unavailable`、`upscale.realesrgan=unavailable`、`upscale=unavailable`、`status=ok`。
- 安装包实跑：`:desktop-app:linuxDebContentsCheck` 通过，产出 `kototoro_0.1.0_amd64.deb` 并核对 515 个文件与
  解包后的 Debian 启动器。`dpkg-deb -f` 的 `Depends` 是 jpackage 自动生成的 24 项（`libasound2t64`、`libX11-6`、
  `libgl1`…），**不含 `libmpv2`**：安装 `.deb` 不会顺带装播放库（要自动装上得用 `--resource-dir` 换 deb 的
  `control` 模板再重新验收）。

已知限制：

- 无窗口（`vo=null`/`ao=null`）时，只有视频轨的文件在这台机器的 libmpv 0.37 上没有时钟、`time-pos` 停在 0:00；
  带音频（`ao=null`）才按实时走。应用里总是有窗口，所以这是测试写法要遵守的约束，不是产品问题。
- `av://lavfi:...` 是不可 seek 的实时源，0.37 上 `seek` 会退化成重启 demuxer 并落到流尾；要验跳转得先落到文件。
- Wayland 原生（不经 XWayland）的窗口号、Flatpak/Snap 沙箱里的 `ldconfig`、跨发行版库路径都还没验。
- `browser=unsupported` 是 WebView2 桥的现状，不代表 Linux 上已有替代实现。

## CI

`.github/workflows/linux-build.yml` 在 `ubuntu-24.04` 上跑同一批命令，手动或由其他工作流调用，不发布产物：

- `Linux player and upscaling`：`apt install libmpv2 xvfb mesa-vulkan-drivers` 后跑 `:desktop-player:test` 与
  `:desktop-runtime:test`（`-PncnnDirectory` 指向脚本下好的两个官方 zip）。job 级 `LC_NUMERIC=en_US.UTF-8`
  是故意的：只有非 C 的数字区域才会走到 `MpvLocale` 要修的那条 mpv 崩溃；`vulkaninfo` 里没有 `llvmpipe`
  直接失败，否则 ncnn 只会以看不懂的方式失败。
- `Linux package`：`-PwithLinuxDistribution` 生成镜像与 `.deb`（版本取 Android versionName 的三段值），
  再跑 `linuxDistributionTest`、`linuxDebContentsCheck`，并用真实阅读器 UI 走一遍超分的安装/放大/记忆。

兼容 JAR 由 `.github/scripts/prepare-linux-ci.sh` 准备，取法与 Windows 相同（`WINDOWS_COMPATIBILITY_URL`
或从固定 Suwayomi/TachiyomiAzIOS 提交用 JDK 25 重建），两个 ncnn release zip 的 SHA-256 与
`DesktopUpscaleTool` 的固定值一致，`DesktopSuperResolutionTest` 用同一份 check 兜住漂移。
CI 没有真实 GPU、Wayland 会话或发行版矩阵，`.deb` 也不上传发布页，所以它替代不了下面的人工验收。

## 后续顺序

1. 在 Fedora/Arch 与 Wayland 会话上各跑一次 `--check-runtime`，确认库路径、`player=ok` 与内嵌渲染；
2. `.deb` 声明 `Recommends: libmpv2`（换 `control` 模板），让安装即带播放库；
3. 阅读器超分的 Linux 下载走一遍真实网络（现在是手动放进 `-PncnnDirectory` 的官方包；下载与校验代码已有
   `DesktopSuperResolution` 的既有用例覆盖，只差一次真实 GitHub 请求）；
4. 托盘、通知、媒体键（三平台一起做，Linux 用 libappindicator/Mpris 之前先确认依赖是否愿意引入）。
