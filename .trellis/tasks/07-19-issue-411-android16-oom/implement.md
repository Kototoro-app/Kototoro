# 执行计划

- [x] 读取 backend/frontend 规范，检查近期图片、Backdrop、详情和主页相关改动。
- [x] 建立 OOM 候选资源图，确认是否能在设备或测试环境复现。
- [x] 对已确认路径实施最小资源生命周期/尺寸修复。
- [x] 增加请求尺寸、路由切换或资源取消的回归保护。
- [x] 运行相关测试、Debug 编译、`git diff --check`，记录设备验证和残余风险。

## 验证记录

- #411 原始日志只有 `ViewRootImpl.performDraw`，但显示堆上限 512 MiB、GC 后不足 1%，没有应用侧调用帧。
- 代码审计确认 `DynamicArtworkBackdrop` 与 `KototoroApp` 的动态 artwork 背景使用
  `rememberAsyncImagePainter` 时没有 request size，可能按原始图片尺寸解码；Panorama 路径已有
  `rememberPanoramaRequestSize` 上限。
- 两个动态 artwork 请求现在统一限制为 `1280x1280`。该图像仅用于 35 px 强模糊的全屏背景，
  不影响封面或阅读内容质量。
- 无 Android 16 / SM-S918B 设备复现环境；设备验证仍待用户或 CI 提供，日志不足以证明全局缓存或
  Backdrop 是根因，因此没有修改全局 ImageLoader 缓存或吞掉 `OutOfMemoryError`。
