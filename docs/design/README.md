# Kototoro 设计语言文档库

本目录定义 Kototoro 的产品级设计语言。它是界面设计、Compose 实现和 UI 审查的规范来源，
不记录临时实现过程。

Kototoro 只维护一套产品体验和两套视觉表达：

- **Material 3 Expressive**：Android 原生表达，依靠颜色、形状、排版和动效建立层级。
- **iOS Glass**：克制的通透表达，只在导航与控制层使用 Glass。

两种风格必须共享信息架构、操作位置、组件语义、状态模型和无障碍基线。风格切换不能改变用户对
Kototoro 的理解方式。

## 文档地图

| 层级 | 文档 | 作用 |
| :--- | :--- | :--- |
| L0 入口 | [Kototoro Design System](./DESIGN.md) | 机器可读令牌、共享约定、组件映射和设计护栏 |
| L1 基础 | [Kototoro 核心语言](./kototoro-design-language.md) | 品牌原则、层级模型、共性交互和跨风格契约 |
| L2 风格 | [Material 3 Expressive](./material3-expressive.md) | Android 原生视觉语法 |
| L2 风格 | [iOS Glass](./ios-glass.md) | iOS 风格材质、层级、动效与降级规范 |
| L3 场景 | [阅读与播放体验](./reading-experience.md) | 漫画、小说和动画的沉浸式体验 |
| L4 组件 | [组件与令牌](./components-and-tokens.md) | 共享组件解剖、尺寸、密度和状态规则 |

工程迁移顺序、现状差距和历史依据继续维护在
[界面风格系统实施文档](../development/interface-style-system.md) 中。

## 规范优先级

出现冲突时，按以下顺序处理：

1. 无障碍、可读性和系统行为；
2. `DESIGN.md` 的结构化语义和数值令牌；
3. Kototoro 核心语言；
4. 场景规范；
5. 当前风格规范；
6. 组件默认值与已有实现。

官方平台文档决定平台行为，Kototoro 文档决定产品身份。第三方项目只能作为实现或组织方式参考，
不能直接成为视觉规范。

## 维护规则

- 新组件先定义语义和状态，再决定 Material 或 Glass 的渲染方式。
- 共享字号、尺寸、间距、颜色角色和组件映射先更新 `DESIGN.md` Frontmatter。
- 能由共享令牌表达的差异，不在业务页面硬编码。
- 规范中的“必须”是合并前验收条件；“建议”允许在记录理由后偏离。
- 新增视觉效果必须说明它服务的层级或状态；没有明确作用的效果不进入设计系统。
- 影响多个页面的 UI 决策先更新本库，再修改实现。
- 每次大范围 UI 变更至少验证浅色、深色、窄屏、大屏、字体放大和减少动态效果。

## 权威来源

- Android 与 Material：Android Developers、Material Design 官方文档和官方 Compose 示例。
- iOS：Apple Human Interface Guidelines 与 Apple Design Resources。
- Backdrop：仅作为 Kototoro iOS Glass 的 Android 渲染实现依据。
- Miuix：只参考紧凑布局、圆角连续性和 Overlay 组织，不作为设计权威，也不引入依赖。
