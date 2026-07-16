# 关闭 Space 后减少运行时开销

## Goal

当用户关闭 Space 功能时，减少 Space 相关的常驻 Flow、数据库监听和 UI 状态对象开销，使普通浏览场景尽量接近 1.6.2 的运行时行为，同时不改变重新开启 Space 后的功能。

## Requirements

- R1. Space 关闭时，来源过滤流不得继续订阅来源数据；应直接输出无 Space 过滤状态。
- R2. Space 关闭时，主界面不得主动创建或收集不必要的 Space 导航恢复、继续阅读状态；普通导航、浏览、收藏和历史功能保持原有行为。
- R3. 路由偏好和来源预设控制器在 Space 关闭时不应执行 Space 数据库读写；Space 重新开启后仍可恢复工作。
- R4. 改动必须保持现有 Space 开启路径、动态开关语义和数据兼容性，不删除 Space 表、迁移或业务能力。

## Acceptance Criteria

- [x] Space 关闭时，`SpaceBrowseScope` 不再订阅 `observeEnabledSources()`，并返回 `null` 的来源限制。
- [x] Space 关闭时，主界面不触发 Space 导航会话和 Space 继续阅读的数据库加载或事件处理。
- [x] Space 关闭时，路由偏好、来源预设控制器不执行 Space 相关数据库读写；开启 Space 后原有切换和恢复流程仍可用。
- [x] 相关单元测试或现有测试通过，项目编译通过。
- [x] 变更不引入新的独立常驻协程或重复的开关判断逻辑。

## Background

- `SpaceFeatureFlags` 已将总开关传递给切换器、持久化导航、沉浸式切换和路由偏好；关闭总开关时这些 effective flag 均为 false。
- `DefaultSpaceCatalogRepository` 在总开关关闭时已经退化为内置 Space 列表，不再观察自定义 Space DAO。
- `SpaceBrowseScope.observeAllowedSourceNames` 当前在 `spaceId == null` 时仍通过 `combine` 订阅来源流，是关闭状态下最明确的可削减订阅。
- `MainActivity` 当前无条件收集三个 Space ViewModel，并无条件启动两个 Space 控制器。

## Out of Scope

- 不删除 Space 代码、Room 表、数据库迁移、资源或构建产物。
- 不实现不包含 Space 的独立构建变体。
- 不修改 Space 数据模型、切换动画、导航状态协议或历史数据。

## Notes

- Keep `prd.md` focused on requirements, constraints, and acceptance criteria.
- Lightweight tasks can remain PRD-only.
- For complex tasks, add `design.md` for technical design and `implement.md` for execution planning before `task.py start`.
