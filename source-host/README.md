# JVM source host

`source-host` 是 P2 的 JVM 侧入口，当前实现 Mihon JAR 检查、源注册和内容执行器。
它使用 `core-source` 的可序列化元数据；Android 应用不依赖此模块，原进程内加载流程不变。

## 已实现

- `MihonJarInspector.inspect(Path)`：读取文本 `AndroidManifest.xml`、解析 1.4–1.6 元数据、
  规范化单/多 entry class，计算 SHA-256，检查 VM 字节码上限。检查时不执行扩展代码。
- `verify(metadata, MihonJarIdentity)`：要求仓库提供的 package/version/hash 与本机产物一致。
- `MihonJarRegistry.load`：校验后才构造 entry，展开全部 `SourceFactory` 实例，
  注册每个源的 `MIHON_{sourceId}` 稳定键、语言、显示名称和 supportsLatest。
- 每个 JAR 使用独立 `URLClassLoader`，由宿主提供的兼容层作 parent。
  同一包重复加载、源 ID 冲突、空/失败 factory 不发布部分注册结果。
- `MihonSourceRuntime` 实现共享 `SourceRuntime`，可通过 `SourceEndpoint` 执行 JSON 请求。
  支持热门、最新、文本搜索、详情/章节、页面及图片 URL 解析；页索引从 0 开始，对应 Mihon page 1。
- `MihonModelRules` 与 Android 共享 ID、URL 和标签/分级规则；memo、HTML 描述、图片 fragment、
  原始页面 URL/index 和请求头保留。LRU snapshot 显式复制默认 copy 方法遗漏的 memo。
- `MihonFilterRules` 与 Android 共享原标签键、分组与状态映射。`getFilterOptions` 提供兼容标签选项；
  `getDynamicFilters` 提供标准 FilterList 的复选框/三态/单选/排序/文本/嵌套组/说明与分隔项。
  索引、排序方向、默认值和未知自定义控件元数据保持；不把 label 当作原生 choice 对象。
- 真实挂起调用接受 Continuation、等待 COROUTINE_SUSPENDED、处理异步完成；支持 Kotlin 的 bridge
  默认方法，旧 Rx 回落由平台提供的 Mihon API 执行。不同扩展可并行，同一源的执行与缓存串行。
- `unload`/`close` 注销后拒绝新调用，待已开始的调用/回调结束才关闭对应 JAR 句柄；
  取消向调用方传播，不返回伪造成功。未合作的扩展回调仍占用 lease，直到它完成。
  不删除产物，不销毁或重建 JVM。

检查器、注册表和 CLI 独立编写，不包含参考项目的 GPL host/shim 源码。
兼容 API 通过反射调用；没有在默认依赖或发行目录中打包 Suwayomi/AndroidCompat/外部扩展。
外部兼容层的初始化和生命周期由嵌入平台负责；仅将其 JAR 加入 classpath 并不等于完成初始化。

## 构建与本地检查

```powershell
./gradlew.bat :source-host:test :source-host:installDist
& "source-host/build/install/source-host/bin/source-host.bat" inspect "C:/path/to/extension.jar"
& "source-host/build/install/source-host/bin/source-host.bat" serve "C:/path/to/host-config.json"
```

独立发行目录中的 `inspect` 输出 `MihonJarMetadata` JSON，UTF-8 文本格式，不需要 AndroidCompat。
`sources <jar> <package> <versionCode> <sha256>` 用于已准备好兼容运行环境的进程；
默认发行目录不携带兼容 API，缺少它时明确报 API_UNAVAILABLE。
生产嵌入入口应持有初始化后的 compatibility ClassLoader，再创建 registry，
在 JVM host 生命周期内复用 registry，向 native 侧只返回协议 DTO。

内容入口由初始化兼容层的平台装配，示例：

```kotlin
val registry = MihonJarRegistry(compatibilityLoader)
registry.load(verifiedJarPath, repositoryIdentity)
val endpoint = SourceEndpoint(MihonSourceRuntime(registry))
// endpoint.exchange(requestJson) is a suspending operation; the platform owns dispatcher/cancellation/lifetime.
```

章节图片执行由平台提供私有目录并显式启用：

```kotlin
val images = FileSourceImageStore(privateImageDirectory)
val endpoint = SourceEndpoint(MihonSourceRuntime(registry, images))
// fetchImage(page) returns SHA-256 metadata and a relative filename in images.directory.
```

