# macOS 桌面原生能力

Windows、macOS 和 Linux 继续共用 `desktop-app` 页面、Controller、数据库、来源与同步规则。
本次改动只扩展浏览器协议的系统实现、播放器的呈现适配器及现有超分资产表，没有新建 macOS 页面。
Android 的播放器仍为 Media3；本次没有改变 Android UI，也不表示播放器页面已完成 CMP 共享。

## 浏览器

`mihon-desktop-compat/src/bridge/swift/KototoroWebViewBridge.swift` 使用 AppKit / WKWebView，
实现现有 `DesktopWebViewBridge` 的逐行 JSON 协议。支持 HTML、HTTP(S) 导航、请求头、UA、二进制 POST、
JavaScript、Cookie、窗口显示/隐藏/缩放及 PNG 截图。用户关闭窗口时只隐藏，由 JVM Session 最终退出进程。
EOF 同样结束进程。导航以 `WKNavigation` 身份关联响应，取消后的迟到回调不会结束后续导航。

每个桥使用独立的非持久化 WKWebsiteDataStore；Cookie 通过已有 `DesktopBrowserCookies` 与 SDK 的
PersistentCookieStore 同步，避免多份浏览器持久化状态互相覆盖。浏览器 localStorage 等网页存储不跨进程保留。
普通导航拒绝 HTTP 错误，交互验证可显式允许错误页面；不绕过 TLS 校验，也不自动打开外部协议。
实际站点的人机验证兼容性仍需逐站验证，离线协议测试不能证明所有站点可用。

`:mihon-desktop-compat:compileMacosWebViewBridge` 使用 `xcrun swiftc` 编译；需要 Xcode Command Line Tools。
`:desktop-app:run` 自动依赖该任务并指定桥程序；macOS 预览包将其放入 `browser/` 资源目录。
`-PdesktopBridgeExecutable` 仍可覆盖开发运行位置，资源定位不会选择 Windows EXE。

