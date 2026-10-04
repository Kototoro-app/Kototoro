# Mihon desktop compatibility adapter

可选 Windows/JVM 平台初始化模块，供 `source-host` 与后续桌面应用装配。使用自己编写的 Application、
持久化 SharedPreferences 桥与可停止的主 Looper，调用本地指定的真实 Suwayomi AppModule/NetworkHelper
和 AndroidCompat API。没有复制研究 bootstrap、修改第三方字节码或覆盖 `os.name`。

默认不包含此模块。通过 `-PwithMihonDesktopCompat` 显式启用，并提供现有 API 目录：

```powershell
./gradlew.bat -PwithMihonDesktopCompat "-PmihonCompatibilityDirectory=D:/tmp/kt-compat36" `
    -PmihonCompatibilityJavaVersion=25 :mihon-desktop-compat:test
```

真实兼容 API 的 class major 为 65，需要 Java 21+；模块自己的字节码仍为 Java 11。Java fixture compiler、
测试进程和运行任务使用独立 toolchain，默认 21；本机只安装 17/25 时显式选 25，Android/Gradle JVM 不切换。
`compatibility-pins.properties` 核对五个关键 API 产物的 SHA-256；Windows 本地预览额外使用
`windows-runtime-pins.properties` 锁定完整选入的 29 个外部 JAR，拒绝集合/哈希变化。
外部 API 为 compileOnly/testRuntimeOnly，不进入模块 JAR 或默认 `source-host` 发行目录。
本模块不下载第三方 API；输入身份清单已接，S3 许可证/来源/notice 审计与公开分发门槛仍待。

扩展需要的 `kotlinx-serialization-json-okio` 由 Gradle 使用全项目统一的 serialization 版本提供，
与 Android 一致；排除该依赖的传递 Okio，沿用桌面原有 classpath 装配与版本选择。
离线扩展在浏览时通过真实 `OkioStreamsKt.decodeFromBufferedSource` 解析 JSON，桌面 UI、独立进程
及随包 JVM 的浏览检查都覆盖此 ABI，避免只验证扩展构造却遗漏响应解析依赖。

## JSONL 调试

在原 `SourceHostConfig` 中使用公开零参数 provider，并配置持久化目录：

```json
{
  "platformClass": "org.skepsun.kototoro.desktop.compat.MihonDesktopPlatform",
  "jars": [],
  "preferenceDirectory": "data/preferences",
  "imageDirectory": "data/images"
}
```

`jars` 按 [source-host 配置说明](../source-host/README.md) 填入已经核对的 path/package/versionCode/hash。
相对路径基于配置文件目录。零参数 provider 的数据根目录为偏好目录同级的 `compat`；嵌入调用可以显式
传入 `MihonDesktopPlatform(dataDirectory)`。初始化必须先传 store，再创建扩展。

```powershell
./gradlew.bat -q -PwithMihonDesktopCompat "-PmihonCompatibilityDirectory=D:/tmp/kt-compat36" `
    -PmihonCompatibilityJavaVersion=25 "-PsourceHostConfig=E:/path/to/config.json" `
    :mihon-desktop-compat:runSourceHost
