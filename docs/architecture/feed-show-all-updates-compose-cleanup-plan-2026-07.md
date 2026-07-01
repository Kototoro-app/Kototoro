# Feed Show All Updates Compose 收敛计划（2026-07）

## 背景

PR #359 引入 Feed 的 `Show all updates` 设置，允许用户在更新流中查看所有已缓存的追踪项。

功能方向可以接受，但合并后的实现把 Feed 专属状态、后台刷新调度和 Display Options UI 直接接入通用顶栏。为了避免后续页面继续向 `KototoroTopBar` 添加业务参数，需要把这次改动收敛为符合 Compose 单向数据流和状态提升习惯的实现。

## 目标

- `KototoroTopBar` 不感知 Feed 业务概念。
- Composable 只表达 UI 状态和用户事件，不直接构造 WorkManager 请求。
- Feed 的 all-updates 数据源由 tracker/domain 层提供，ViewModel 不直接拼 DAO 查询。
- Display Options Sheet 保持可扫描，长说明不塞进开关行摘要。

## 非目标

- 不改变 `Show all updates` 的用户可见行为。
- 不引入新的导航、设置或 Work 调度框架。
- 不处理实体身份迁移、备份同步或 tracking 语义迁移。

## 执行计划

### Phase 1：低风险 Compose 清理（已完成）

- 删除 Feed 路由中未使用的 `showAllUpdates` state collection。
- 把顶栏刷新动作接到已有 `TrackWorker.Scheduler.startNow()`，移除 Composable 内的 WorkManager 请求构造。
- 将 `KototoroTopBar` 的 Feed 专属参数替换为通用 `displayOptionsExtraContent`。

验收：

- Feed Display Options 仍能显示 `Show all updates`。
- 非 Feed 页面不显示 Feed 设置。
- `Trigger Update` 仍触发一次 tracking refresh。
- `./gradlew :app:compileDebugKotlin --no-daemon` 通过。

### Phase 2：页面能力建模（已完成）

- 将 Feed 专属 display options 抽成页面级 composable。
- `KototoroApp` 只负责把当前页面能力传给顶栏，不让顶栏通过 `isFeedScreen` 分支判断业务。
- 保留已有 browse tracking recommendations extra content，但与 Feed extra content 使用同一组合入口。

验收：

- `KototoroTopBar` 参数中不再出现 Feed 业务名。
- 新增页面设置时不需要继续添加页面专属 boolean。
- `KototoroTopBar` 通过通用 `displayOptionsExtraContent` 接收页面扩展内容。

### Phase 3：数据层边界收敛（已完成）

- 将 `observeAllTracks + findAllByMangaIds + TrackingLogItem` 映射下沉到 `TrackingRepository` 或专用 use case。
- `FeedViewModel` 只根据设置选择 `observeTrackingLog` 或 all-updates feed flow。
- 移除 `FeedViewModel` 对 `MangaDatabase` 的直接依赖。

验收：

- `FeedViewModel` 不直接访问 DAO。
- all-updates 显示条目、数量和排序与合并后行为一致。
- all-updates 聚合由 `TrackingRepository.observeAllTrackingLogItems()` 提供。
- `./gradlew :app:compileDebugKotlin --no-daemon` 通过。

### Phase 4：文案和交互整理（已完成）

- 缩短开关行 summary。
- 将较长说明放在展开区域，和 `Trigger Update` 按钮一起只在开关启用时显示。
- 确认 Display Options Sheet 在小屏下无文本挤压。

验收：

- 开关行保持单一职责。
- 长说明不影响其他 display options 的扫描效率。
- 说明文案已缩短，展开区域保持稳定间距。
- `./gradlew :app:compileDebugKotlin --no-daemon` 通过。

## 验证命令

```bash
./gradlew :app:compileDebugKotlin --no-daemon
```

如 Phase 3 修改数据映射，追加：

```bash
./gradlew :app:testDebugUnitTest --no-daemon
```

## 当前状态

- 已完成 Compose 低风险清理、页面能力建模、数据层边界收敛、文案和交互整理。
- 已补充 all-updates 数据映射单元测试。
- 当前验证覆盖为编译检查和 mapper 单测。