`describe().isImageFetchingSupported` 仅在配置 store 的 HTTP 源上为 true；默认关闭。
`SourcePage.requestContext` 保留原始 Page 的 index/url/imageUrl/uri，页面 ID 不能替代原始 index。
`fetchImage` 必要时调用源的 getImageUrl，再执行真实 getImage；请求走扩展自身的 imageRequest/client，
拦截器解密/解扰后的 response body 才写入文件，保留原始 URL fragment，不另建 HTTP 下载器。
旧 mihon://image/resolve 包装可恢复上下文；缺少 index 的普通 URL 不猜测，native URI 暂报不支持。

文件逐块写入、SHA-256 命名（hash.img）、同目录原子发布；默认单张限制 64 MiB，拒绝空/长度不符/超限
和未知图片签名。相同内容复用，已有损坏 blob 校验后替换；多个 store 的同文件校验/发布在本进程串行。
原生侧根据平台持有的私有根目录读取相对路径；JSON 只传大小/hash/type/来源/pageId，不传字节或绝对路径。
类型来自文件头，属于格式识别，不保证完整解码或 iOS 可渲染。目录须由一个 JVM host 独占发布管理。
取消会关闭响应并清理该调用的暂存文件；迟到响应也关闭，在 body/回调结束前保持扩展 loader lease。
原子发布后才发生的取消可能留下完整缓存文件，不能据此向调用方返回成功；平台负责缓存保留/清理策略。
Android parser ABI 没有 context 字段，Android adapter 对携带 host context 的页面显式拒绝，避免静默丢失。

封面执行使用独立的 `fetchCover(content, large)` / `cover` 协议，`isCoverFetchingSupported` 同样要求
HTTP 源与 image store。`SourceCoverArtifact` 返回 contentId，章节 `SourceImageArtifact` 仍返回 pageId，
两者保留十进制字符串 ID、相对 hash.img 和已物化字节的 metadata，不发送图片字节/绝对路径。
请求沿用 Android 的处理顺序：尝试扩展 imageRequest，章节上下文不适用时使用 source.headers 的 GET；
始终通过扩展自己的 client，保留自定义请求和 client 拦截器。已存在的封面 Referer 补充策略在
`MihonModelRules` 共享，来源声明的 Referer 不覆盖；默认回退保持既有行为。
封面通过外部平台提供的公开 OkHttp enqueue/Callback ABI 执行，不引入第二个 HTTP client 或依赖。
取消调用 actual Call.cancel，迟到回调结束前保留 loader lease 和来源执行等待；body 复用同一限额、
流式写入、原子发布与关闭规则。Windows 原生解码/有界缓存/UI 的实际扩展验证见计划 §41。

## Windows / 桌面常驻调试

`serve` 在一个 JVM 中复用同一个 registry/runtime，stdin/stdout 为 UTF-8 JSONL，每行一个现有版本化
请求/响应。响应立即 flush，空行跳过，协议错误后继续，EOF 关闭 host/platform；日志转到 stderr。
没有 HTTP 服务或网络端口。Windows 可以对同一执行器设置 JVM 断点；JVM 调试参数通过显式启动传入。

```json
{
  "platformClass": "your.desktop.InitializedCompatibilityPlatform",
  "jars": [{
    "path": "extensions/example.jar",
    "identity": {"packageName": "your.extension.package", "versionCode": 1, "sha256": "<verified sha256>"}
  }],
  "imageDirectory": "images",
  "preferenceDirectory": "preferences"
}
```

相对路径基于 config 所在目录；绝对路径同样可用。identity 必须是预期仓库身份，不能跳过验证。
provider 是 classpath 上实现 `SourceHostPlatform` 的公开零参数类：initialize 初始化兼容层并返回它的
ClassLoader，close 释放平台线程/资源。通过原始 `java -cp ... SourceHostCliKt serve ...` 启动时，
平台将 provider 和兼容产物加入 classpath。default installDist 不包含它们；inspect 仍可独立运行。

