# 官方依据对照表

本文件记录 Kototoro 设计决策与官方权威来源的逐条对照，用于在官方规范更新
（WWDC、Material 3 版本迭代）时增量核对。它是说明性文档，不是规范来源；
数值和语义冲突仍以 [DESIGN.md](./DESIGN.md) Frontmatter 为准。

## 1. iOS Liquid Glass（Apple）

官方来源：

- [Meet Liquid Glass — WWDC25 session 219](https://developer.apple.com/videos/play/wwdc2025/219/)
- [Apple HIG: Materials](https://developer.apple.com/design/human-interface-guidelines/materials)
- [Apple HIG: Accessibility](https://developer.apple.com/design/human-interface-guidelines/accessibility)

| # | 官方要求（原文要点） | Kototoro 立场 | 对应规范 | 状态 |
| :--- | :--- | :--- | :--- | :--- |
| 1 | Glass 最好只用于导航层；不要放在内容层 | 内容层禁止 Glass，Glass 只承载导航与控制 | [ios-glass.md](./ios-glass.md) §2 | ✅ 一致 |
| 2 | Regular 与 Clear 变体 never be mixed | 同一屏幕不得混用；当前只实现 Regular，禁止用 `containerAlpha` 模拟 Clear | [ios-glass.md](./ios-glass.md) §2.3 | ✅ 已补 |
| 3 | Clear 三条件：媒体背景 + 可接受变暗层 + 前景 bold and bright | 三条件完整收录 | [ios-glass.md](./ios-glass.md) §2.3 | ✅ 已补 |
| 4 | Avoid glass on glass；玻璃上的次级元素用 fills/transparency/vibrancy | 禁止玻璃叠玻璃；Glass 容器内次级条目用稳定 Surface 或纯填充 | [ios-glass.md](./ios-glass.md) §2.2 | ✅ 已补 |
| 5 | 小控件明暗可翻转；大表面（菜单/侧栏）don't flip | 小控件可翻转，大表面只做轻微色调调整 | [ios-glass.md](./ios-glass.md) §3 | ✅ 已补 |
| 6 | 玻璃元素进出场通过调制 lensing materialize，不存在 fade | 进出场优先位移动画；淡入淡出仅作低版本降级 | [ios-glass.md](./ios-glass.md) §7 | ✅ 已补 |
| 7 | Reduced Transparency 让玻璃 frostier、遮住更多背景 | Kototoro 的“减少视觉特效”开关整体关闭 Glass、直接降 Stable；Apple frostier 语义仅作参考，不模拟（Android 无系统级 Reduce Transparency） | [ios-glass.md](./ios-glass.md) §8 | ⚠️ 有意简化 |
| 8 | Increased Contrast 使元素趋向黑白并加对比边框 | 提高不透明度与前景对比，关键元素趋向黑白 + 对比边框 | [ios-glass.md](./ios-glass.md) §8 | ✅ 已补 |
| 9 | Reduced Motion 减弱效果强度并禁用弹性属性 | 取消视差、折射变化和大幅形变 | [ios-glass.md](./ios-glass.md) §8 | ✅ 一致 |
| 10 | 阴影随背景自适应：文本上加深、纯色亮背景上变浅 | 动态阴影尚未实现，固定 Shadow；纯白/纯黑背景通过 Surface tint 补明度差 | [ios-glass.md](./ios-glass.md) §3 | ⚠️ 已知差距 |
| 11 | 玻璃 tint 跟随背景内容（tinting system） | 表面染色从语义色派生，不实时提取高饱和色 | [ios-glass.md](./ios-glass.md) §3 | ✅ 一致（刻意更保守） |
| 12 | 连续圆角是 Liquid Glass 的几何基础 | 尚未落地 continuous corner；见 Miuix 参考章节 | [ios-glass.md](./ios-glass.md) §4 | ⚠️ 已知差距 |
| 13 | 色差/折射（chromatic aberration）是 Liquid Glass 的组成部分 | 实现关闭 `chromaticAberration`（性能决策），文档未记录 | [GlassSurface.kt](../../app/src/main/kotlin/org/skepsun/kototoro/core/ui/glass/GlassSurface.kt) | ⚠️ 有意偏离，建议补充记录 |

## 2. Material 3 Expressive（Google）

官方来源：

- [Expressive Design: Google's UX Research](https://design.google/library/expressive-material-design-google-research)
- [Material 3 in Compose](https://developer.android.com/develop/ui/compose/designsystems/material3)
- [Compose Carousel](https://developer.android.com/develop/ui/compose/components/carousel)
- [MaterialExpressiveTheme API](https://developer.android.com/reference/kotlin/androidx/compose/material3/MaterialExpressiveTheme)
- [MotionScheme API](https://developer.android.com/reference/kotlin/androidx/compose/material3/MotionScheme)

| # | 官方要求（原文要点） | Kototoro 立场 | 对应规范 | 状态 |
| :--- | :--- | :--- | :--- | :--- |
| 1 | Expressive 由 color / shape / size / motion / containment 五要素构成，为可用性服务 | 语义颜色、形状层级、排版、组件状态与一致动效；表现力预算 | [material3-expressive.md](./material3-expressive.md) §1-3 | ✅ 一致 |
| 2 | 每个屏幕注意力集中到关键元素；同一时刻一个焦点 | 每个屏幕只选择一个主要表现手段 | [material3-expressive.md](./material3-expressive.md) §3 | ✅ 一致 |
| 3 | 少数用户偏好 calmer 版本；不熟悉的新形式有学习成本 | 默认克制；Expressive 不等于普遍放大加粗 | [material3-expressive.md](./material3-expressive.md) §4.1 | ✅ 立场一致，默认激进度可再收敛 |
| 4 | 打破基础交互范式会导致可用性下降（乱排播放列表反例） | 收藏/历史/下载/搜索保持标准 grid/list；Discover 提供列表模式保底 | [interface-style-system.md](../development/interface-style-system.md) §12.3 | ✅ 一致 |
| 5 | Carousel 避免自动推进 | Hero Carousel 默认不自动轮播；系统动画关闭时强制禁用 | [material3-expressive.md](./material3-expressive.md) §4 | ✅ 已修复实现 |
| 6 | 触控目标、对比度应达到并超过无障碍标准 | 48 dp 最小触控；12 sp 元数据下限（封面微标记 11 sp 例外） | [DESIGN.md](./DESIGN.md) frontmatter | ✅ 一致 |
| 7 | 动效按 spatial / expressive / effects 分级，由 MotionScheme 统一提供 | 文档已规定分级意图；尚未接入官方 MotionScheme，动效常量散落业务层 | [interface-style-system.md](../development/interface-style-system.md) §4.8 | ⚠️ 已知差距 |
| 8 | 排版 ramp 使用大字号 display 建立层级 | 刻意收敛到 28/36 主标题，display 角色未映射（57/45/36 默认值保留） | [KototoroTheme.kt](../../app/src/main/kotlin/org/skepsun/kototoro/core/ui/theme/KototoroTheme.kt) | ⚠️ 有意偏离，display 角色需显式对齐 |
| 9 | LoadingIndicator 形状变形是高注意力组件，按场景使用 | 当前实现是 AndroidView 包装 Material progress indicator，非 Compose 组件 | [interface-style-system.md](../development/interface-style-system.md) §12.5 | ⚠️ 文档已修正为现状 + 目标态 |
| 10 | Expressive 形状使用变量圆角/连续曲线 | Kototoro 使用固定圆角等级（28/20/24/36），业务硬编码需收敛到 token | [DESIGN.md](./DESIGN.md) frontmatter `rounded` | ⚠️ 已知差距 |

## 3. 维护规则

- 官方文档更新（如 WWDC 2026、Material 3.5）后，在本表新增或修订对应行，并在
  `DESIGN.md` Known gaps 记录需要落实的项。
- 本表只描述"官方要求 — Kototoro 立场 — 状态"，不重复规范正文。
- "有意偏离"必须在对应规范文档中记录理由，否则视为待修复差距。
- Backdrop 库版本记录：当前 `2.0.0-alpha03`，库 API 事实（effect 顺序 color filter → blur →
  lens、blur 需 Android 12、lens/RuntimeShader 需 Android 13、`lens` 仅支持 `CornerBasedShape`、
  Highlight/Shadow 为静态默认值、无内容感知阴影与边缘消隐）以对应 tag 源码为准，升级后复核。
