# Desktop runtime

Windows/JVM 平台存储装配，供后续桌面应用使用；复用 `core-db` 的 v84 schema/DAO 和 `source-host`
图片存储。本模块保持无 UI；桌面界面由 [desktop-app](../desktop-app/README.md) 消费。
完整兼容层和 Windows 安装包仍待完成。

```powershell
./gradlew.bat :desktop-runtime:test :desktop-runtime:installDist
& "desktop-runtime/build/install/desktop-runtime/bin/desktop-runtime.bat" storage "E:/path/to/desktop-data"
```

`storage` 打开指定持久化目录，首次创建 v84 数据库，输出 UTF-8 JSON 的实际 schemaVersion、来源数量与
数据库/图片/偏好目录后关闭 runtime。已存在数据库必须通过 Room schema/版本校验；不做破坏性重建或旧 Android
迁移。请为应用选择独立数据目录。生成的发行目录包含已有版本的 Room/SQLite JVM 库，可由 JDK 直接运行；
它没有 Android 应用组件。此仓库的 Gradle 构建仍使用既有 Android/KMP 工具链。

桌面应用的构造装配入口：

```kotlin
val storage = DesktopRuntime.open() // Windows: LOCALAPPDATA/Kototoro
// Share storage.database with repositories; pass storage.images to MihonSourceRuntime(registry, storage.images).
// A SourceHostPreferencePlatform binds storage.preferences before constructing any extension.
// The application owns storage lifetime, including close() when its work has stopped.
```

默认根目录来自 Windows 的 LOCALAPPDATA，缺省时用 user.home/AppData/Local/Kototoro；其他 JVM 的通用
回退是 user.home/.kototoro。目录选择本身不写文件，也不修改用户环境变量；open 接受显式 root 供测试/
便携应用使用。路径归一为绝对目录，支持中文/空格，数据库、图片与 preferences 放在同一私有根目录下。

preferences 使用共享的 SourcePreferenceStore 契约和 host 文件 backend，保留六种 SharedPreferences
类型；原 namespace/key 不重命名，单份 UTF-8 快照原子替换，目录有独占进程锁。DesktopRuntime 持有
store 并在 close/取消交付时释放；启动时若偏好目录已被占用，也回收数据库连接。
platform 必须在扩展构造前绑定此 store，不能运行两个 owner 同时打开相同偏好目录；同 JVM 嵌入直接
传 storage.preferences，独立 source-host serve 则由 session 持有 config 指定的目录。
详细 ABI、commit/apply 和监听行为见 source-host README；标准控制树/变更协议已接入 host，
desktop-app 已接标准源设置界面。

数据库 builder 使用固定 Room 2.8.4、项目已验证的 bundled SQLite 2.6.2 和 Dispatchers.IO，沿用
[官方 Room KMP 的 JVM 构建方式](https://developer.android.com/kotlin/multiplatform/room)。在线文档已转向
Room 3 alpha，项目保持当前版本与 API，不据此升级依赖。startup 强制执行 DAO 查询，完成 schema 校验
后才创建图片 cache 并返回可用 runtime。

`DesktopSQLiteDriver` 跟踪本 runtime 创建的连接，关闭时补回收 Room 初始化失败后没有进入池的句柄。
Windows 上对 corrupt/v83 数据库的测试曾发现常规 Room.close 留下文件占用；driver owner 修复该路径，
没有改写库字节码或使用反射。正常 pool 关闭与 driver 兜底关闭不会重复关闭，清理异常保留原错误。
`DesktopRuntime.close` 幂等；dispatcher 返回期间取消也关闭已创建但未交付的 runtime。

测试运行真实 Windows SQLite native 库，覆盖磁盘持久化/重开、来源/漫画/收藏/历史 DAO、Flow、v84/Room
identity 与 Android schema 对齐、图片目录、未知版本/损坏数据保留、启动失败句柄释放、取消与独立发行 CLI。
测试只操作临时目录；本机 CLI smoke 使用 ignored build 目录，没有接触用户实际 LocalAppData 数据库。

`DesktopLibrary` 将源 DTO 写入现有漫画/标签/章节/收藏/历史表，直接使用 manga_id，拒绝身份冲突，
保留标签 pin、收藏创建时间与分支阅读进度；重启持久化和错误不改写数据由专门测试覆盖。
`recordPage` 可接收最后可见页：历史 page 保留页组起始锚点，percent 包含页组末页，双页读到
章节结尾不会少算一页。默认值保持单页调用行为；复用现有 v84 字段，没有 schema 变更。
DesktopLibraryBackup 复用 core-backup 模型导出/合并恢复收藏分类、收藏、历史、书签、统计、内容和来源排序；
ZIP 私有预览、流式读写、CRC/格式校验、原子数据库合并、分类重映射及历史删除记录保护见计划 §53–54。
书签按完整主键匹配，统计会话计数取较大值；书签/统计独有作品也导出，重复恢复不累加统计。
旧 Work 收藏/历史/统计投影到漫画锚点，保留完整 Long 编号；现有详情/章节/opaque source data 不被备份缺失字段清空。
其余备份节、外部格式导入与同步、网络/HTTP cache 生命周期和高级控件继续推进；
封面已接桌面 UI，Windows 本地应用打包见计划 §52；
iOS 数据库 builder、原生编译/真机验证仍待完成。
