# iOS Google Drive 同步

设置 → Google Drive 同步提供账号连接、手动双向同步、可选的打开应用时同步及断开连接。
自动同步默认关闭；前台触发有 60 秒冷却，不承诺 iOS 后台定时执行。

## 配置 Google OAuth

1. 在 **Android 正在使用的同一个 Google Cloud 项目** 中启用 Google Drive API，创建类型为 **iOS** 的 OAuth 客户端。
   不要使用 Android 或 Web Client ID，也不要另建项目：`appDataFolder` 按应用隔离，另一个项目不能用来读取原同步文件。
2. Bundle ID 必须与安装包一致；默认是 `org.skepsun.kototoro.ios`。无需 App Store ID，无需先上架。
3. OAuth 同意屏幕配置 `openid`、`email` 和 `https://www.googleapis.com/auth/drive.appdata`。
   Testing 状态下加入测试账号；Drive 授权受测试模式的七天过期规则影响。
4. 为应用 target 的 Debug/Release 设置以下构建参数：
   - `KOTOTORO_GOOGLE_CLIENT_ID`：iOS Client ID，例如 `123-ios.apps.googleusercontent.com`。
   - `KOTOTORO_GOOGLE_REVERSED_CLIENT_ID`：反向域名，例如 `com.googleusercontent.apps.123-ios`。
   两项通过 Info.plist 注入，代码会核对回调 scheme；缺少配置时禁用连接并显示说明。
   Client ID 是公开标识，不要在应用中添加 Client Secret。

也可在命令行构建时传参：

```bash
xcodebuild -project "iosApp/Kototoro.xcodeproj" -scheme "Kototoro" \
  -configuration Debug -sdk iphonesimulator \
  -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath "iosApp/build/DerivedData" CODE_SIGNING_ALLOWED=NO \
  KOTOTORO_GOOGLE_CLIENT_ID='123-ios.apps.googleusercontent.com' \
  KOTOTORO_GOOGLE_REVERSED_CLIENT_ID='com.googleusercontent.apps.123-ios' build
```

官方依据：[iOS 客户端配置](https://developers.google.com/identity/sign-in/ios/api-access)、
[原生 OAuth / PKCE](https://developers.google.com/identity/protocols/oauth2/native-app)、
[Drive 应用数据目录](https://developers.google.com/workspace/drive/api/guides/appdata)、
[OAuth 测试模式](https://support.google.com/cloud/answer/15549945)。

## 协议与数据边界

- HTTP 复用 `core-net/GoogleDriveSyncApi`，同步编排复用三端共用的 `core-library/GoogleDriveSyncCoordinator`；
  写入 Android 同名的 `kototoro_sync_content_v3.json`，
  标记为 `schema=2`、`namespace=kototoro.content.v3`、`semantic_schema=1`。
- 没有 v3 文件时从 `kototoro_sync_work_v2.json` 迁移，保留原文件。旧 `kototoro_sync.json` 不自动导入。
- 双向同步作品、收藏分类、收藏关系、阅读历史/进度、阅读统计、更新记录。
  使用共享 Kotlin 序列化，64 位作品/章节编号不经过 Swift Double 或 JavaScript。
- 同源同 URL 的作品复用本地编号，编号碰撞通过共享 `importSyncContent` 分配负编号。
  数据库导入与快照构建处于同一 Room 写事务；失败回滚。读取删除记录参与比较，防止旧云端进度复活已删除历史。
- 未安装的来源不会导致作品被丢弃，现有缺失来源入口负责后续恢复。
- iOS 当前不应用云端扩展仓库、源开关、JSON 源、扩展安装包或 Android 设置；上传时保留这些已知协议字段。
  不触碰单独的 `kototoro_sync_settings_v1.json`，不自动安装任何扩展。不传输本地媒体文件。
- 类别协议缺少独立的修改时间，沿用共享协议的编号/创建时间规则；跨设备同编号的新分类及同时重命名仍有协议限制。
- 下载失败、损坏/空文件或不支持的版本会中止上传。上传前复查输入文件集合和版本，冲突最多重试三次，
  耗尽后报错；不执行远端重复文件删除。与 Android 现有 Drive 协议一样，版本检查与上传之间没有原子 CAS，
  两端恰好同时上传仍存在竞态；重要迁移应顺序同步两台设备。

## 授权与存储

`ASWebAuthenticationSession` 打开 Google 官方授权页，使用随机 state、PKCE S256 和严格回调匹配。
通过 token endpoint 换取凭据并从 Google userinfo 读取账号身份。刷新令牌和访问令牌仅写入设备本地 Keychain，
不进入 UserDefaults、Room 或备份。过期前自动刷新，Drive 401 时刷新后重试一次。
断开连接只清理本机授权和自动同步开关，不撤销同项目安卓端的授权，也不删除本地或云端数据。

## 验证

```bash
./gradlew :ios-shared:iosSimulatorArm64Test -PiosSimulatorDevice=<UUID> \
  --tests 'org.skepsun.kototoro.ios.IosGoogleDriveSyncTest'
./gradlew :core-domain:jvmTest --tests 'org.skepsun.kototoro.sync.google.*'
xcodebuild -project "iosApp/Kototoro.xcodeproj" -scheme "Kototoro" \
  -destination 'platform=iOS Simulator,id=<UUID>' -derivedDataPath "iosApp/build/DerivedData" \
  CODE_SIGNING_ALLOWED=NO -parallel-testing-enabled NO \
  -only-testing:KototoroUITests/GoogleDriveSyncTests test
```

Native 测试使用 Android 格式 JSON、真实 Room 数据库与 Ktor MockEngine；Swift 测试覆盖 PKCE 标准向量、
错误/伪造回调拒绝、表单编码和未配置构建的设置入口。UI 未配置用例需在默认空 Client ID 的构建中运行。

真实账号验收需要配置上述 iOS 客户端：Android 同步一部作品 → iOS 用同一账号同步并检查分类及进度 →
iOS 改变进度/取消收藏并同步 → Android 同步回读 → 两端重启后复查。
目前未执行真实 Google OAuth 和两台实机间的云端验收，不能把模拟测试等同于真实账号验收。
