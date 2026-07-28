---
version: 1
name: kototoro
description: >-
  Content-first Android design system for manga, novels, and animation.
  It defines one product experience with Material 3 Expressive and iOS Glass renderings.

principles:
  - content-first
  - continuous
  - contextual
  - calm-and-expressive
  - compact-and-reachable

interfaceStyles:
  material3-expressive:
    role: android-native-default
    hierarchy: color-shape-typography-container-motion
    glass: prohibited-for-ordinary-content
  ios-glass:
    role: translucent-control-rendering
    hierarchy: material-thickness-outline-content-response
    glass: navigation-and-controls-only

colors:
  canvas:
    composeRole: background
    use: page-background
  content-primary:
    composeRole: onSurface
    use: titles-body-key-actions
  content-secondary:
    composeRole: onSurfaceVariant
    use: supporting-text-metadata
    alpha: 1.0
  surface-stable:
    composeRole: surface
    use: content-dialog-sheet
  surface-low:
    composeRole: surfaceContainerLow
    use: quiet-grouping
  surface-container:
    composeRole: surfaceContainer
    use: controls-and-groups
  surface-high:
    composeRole: surfaceContainerHigh
    use: menus-floating-panels
  accent:
    composeRole: primary
    use: primary-action-focus-progress
  on-accent:
    composeRole: onPrimary
    use: content-on-accent
  selected:
    composeRole: secondaryContainer
    use: selected-container
  on-selected:
    composeRole: onSecondaryContainer
    use: content-on-selected
  outline:
    composeRole: outlineVariant
    use: hairlines-and-subtle-boundaries
  error:
    composeRole: error
    use: destructive-and-error-state
  on-error:
    composeRole: onError
    use: content-on-error
  scrim:
    composeRole: scrim
    use: modal-separation

fontFamilies:
  default:
    family: sans-serif
    source: android-system
    requirements:
      - offline-available
      - complete-cjk-coverage
  material3-expressive-recommended:
    family: Roboto Flex
    fallback: sans-serif
  ios-glass:
    family: sans-serif
    note: do-not-bundle-or-imitate-sf-pro

typography:
  destination-title:
    composeRole: headlineMedium
    fontSize: 28sp
    lineHeight: 36sp
    fontWeight: 600
    letterSpacing: 0sp
    use: primary-destination-top-bar
    styleOverrides:
      ios-glass:
        fontWeight: 700
  hero-title:
    composeRole: headlineLarge
    fontSize: 32sp
    lineHeight: 40sp
    fontWeight: 600
    letterSpacing: 0sp
    use: details-hero-and-single-focus-empty-state
    styleOverrides:
      ios-glass:
        fontWeight: 700
  page-title:
    composeRole: titleLarge
    fontSize: 22sp
    lineHeight: 28sp
    fontWeight: 600
    letterSpacing: 0sp
    use: secondary-page-sheet-dialog
  section-title:
    composeRole: titleMedium
    fontSize: 16sp
    lineHeight: 24sp
    fontWeight: 600
    letterSpacing: 0sp
    use: content-and-settings-group
  item-title:
    composeRole: bodyLarge
    fontSize: 16sp
    lineHeight: 24sp
    fontWeight: 500
    letterSpacing: 0sp
    use: list-and-settings-primary-text
  card-title:
    composeRole: bodyLarge
    fontSize: 16sp
    lineHeight: 24sp
    fontWeight: 600
    letterSpacing: 0sp
    use: content-card-primary-text
  body:
    composeRole: bodyLarge
    fontSize: 16sp
    lineHeight: 24sp
    fontWeight: 400
    letterSpacing: 0sp
    use: prose-dialog-message-form-content
  supporting:
    composeRole: bodyMedium
    fontSize: 14sp
    lineHeight: 20sp
    fontWeight: 400
    letterSpacing: 0sp
    use: subtitle-summary-helper-text
  label:
    composeRole: labelLarge
    fontSize: 14sp
    lineHeight: 20sp
    fontWeight: 500
    letterSpacing: 0sp
    use: menu-and-compact-control
  action-label:
    composeRole: labelLarge
    fontSize: 14sp
    lineHeight: 20sp
    fontWeight: 600
    letterSpacing: 0sp
    use: button-dialog-action-snackbar-action
  metadata:
    composeRole: labelMedium
    fontSize: 12sp
    lineHeight: 16sp
    fontWeight: 500
    letterSpacing: 0sp
    use: chip-tab-status-time-source
  micro:
    composeRole: labelSmall
    fontSize: 11sp
    lineHeight: 16sp
    fontWeight: 500
    letterSpacing: 0sp
    use: badge-and-nonessential-short-mark