配置 preferenceDirectory 时，provider 必须实现 `SourceHostPreferencePlatform.initialize(preferences)`。
它应在返回 ClassLoader 前将兼容 Application.getSharedPreferences 绑定到传入的 store；之后 session 才
构造扩展。`MihonPreferenceBridge` 使用父兼容层自己的 Android SharedPreferences/Editor/listener 接口，
可供平台的具体 Application/Context override 委托；不能用接口 Proxy 替代具体 Application。
未实现这项 SPI 的 provider 会在加载前失败；不配置时保留原初始化入口。store 在平台 close 后关闭，
因此平台关闭回调仍可保存偏好。兼容层默认 CustomContext 使用自己的 JavaSharedPreferences，不能据此
假定已接到我们的数据目录，或在构造后恢复一份快照来代替正确的初始化。

类型契约在 core-source：String/Boolean/Int/Long/Float/StringSet 不混用，Long 用十进制字符串，Float 用
原始 IEEE bits；集合读写隔离，命名空间/键保留原值（包括 `source_<id>`）。文件名为 namespace 的
SHA-256，UTF-8 文档保留 namespace/version，单份默认限额 4 MiB；损坏/未知版本拒绝读取并保留原文件。
单目录一个进程 owner，写入同目录临时文件、force、atomic move，close 释放 Windows 文件锁。
edit 的 clear 先于最终 key 变更；写盘失败返回 false，但保留已发布内存，后续 edit 重试整个快照。

bridge 的 commit 同步返回持久化结果；apply 也同步完成磁盘尝试，尚未实现 Android 异步写盘调度。
平台必须提供监听器 dispatcher；监听器弱引用，Android R+ clear 的 null key 通知可切换为旧目标行为。
此实现负责存储 ABI；标准可执行 PreferenceScreen 控制树与变更协议见下，动作/输入绑定与原生设置 UI
仍待。存储 ABI 的实际证据见计划 §31。

提供标准设置协议时，provider 进一步实现 `SourceHostPreferenceUiPlatform.preferenceContext()`，返回已初始化
父 AndroidCompat 运行时的 Context。嵌入调用可传 `MihonSourceRuntime(registry, images, context)`；没有 Context
或源未实现 ConfigurableSource 时，describe 的 isPreferencesSupported=false，操作明确报 unsupported。
Android adapter 继续使用原设置入口，本协议默认不支持。

```json
{"version":1,"requestId":"settings-1","call":{"operation":"preferences","sourceName":"MIHON_9007199254740993"}}
{"version":1,"requestId":"settings-2","call":{"operation":"updatePreference","sourceName":"MIHON_9007199254740993","revision":"<screen revision>","nodeId":"pref:0","value":{"operation":"string","value":"<native entryValue>"}}}
```

preferences 运行真实 setupPreferenceScreen，返回源身份、revision、路径 node ID、key/标题/summary、当前/
默认类型值、选择的原始 entryValues、enabled/visible 与子树。原生 Screen 在 setup 前后均绑定偏好，
控件对象留在 loader generation/session 内。标准 List/MultiSelect/EditText/Switch/CheckBox 支持修改；
重复 key、坏 choices、自定义 action、带 OnBindEditTextListener 的输入控件显式 UNSUPPORTED。
父 group 的 enabled/visible 限制传到子节点。未知扩展控件不能降级成直接 key 编辑器。

updatePreference 先完整校验 revision/ID/可编辑状态/类型/选择值，然后调用原生 callChangeListener；接受后
调用原生 saveNewValue，再以空 commit 等待/重试持久化，不绕过源的保存行为。返回 ACCEPTED/REJECTED/
PERSISTENCE_FAILED 和更新后的树/revision。回调可修改其他控件/偏好或抛异常；即使拒绝/抛异常也使旧
revision 失效，已发生的源侧效果不回滚，错误信息沿原协议脱敏。更新本身也可在 caller 取消后完成副作用；
同步回调直到实际结束都持有同源执行 mutex/loader lease，其他 runtime 实例等待。

再次 preferences 会重建树并使旧 revision 失效；不同 runtime session/卸载重载均不能复用旧 token。
更新不会自动重构扩展构造时缓存的 domain/client，平台若需重建应显式处理。再次 update 会再次执行回调，
调用方不能把带副作用的设置更新当作安全的自动重试。实际 JAR/API 与回归证据见计划 §32；原生 UI、
依赖输入绑定/自定义动作和 Android UI Looper 行为仍待。

```json
{"version":1,"requestId":"windows-1","call":{"operation":"sources"}}
```

