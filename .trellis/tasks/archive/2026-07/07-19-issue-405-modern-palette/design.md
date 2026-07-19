# 技术设计

检查 `AppearanceSettingsScreen`/`AppearanceSettingsFragment`、`AppSettings`、`InterfaceStyleTokens` 和 `KototoroTheme` 的实际条件分支。将 iOS 默认配色定义为主题 token 的明确预设，并移除仅在文案层或 UI 层禁用编辑的条件；默认值、手动覆盖值和资源说明共用同一解析结果，避免文案与实际颜色分叉。

保留现有 preference key 和旧值兼容，不做数据库迁移。若当前已有对应预设，只修正启用条件和说明；只有代码证据表明缺失时才新增 token。
