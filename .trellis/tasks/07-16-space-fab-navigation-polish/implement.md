# 执行计划：Space FAB 与主导航栏自适应及视觉打磨

## 前置检查

- [x] 审阅 `prd.md`、`design.md` 和本执行计划。
- [x] 确认只修改 UI 布局、样式、测试和必要文档，不修改 Space 切换数据流。
- [x] 在实现前读取 `trellis-before-dev` 指向的 frontend/core UI 规范。

## 实施顺序

### 1. 导航上限与布局规格

- [x] 将主导航产品上限恢复为 5 项。
- [x] 搜索现有导航宽度、FAB 尺寸和 inset 计算，避免重复常量。
- [x] 增加可测试的导航/FAB 布局规格解析，覆盖 regular、compact、minimum 三种密度。
- [x] 让浮动导航把 FAB 宽度、间距、系统 inset 纳入可用宽度预算，并让导航栏容器按按钮内容宽度包裹。
- [x] 保持所有导航项和 FAB 的最小点击区域不低于 48dp。

### 2. Compose FAB 视觉层级

- [x] 将主界面/列表/详情的 Space FAB 接入现有 `GlassSurface`/Haze 体系。
- [x] 为 FAB 选择区别于 BottomBar 的强调色、边框和阴影层级。
- [x] 保留 Space 图标、custom monogram、content description 和现有点击行为。
- [x] 验证明暗主题、玻璃开关关闭和低版本 fallback（复用现有 `GlassSurface` fallback 管线）。

### 3. 详情、阅读器和播放器避让

- [x] 保持列表和详情右下位置不变，沿用现有底部面板展开/收起避让逻辑。
- [x] 保持播放器位于 control bar 上方，沿用现有 control bar 高度避让逻辑。
- [x] 为阅读器计算 docked toolbar、zoom control、timer control 的右下遮挡，避免 FAB 重叠。
- [x] 通过窗口 inset 和下一次布局回调覆盖全屏/旋转后的重新定位；reduced motion 不改变布局语义。

### 4. 传统 View FAB fallback

- [x] 为阅读器/播放器 XML FAB 使用与 Compose FAB 对齐的颜色、边框、圆角/尺寸和 elevation token。
- [x] 不引入新的 Haze source 或改造阅读器/播放器页面根布局。

### 5. 回归验证

- [x] 增加/更新布局规格单测和必要的导航上限回归测试。
- [x] 运行 Debug 编译、JVM 单测；lint 分析因仓库环境长时间无输出而终止，未得到 lint 诊断结果。
- [x] 尝试 AndroidTest 编译；被仓库既有旧 API 测试阻塞，已记录具体文件、错误和本任务关联性。
- [x] 检查 git diff、未改动无关文件和任务文档一致性。

## 验证结果

- `:app:compileDebugKotlin`：通过。
- `:app:testDebugUnitTest`：通过；包含主导航上限和三档布局规格测试。
- 用户回归反馈：修复按钮数量较少时导航栏仍固定占满剩余宽度的问题，改为内容自适应宽度。
- 用户回归反馈：降低 FAB 白色玻璃边缘的视觉权重，提高 `primaryContainer` 强调色覆盖度，避免内部出现白色几何高亮。
- Haze 伪影修复：为 `GlassSurface` 增加独立的 `expandHazeLayerBounds` 参数，圆形 FAB 使用 `false`，避免扩展的 Haze 层越出圆形边界形成白色多边形；未使用 `dialogSurface`，保留 FAB 阴影。
- 用户回归反馈：expressive 五项导航在 compact/minimum 档保留选中项文本，使用缩小字号和标签宽度限制；仅在 48dp 触摸目标也无法满足时隐藏文本。
- 用户回归反馈：Space 过渡幕布保持原有背景语义，暗色主题通过 `LocalContentColor = Color.White` 提升幕布内文字和图标对比度；浅色 FAB 强调色覆盖度提高到 86%，继续压制白色几何高亮。
- `:app:compileDebugAndroidTestKotlin`：被既有 AndroidTest 旧 API 问题阻塞，涉及 `SampleData.kt`、`AppShortcutManagerTest.kt`、`AppBackupAgentTest.kt`、多个 integration test，以及 `SpaceWorkDaoTest.kt` 的旧构造函数调用；与本任务生产代码无关。
- `:app:lintDebug`：分析长时间无输出，因资源占用过高终止；没有产生 lint 报告或具体 lint finding。
- `git diff --check`：通过。

## 验证命令

```bash
JAVA_HOME="/usr/lib/jvm/java-17-openjdk-amd64" ./gradlew :app:compileDebugKotlin --no-daemon
JAVA_HOME="/usr/lib/jvm/java-17-openjdk-amd64" ./gradlew :app:testDebugUnitTest --no-daemon
JAVA_HOME="/usr/lib/jvm/java-17-openjdk-amd64" ./gradlew :app:lintDebug --no-daemon
JAVA_HOME="/usr/lib/jvm/java-17-openjdk-amd64" ./gradlew :app:compileDebugAndroidTestKotlin --no-daemon
```

## 风险与回滚点

1. 导航宽度预算：若窄屏仍无法容纳，先回滚视觉压缩，不降低点击目标或静默丢弃导航项。
2. Haze 样式：若低版本或性能不达标，回退到同 token 的半透明容器，保留布局和交互修复。
3. 阅读器避让：若辅助控件状态不稳定，优先隐藏 FAB 或沿用现有 bottom margin，不能遮挡阅读操作。