spacing:
  xs: 4dp
  sm: 8dp
  md: 12dp
  lg: 16dp
  xl: 24dp
  xxl: 32dp
  xxxl: 48dp

sizes:
  minimum-touch-target: 48dp
  compact-icon: 20-24dp
  main-top-bar-height: 64dp
  secondary-top-bar-height: 56dp
  top-bar-touch-slot: 48dp
  top-bar-visible-control:
    material3-expressive: 48dp
    ios-glass: 44dp
  top-bar-icon:
    material3-expressive: 24dp
    ios-glass: 22dp
  narrow-screen-horizontal-padding: 16-24dp

rounded:
  material3-expressive:
    control: 20dp
    group: 28dp
  ios-glass:
    control: 12dp
    group: 18dp
  compact: 8dp
  pill: full
  circle: full

depth:
  content:
    surface: "{colors.surface-stable}"
    shadow: none
  group:
    surface: "{colors.surface-low}"
    boundary: optional-hairline
  control:
    surface: "{colors.surface-container}"
    boundary: semantic
  transient:
    surface: "{colors.surface-high}"
    separation: scrim-or-single-elevation
  glass:
    allowedStyle: ios-glass
    allowedLayers:
      - navigation
      - controls
    prohibitedLayers:
      - content
      - long-text
      - forms

components:
  main-top-bar:
    height: "{sizes.main-top-bar-height}"
    titleTypography: "{typography.destination-title}"
    horizontalPadding: "{spacing.lg}"
    touchSlot: "{sizes.top-bar-touch-slot}"
    titleLines: 1
    longTitleBehavior: ellipsis
    directActionsMaximum: 2
  secondary-top-bar:
    height: "{sizes.secondary-top-bar-height}"
    titleTypography: "{typography.page-title}"
    touchSlot: "{sizes.minimum-touch-target}"
    titleLines: 1
  list-item:
    titleTypography: "{typography.item-title}"
    supportingTypography: "{typography.supporting}"
    minimumTouchTarget: "{sizes.minimum-touch-target}"
    container: optional-group-only
  content-card:
    titleTypography: "{typography.card-title}"
    supportingTypography: "{typography.supporting}"
    metadataTypography: "{typography.metadata}"
    titleLinesMaximum: 2
    image: content-not-decoration
  hero-overlay-control:
    scope: controls-embedded-in-panorama-cover
    surface: black-42-percent
    contentColor: white
    appliesToAllInterfaceStyles: true
    minimumTouchTarget: "{sizes.minimum-touch-target}"
    appliesTo:
      - hero-schedule
      - hero-service-selector
      - hero-score
      - hero-source
      - hero-page-indicator
    excludes:
      - hero-primary-title
      - hero-supporting-copy
      - popup-window-menu
  details-panorama:
    descriptionTypography: "{typography.supporting}"
    material3-expressive:
      topBarControls: translucent-theme-surface
      infoCard: translucent-theme-surface
      bottomDock: opaque-stable-surface
    ios-glass:
      topBarControls: same-window-backdrop
      infoCard: same-window-backdrop
      bottomDock: same-window-backdrop
    panoramaDisabledFallback: opaque-stable-surface
  popup-menu:
    surface: "{colors.surface-high}"
    itemTypography: "{typography.label}"
    itemMinimumHeight: "{sizes.minimum-touch-target}"
    supportingText: discouraged
    grouping: spacing-or-divider
    nestedCards: prohibited
  sheet:
    surface: "{colors.surface-stable}"
    titleTypography: "{typography.page-title}"
    bodyTypography: "{typography.supporting}"
    actionTypography: "{typography.action-label}"
    horizontalPadding: "{spacing.lg}"
    dragHandle: only-when-dragging-is-meaningful
  dialog:
    surface: "{colors.surface-stable}"
    titleTypography: "{typography.page-title}"
    bodyTypography: "{typography.supporting}"
    actionTypography: "{typography.action-label}"
    contentPadding: "{spacing.xl}"
    use: short-blocking-decision
    longTaskAlternative: sheet-or-page
  button:
    typography: "{typography.action-label}"
    minimumTouchTarget: "{sizes.minimum-touch-target}"
    pillUse: short-primary-or-compact-actions-only
  chip-tab-segment:
    typography: "{typography.metadata}"
    selectedFontWeight: 600
    minimumTouchTarget: "{sizes.minimum-touch-target}"
  text-field:
    inputTypography: "{typography.body}"
    labelTypography: "{typography.metadata}"
    helperTypography: "{typography.metadata}"
    minimumHeight: "{sizes.minimum-touch-target}"
  snackbar:
    messageTypography: "{typography.label}"
    actionTypography: "{typography.action-label}"
  tooltip:
    typography: "{typography.metadata}"
  continue-action:
    semantic: resume-last-reading-or-playback-position
    mainRendering: fab
    cardRendering: compact-bottom-end-action
    emptyBehavior: hidden
  space-sidekick:
    semantic: switch-space-context
    entryLayer: control
    panelSurface: "{colors.surface-stable}"
    placement: upper-right
  reader-controls:
    width: content-adaptive-with-maximum
    progress: persistent-while-controls-visible
    hiddenBehavior: remove-from-semantics-and-hit-testing

