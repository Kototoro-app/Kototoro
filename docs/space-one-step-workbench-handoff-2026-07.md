# Space 一步工作台开发交接（2026-07）

## 1. 文档用途

本文用于把 `feat/space-one-step-workbench` 分支迁移到另一台开发机器后继续开发。它记录的是当前分支的真实实现状态，而不是最终产品承诺。

长期架构和数据边界仍以 [Entity Space 实施计划](./entity-space-implementation-plan-2026-07.md) 为准。继续开发前还应阅读仓库根目录的 `AGENTS.md`；其中包含 JDK、Gradle、测试和仓库约束。

## 2. 交接快照

```text
Repository: https://github.com/Kototoro-app/Kototoro.git
Base branch: origin/devel
Feature branch: feat/space-one-step-workbench
Base commit: e3b3672068c79d8bbb407a90113e1589485fbab8
Feature HEAD: 035eeb385dbc5e0c2a063ad7f0b2b33f41c1efd0
Feature commits: 5
Working tree at handoff: clean
```

当前 5 个提交按时间从旧到新排列：

```text
f1de8d3 feat(space): add one-step workbench interaction
21113b0 feat(space): enrich workbench session previews
d5712aa refactor(space): encapsulate workbench drag state
fdefa20 feat(space): adapt workbench previews to window size
035eeb3 feat(space): require explicit setup for new installs
```

这些提交当前作者是 `Codex <codex@openai.com>`。如果需要改成维护者身份，应在发布或多人基于该分支开发前一次性重写；重写会改变全部 5 个 commit hash。不要在分支已被其他人拉取后反复改写历史。

## 3. 新机器快速启动

### 3.1 使用交付压缩包

交付包包含真实 `.git` 目录，不需要再次执行 `git init`：

```bash
unzip Kototoro-space-one-step-workbench-with-dotgit-035eeb3.zip
cd Kototoro-space-one-step-workbench-with-dotgit-035eeb3
git status
git log --oneline -5
git remote -v
```

预期当前分支为 `feat/space-one-step-workbench`，工作区无修改。交付仓库继承了上游的 partial clone 配置，当前分支所需对象可用；读取尚未落地的旧历史 blob 时，Git 可能联网补取对象。

连接远端并更新基线：

```bash
git fetch origin devel
git log --oneline --decorate --graph origin/devel..HEAD
```

不要直接用系统密码进行 GitHub HTTPS 推送。应使用 GitHub Personal Access Token、Git Credential Manager 或 SSH key。当前交付分支未设置 upstream；认证完成后执行：

```bash
git push -u origin feat/space-one-step-workbench
```

如果组织仓库仍返回 HTTP 403，先检查当前凭据实际对应的 GitHub 账号、组织 SSO 授权和仓库写权限。不要通过反复输入账号密码排查，因为 GitHub 已不支持 Git 的密码认证。

### 3.2 开发环境

- JDK 17：Gradle 运行时必须使用 JDK 17；应用字节码目标仍是 Java 11。
- Android SDK 36，Build Tools 35.0.0，最低 SDK 26。
- Gradle Wrapper 9.0.0；始终使用仓库内的 `./gradlew`。
- Kotlin 2.2.10，Compose BOM 2026.05.01。
- 原生模块需要 CMake 3.22.1 和 Android NDK 工具链。
- `app/build.gradle` 使用 Groovy DSL，不是 Kotlin DSL。

如本机 SDK 路径未被 Android Studio自动识别，可创建不入库的 `local.properties`：

```properties
sdk.dir=/absolute/path/to/Android/sdk
```

首次同步需要访问 Google Maven、Maven Central、JitPack、字节跳动仓库或仓库内配置的阿里云镜像。

### 3.3 最小验证路径

先执行范围较小的检查，再进行完整构建：

```bash
./gradlew :app:compileDebugKotlin --no-daemon

./gradlew :app:testDebugUnitTest \
  --tests "org.skepsun.kototoro.space.ui.SpaceWorkbenchTest" \
  --tests "org.skepsun.kototoro.space.ui.SpaceViewModelTest" \
  --tests "org.skepsun.kototoro.settings.compose.SpacesSettingsScreenTest" \
  --tests "org.skepsun.kototoro.core.prefs.AppSettingsSpaceFeatureDefaultsTest" \
  --no-daemon

./gradlew :app:testDebugUnitTest --no-daemon
./gradlew :app:assembleDebug --no-daemon
```

