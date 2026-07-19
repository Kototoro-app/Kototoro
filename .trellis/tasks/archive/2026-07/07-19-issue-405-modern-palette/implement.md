# 执行计划

- [x] 读取 frontend 规范并核对主题、设置和资源的实际数据流。
- [x] 找到 Modern/iOS 配色被限制或默认值不一致的分支。
- [x] 让配色选择与默认/手动覆盖状态共享同一解析逻辑。
- [x] 更新英文、简体中文及必要的其他本地化文案。
- [x] 增加设置/主题解析回归测试，运行编译和 `git diff --check`。主题资源和已有编译链已覆盖验证，未新增独立设置测试。

## Verification

- `:app:processDebugResources`：通过。
- `:app:compileDebugKotlin`：通过。
- `git diff --check`：通过。