桌面应用可以在同 JVM 中装配 registry/runtime/endpoint；JSONL 也可供外部调试工具使用。
Windows UI 计划采用独立 Compose Desktop 外壳，共享业务/reader 语义；数据库/偏好存储已落地。
可选 [mihon-desktop-compat](../mihon-desktop-compat/README.md) 已提供基础平台 provider 和实际 API 离线测试，
完整平台能力、偏好原生 UI/高级控件、浏览器/图片消费与打包继续推进，证据边界见计划 §29–33。
Windows 验证不替代 iOS Zero/真机验收。

`SourceFilter.dynamicFilters` 按节点 ID 传输类型化变更：`Toggle`、`TriState`、`Choice`、`Sort`、`Text`；
Sort 接受 null 清空选择。节点 ID 包含树路径、类型和名称，重复名称可区分；未知/重复 ID、类型错配、
非法索引、跨源标签明确拒绝。树中的 UNSUPPORTED 控件不能修改；字段/选择定义变化后需重新取树。
值的 JSON 使用共享序列化器的 `operation` 区分联合类型，外层 `dynamicFilters` 也是独立协议操作。

JVM host 保留原生过滤器 subclass 与 choice 对象，只写 state；全量校验通过后才写入。
每次搜索在真正回调结束时恢复之前的 state；仅 query/typed 变更不会清掉未指定控件的默认值。
忽略取消的扩展仍持有 native 状态与 lease，同源后续操作（包括另一 runtime 实例）等待其结束。
Android UI 的标签请求继续原先的状态策略；动态树 API/typed 变更暂由 JVM host 提供，
Android endpoint 对 typed 变更显式报 UNSUPPORTED_OPERATION，不在 parser 投影时丢弃。

通用 year/locale/author 等独立字段尚未映射；应通过源提供的对应控件表达，否则明确返回
`UNSUPPORTED_OPERATION`。相关推荐亦未接入。两个详情模式都刷新源数据；snapshot 用于
保存扩展上下文，不缓存详情响应。`getPageUrl` 仅返回 URL；图片读取应调用启用后的 fetchImage，
以保留扩展客户端和原始 Page 上下文。

SHA-256 验证证明产物与调用方的预期相符；信任哪个仓库、下载、持久化安装记录和更新策略属于安装层。
产物必须在加载期间保持不变；该注册表不提供扩展沙箱或类加载后的文件完整性监控。

## 测试范围与后续

单元测试使用独立编译的 Java 11 JAR fixture 和最小测试 ABI，覆盖元数据/身份校验、XML 边界、
bytecode ceiling、单源/多语言 factory、64 位 ID、冲突、失败原子性、context ClassLoader 恢复与句柄生命周期。
执行器测试还覆盖 JSON 闭环、页索引、异步 resume、取消/卸载、partial details、memo、章节排序、
毫秒时间戳、页面上下文、受保护 imageRequest 的 headers 和结构化错误。测试 ABI/扩展不会进入发行包。
动态筛选测试还覆盖原生控制树/索引、包含排除、排序方向、文本前缀、状态恢复、取消等待与卸载重载隔离。
图片测试覆盖 JSON/context、真实 response 关闭、迟到回调/取消/卸载、blocked body 清理、completion
失败资源释放、逐块文件写入、并发发布、去重/损坏修复、限额/长度/格式失败和文件头类型识别。

本机另用 Claude 已保留的真实 JAR/兼容运行时做离线检查及四个扩展的加载实验，见
`docs/architecture/kmp-ios-plan-2026-10.md` §25。临时驱动、JAR、兼容层和结果日志均没有进入模块发行目录。

另以真实 Suwayomi API 编译了独立的离线 Rx 扩展，经过自己的 registry/runtime/JSON client 验证
默认 Rx 到 suspend 调用链及真实控制对象写回/恢复；没有访问站点。详细证据见计划 §26–27。
图片检查使用真实 getImage/client/interceptor 链，终端本地拦截器返回加密 fixture，解密后 PNG 字节
经自己的 runtime/protocol/store 完整保存；没有调用网络 proceed。证据见计划 §28。

下一步是偏好高级控件、相关推荐和完整图片格式/缓存保留管理；动态树的原生 UI 仍待接入。
CLI 提供 inspect/sources/serve，Windows 桌面平台/界面按计划 §29 推进；
内容执行入口供平台以库方式装配。JNI、OpenJDK Mobile 构建、WKWebView/Cookie/JS 和 iOS 应用仍待。
