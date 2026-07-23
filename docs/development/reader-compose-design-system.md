# Reader Compose Design System

漫画与小说阅读器共享同一控制外壳，但不共享正文渲染业务。

## 稳定信息架构

- 顶栏：返回、作品名/章节、书签、更多。
- 进度区：小说使用连续位置轨道；漫画分页使用邻近页缩略图轨道，长条模式使用位置轨道。
- 底栏：固定为导航、显示、工具。更多只存在于顶栏。
- 导航负责位置选择，显示负责阅读呈现，工具负责对当前内容执行动作。

## Module seam

共享 Module 只公开：

- `ReaderControlDestination`
- `ReaderControlItem`
- `ReaderPrimaryControlBar`
- `ReaderControlGroup`
- `ReaderControlTokens`

它不认识章节、OCR、TTS、翻译引擎或图片页面。漫画与小说作为 Adapter 提供各自的标签、图标、状态和面板内容。

## 视觉规则

- 触控目标不小于 48dp，底部主入口区域不小于 72dp。
- 正文字号、图标尺寸不得为了提高密度而缩小；密度通过分层、二级编辑和减少平铺选项获得。
- 同组控件放入单一 `surfaceContainer` 容器；禁止一项一卡形成碎片化界面。
- 选中态使用 `secondaryContainer`，操作按钮使用 tonal 层级，危险操作单独使用 error 色阶。
- Sheet 内容宽度最大 760dp；手机使用完整可用宽度，大屏保持阅读设置的视觉聚焦。
- 连续参数在主层显示标题、当前值和进入符号，具体滑块放入二级编辑面板。
- 枚举选择在主层显示当前值，候选项使用菜单或二级面板，不在主 Sheet 全量平铺。

## Current adoption

- 漫画阅读器已使用共享三入口底栏，并在其上方保留分页进度轨道。
- 导航入口复用共享章节/预览/书签面板，显示入口连接 Compose 显示设置。
- 工具入口目前只连接已有翻译动作，下一阶段替换为动态工具 Sheet。
- 小说阅读器需在正文切换到同一 Compose 根之后接入，避免覆盖层抢占旧 View 手势。
