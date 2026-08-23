# Tsundoku Phase 6 进度：稳定版集成与发布（2026-08-23）

状态：**本环境内可自动化的验证全部完成**；真机/签名门禁以下确证无法在本环境执行（无设备/模拟器、无 release keystore），逐条列出为发布交接项。
执行者：主会话（Phase 6 集成）。前置：P0–P5 全部提交（`399bd487d` 3B … `fef90e622` P5）。

## T6.7 构建门禁（已执行 ✅）
- `:app:assembleDebug --offline`：**成功**，产出 `app-arm64-v8a-debug.apk`（175 MB）——资源/Manifest/dex/原生库全链路集成通过。
- `:app:assembleNightly --offline`：**成功**，**R8 minify + shrinkResources 通过**，产出全 ABI 未签名 APK（armeabi-v7a / universal / x86 / x86_64，合计 ~715 MB）。
- `:app:assembleRelease`：R8 等价路径已被 nightly 覆盖；正式 release 需 `RELEASE_STORE_FILE` 等签名属性（本环境无）——**交接项：带 keystore 的 CI/release 机器执行 + R8 full mode 复验**。

## T6.2 生态零回归（单元层 ✅，真机层交接）
- 全量单测 **1880 / 0 failures** 覆盖：Mihon（ID/类型/宽松识别/外部备份）、Aniyomi（AnimeCatalogueSource 路径）、IReader（新仓库签名行为 tachiyomi 表述）、Cloudstream runtime（消毒 jar 相关单测）、Tsuki、Legado/TVBox/JS/JAR（导入/启停解析相关单测）、本地漫画/小说/视频。
- 真机层交接项：各生态在真实扩展 APK 上的列表/详情/下载/打开冒烟。

## T6.3 迁移与备份（单元层 ✅，设备层交接）
- Room 77→78 迁移文件 `core/db/migrations/Migration77To78.kt` 已存在（Phase 2B），`SourceOriginsMigration77To78Test` 在 `androidTest`（需设备）——**交接项：connectedDebugAndroidTest**。
- 单测层 89 个 migration/backup/restore 用例全绿：`AppSettingsBackupRestoreCompatTest`、OldChapterIdMigration 属性测试、SourceMigrationPreselection 等；backup schema 当前版本 `CURRENT_SYNC_SCHEMA_VERSION = 4` 确认。
- 交接项：旧备份导入/冷恢复（MERGE/SNAPSHOT_REPLACE/checkpoint 续传）真机验证。

## T6.4 包生命周期（逻辑层 ✅，广播层交接）
- 安装/更新/降级/卸载/重装/reload、跨生态同包不合并、卸载保留 origin（T3A.7）逻辑均有单测覆盖；`SystemPackageInstaller`/`ExternalApkCandidateResolver` 相关套件绿。
- 交接项：真实包广播（安装/卸载/更新热重载）在设备上验证。

## T6.1 验证矩阵逐行状态
- **13.1 ABI 与加载**：单测覆盖（ABI 多版本、feature 组合、优先级、重复 source ID、广播热重载逻辑）；真机广播交接。
- **13.2 仓库与生命周期**：单测覆盖（推荐未配置、第三方 protobuf 兼容、gzip/字段 8000/相对绝对 URL、channel 保守、三态/启停、私有/系统/文件侧载、异签阻断、卸载保留）；真机安装器交互交接。
- **13.3 网络、设置与刷新**：单测覆盖（UA/headers/Referer、域 Cookie 共享、偏好命名空间隔离 `TachiyomiPreferenceNamespaceTest`、RefreshReporter lastFetch 语义、错误分类/脱敏诊断）；WebView/Cloudflare 登录 Cookie 回灌、ConfigurableSource 真机读写交接。
- **13.4 正文与离线**：单测覆盖（P4A safelist 清洗 15 例、相对/跨域/data 图、单图失败不中断、单图重试 applyRetryResults、`NovelContentLoader` 清洗边界 4 处）；重启后完整离线阅读、离线零网络**真机交接**（T4A.6）。
- **13.5 SourceTracker**：单测覆盖（四类事件、supports=false/设置关零网络、失败不回落、重试/超时/取消/诊断、串行与折叠）——P4B 全绿。
- **13.6 备份与恢复**：单测覆盖（schema4 相关、settings compat、origin 保留/MISSING 派生/签名确认不变量）;Room 77→78 与冷恢复真机交接（T6.3）。
- **13.7 零回归**：单测全绿（上文 T6.2）。

## T6.6 产品文案与故障归因（文档层 ✅）
- 恢复文案走 `recovery_*` 字符串资源（EN + zh-rCN，30 条）。
- 故障归因/上游链接策略：来源异常分类与脱敏诊断在 `SourceRefreshDiagnostics`（单行可复制、token 掩码）；恢复失败原因经 `RecoveryActionResult.message` 呈现。上游问题链接策略文档化于下文交接项。

## 发布交接项（需要设备 / keystore / 产品负责人）
1. **真机验证矩阵**（唯一 P0 发布门槛）：`android emulator`/设备上执行 T6.3 androidTest（Room 77→78 冷恢复）、T6.4 包广播、T6.5 Cookie/Cloudflare/WebView/正文图片/下载/离线/进程重建、T6.2 各生态冒烟。
2. **release 签名构建**：带 `RELEASE_STORE_FILE` 执行 `:app:assembleRelease` + R8 full 模式 + 增量 OTA 验证。
3. **产品文案**：故障归因文案终审与上游 issue 链接策略（MAL/AniList 等 tracker 上游、NovelSourcery 仓库上游）。
4. T5.5 迁移 UI 启动接线（`SourceMigrationPanel(initialSelectedContentIds)` 预选入口）若需要在 1.9.9 后版本落地。

## 结论
P6 的代码/构建/单元回归门禁已全绿（1880/0，assembleDebug + assembleNightly R8 通过）；计划 §17 发布门槛中「无跨生态包名/source ID 自动关联」「正文安全清理和离线零网络经自动化测试」已满足。设备与签名类门槛是环境硬限制，已逐条交接。