调试 APK 会按 ABI 拆分并包含 universal 版本。UI 和手势行为仍需要真机或模拟器人工验证，JVM 单元测试不能替代触摸坐标、窗口 Insets、动画和进程恢复测试。

## 4. 本分支完成了什么

相对 `origin/devel`，本分支修改 15 个文件，新增约 1100 行，核心是把已有 Space 切换能力扩展为类似锤子“一步”的工作台交互。

### 4.1 用户交互

- 短按 Space FAB：继续打开原有 `SpaceSwitcherSheet`。
- 长按 Space FAB：打开右侧 `SpaceWorkbench`。
- 长按并拖动 FAB：工作台打开，拖动代理跟随手指；进入 Space 卡片后产生磁吸和触觉反馈。
- 松手到其他 Space：代理动画落入目标卡片，然后触发 Space 切换。
- 松手到当前 Space：关闭工作台。
- 松手在卡片外：结束拖动态，但保留工作台，用户仍可点击卡片选择。
- 拖动取消：代理回到起点并关闭工作台。
- 点击遮罩或系统返回：关闭工作台。

主内容在工作台打开时缩放到 `82%`，以左侧中点为变换原点，并增加圆角和阴影。工作台是右侧 rail，而不是同时保活三套真实页面。

### 4.2 轻量预览策略

`SpaceWorkbench` 只保留当前 Space 的真实 Compose 内容。非活动 Space 通过已持久化的 session 和最近阅读数据生成语义预览：

- Space 名称和图标；
- 最近作品标题；
- 本地缓存封面；
- 当前 session 是详情页还是某个来源列表。

封面请求使用 `diskCachePolicy(READ_ONLY)` 和 `networkCachePolicy(DISABLED)`，打开工作台不会为了预览触发网络下载。这一约束应保留，避免快速切换面板变成新的网络和内存热点。

窗口自适应规则集中在 `resolveSpaceWorkbenchLayoutSpec()`：

| 可用宽度 | Rail 宽度 | 封面高度 |
| --- | ---: | ---: |
| `< 600dp` | 132dp | 92dp |
| `600dp ..< 840dp` | 188dp | 108dp |
| `>= 840dp` | 240dp | 128dp |

可用高度小于 `520dp` 时隐藏封面，保证紧凑横屏仍可操作。

### 4.3 新安装默认行为

新安装不再默认启用 Entity Space。用户在设置中首次启用时，会看到说明和明确的启用按钮。

兼容策略：

- 新安装：`isEntitySpaceEnabled = false`，`hasSeenSpaceOnboarding = false`。
- 旧版本升级且从未写入 onboarding 键：保留原来的 Space 开启体验，并标记 onboarding 已处理。
- 用户选择“暂不”：只记录已看过引导，不启用 Space；以后再次打开开关不会重复弹窗。

这部分仍基于 `SharedPreferences` 中的 app version 和 Space feature keys，不涉及 Room schema 迁移。

## 5. 代码入口与职责

| 文件 | 当前职责 |
| --- | --- |
| `main/ui/MainActivity.kt` | 接收 Compose Space action；负责选择 Space 以及恢复沉浸式会话。 |
| `main/ui/compose/KototoroApp.kt` | 持有 workbench gesture state；连接 FAB、主内容变换、Workbench、session 与 resume state。 |
| `space/ui/SpaceSwitcher.kt` | FAB 短按、长按、长按拖动检测；把局部触点转换为 root 坐标。 |
| `space/ui/SpaceWorkbench.kt` | Workbench rail、卡片、缓存封面、命中测试、拖动代理和 settle 动画。 |
| `space/ui/SpaceWorkbenchGestureState.kt` | 独立且可测试的拖动状态机。 |
| `space/ui/SpaceViewModel.kt` | Sheet/Workbench 可见性、切换中状态和 Space 激活。 |
| `space/ui/SpaceSwitcherDelegate.kt` | Reader/Novel Reader/Video Player 等非主 Compose 宿主的旧式切换面板适配。 |
| `settings/compose/SpacesSettingsScreen.kt` | 首次显式启用 Space 的 onboarding。 |
| `core/prefs/AppSettings.kt` | 新安装默认值、onboarding 键和旧版本升级兼容。 |

相关但不是本分支新建的持久化链路：

