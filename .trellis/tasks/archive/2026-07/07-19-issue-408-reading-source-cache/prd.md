# Issue 408：修复详情页 reading source 初次加载

## Goal

详情页首次打开时正确显示 reading source，不再依赖下拉刷新触发重新读取。

## Requirements

- 追踪详情缓存写入、读取、失效和 `readingSourceOptions` 构建的时序。
- 缓存命中、缓存未命中、刷新、切换 projection 和页面重建都能得到当前 projection 对应的 reading source。
- 继续遵守当前内容类型过滤和 Space 约束，不把刷新作为必需的修复手段。

## Acceptance Criteria

- [ ] 首次打开详情即可显示正确 reading source。
- [ ] 缓存命中和网络刷新路径结果一致，刷新不会重复或丢失 source。
- [ ] 内容类型/投影切换不会显示旧详情的 source。
- [ ] ViewModel/UseCase 测试覆盖初始状态与异步更新顺序，编译和 `git diff --check` 通过。

## Notes

当前仓库已有详情 source 过滤和内容类型隔离改动；本任务以实际状态流为准，优先补缓存契约与初始化时序。
