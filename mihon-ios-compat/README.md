# iOS Mihon JVM 平台适配器

可选模块；普通 Android/source-host/iOS 构建不包含本模块及第三方 API。
编译需 JDK 24、本地 Mobile AndroidCompat/Suwayomi JAR，以及 `compatibility-pins.properties` 中三个固定 API 摘要。
与 Windows 的 API pin 分离，自己的字节码仍为 Java 11。完整本地预览命令见 `../iosApp/README.md`。

```bash
./gradlew :mihon-ios-compat:stagePreview -PwithMihonIosCompat \
    -PmihonIosCompatibilityDirectory="/absolute/path/to/mobile-api"
```

平台使用应用沙盒中的独立 Application、持久化偏好桥、可停止的兼容 Looper、Koin/Injekt 与源 HTTP 客户端。
复用 `source-host` 的偏好和执行实现；没有启动服务端或使用上游 ExtensionHost。
每个 JVM 只允许一个兼容平台生命周期；关闭后重开须重启进程，扩展的常驻会话由 source-host 持有。
Close 尝试回收 HTTP 客户端、CookieHandler、容器和线程，最后释放偏好桥。

WKWebView 与 CookieManager 由本模块的 `IosWebKit`、`IosWebViewProvider` 和 `IosCookieManager` 接到 Swift。
WebView 基类提供扩展所需的显示资源；扩展生成的 MotionEvent 经 ViewDelegate 转为页面指针与点击事件，
包括章节分页按钮中的 SVG 子元素。触摸事件不依赖 Android 的原生输入系统。
图形、JavaScript、PDF、常用 ICU、系统资源和压缩由项目自写的 [`mihon-ios-shims`](../mihon-ios-shims/README.md)
提供，JNI 在 VM 启动时注册，不加载 TachiyomiAzIOS 的 shim JAR 或 boot shim。
包管理、未列出的系统服务以及复杂 Android framework API 仍明确 unsupported；支持边界见该模块 README。
Comix 1.6.59 使用的 ImageReader/虚拟显示链路映射为 WKWebView 后台呈现；页面不经 Android 图像队列。
Presentation 的挂载、尺寸和释放经 `IosBrowserPresentation` 回调进入本模块，再由 Swift 执行；
WebView 销毁时先解除 Java 父子关系，让扩展的层级监听器释放 reader、虚拟显示和 HandlerThread。
详情/目录加载与正文图片兼容性须分别验证，不能以目录成功推定正文可读。
已用真实 Comix 1.6.59 验证列表、详情/章节和首章图片（iOS 模拟器）；
`BrowserDisplayTest` 覆盖无帧队列、挂载失败、父子关系、显示释放、重复关闭及排空线程退出。
本模块已验证再漫画 1.6.0 的转换/枚举/公开列表、详情/封面/章节及首章图片阅读；
默认预览和真实 APK 预览均非正式发行，S3 与真机门槛待完成。
