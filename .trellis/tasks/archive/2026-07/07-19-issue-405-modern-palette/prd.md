# Issue 405：支持 Modern 配色自定义与说明调整

## Goal

让 Modern/iOS 风格可以自定义配色，同时明确说明该风格的默认配色预设，避免用户误以为配色设置完全无效。

## Confirmed Facts

- 当前资源已存在 `appearance_color_scheme_ios_note`，以及 Modern/iOS 默认值和手动覆盖相关文案。
- 需要以实际 `InterfaceStyle` 与主题 token 行为为准，不能只修改文案。

## Requirements

- Modern/iOS 风格下颜色设置可生效并持久化。
- 配色选项说明明确显示 iOS 风格当前使用的默认预设；无对应预设时补充正式的配色方案。
- 保持 Material 3/Legacy 风格现有默认值和自定义行为。
- 中英文资源保持语义一致。

## Acceptance Criteria

- [ ] 切换 Modern/iOS 风格后，配色选择器可用，重启后手动值仍保持。
- [ ] 未手动覆盖时显示实际 iOS 默认预设名称；手动覆盖后文案准确反映状态。
- [ ] 默认预设确实存在于主题配色映射中，浅色/深色模式均可生成有效 scheme。
- [ ] 中英文文案测试/资源检查、编译和 `git diff --check` 通过。

## Out Of Scope

- 不重做完整主题设计，不增加与现有 token 无关的第三方配色系统。