```

任务把标准输入交给常驻 host，每行一个 SourceRequest，EOF 退出。Gradle 自身可能打印构建信息；程序化
JSON transport 直接调用 `SourceHostCliKt serve` 并使用显式 classpath，CLI 的 stdout 只含 UTF-8 响应，
第三方日志转 stderr。默认 `source-host.bat` 没有这些外部 API，不能直接运行这个 provider。

## 生命周期与能力边界

- 同一 JVM 只允许一个平台生命周期；初始化失败也需要新进程重试。扩展重载使用已有 registry，不能重建
  全局 SDK。拒绝已有全局 Koin/main Looper，不接管其他应用的状态。
- 真实网络 helper 使用自有 `cookie_store` 偏好；listener 派发到独立兼容主 Looper。私有 files/cache/no-backup
  目录由平台创建。没有使用 stock CustomContext/ConfigManager 或生成 `server.conf`。
- close 取消已知客户端调用、关闭 cache/pool/dispatcher，归还自己替换的 CookieHandler/Injekt/Koin，退出
  Looper；store 仍由调用方持有。CLI session 在平台之后关闭 store。
- SDK 的 UAFlow GlobalScope observer 没有公开 Job，依赖进程退出终结；WebView factory 也为进程级状态。
  因此 close 不代表清除了第三方 SDK 的全部后台任务或允许另一次平台初始化。
- SDK NetworkHelper 的 HTTP cache 仍创建在系统临时目录，close 释放句柄；其路径迁移/容量/遗留目录清理
  尚未实现。Application.cacheDir 已私有化，不等于 SDK HTTP cache 已私有化。
- WebView factory 从显式 `kototoro.compat.bridge.exe` 或 Compose 安装资源目录的 `browser/` 找到自有桥；
  显式路径无效不静默改用其他桥。二者都不可用时明确 unsupported，
  不触发默认 KCEF 下载。系统服务、Resources、PackageManager 明确 unsupported。
  桥 SDK DLL 的本机位置为 `build/webview2-sdk/1.0.4258.31/extracted`，
  `:mihon-desktop-compat:compileWebViewBridge` 使用 Windows Framework C# 编译器生成 exe。
  该任务尚未自动供应 SDK；缺少本机文件时不会得到可执行桥，不能视为成功验证。
  已验证离线 HTML/JS、真实请求头/UA/原始字节 POST、HTTP(S) base URL 与相对资源、停止/取消恢复，
  以及 SDK CookieManager/OkHttp 与 WebView2 的双向 Cookie 同步。回调在兼容主 Looper 执行。
  新浏览器首次同步清除 SDK 已删除的 profile Cookie；后续同步只推送 SDK 中变更的值，保留浏览器修改。
  删除 RPC 在原生 Cookie 查询确认目标已消失后返回成功，最长等待 2 秒；超时明确报错。
  桥提供可见、可缩放的 Windows 窗口与只读地址栏，关闭按钮隐藏窗口；最终进程关闭由桥 owner 负责。
  显示/隐藏命令可在导航期间执行，窗口句柄从初始化到销毁保持稳定。桌面调试页接入网址、窗口控制、
  当前页面 JS 和手动 Cookie 同步。桌面 Session 显式启用挑战交互；零参数平台/JSONL host 默认关闭。
  当前固定 SDK 恢复时截断带点的域名，平台初始化从同一个 `cookie_store` 快照通过公开 Cookie API
  补回缺失的有效持久化记录；不改第三方 JAR、不建立第二套 Cookie 持久化。该适配依赖固定存储格式。
  Secure/HttpOnly、host-only/domain、path、会话与过期属性已有恢复/清除测试；SameSite/分区 Cookie、
  SDK 同名多 path、多 WebView 并发、现代错误回调、完整 WebView API、真实挑战和代表源验收仍待。
  标准源设置、阅读器/下载 UI 与 Windows 本地 EXE/ZIP/MSI 已由 [desktop-app](../desktop-app/README.md) 接入；
  完整平台 API/代表源、实际安装及公开分发门槛仍待。

真实 API 的五项测试涵盖 Unicode/空格目录、Cookie 存储、Looper listener、初始化失败清理、跨进程设置
恢复、真实 Rx/client/interceptor 离线图片链、CLI provider 反射/UTF-8/EOF，以及 Windows 文件句柄释放。
fixture 独立编译成 JAR，不进入父 classpath 或模块发行 JAR；终端 interceptor 返回本地加密 PNG，不访问网络。
另用本地四个真实扩展加载/展开 77 个源，ID 与存档完整集合一致；只验证加载和元数据。
证据与日志见 [KMP 计划](../docs/architecture/kmp-ios-plan-2026-10.md) §33。
iOS Mobile JVM、JNI、WKWebView、真机门槛继续独立推进。

## 桌面来源的人工网页验证

`MihonDesktopPlatform(..., enableBrowserChallenges = true)` 在桥可用时发布 `browserChallenges.pending`。
桌面 UI 通过 prompt ID 确认/取消或重新显示窗口；接受后拉取 Cookie，原 GET/HEAD 请求只重试一次。
同一平台一次处理一个交互，默认超时两分钟；排队 Call 取消不会取消当前交互。桌面操作通过
`withRequestCancellation` 将协程 owner 传递到同步 SDK/Rx 调用，窗口关闭先结束交互再等待源任务。
JSONL host 不设置该选项，避免打开缺少确认 UI 的窗口；嵌入方启用后须处理 pending 与取消。

固定 SDK 的 CloudflareInterceptor 调用 FlareSolverr/ServerConfig，并不使用 WebView factory。
适配通过原 client.newBuilder 保留此 interceptor 的原实例，并在其后加入桌面验证处理，满足再漫画
等扩展对默认客户端的检查。后置处理拦截未解决的挑战及 SDK 特定的 403/503 响应，避免调用服务器
配置；保留 SDK CookieJar、UA、cache、dispatcher、connection pool 与其他拦截器。
不建立第二个 NetworkHelper 或修改第三方 JAR。
NetworkHelper 是 final 且没有公开 setter，因此版本适配集中于 `DesktopChallengeInterceptor.kt`：
核对拦截器数量、`client$delegate` / `cloudflareClient$delegate` 的 Lazy 字段类型，然后反射绑定
两个客户端到同一新实例；不匹配则初始化失败并清理。这是固定哈希 SDK 的私有 ABI 依赖，升级必须
重验，不能视为可兼容任意 Suwayomi 版本。普通 Gradle 验证五个关键 JAR，Windows 本地预览
额外验证所有选入外部 JAR；S3 审计/签名/升级验收仍待。

检测使用 [Cloudflare 官方响应标记](https://developers.cloudflare.com/cloudflare-challenges/challenge-types/challenge-pages/detect-response/)
`cf-mitigated: challenge`；普通 403 / Server: cloudflare 不触发交互。POST 等方法不自动重发。
SDK 识别的 403/503 + Server: cloudflare/cloudflare-nginx 转为明确的 IOException；验证后的响应仍带
挑战标记也明确失败。离线 fixture 检查默认客户端中保留真实 SDK CloudflareInterceptor 实例。
浏览器继承原请求 UA，push 同一 SDK Cookie store，接受后 pull；显式允许交互页面的 HTTP 4xx/5xx
导航，网络连接错误仍失败。常规 WebView/调试导航保持原行为。自有 loopback 扩展证明请求恢复，
不是实际 Cloudflare 求解或代表源兼容率证据。
