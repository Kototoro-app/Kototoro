# ADR 0001 — Glass Finish Tuner（玻璃质感微调器）

- 状态：Accepted
- 日期：2026-08-31（前序研究自 2026-08-14 起）
- 关联：`docs/design/glass-rendering-backend-decision.md`（渲染后端选型）、`docs/design/ios-glass.md`、`docs/design/components-and-tokens.md`

## 背景

Kototoro 已在 Backdrop（`io.github.kyant0:backdrop` 2.0.0）之上建立了 `GlassSurface` 渲染管线
与 25+ 调用点。上游还原工作（Vetro / legado / KeiOS / BiliPai / SimpMusic / SPICaWeather3 / BiliTV）
显示每个应用的"液态玻璃"在模糊半径、折射强度、高光边缘、阴影与描边上区别很大，且这些参数
在 Kototoro 旧实现里是硬编码的。

因此需要：(1) 一套可调、可持久化、可预设的玻璃质感模型；(2) 不破坏既有安装的像素输出；
(3) 能表达上游差异（含"颜色分级"与"高光样式"这类非旧版能力）。

## 决策

### 1. 参数模型：25 个参数 × 6 个作用域

- 参数 `GlassTuningParam`（25 个）：
  - 管线开关：`glass_enabled`、`vibrancy`、`depth_effect`、`chromatic_aberration`、`shadow_enabled`
  - 颜色分级：`saturation`（0.5–2.0，slider）、`brightness`（−0.5–+0.5，slider）→ `colorControls`
  - 模糊/折射：`blur_radius_dp`、`lens_height_dp`、`lens_amount_dp`
  - 表面：`surface_alpha`
  - 边缘：`rim_enabled`、`rim_alpha`、`highlight_style`（OPTION：Default/Ambient/Plain）、
    `hairline_enabled`、`hairline_alpha`
  - 阴影：`shadow_radius_dp`、`shadow_offset_dp`、`shadow_alpha`
  - 按压反馈（仅可按压角色）：`press_highlight_alpha`、`press_inner_shadow_radius_dp`、
    `press_inner_shadow_alpha`、`press_chromatic_aberration`、`press_scale_percent`、`press_lens_strength`
- 参数种类 `ParamKind { SWITCH, SLIDER, OPTION }`，OPTION 以 FilterChip 呈现（当前仅 `highlight_style`）。
- 作用域 `GlassTuningScope` 6 个：GLOBAL + TOP_BAR / BOTTOM_BAR / PILL_CONTROL / BOTTOM_PANEL / MENU。
- `GlassTuning.uniformParams`：全局一致的参数默认"跟随 Global"；`pressFeedbackParams` 仅在
  可按压角色（BOTTOM_BAR / PILL_CONTROL / BOTTOM_PANEL）暴露。
- `GlassTuning.legacyFallback(scope, param)` 编码了重构前硬编码值，**未触碰的安装逐参数像素一致**。

### 2. 存储与解析

- 每个作用域一份 JSON（`glass_tuning_<scope>`），kotlinx.serialization，decode 时 `ignoreUnknownKeys = true`。
- `GlassScopeConfig { values, followGlobal: Set<String>, initialized }`。
- 角色取值：`followGlobal` 中有该 key → 用 GLOBAL；否则用角色 `values`；再缺 → `legacyFallback`。
- `GlassTuningState.value(scope, param)` 是唯一取值入口（含预览 `withValues`）。

### 3. 预设 = 全局基线 + 可选角色 delta（Global + per-role delta）

- `GlassPreset` 携带 `config`（Global 25 键）与 `roleOverrides: Map<Scope, Map<key, Float>>`。
- `applyPreset(config, roleOverrides)`：写 GLOBAL；无 delta 的角色 → `followAll()`；
  有 delta 的角色 → `presetScopeConfig(overrides)`（overridden keys 用本地值，其余继续跟随 GLOBAL）。
- `matches()` 严格判定：Global 全 25 键精确相等 + 无 delta 角色全部跟随 + 有 delta 角色各
  override key 本地生效。回退值取 `legacyFallback`（与解析一致，勿退回到裸 `param.fallback`——
  二者在 GLOBAL 的 `RIM_ENABLED` / `DEPTH_EFFECT` 上不同）。
- 角色 delta 示例（Control Center 预设）：
  - MENU 关阴影（legado ReaderMenuGlass `shadow = null`）；
  - PILL_CONTROL 用温和 lens（8/12）——体育场形小控件（紧凑 tabs 导轨/分组 pill/底栏选中 pill）
    的圆角半径等于其最短边一半，即使 ≤ 圆角半径的安全钳制光环仍会贴着圆弧露出内部弧角，
    因此在小 pill 上收敛折射，大表面（顶栏/底栏/面板）保持 24/24 强折射。