states:
  required:
    - default
    - pressed
    - focused
    - selected
    - disabled
    - loading
    - error
    - empty
  rules:
    - state-must-not-rely-on-color-alone
    - animation-is-transition-not-state
    - both-interface-styles-share-the-same-state-model

motion:
  purposes:
    - explain-spatial-relationship
    - communicate-state-change
    - confirm-action-result
  simultaneousDominantMotionMaximum: 1
  reducedMotion:
    replace:
      - large-translation
      - large-scale
      - parallax
      - blur-transition
    with:
      - fade
      - immediate-state-change

accessibility:
  minimumTouchTarget: "{sizes.minimum-touch-target}"
  fontScaling:
    - allow-container-growth
    - allow-wrapping-outside-single-line-top-bars
    - never-clip-required-actions
  highContrast:
    - increase-surface-opacity
    - preserve-semantic-colors
    - keep-primary-content-on-high-contrast-foreground
  reducedTransparency:
    ios-glass: stable-surface
  talkBack:
    - preserve-semantic-order
    - hide-decorative-layers
    - provide-labels-for-non-obvious-icons

responsive:
  compact:
    navigation: bottom-navigation
    grids: reduce-column-count
    search: compact-entry-to-overlay-when-needed
    modalLongTask: bottom-sheet-or-full-screen
  medium:
    navigation: rail-or-adaptive-bottom-navigation
    grids: increase-column-count-with-stable-card-width
  expanded:
    navigation: rail-or-drawer
    modalLongTask: dialog-or-supporting-pane
  rules:
    - preserve-operation-semantics
    - change-layout-not-information-architecture
    - cap-floating-control-width

copy:
  actionLabels: task-verbs
  consistentTerms:
    continue: 继续
    progress: 进度
    translate: 翻译
    manga: 漫画
    novel: 小说
    animation: 动画
  avoid:
    - redundant-feature-prefix
    - redundant-mode-prefix
    - redundant-ai-prefix
---

# Kototoro Design System

本文件是 Kototoro 设计系统的统一入口。Frontmatter 是供设计代理、代码代理和检查工具读取的结构化
契约；正文解释这些令牌为何存在、如何组合，以及哪些做法被明确禁止。

具体场景和渲染细节继续由同目录的分层文档说明。数值或语义令牌发生冲突时，以本文件 Frontmatter
为准；无障碍、可读性和平台系统行为始终拥有更高优先级。

## 1. Overview

Kototoro 是漫画、小说和动画的统一内容消费工具。设计系统追求内容优先、连续、有上下文、平静而
有表现力，以及紧凑且可触达。

产品只维护一套信息架构和两套视觉表达：

- Material 3 Expressive 是 Android 原生默认表达。
- iOS Glass 只改变导航与控制层的材质、轮廓和有限字重。

风格切换不得改变操作位置、组件语义、状态模型、响应式策略或无障碍基线。

## 2. Colors and surfaces

- 业务界面只使用语义颜色，不持有固定品牌色或风格专用透明颜色。
- 一个屏幕最多保留一个高饱和主信号；成功、警告和错误等语义状态除外。
- 主文字使用 `onSurface`，说明与元数据使用完整的 `onSurfaceVariant`，禁止叠加任意低透明度。
- 层级优先通过 `surface → surfaceContainerLow → surfaceContainer → surfaceContainerHigh`
  建立，不用连续阴影制造深度。
- Glass 不是新的 Surface 层级，只是 iOS 风格控制层的渲染方式。

## 3. Typography

排版使用单一系统无衬线字体族，通过字号、行高和有限字重建立层级。用户字体可以替换字体族，
但不能改变语义角色和字号梯度。

共同字重语法：

- Regular `400`：正文和说明；
- Medium `500`：条目、菜单和紧凑标签；
- SemiBold `600`：页面、面板、区块和操作；
- Bold `700`：iOS 一级标题、关键数值或需要对比保护的媒体叠加标题。

正文行高保持约 `1.4–1.5`，标题约 `1.25–1.3`。中文、日文和韩文保持 `0sp`
字距；拉丁大标题也不在业务页面自行添加负字距。

## 4. Layout and spacing

