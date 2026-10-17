# iOS 阅读布局与宽窗口适配（2026-10-17）

- 新的阅读偏好默认右到左分页；已保存的全局和作品设置继续生效。
- 原 `doublePage` 字段及默认关闭值保留，界面改名为“自动双页（横屏或宽窗口）”。
  开启后，仅分页模式在横向窗口或 regular 水平尺寸类别下使用双页；普通竖屏、窄分屏及连续条漫
  保持单页/连续布局。布局计算使用偏好的副本，不会把临时单页结果写回开关。
- 展开后的 iPhone Duo 使用 regular 水平尺寸类别，因此按当前窗口的 SwiftUI 环境适配；
  不依赖机型字符串、全局屏幕或设备方向。窗口缩窄后重新排页，当前阅读页仍由既有模型恢复。
- 主导航使用原生 `TabView` 的 `sidebarAdaptable` 样式及侧栏首选位置，窄窗口保留标签导航。
- 双页先分别适配半个视口，再将两页宽度与间距整体居中；左右阅读顺序和进度锚点保持。
  原实现对不等宽页面分别按半屏摆放，产生偏移；LTR 还可能留下额外的页间空隙。
  `KEEP_START` 仍保留用户指定的起始位置行为。

本机只有 Xcode 27.0 / iOS 27.0，无 Duo 设备类型。Apple 说明使用 iOS 27.1 SDK 构建，标准导航
才能充分采用 Duo 的全屏、边缘竖排布局；完整姿态及不对称安全区域仍需在 Xcode 27.1 的 Duo
模拟器上验收。本轮不将普通 iPhone 或宽窗口测试称为 Duo 专机验证。

参考：[Apple：Prepare your app for iPhone Duo](https://developer.apple.com/videos/play/tech-talks/111461/)、
[SwiftUI sidebarAdaptable](https://developer.apple.com/documentation/swiftui/tabviewstyle/sidebaradaptable)。

回归覆盖：默认偏好与旧 JSON、已有双页状态、普通竖屏/横屏/regular 宽窗口/窄分屏、
连续模式禁用双页、不等宽双页的整体居中/无额外间距/阅读顺序和进度锚点。

已通过全部 190 项 reader-core JVM 测试、9 项 Swift 偏好/作品库索引测试。
模拟器使用已有本地 `fixture_comic`：旧版双页开关开启后安装新包，横屏继承开启状态，
两页相邻并整体居中；旋回普通竖屏后显示一页。宽窗口及 Duo 专机验证边界见上文。
竖屏设置面板确认开关仍为开启，未被临时单页布局改写；关闭开关后，横屏也仅显示一页。
测试结束已将该 fixture 的双页、封面开关还原为原值。

最终真实源应用包构建成功并安装，安装包动态库与构建产物 SHA-256 一致。
普通 iPhone 的 Resize mode 调至 900×675 后仍采用底部标签栏；调整像素尺寸并不等于取得 Duo
的 regular 尺寸类别及系统姿态行为，因此边缘竖排导航尚未完成专机视觉验收。
