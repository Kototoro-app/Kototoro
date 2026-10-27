# Kototoro iOS JVM shims

本模块独立实现 iOS 嵌入式 JVM 缺少的扩展平台 API，以 Android 公开 API、JVM 扩展 ABI 和 Apple 公开框架为依据。
不包含 TachiyomiAzIOS 的 Java shim 源码或二进制，不依赖其 `NativeBridge`。模块只随
`-PwithMihonIosCompat` 启用，默认 Android / source-host 发行不包含它。

`iosApp/Native/KototoroNative.mm` 是配套的 JNI 实现。嵌入式 JVM 启动时注册 native 方法；macOS 测试用同一文件
编译成动态库，通过 `kototoro.native.library` 加载，没有另一套模拟后端。

| 能力 | 实现 |
| --- | --- |
| Bitmap / BitmapFactory / ImageDecoder | Java ARGB 像素缓冲区，ImageIO 解码、采样和 PNG/JPEG 编码；裁剪、复制、像素读写与回收 |
| Canvas | Java 仿射变换、缩放、裁剪和 alpha 合成；平移、旋转、斜切与 save/restore |
| Paint / Typeface / TextPaint / Layout / StaticLayout | CoreText 字体测量与渲染；换行、折行和常见文本对齐 |
| PdfRenderer / ParcelFileDescriptor | CoreGraphics PDF 渲染；单页生命周期、描述符所有权和关闭检查 |
| QuickJs ABI | 每实例独立的 JavaScriptCore context；同步求值、Java 接口绑定、JS 接口代理、异常与关闭 |
| ICU 常用 API | JDK 规范化、排序和分词；保留 UTF-16 搜索位置。NFKC casefold 使用固定 Unicode 17 官方数据 |
| JsonReader | 自写流式 token 状态机，嵌套跳过、类型检查、完整 64 位整数和可选宽松解析 |
| zstd | 已有 aircompressor 的 Java 编解码器，供 OkHttp 和 Okio ABI 使用，关闭传递到底层流 |
| 系统资源 / 内存 / 时钟 | 独立 Resources、Configuration、DisplayMetrics、JVM 内存数据与单调时钟 |
| WebView 基类 / 输入事件 | AbsoluteLayout 显示资源；内存 MotionEvent 指针数据，经兼容 provider 转发到 WKWebView 页面 |
| 浏览器虚拟显示 | RGBA ImageReader/Surface 渲染目标、DisplayManager/VirtualDisplay/Presentation 生命周期，交由 WKWebView 挂载和渲染；HandlerThread 使用真实兼容 Looper |
| 网络适配 | ServerConfig 的 FlareSolverr 配置 ABI 和 UserAgentInterceptor；WebView 与 Cookie 在 mihon-ios-compat |
| 网络传输 | 替换 OkHttp 5.4.0 的 `ConnectInterceptor`：连接、TLS 与解压改由 URLSession 执行（`NativeTransport`）；应用/网络拦截器、重定向、Cookie jar、缓存仍在 OkHttp。WebSocket、自定义信任/主机名校验、证书固定与显式代理保留 OkHttp 连接；`-Dkototoro.native.net=false` 关闭 |
| 消息摘要 | JCA 提供者 `KototoroIOS`（CommonCrypto 的 MD5/SHA-1/SHA-256/SHA-384/SHA-512，含常用别名与 OID）在 native 注册后插到首位，图片物化、产物校验、扩展与 JSSE 的 `MessageDigest` 均使用；`-Dkototoro.native.digest=false` 关闭 |

明确的能力边界：这不是完整 Android framework。QuickJS 引擎专用字节码不能由 JavaScriptCore 执行，`compile/execute`
会明确报不支持；PDF 当前只支持整页默认变换；ImageDecoder 自动去边和背景覆盖不支持；复杂 Android span/bidi 排版、
任意 Path、宽色域和硬件 Bitmap 不支持。WebP 编码仅在系统 ImageIO 实际提供该编码器时成功，不把其他格式伪装成 WebP。
JDK collation 与 ICU 的语言排序不保证完全一致。

浏览器虚拟显示仅覆盖扩展将 WebView 放入 Presentation 并排空 ImageReader 的用法。页面由 WKWebView 直接渲染，
没有 Android CPU 图像队列，因此 acquire 返回 null，不伪造帧或图像回调；不支持像素平面读取、录屏、镜像或安全显示。
Presentation 移除、虚拟显示释放和 WebView 销毁会解除挂载，移除回调可关闭 reader 和排空线程。
为保留固定 AndroidCompat 的完整 View ABI，构建时沿用其 View.class，仅将仍为桩的 final getParent()
改为读取既有 mParent 字段；其他方法保持输入字节码。此生成类保留输入 API 的来源与许可，不视为独立重写。

Unicode 数据的原始地址与 SHA-256 记录在 `nfkc-casefold-17.txt`，随 JAR 包含 Unicode-3.0 许可。
OpenJDK、AndroidCompat/Suwayomi 和其他依赖仍保留各自来源及许可；自行替换 shim 不等于重新许可这些依赖。

验证（Apple Silicon Mac、Xcode、JDK 24、已准备的 Mobile API）：

```bash
python3 "iosApp/Scripts/test-ios-shims.py"
python3 "iosApp/Scripts/build-jvm-preview.py"
python3 "iosApp/Scripts/stage-mihon-preview.py"
python3 "iosApp/Scripts/test-jvm-simulator.py"
python3 "iosApp/Scripts/test-jvm-simulator.py" --shims
```

第一条运行纯 JVM 测试和真实 Apple 框架 JNI 测试。构建与打包从固定 OpenJDK Mobile 源码、本项目 iOS/Zero 适配和本次
静态库的 `nm` 输出构建运行时，不读取 TachiyomiAZ 的源码、补丁或导出符号表，也不从 IPA 提取 Java 模块镜像或
boot shim。固定的主机 JDK 26 只作为构建工具；使用该 Mobile 修订的 JlinkTask，禁用依赖主机 runtime internals 的
JLI 预生成。iOS Java 类、时区和证书数据来自本地 OpenJDK 构建。
最后两条在启动的模拟器中直接执行无 UI、无网络的回归：同一个 C/JNI transport，以及真实 JavaScriptCore、
UTF-16、ImageIO、CoreText 和 JAR 资源读取。资源流回归同时验证 iOS 的 Core JDK Darwin 平台映射。
它们不替代完整应用和在线扩展的 UI 测试。

兼容 API 与已有第三方依赖目前仍从固定 SHA-256 的发行容器提取：仅 AndroidCompat、Suwayomi 与其他基础库，
排除所有 `000-*` shim JAR。预览将 `00-kototoro-ios-shims.jar` 排在第一位，并拒绝 `app/tachiaz/` 类与旧 boot profile。
`runtime-origin.json` 和 `inventory.json` 分别记录运行时来源和最终预览文件的摘要。产物保留在 ignored `iosApp/build`。
