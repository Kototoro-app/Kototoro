# 整合 pull 后主界面冲突

## Goal

整合 origin/devel pull 产生的 MainActivity 与 KototoroApp 冲突，兼容 Space 导航、继续阅读与背景样式改动，并完成验证。

## Requirements

- 清理 `MainActivity.kt` 与 `KototoroApp.kt` 中的 Git 冲突标记。
- 保留本地 Space 导航、Space 会话恢复、过渡幕及 FAB 锚点行为。
- 合并远端 `lastReadContent` 状态，使动态背景仍可读取最近阅读内容。
- 兼容继续阅读入口与当前 `KototoroBottomNav` 的 Space 导航扩展参数。
- 不修改无关业务逻辑，不执行提交或推送。

## Acceptance Criteria

- [x] `git diff --check` 不再报告 leftover conflict marker。
- [x] `git diff --name-only --diff-filter=U` 无输出。
- [x] `MainActivity` 的 Compose 参数来源完整，Space 与 `lastReadContent` 均能传入 `KototoroApp`。
- [x] `MainBottomChrome` 与 `KototoroBottomNav` 的参数契约一致，保留 Space FAB/rail 内容及继续阅读能力。
- [x] 相关 Android Kotlin 编译或等价静态验证通过。

## Notes

- Keep `prd.md` focused on requirements, constraints, and acceptance criteria.
- Lightweight tasks can remain PRD-only.
- For complex tasks, add `design.md` for technical design and `implement.md` for execution planning before `task.py start`.
