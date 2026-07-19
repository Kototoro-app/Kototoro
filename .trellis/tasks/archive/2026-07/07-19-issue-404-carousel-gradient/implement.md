# 执行计划

- [x] 读取 frontend 规范，确认轮播背景和渐变的现有约束。
- [x] 抽取或简化底部渐变参数，降低图片背景上的不透明纯色覆盖。
- [x] 覆盖 detached/non-detached、明暗主题和 Panorama 开关的回归。
- [x] 运行相关测试、`:app:compileDebugKotlin` 和 `git diff --check`。
- [x] 记录设备/截图验收结果后完成子任务。设备截图未执行，保留为视觉验收风险。

## Verification

- `:app:testDebugUnitTest --tests org.skepsun.kototoro.discover.ui.compose.DiscoverHeroCarouselTest`：通过。
- `:app:compileDebugKotlin`：通过。
- `git diff --check`：通过。
