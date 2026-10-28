# 桌面 Google Drive 同步

入口为桌面「备份与恢复」页面中的 Google Drive 区域。先连接账号，再开启 Google Drive 同步。
支持手动同步、启动时同步，以及应用运行期间的定时同步；退出应用后不执行后台任务。

## OAuth 配置与凭据

默认桌面 OAuth Client ID：

```text
937269114137-905oul32pv7n3j2ek9caf42eq5gn09td.apps.googleusercontent.com
```

它必须是在 Android 使用的同一 Google Cloud 项目下创建的 **Desktop app** 客户端；在该项目中启用
Drive API，并按 OAuth 发布状态配置测试账号。iOS 继续使用独立的 iOS 类型客户端。

桌面使用系统浏览器、PKCE S256、随机 state 和 `127.0.0.1` 随机端口回调。仅申请 `openid`、`email`
和 `https://www.googleapis.com/auth/drive.appdata`。回调校验方法、路径、Host、state 和重复参数，
授权等待三分钟后结束，取消或退出会释放监听器。内置来源浏览器不参与授权。

本项目的桌面 OAuth 客户端在授权码兑换和令牌刷新时都需要 `client_secret`；PKCE 不能替代该参数。
已用不涉及用户授权的无效测试码验证真实 Google token 端点：缺失时返回 `invalid_request`，
补齐客户端配置后进入预期的无效测试码检查（`invalid_grant`）。

本地构建在 Git 忽略的 `local.properties` 中配置：

```properties
googleDrive.desktop.clientSecret=<桌面 OAuth 客户端密钥>
```

CI 在仓库 Actions Secrets 中配置 `GOOGLE_DRIVE_DESKTOP_CLIENT_SECRET`。桌面工作流把它传入
`KOTOTORO_GOOGLE_DESKTOP_CLIENT_SECRET` 环境变量，nightly/release 和嵌套工作流也显式转发此 Secret。
环境变量优先于本地文件。Gradle 将配置生成到忽略的构建资源，构建诊断只记录摘要，不输出值。

桌面客户端配置会随应用包分发，不能当作服务端保密凭据；PKCE 和随机 state 继续启用。
用户授权后的访问令牌与刷新令牌独立保存在：

- macOS：系统 Keychain，通过已有 JNA 调用 Security framework。
- Windows：当前用户的 Credential Manager。
- Linux：Secret Service，需安装 `secret-tool` 并有可用的桌面钥匙串。

钥匙串不可用时明确报错，不回退到普通文件。凭据按数据目录与客户端标识隔离，不进入 Room、偏好文件或备份。
访问令牌临近到期时自动刷新，Drive 返回 401 时刷新并重试一次；授权失效后等待用户重新连接。
断开连接仅清理本机凭据，不调用会影响同项目其他客户端的 Google 撤销授权操作。
缺少客户端配置时，在打开浏览器前显示配置错误；服务端的客户端配置错误不会再误报为用户授权失效。
首次连接失败时保持未登录界面，已有启用后端缺失凭据时仍保留关闭后端的恢复入口。
浏览器的回调提示只表示授权码已交回应用，连接是否成功以应用最终状态为准。