参考：[Apple WKWebView](https://developer.apple.com/documentation/webkit/wkwebview)、
[WKHTTPCookieStore](https://developer.apple.com/documentation/webkit/wkhttpcookiestore)。

## 内嵌视频

macOS 不把 JNA 的 AWT NSView 指针传给 mpv `wid`。默认由 `desktop-player/MpvMacOpenGlRenderer`
创建独立的 CGL OpenGL 4.1 core 上下文，接入 libmpv `render_gl.h`；在后台 GPU 上渲染至 FBO，
读回图像后由 `MacVideoPanel` 呈现。上下文不与 Compose/Skiko 共用，也不在 AWT 线程渲染。
每次调用前绑定自有上下文，完成后恢复调用线程之前的上下文；初始化失败同样释放已创建的 CGL 资源。
输出保持视口比例，最多 1920×1080，沿用现有播放状态、音轨/字幕、续播、线路切换、章节历史和 DLNA 控制。
默认 GPU 路径保留 mpv 的硬解选择，并可实际执行视频 Anime4K 与 FSR GLSL 画质增强。

`-Dkototoro.mpv.renderer=software` 可切回 `MpvSoftwareRenderer` 的 `sw` API 用于排障；
Gradle 开发启动也可传 `-PmpvRenderer=software`，无需修改环境变量。
该路径的硬解自动选项映射为 `auto-copy`，不支持 GLSL，选择增强时会明确报错。
渲染与销毁串行化，render context 在 mpv handle 之前释放；macOS 播放器由 Compose 生命周期持有，
原生窗口呈现路径则用 generation 拒绝迟到实例。帧缓冲按尺寸复用，单帧图像交给 Swing 呈现。

GPU 路径仍包含 FBO 读回与 Swing 图像呈现的复制成本，尚非零拷贝原生显示；软件排障路径同样有 CPU 成本。
macOS 发行包默认随附 libmpv 与完整非系统 dylib 依赖，无需 Homebrew。
`distribution/macos-mpv-pins.json` 固定第三方 `178meorg/libmpv-macos-build` 的版本、CPU 与 SHA-256；
该构建使用 `178meorg/mpv` 分支及 `enhanced-lgpl` 配置。准备脚本校验归档、CPU 与依赖闭包，
将库之间的引用重定位至 `@loader_path`，去除构建机 rpath，再执行 ad-hoc 签名。
原始许可、补充 Lua MIT 许可、构建元数据与最终依赖哈希一并放入 `resources/mpv/`。
显式指定目录与应用数据目录仍可用于开发与替换兼容库。
该构建的 FFmpeg 以 `--disable-encoders --disable-muxers` 编译，mpv 的编码输出（`--o`）不可用，因此 macOS 上
阅读器的 Anime4K 页面增强（`MpvImageEnhancer`）不走 Windows/Linux 的 `gpu` 滤镜 + PNG 编码路径，而是用同一个
CGL 渲染器在离屏 FBO 中按输出尺寸渲染（上限 16384，页面最大输出 6000×8400），读回后由 ImageIO 写 PNG；
`MpvPlayerTest` 的素材也改为测试内直接写出的 WAV，不再依赖 libmpv 编码。

参考：[Apple CGL 像素格式](https://developer.apple.com/library/archive/documentation/GraphicsImaging/Conceptual/OpenGL-MacProgGuide/opengl_pixelformats/opengl_pixelformats.html)、
[mpv render_gl.h](https://github.com/mpv-player/mpv/blob/master/include/mpv/render_gl.h)、
[mpv render.h](https://github.com/mpv-player/mpv/blob/master/include/mpv/render.h)、
[官方软件呈现示例](https://github.com/mpv-player/mpv-examples/blob/master/libmpv/sdl/main_sw.c)。

## 阅读器超分

现有下载、校验、解包、运行和缓存全部复用 `DesktopSuperResolution`。补入以下官方通用二进制：

| 程序 | 官方资产 | SHA-256 |
| --- | --- | --- |
| RealCUGAN | `realcugan-ncnn-vulkan-20220728-macos.zip` | `0df908cbb98b480f85897221b96d37b0bdb70f82d81b2c7037fe950dd5c0fa33` |
| Real-ESRGAN | `realesrgan-ncnn-vulkan-20220424-macos.zip` | `e0ad05580abfeb25f8d8fb55aaf7bedf552c375b5b4d9bd3c8d59764d2cc333a` |

资产来自 [RealCUGAN 官方 release](https://github.com/nihui/realcugan-ncnn-vulkan/releases/tag/20220728)
和 [Real-ESRGAN 官方 release](https://github.com/xinntao/Real-ESRGAN/releases/tag/v0.2.5.0)。
原先“macOS 上游没有包”的描述有误，已修正。

## 本机验证

2026-10-28 在 Apple Silicon macOS 上执行：

- `:desktop-app:compileKotlin` 与 Swift 原生桥编译通过。
- `MihonBridgeLocationTest`、`DesktopWebViewBridgeTest`、`DesktopBrowserRequestTest`、
  `DesktopBrowserWindowTest` 验证资源定位、JS、Cookie、POST、请求头、超时恢复、原生窗口与截图。
- `DesktopPlatformTest` 与 `DesktopSuperResolutionTest` 验证能力/资产一致性、错误哈希拒绝，
  并使用真实官方 macOS 程序完成 RealCUGAN 2x、Real-ESRGAN 4x、AnimeVideo 2x 与缓存复用。
- `MpvSoftwareRendererTest` 使用真实 libmpv 验证彩色帧、尺寸切换及幂等关闭；测试视频在临时目录生成，
  未加入应用发行资源。
- `MpvMacOpenGlRendererTest` 在真实 CGL 上验证红蓝上下分区画面方向、GLSL 实际改变像素、撤销 shader、
  调整输出尺寸、跨线程调用、上下文恢复与幂等关闭。
- `DesktopAppTest` 的真实视频窗口探针验证出帧截图、线路切换、DLNA 中继、返回详情后的续播、
  下一集与历史保存；默认 macOS GPU 路径已执行 Anime4K 质量模式和 FSR 编译断言，软件排障分支保留跳过。
- `DesktopBrowsePagingTest` 回归来源列表自动追加、结束状态及搜索分页。

真实视频探针还发现保留在详情页底下的来源列表会触发分页，迟到的请求把正在播放的页面切回浏览页。
`browseNextPage` 现在同时检查排队前和执行时的当前页面与来源，避免后台分页改变前台导航。

## 本地预览发行验证

`createDistributable` 生成自带 JVM 的 `.app`。Compose 的资源复制会丢失 POSIX 执行位，
因此生成应用后针对 WKWebView 桥恢复执行权限，再交给 DMG 打包；不会改写桥程序的代码字节。

`macosDistributionTest` 将真实应用复制到含中文和空格的目录，清除开发用 Java 环境变量，
通过原生启动器连续执行导入与重开检查：来源、数据库版本、备份、图像解码、WKWebView，
以及 JVM 确实位于复制后的 bundle 内。同时清除外部库环境变量，强制使用搬移后的包内 libmpv，并要求 OpenGL 实际出帧。
权限位和本机图形环境不能仅靠文件内容哈希代表，因此该发行验证任务每次都会执行。

`packageDmg` 依赖此验证任务，并直接消费经过验证的应用镜像：

```bash
./gradlew -PwithDesktopApp -PwithMacosDistribution \
  "-PmihonCompatibilityDirectory=/absolute/path/compatibility" \
  :desktop-app:packageDmg
```

Mac 打包按构建主机 CPU 自动选择固定归档；离线可通过 `-PmacosMpvArchive=/absolute/path/archive.tar.gz`
提供同一 SHA-256 的归档。构建不需要安装系统播放库。
默认产物位于 `desktop-app/build/compose/binaries/main/dmg/Kototoro-1.0.0.dmg`，
其中的 `1.0.0` 是可通过 `desktopPackageVersion` 指定的本地包版本。
开发 `run` 任务在配置阶段解析桥路径，已验证 Gradle 配置缓存重用；软件排障路径同样通过生产自检。

本机已生成 DMG，并将其只读挂载后直接运行其中的原生启动器：自带 JVM、WKWebView、OpenGL 实际出帧
均通过；`codesign --verify --deep --strict` 校验通过。该包为本地 ad-hoc 签名，不能等同于 Developer ID 签名或公证。
验证报告在 `desktop-app/build/reports/macos-dmg/runtime.properties`；只读验证卷结束后已卸载。

## CI 打包

`.github/workflows/macos-build.yml` 在 `macos-15`（arm64）与 `macos-15-intel`（x64）上各打一个 DMG，
产物名与更新客户端一致：`Kototoro-<版本>-macos-<arm64|x64>-<nightly|release>.dmg` 及 `.sha256`。
兼容层 JAR 不在 macOS 上重建，而是用 `prepare-compatibility.py` 从同一次运行的 Windows ZIP 中按
`windows-runtime-pins.properties` 校验提取；nightly/release 通过 `compatibility-artifact` 传入 Windows 产物名，
手动运行时先调用 Windows 工作流。托管 runner 是虚拟机，先用 CGL 探测是否有加速 OpenGL：有则分发测试检查 GPU
渲染路径，没有则以 `-PmacosDistributionRenderer=software`（启动器收到 `-Dkototoro.mpv.renderer=software`）检查
软件渲染并给出 warning。之后与 Windows 一样运行打包后的 `--check-update`，核对版本戳、渠道与 `macos_app`
安装形态，并做 `codesign --verify --deep --strict`。nightly 上传到 nightly 仓库的 Release，release 附到 tag 的 Release。
包仍是 ad-hoc 签名、未公证，用户首次打开需在“隐私与安全性”中确认。

## 应用自动更新

更新页面仍是 Windows/macOS 共用的 `DesktopAppUpdateSection`，状态与下载规则继续消费
`DesktopUpdateOperations`、`DesktopAppUpdateClient` 和 `DesktopUpdateInstaller`。
`DesktopUpdateTarget` 将 Windows x64、macOS arm64、macOS x64 的资产隔离；同一 GitHub release
中的其他芯片、系统与渠道均不会被选中。切换渠道时清除旧渠道的下载状态，并忽略迟到的查询结果。

macOS 只接收 `Kototoro-<version>-macos-<arm64|x64>-<release|nightly>.dmg`，以及同名 stem 的
`.sha256`。下载验证公开的哈希、声明长度及 UDIF trailer；暂存前再核对文件哈希。
`MacosAppUpdater` 只读挂载 DMG，通过 `ditto` 保留 bundle 的符号链接与执行权限，再卸载卷。
候选 bundle 必须有正确 bundle ID、有效代码签名、本机 CPU 和匹配 release 的桌面版本 stamp。
已使用 Apple 团队签名的安装，还要求候选满足同一团队的 Apple 签名要求；本地 ad-hoc 预览继续允许 ad-hoc 更新。
CPU 直接由 Mach-O 薄／通用头读取，用户安装更新不依赖 Xcode 或 `lipo`。

只有可写 `.app` 安装可替换自身；只读 DMG、受保护目录和 Gradle 启动的副本不能直接安装。
若用户将 `--data-dir` 指向安装包内部，同样禁用替换，避免将自定义数据目录一并替换。
安装请求先完成校验，再启动 `/bin/sh` 助手等待当前进程退出；超时取消，不强杀正在写数据库的应用。
助手把新 bundle 复制至安装目录同一文件系统，重新验证签名和身份，使用重命名替换；失败时还原旧 bundle。
重启通过系统 `open -n` 创建新实例并保留 `--data-dir`，避免复用另一份已打开的副本；
用户选择退出时安装，则不重新打开应用。
旧 bundle 保留在应用同级 `.kototoro-update.XXXXXX/previous.app`，便于手动恢复。
回滚覆盖替换失败和重启命令失败，不宣称能检测新进程启动后的所有崩溃。

生成可供更新查询识别的资产：

```bash
./gradlew -PwithDesktopApp -PwithMacosDistribution \
  "-PmihonCompatibilityDirectory=/absolute/path/compatibility" \
  :desktop-app:macosUpdatePackage
```

任务先运行应用搬移验证和 DMG 打包，再将带 CPU／渠道的 DMG 与 `.sha256` 输出到
`desktop-app/build/compose/binaries/main/update/`。需要将两份文件发布到对应 release/nightly 仓库，
客户端才会在线发现；本次没有发布远端 release。

验证包含混合平台／CPU／渠道的 release 解析，DMG 下载校验与损坏拒绝、Mach-O CPU 读取、
更新诊断报告、更新页面的渠道切换与设置持久化。`MacosAppUpdateIntegrationTest` 使用真实 DMG 与
代码签名，在临时目录执行暂存、替换、真实重启并完成生产自检，再执行故意失败的重启命令，
确认回滚恢复原 bundle，用户数据保持不变。
没有修改用户已安装的应用。

参考：[Apple 代码签名要求](https://developer.apple.com/documentation/technotes/tn3127-inside-code-signing-requirements/)。

Windows/Linux 本机交互本次未回归。Developer ID 签名、公证与公开发行仍未完成。
