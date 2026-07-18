# 修复主页随机入口偶发闪退

## Goal

修复主页快捷入口“随机”按钮在随机内容获取失败时导致应用闪退的问题。
随机内容暂时不可用时，用户应留在主页并收到可处理的错误反馈。

## Requirements

- 处理 `ExploreRepository.findRandomContent(tagsLimit = 8)` 无法返回内容的情况，避免异常从 `HomeViewModel.openRandom()` 未处理地传播到应用进程。
- 保留现有随机内容获取、详情解析、实体映射和加载状态逻辑。
- 失败后必须恢复随机按钮可点击状态，并通过项目既有错误事件/展示机制反馈错误。
- 不改变远程列表等其他随机入口的现有行为。

## Confirmed Facts

- GitHub Issue 396（版本 1.6.2，Android 16，Samsung S23 Ultra）日志为 `NoSuchElementException`，堆栈指向 `ExploreRepository.findRandomContent()`。
- 主页随机入口位于 `HomeViewModel.openRandom()`；该方法当前直接使用 `viewModelScope.launch`。
- `ExploreRepository.findRandomContent()` 在最多五次候选尝试均失败时执行 `throw NoSuchElementException()`（`ExploreRepository.kt:25-44`）。
- 失败原因可能是源列表为空、详情请求失败、结果为空、命中黑名单或被 NSFW 过滤。
- 工作区已有与本任务无关的未提交改动，必须保留。

## Acceptance Criteria

- [x] 随机内容获取成功时，行为与现状一致，能够继续打开内容详情。
- [x] 随机内容获取失败时，应用不崩溃，主页保持可用并通过现有 `onError` Snackbar 机制反馈错误。
- [x] 失败流程结束后，随机按钮的加载/禁用状态被清理，可以再次点击。
- [x] `:app:compileDebugKotlin` 和 `git diff --check` 通过。

## Implementation Note

- `HomeViewModel.openRandom()` now uses `BaseViewModel.launchJob(Dispatchers.Default)` so failures are routed to the existing `EventExceptionHandler` and `onError` flow. The existing `finally` block still resets `isRandomLoading`.

## Out of Scope

- 不重写随机内容筛选算法。
- 不调整内容源、黑名单、NSFW 或推荐设置的业务规则。
- 不修改 Issue 之外的主页布局或快捷入口。

## Notes

- Keep `prd.md` focused on requirements, constraints, and acceptance criteria.
- Lightweight tasks can remain PRD-only.
- For complex tasks, add `design.md` for technical design and `implement.md` for execution planning before `task.py start`.