参考：[Google 桌面 OAuth](https://developers.google.com/identity/protocols/oauth2/native-app)、
[应用数据目录](https://developers.google.com/workspace/drive/api/guides/appdata)、
[Drive 上传会话](https://developers.google.com/workspace/drive/api/guides/manage-uploads#resumable)。

## 共享范围

- `core-domain/GoogleDriveSyncCodec`：三端共用的格式校验和序列化，损坏、空白、不支持的协议中止上传。
- `core-library/GoogleDriveSyncCoordinator`：Android、桌面和 iOS 共用查询、下载、合并、版本复查与上传流程。
  复查所有输入文件集合及版本，冲突最多尝试三轮，耗尽后停止写入。
- `core-library/LibraryDriveSync`：桌面与 iOS 共用 Room 写事务、作品身份映射、删除记录比较及上传快照生成。
  Android 仍通过自身存储适配执行设置、来源状态、扩展恢复与数据清理；并未把整个 Android Repository 搬到共享层。
- `core-net/GoogleDriveSyncApi`：各端提供 HTTP 引擎，使用同一 Drive HTTP 协议。首次上传使用 resumable
  session，在内容完成前不发布空文件；更新已有文件沿用 media PATCH。
- `core-ui/GoogleDriveSyncSettingsContent`：Android 与桌面实际调用同一份账号、同步控制与确认界面，
  宿主声明支持的操作。Android 保留系统授权、导航、insets 和对话框渲染。

`core-library` 已增加 Android 目标。同步流程的本地写入回调拥有事务，网络请求位于事务之外。

## 数据与行为

读写现有 `kototoro_sync_content_v3.json`，没有当前文件时从 work v2 文件读取迁移种子，保留旧文件。
同步作品、收藏分类、收藏关系、历史与阅读进度、统计、更新追踪与更新日志。64 位编号始终由 Kotlin 处理。

桌面暂不应用云端设置、扩展仓库、来源开关、JSON 来源及扩展包；上传时保留这些已知协议字段。
独立设置文件不被桌面修改。书签和本地媒体不属于当前 Drive 书库协议。

开启 Drive 前，界面沿用 Android 的后端切换确认；开启后暂停 WebDAV 自动任务并禁用其操作，
关闭 Drive 后恢复已保存的 WebDAV 配置。授权损坏或缺失时仍可关闭 Drive，避免其他后端入口被锁住。
启动同步沿用十五分钟尝试冷却；定时同步失败后退避十分钟；手动与自动同步不并行。

版本复查与上传之间仍没有原子 CAS，极端同时上传存在竞争。分类缺少独立修改时间，仍有原协议的
同编号分类与同时重命名限制。桌面和 iOS 不删除重复远端文件，Android 保留已验证重复文件的清理行为。

## 验证

```bash
./gradlew :core-net:jvmTest :core-library:jvmTest
./gradlew -PwithDesktopApp -PmihonCompatibilityDirectory=build/desktop-compatibility \
  :desktop-app:test --tests '*GoogleDrive*'
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest \
  --tests 'org.skepsun.kototoro.sync.google.*'
./gradlew :ios-shared:iosSimulatorArm64Test -PiosSimulatorDevice=<UUID> \
  --tests 'org.skepsun.kototoro.ios.IosGoogleDriveSyncTest'
```

桌面测试覆盖 PKCE 标准向量、本机回调、令牌兑换、401、凭据隔离和真实 Room 数据库往返；
共享测试覆盖协议拒绝、冲突和取消，UI 测试覆盖窄宽窗口、后端切换确认与能力差异。
Windows 凭据管理器仍需 Windows 实机验收，模拟授权不代替真实账号云端验收。

真实账号验收：Android 同步一部作品 → 桌面连接同账号并同步 → 检查分类与进度 → 桌面修改进度、
取消收藏后同步 → Android 回读 → 两端重启复查。此步骤需要用户在浏览器完成 Google 登录。

### 本次验证记录（2026-10-28）

- Android 与桌面 Kotlin 编译通过，共享 UI／桌面文案校验通过。
- `core-net` 105 项、共享同步编排 4 项、桌面 Drive 8 项、iOS Drive 9 项测试通过。
- Android Drive 专项 46 项通过。常规单元测试任务会编译全部测试源码，目前被既有
  `SettingsItemGroupTest` 的内部成员访问及 `VideoPlayerControlsTest` 的 `formatDuration` 引用阻塞。
  专项验证通过临时 Gradle init 脚本将测试源码限定到 `sync/google`，未修改这些无关测试。
- 桌面测试实际运行于 macOS；已验证 Keychain 的隔离账号查询、真实本机 HTTP 回调及 380／1000 像素窗口交互，
  截图位于 `desktop-app/build/reports/google-drive-{380,1000}.png`。
- macOS 真实 Google 登录已由用户确认成功；Windows 凭据管理器和跨设备云端往返尚未执行实机验收。

### 桌面授权配置修复（2026-10-28）

补齐授权码兑换和刷新请求中的客户端密钥，修复 `invalid_request` 被误判为授权失效、
首次失败仍展开同步选项的行为。回归覆盖缺少配置时不打开浏览器、不发送请求、不保存凭据，
以及密钥仅用于 token endpoint、刷新令牌兼容和已有后端恢复入口。
更新后的 macOS 应用已由用户确认 Google 登录成功，GitHub Secret 也已由用户配置；跨设备同步仍需单独验收。
