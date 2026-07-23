# Compose 小说阅读器功能等价矩阵

本文定义小说阅读器从 XML/View/Fragment 迁移到纯 Jetpack Compose 的验收边界。旧实现仍是行为权威；在对应能力具备测试或设备验证前，不删除其唯一实现。

## 目标架构

- `NovelReaderActivity` 最终继承 `BaseComposeActivity` 并直接设置 Compose content，不 inflate XML，也不持有 ViewBinding 或 `ComposeView`。
- ViewModel 暴露不可变 UI state；Composable 只消费状态并上报事件，符合 Compose 单向数据流与状态提升原则。
- 分页使用 Compose Foundation Pager，连续阅读使用带稳定 key 的 `LazyColumn`。
- 顶栏、底栏、TTS、信息栏、消息、加载、章节和设置均使用 Material3 Compose。
- Space 切换继续使用全局 `SpaceSwitcherDelegate`，小说阅读器不创建专用实现。

## 等价矩阵

| 能力 | 旧实现 | Compose 验收要求 | 当前状态 |
| --- | --- | --- | --- |
| 章节加载与本地/在线回退 | `NovelContentLoader`、`EpubInternalChapterLoader` | 保留 EPUB、Legado、本地缓存与远端回退契约 | 业务层保留，待从 Activity 提升状态 |
| 分页阅读 | `NovelReaderView` | 字符级分页、拖动翻页、RTL、双页、旋转后锚点稳定 | 未迁移 |
| 连续阅读 | `RecyclerView`、`NovelContinuousAdapter`、`NovelChapterView` | 跨章追加/前插、稳定锚点、边界加载、进度准确 | Compose 多章节 `LazyColumn` 已接管唯一可见正文，支持稳定 key、前插/追加、图片、翻译、当前章节同步和 Compose 进度轨道；旧 Adapter 仅保留为回退业务实现，分页分支暂未切换 |
| 排版 | `NovelTypography`、`NovelReaderSettings` | 字体、字号、行距、段距、边距、对齐、方向即时生效 | 基础 Compose 消费者已接入 |
| EPUB/网络内嵌图片 | `NovelInlineImageSupport`、`NovelChapterView` | headers、EPUB 相对路径、点击查看与失败状态完整 | Compose 已接入加载与点击；需补失败/占位语义 |
| 翻译 | `NovelTranslationProcessor` | 原文/译文/双语模式切换不丢锚点，部分结果渐进显示 | Compose 文档块已接入；需设备验证与状态提升 |
| TTS | `TtsService`、Activity View 控件 | 播放、暂停、前后句、音色、跟随高亮、跨页/章连续 | 播放状态与控制条已迁入 ViewModel/Compose；连续 Compose 正文已根据 token 字符范围高亮普通文本段落。内嵌图片段落和分页正文高亮仍待迁移 |
| 触控、键盘和音量键 | `ReaderControlDelegate`、View 手势 | 九宫格、长按、翻页方向、无障碍不回归 | Compose 仅有章节内点击入口 |
| chrome 与全屏 | XML toolbar、`ReaderActionsView`、`ReaderInfoBarView` | Compose 控件、系统栏、Insets、动画和消息统一 | 加载状态、临时消息和 TTS 控制条已迁入 ViewModel 驱动的 Material3 Compose overlay；连续滚动分支的 Compose 正文已启用，工具栏、信息栏与主操作栏仍待迁移 |
| 章节与设置面板 | `NovelChaptersSheet`、`NovelReaderConfigSheet` | Material3 Compose、自适应分区/多列布局、状态提升 | 已迁移：设置面板使用分区 Chip/Slider/Switch 与自适应操作区；章节面板支持搜索、正反序、分组、当前章定位和宽屏宽度约束；对应 Fragment、Adapter、ViewBinding 和 XML 已删除 |
| Space | `SpaceSwitcherDelegate` | 复用全局仓库、会话恢复、切换协调、Sheet 和转场 | 当前使用共享 Delegate；待直接组合全局 Compose FAB |

## 删除门槛

1. `NovelReaderView` 的分页、图片、翻译和 TTS 高亮均有 Compose 等价实现。
2. `NovelContinuousAdapter`/`NovelChapterView` 的跨章锚点和边界加载通过测试。
3. `NovelReaderConfigSheet` 与 `NovelChaptersSheet` 已由 Compose modal 替代。
4. Activity 不再引用 `viewBinding`、`findViewById`、`RecyclerView`、`View` 手势或 View Transition。
5. 删除 `activity_novel_reader*.xml`、小说 Sheet/item XML 和对应 View/Fragment 后编译与定向测试通过。