- 所有结构间距落在 `4 / 8 / 12 / 16 / 24 / 32 / 48 dp` 离散尺度上。
- 紧凑布局减少的是可见空白和嵌套容器，不是触摸目标或正文行高。
- 页面、内容网格和浮动控制根据窗口尺寸改变列数、宽度和呈现方式，不改变操作语义。
- 内容自适应控件必须设置合理最大宽度，不默认 `fillMaxWidth`。

## 5. Depth, shapes, and materials

- 内容层保持稳定 Surface；普通卡片和列表不使用 Glass。
- Group、Control 和 Transient 逐级使用语义 Surface，必要时增加一条 Hairline。
- Popup、Dialog 等跨窗口组件不能采样根窗口 Backdrop，必须使用稳定 Surface。
- 圆角表达容器关系；Pill 只用于短动作、状态或紧密选择组。
- 内层圆角不得在视觉上大于外层轮廓，避免容器连续套层。

## 6. Components

共享组件必须分离语义、布局、视觉、行为和风格适配。业务页面只提供数据、动作与必要布局信息，
不得拼装长串风格 Modifier 或覆盖主题排版。

组件排版、尺寸和状态读取 Frontmatter 中的 `components` 映射。新增组件必须先声明：

1. 语义和无障碍名称；
2. 内容结构和命中范围；
3. 所需状态；
4. 使用的语义令牌；
5. Material 与 iOS Glass 的渲染差异；
6. 窄屏、大屏和字体放大行为。

## 7. States and motion

所有共享组件至少覆盖 `default / pressed / focused / selected / disabled / loading / error / empty`。
状态不能只靠颜色表达，动画也不是额外状态。

动效只用于解释空间关系、状态变化和操作结果。同一时刻只有一个主导动效。开启减少动态效果后，
大幅位移、缩放、视差和模糊过渡改为淡变或即时切换。

## 8. Responsive behavior and accessibility

- 紧凑窗口优先底部导航、减少网格列数，并把复杂搜索或长任务转为 Overlay/Sheet。
- 中等与展开窗口可使用 Navigation Rail、Drawer、Dialog 或 Supporting Pane。
- 所有触摸操作保留至少 48 × 48 dp 命中区。
- 字体放大时容器允许增高或换行；单行顶栏保留省略，但必要操作不得裁切。
- 减少透明度时，iOS Glass 降级为 Stable Surface。
- TalkBack 顺序不随视觉风格变化，装饰层不暴露语义节点。

## 9. Do

- 使用语义令牌和主题角色。
- 用大标题建立页面身份，用较小但高对比的说明补充上下文。
- 用一个容器表达一个任务组、选中状态或交互边界。
- 保留内容封面、正文、漫画页和视频作为第一视觉焦点。
- 在设计阶段同时定义状态、响应式和无障碍行为。
- 影响多个页面的决策先更新本文件，再修改实现。

## 10. Don't

- 不在业务页面硬编码字号、圆角、透明度和动效参数。
- 不为每个条目增加卡片、胶囊、阴影或 Glass。
- 不把 Bold 当作 Expressive 的默认字重。
- 不用低透明度灰字代替真实的信息层级。
- 不让两个高饱和颜色、大尺寸元素或强动效同时竞争第一焦点。
- 不因风格切换改变操作语义、位置或状态。

## 11. Related specifications

- [核心设计语言](./kototoro-design-language.md)
- [Material 3 Expressive](./material3-expressive.md)
- [iOS Glass](./ios-glass.md)
- [阅读与播放体验](./reading-experience.md)
- [组件与令牌说明](./components-and-tokens.md)
- [界面风格系统实施文档](../development/interface-style-system.md)

## 12. Known gaps

- `KototoroTheme` 已对齐基础语义字号；卡片标题、操作标签等同一 Compose role 下的不同字重仍需在组件层逐步收敛。
- 主界面主行与设置顶栏已分别统一为 64 dp 和 56 dp；内联 Tab、筛选轨道与共享菜单已使用 48 dp 点击目标。
- 展示选项、搜索筛选、Space 切换、阅读统计、媒体宇宙、小说章节和翻译任务等标准 Sheet 已使用共享稳定表面契约；全屏任务、详情面板及阅读器手势型 Sheet 保留专用策略。
- Settings 范围内直接使用 Material `AlertDialog` 的页面已统一排版与容器策略，其余业务域 Dialog 仍需按组件逐步迁移。
- 部分业务组件仍直接覆盖 `fontWeight`、字号或透明度，尚未迁移到统一语义角色。
- 缺少覆盖两种风格、字体放大、减少动态效果和减少透明度的系统化截图测试。
- 当前 Material3 依赖版本尚不能直接采用公开稳定的 Expressive Theme/Motion API。