| 层 | 关键类型 |
| --- | --- |
| Active Space | `SpaceRepository`, `DefaultSpaceRepository`, `KEY_ACTIVE_SPACE` |
| Feature flags | `SpaceFeatureFlagsRepository`, `DefaultSpaceFeatureFlagsRepository` |
| Space catalog | `SpaceCatalogRepository`, `DefaultSpaceCatalogRepository` |
| Navigation snapshot | `SpaceSessionRepository`, `DefaultSpaceSessionRepository`, `SpaceNavigationSessionViewModel` |
| 最近上下文 | `SpaceResumeStateSource`, `SpaceResumeViewModel` |
| 沉浸式恢复 | `ImmersiveSpaceSessionRegistry` 及各 Reader/Player adapter |

## 6. 状态流和不变量

### 6.1 Workbench 状态流

```text
FAB pointer input
  -> SpaceWorkbenchGestureState.start/move/release/cancel
  -> SpaceAction.OpenWorkbench
  -> SpaceViewModel.transientState
  -> SpaceUiState.workbenchVisible
  -> SpaceWorkbench measures card bounds in root coordinates
  -> resolveSpaceWorkbenchDropTarget
  -> settle animation
  -> SpaceAction.SelectSpace
  -> SpaceRepository.activate
  -> navigation/session restoration
```

`SpaceWorkbenchGestureState` 的相位只能按以下方向变化：

```text
IDLE -> DRAGGING -> SETTLING -> IDLE
```

卡片外松手是例外：`DRAGGING -> IDLE`，但 Workbench 仍可见。不要把 Compose animation state、card bounds 或 pointer coordinates 放进 `SpaceViewModel`；它们是单个 UI surface 的瞬态状态。

### 6.2 必须保持的所有权边界

- `space_id` 是体验会话键，不是作品身份键。
- 收藏、历史、统计和 tracking 继续由 Work / Entity 持有，不能复制为三套 Space 数据。
- 章节、播放 URL 和下载由具体 Projection 执行。
- Space session 只引用 Work / Projection 和导航状态；删除 session 不得删除作品数据。
- 跨媒介作品保持为独立 Work，通过 relation 连接，不因标题相同而合并。
- Workbench 不应保活三份 `NavHost`、Reader 或 Player。

## 7. 当前测试覆盖

新增或扩展的 JVM 测试：

- `SpaceWorkbenchTest`
  - 卡片命中和稳定排序；
  - route 到轻量位置描述的映射；
  - 磁吸、选择、取消、当前 Space drop；
  - compact/medium/expanded 布局规则。
- `SpaceViewModelTest`
  - Workbench 打开/关闭；
  - Sheet 与 Workbench 互斥；
  - 切换完成后 overlay 收起。
- `SpacesSettingsScreenTest`
  - onboarding 触发条件。
- `AppSettingsSpaceFeatureDefaultsTest`
  - 新安装默认关闭；
  - 旧用户升级兼容。

测试目前偏向纯状态和纯函数。Compose UI test、无障碍操作、真实 pointer input、旋转/分屏和进程死亡恢复仍是缺口。

## 8. 已知限制和风险

### P0：合并前必须验证

1. **手势冲突**：`combinedClickable` 与 `detectDragGesturesAfterLongPress` 同时挂在 FAB 上，需要在不同 Android 版本和触控设备上确认短按、长按、拖动不会重复触发。
2. **坐标系**：FAB 和卡片通过 root coordinates 对齐；状态栏、导航栏、横屏 cutout、窗口化和折叠屏可能暴露偏移。
3. **动画生命周期**：快速 Back、旋转或 feature flag 关闭时，`SETTLING` animation 可能被取消；必须确认 gesture state 最终 reset，不会留下透明 FAB 或无法操作的 overlay。
4. **旧用户迁移**：用真实旧版 SharedPreferences 升级，确认 `previousVersion > 0` 时不会意外关闭已存在的 Space 功能。
5. **功能关闭路径**：Space 未启用时不应展示 FAB、Workbench 或持久化导航副作用。

### P1：建议下一阶段完成

1. 为 `SpaceWorkbench` 增加 Compose UI tests：点击遮罩、Back、点击 active/inactive card、switchInProgress 禁用状态。
2. 加入 TalkBack 验证；拖动不是唯一入口，卡片点击和长按语义必须始终可达。
3. 使用 `WindowSizeClass` 或项目统一窗口 token 替代当前局部阈值，前提是不会引入额外依赖或分裂布局规范。
4. 为 workbench 展示建立性能基线：首次打开耗时、重组次数、图片缓存命中和内存峰值。
5. 评估 Reader、Novel Reader、Video Player 是否也需要完整 Workbench。当前 `SpaceSwitcherDelegate` 只兼容新增 action，沉浸式界面仍主要使用原切换面板。
6. 补充用户文档和截图，明确短按与长按拖动两套路径。

