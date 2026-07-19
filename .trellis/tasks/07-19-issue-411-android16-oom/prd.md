# Issue 411：定位并修复 Android 16 OOM 崩溃

## Goal

根据 #411 的 Android 16 崩溃日志定位应用侧内存压力来源，修复可证明的资源生命周期或尺寸问题，避免在绘制阶段触发 OOM。

## Confirmed Facts

- Kototoro 1.6.3 build 1196、Android 16、Samsung SM-S918B。
- `java.lang.OutOfMemoryError` 发生在 `ViewRootImpl.performDraw`，堆上限约 512 MiB，GC 后剩余不足 1%。
- 日志没有暴露应用侧调用帧，因此根因需要结合近期图片、Panorama、Backdrop 和页面生命周期代码进一步证实。

## Requirements

- 先建立内存压力来源证据，再选择资源上限、取消、复用或生命周期修复。
- 重点检查动态图片/Panorama、Backdrop/玻璃渲染和页面切换时的旧资源是否同时存活。
- 不用全局吞掉 `OutOfMemoryError`，不通过无提示地删除用户内容解决问题。
- 修复后保持必要图片质量、页面功能和非 Android 16 行为。

## Acceptance Criteria

- [ ] 已记录根因或最小可复现资源路径，并能解释为何会在绘制阶段耗尽堆。
- [ ] 目标路径限制峰值资源或正确释放旧资源，重复进入/切换/滚动不会无限累积。
- [ ] 相关单元/静态测试通过；设备验证或无法复现的限制有明确记录。
- [ ] Debug 编译和 `git diff --check` 通过。

## Out Of Scope

- 不进行无证据的全局图片缓存重构。
- 不把 OOM 统一转成普通错误后继续使用已经失效的渲染资源。
