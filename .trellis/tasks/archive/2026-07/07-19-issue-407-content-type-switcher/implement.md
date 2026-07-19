# 执行计划

- [x] 读取 frontend 规范及已有 `unify-popup-menu-glass` 任务，确认当前实现和未完成部分。
- [x] 修复内容类型列表、小说按钮和中心/左右布局。
- [x] 接入已有 root Backdrop host，移除原始按钮的额外容器。
- [x] 抽取并测试长按、拖拽、松开和取消状态转换。
- [x] 验证 Space 开启/关闭、iOS/Material 3、窄屏和旋转路径，运行编译与 `git diff --check`。设备视觉验收未执行，保留为风险。

## Verification

- `:app:testDebugUnitTest --tests org.skepsun.kototoro.main.ui.compose.SwipeableFilterChipTest`：通过。
- 该测试同时触发 `:app:compileDebugKotlin`：通过。
- `git diff --check`：通过。