### P2：产品验证后再做

- 更丰富的 session 缩略图或真实页面快照；先验证隐私、存储和失效策略。
- 自定义 Space、排序、删除和跨设备同步。
- 跨 Space 搜索和媒体关系跳转。
- Workbench 中直接拖放作品，而不仅是拖动 Space FAB。

不要在“一步”交互尚未通过可用性测试前把上述 P2 范围带入当前 PR。

## 9. 建议的人工验收清单

至少覆盖手机竖屏、手机横屏和大屏/平板：

- 新安装默认看不到 Space 功能；设置中启用会出现一次 onboarding。
- 旧安装升级后原有 Space 行为不被关闭。
- 短按 FAB 仍打开原 switcher sheet。
- 长按 FAB 打开 Workbench；Back 和遮罩点击能关闭。
- 拖动到漫画、小说、动画卡片分别能正确切换。
- 拖到当前 Space 会关闭；拖到卡片外会保留 Workbench。
- 卡片外松手后，可以继续通过点击卡片切换。
- 切换中不会接受第二次选择。
- 无网络时 Workbench 不请求封面网络资源，已有缓存封面仍能显示。
- 紧凑横屏隐藏封面，但所有 Space 仍可访问。
- 旋转、分屏调整、切后台再回来后没有卡死、错误缩放或透明 FAB。
- 从详情页、来源列表和顶层页打开时，位置文案正确。
- Reader、Novel Reader、Video Player 的原 Space 切换不回退。
- 开启系统“移除动画”或应用 reduced motion 时不出现长 settle 动画。

## 10. 推荐后续提交顺序

为了便于 review，后续不要把测试、沉浸式适配和视觉扩展塞进一个提交：

```text
test(space): cover workbench compose interactions
fix(space): harden workbench gesture cancellation
feat(space): expose workbench in immersive surfaces
docs(space): document one-step switching gestures
```

每个提交完成后至少运行相关单测和 `:app:compileDebugKotlin`。触及 Reader/Player 或 native 代码时，再扩大到 assemble 和设备回归。

## 11. Git 协作注意事项

在新的开发机器开始修改前：

```bash
git status --short --branch
git fetch origin devel
git rebase origin/devel
```

仅在 feature 分支尚未共享或团队已明确协调时 rebase。若远端已存在同名分支，先比较：

```bash
git log --left-right --graph --oneline origin/feat/space-one-step-workbench...HEAD
```

当前工作树可能来自 partial clone，但这不影响正常提交。不要提交 `.gradle/`、`local.properties`、APK、签名文件、凭据或 IDE 私有配置。

如决定重写当前 5 个提交的作者信息，应先备份分支，再一次性完成并使用 `--force-with-lease` 推送；作者重写属于历史改写，不应与功能修改混在同一次操作中。

## 12. 完成交接的判定

另一台机器满足以下条件即可视为接手成功：

1. `git status` 显示正确 feature branch 且工作区干净；
2. 能看到上面列出的 5 个 feature commit；
3. JDK 17 和 Android SDK 36 被 Gradle 正确识别；
4. 4 组定向单测通过；
5. `:app:compileDebugKotlin` 通过；
6. Debug APK 可安装，并完成第 9 节的核心手势验证；
7. GitHub 认证方式和分支推送权限已确认。

## 13. 常驻 L 工作台的阅读进度约束

常驻工作台中的漫画阅读进度统一归属顶部工作台，不再占用缩放后阅读区的底部空间：

- 竖屏和横屏都使用横向滑杆，页码与滑杆保持同一行；横屏不随右侧命令区改成竖向控件。
- 进度状态只来自 `EmbeddedReaderCockpitState`，跳页只通过 `EmbeddedReaderCommands.seekToPage` 派发，避免工作台维护第二份阅读状态。
- 固定模式开启时隐藏 `EmbeddedReaderHost` 内部底部进度条；关闭固定模式时保留 Compose 底栏作为独立阅读器回退。
- 顶部进度条只在漫画阅读上下文且命令已就绪时展示，不为列表、详情或主页占用高度。
- L 工作台在 MD3、iOS 两种视觉偏好下都使用普通不透明主题色，不应用 Haze、Backdrop、模糊、噪点或玻璃高光。