### 4. 颜色分级与高光样式（解锁上游配方）

- `BackdropEffectScope.colorControls(brightness, saturation)` 在 blur 之前施加，中性值跳过
  （不叠加 vibrancy 的饱和度提升）。VIBRANT 预设 = SimpMusic / SPICaWeather3 的"活力"配方。
- `HighlightStyle` 三态：0 Default（specular，angle 跟重力 / 悬浮 chrome 固定 45°）、
  1 Ambient（均匀边缘柔光，BiliTV 观感）、2 Plain（无 shader 纯色）。
- 新参数一律中性默认（饱和度 1、亮度 0、样式 Default），保证未触碰安装不变。

### 5. Lens 安全钳制（本 ADR 最重要的运行期约束）

Kyant lens SDF 硬性约束（同 KeiOS `BackdropLensSafety`，与库注释一致）：

> `refractionHeight ≤ 表面最小圆角半径`；`refractionAmount ≤ 表面最短边`。

超出会在小表面（紧凑 tabs 胶囊、分组控件、pill）产生**内部弧形折射伪影与角部不连续**。
`GlassSurface.resolveGlassLensParameters(...)` 在每次 `lens()` 前按 shape/size/layoutDirection/
density 钳制，非法输入返回 null 并跳过 lens。大表面（顶栏/底栏/面板）不受影响。

教训：预设参数可以"炸裂"，但渲染器必须对库约束做防御——上游（KeiOS）为此专门实现了
`BackdropLensSafety`，我们此前缺了这道钳制，Control Center 预设在小胶囊上暴露了该缺陷。

### 6. 自定义预设与导出/导入

- `GlassCustomPreset { id, name, global, roleOverrides }`：`toCustomPreset()` 快照当前有效值
  （Global 全 25 键 + 各角色未跟随的 delta），JSON 列表持久化于 `custom_glass_presets`。
- 预设行新增"保存为预设 / 导出 / 导入 / 恢复默认"操作；自定义 chip 可点击应用（× 删除需确认）。
- 导出/导入走剪贴板 JSON（无需权限）；导入按 id 去重合并。

### 7. 上游依据（每条预设的来源还原）

| 预设 | 还原对象 | 关键特征 |
| :--- | :--- | :--- |
| Control Center | BiliPai（iOS 控制中心式） | 强折射 lens 24/24 + depth + rim 0.75 + 24dp 大软阴影；MENU 关阴影；PILL_CONTROL lens 8/12 |
| Liquid | Kyant sample | blur 8 / lens 16·24，无 rim/阴影 |
| Soft | 通用柔和 | 更高 surface_alpha 0.42，无 rim |
| Clean | 干净玻璃 | 无 rim/阴影/折射弱 |
| Refraction | Vetro (Phnem) | 强折射 lens 16·44 / blur 2.4，无 rim，hairline 0.25 |
| Reader | legado reader glass | blur 12 / 无 lens，rim 缘、无阴影 |
| Eco | 省电降级 | blur 8 / 无 lens，rim + hairline 0.12，无阴影 |
| Vibrant | SimpMusic / SPICaWeather3 | 饱和 1.5 + 亮度 +0.05 + 色差（colorControls 配方） |
| Depth | BiliTV Native / KeiOS | depth + lens 20·28 + **Ambient** 高光 + 深阴影 |

## 后果

- **正向**：质感参数集中、可调可共享；预设可表达角色差异；新能力（颜色分级/Ambient）解锁上游配方；
  lens 安全钳制修复小表面伪影；自定义预设 + 剪贴板导入导出提供跨设备共享。
- **负向/风险**：新增 3 参数后预设键集合统一为 25（老预设显式补中性键，行为不变）；
  全 App 玻璃由 6 份 JSON 驱动，需保证 `GlassTuningState` 为唯一取值入口；
  剪贴板导入为信任输入，decode 失败静默返回空。
- **兼容性**：`ignoreUnknownKeys` + 中性默认值保证旧存档与未触碰安装字节不变；
  `matches()` 只在预设显式键上判定精确相等。

## 验证

- 单测：`GlassTuningTest`（16 用例）覆盖解析、均匀参数、preview 不改持久化、预设键完整性、
  高光样式映射、lens 钳制、角色 delta、自定义预设快照/序列化/匹配。
- 运行：`./gradlew :app:testDebugUnitTest --tests "org.skepsun.kototoro.core.ui.glass.GlassTuningTest"`。
