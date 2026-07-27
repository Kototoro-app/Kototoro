# Kototoro Material 3 Expressive 规范

## 1. 定位

Material 3 Expressive 是 Kototoro 的 Android 原生默认表达。表现力来自语义颜色、形状层级、
排版、组件状态和一致动效，不依赖 Haze、Backdrop 或大面积毛玻璃。

## 2. 渲染边界

- 内容层、卡片、Sheet、Dialog、菜单和底栏默认使用 Material 语义 Surface。
- 可在滚动顶栏使用非常克制的半透明背景，但必须在没有模糊时仍可读。
- 禁止为普通页面引入 Haze 或 Backdrop。
- 禁止固定白色蒙层、灰色雾层和与主题无关的透明颜色。

## 3. 表现力预算

每个屏幕只选择一个主要表现手段：

- 强调当前主动作；
- 强调当前选中状态；
- 强调内容封面或媒体；
- 强调状态变化的动效。

其余元素回到标准 Surface、排版与间距。不要同时使用高饱和色、大尺寸、异形容器和强动效竞争。

## 4. 组件原则

- 使用 `MaterialExpressiveTheme` 与统一 `MotionScheme`，不在业务组件散落动画参数。
- 一级主界面使用共享的 64 dp 顶栏骨架和 28/36 sp 大标题；二级任务页使用 56 dp 小顶栏和
  22/28 sp 标题。Expressive 不等于把所有页面都改成 `LargeTopAppBar`。
- ButtonGroup 只用于同级互斥或紧密相关操作，不把整条工具栏做成胶囊集合。
- Carousel 只用于媒体发现和封面浏览，不用于设置项与普通导航。
- FAB 只表示当前页面的主要动作；主界面固定为继续阅读/播放。
- Loading 必须表达真实等待状态，不能成为常驻装饰。
- 设置页面以列表和分组层级为主，顶栏可轻微半透明，其余保持稳定 Surface。

### 4.1 Kototoro 的 Expressive 语法

Kototoro 的 Expressive 不是普遍放大和普遍加粗，而是以下组合：

1. 用 28/36 sp 的一级主标题建立页面身份；
2. 用 14/20 sp 的高对比说明补充上下文；
3. 用一个语义容器表达当前任务组或选中状态；
4. 用紧凑间距保持内容扫描效率，同时保留 48 dp 命中区；
5. 把强颜色、异形或主导动效留给当前唯一焦点。

字体、字号、字重和组件映射以[组件与令牌](./components-and-tokens.md)为准。页面不得自行把
`bodyMedium` 降低透明度，也不得为每个卡片标题重复指定 `Bold`。

## 5. 色彩、形状与动效

- 动态色需保持 Kototoro 内容优先和语义色一致性。
- 选中态优先使用 `secondaryContainer` 等容器色，避免只改变图标颜色。
- 大容器使用较温和圆角，小型强调控件可更圆润；同一屏幕不混用过多轮廓。
- 进入、退出、选中和展开使用同一套空间逻辑。
- 遵循系统动画缩放与减少动态效果设置。

## 6. 验收

- 关闭所有透明度后，界面是否仍完整？
- 表现力是否集中在一个焦点？
- 是否存在仅为“玻璃感”引入的 Haze/Backdrop？
- 选中、禁用、加载和错误状态是否不只依赖颜色？
- 组件是否使用 Material 语义角色而非页面硬编码？

## 7. 官方依据

- [Material 3 in Compose](https://developer.android.com/develop/ui/compose/designsystems/material3)
- [MaterialExpressiveTheme](https://developer.android.com/reference/kotlin/androidx/compose/material3/MaterialExpressiveTheme)
- [MotionScheme](https://developer.android.com/reference/kotlin/androidx/compose/material3/MotionScheme)
- [Expressive Material Design research](https://design.google/library/expressive-material-design-google-research)

## 8. Compose 版本基线

- 当前工程由 Compose BOM `2026.05.01` 管理，Material3 实际版本为 `1.4.0`。
- 该版本的 `MaterialExpressiveTheme` 与 `MotionScheme` 仍是库内部 API，Kototoro 不绕过可见性调用。
- 当前实现由 `InterfaceStyleTokens`、语义色、Expressive 排版与组件状态承载；业务组件不得散落私有动效常量。
- 官方公开入口 `Material3 1.5.0-alpha24` 要求 Compose UI `1.12.0-beta01`、`compileSdk 37` 与 AGP `9.1`。
- 只有在工程整体升级到上述构建基线并通过回归验证后，才将主题根替换为公开的
  `MaterialExpressiveTheme`/`MotionScheme`；不得仅覆盖单个 Material3 依赖版本。
