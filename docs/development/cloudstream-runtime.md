# Cloudstream 运行时构建与接入

Kototoro 不应从 Cloudstream APK、pre-release APK 或其中的 `classes.jar` 提取插件运行时。当前运行时来自
`recloudstream/cloudstream` 的 Kotlin Multiplatform `:library:jvmJar`：

- 固定提交：`caf0db3dc13bd75a496d4a94152bb8f22f8fdb1e`
- Gradle 任务：`:library:jvmJar`
- 产物：`library-jvm-1.0.1.jar`
- SHA-256：`0a281a34bc4335f024b9bf0ac55ba341d7ae99f5b8918c692c669d7d0bed600e`

## 为什么使用 jvmJar

Cloudstream 插件依赖的是库的 Kotlin/JVM ABI，而不是 Cloudstream Android 应用壳。APK 的 `classes.jar` 混入 UI、
Service、Receiver、Data Binding、AndroidX、Coil、协程等实现。宿主再按包名逐项删除这些类，会产生两个问题：

1. 重复类和依赖版本冲突，例如 JVM `org.json` 覆盖 Android framework `org.json`。
2. 删除规则会意外破坏 Kotlin 二进制 ABI，例如默认参数生成的 `$default` 方法。问题只在插件运行到特定分支时出现，
   继续按异常穷举类或方法无法得到稳定结果。

`:library:jvmJar` 是 Cloudstream 自己定义的插件库边界。它是薄 JAR，不携带 Android 应用壳和第三方依赖，因而让
Kototoro 的依赖解析成为唯一运行时来源。

## 可复现构建

要求：JDK 17、Git，以及可用的 `../cloudstream` Git 对象库。脚本不会 checkout、reset 或修改 Cloudstream 工作区；
它通过 `git archive` 将指定提交导出到临时目录，因此源仓库有未提交修改也不会污染结果。

```bash
./scripts/build_cloudstream_runtime.sh
```

默认输出到：

```text
build/cloudstream-runtime/library-jvm-1.0.1.jar
```

可以通过环境变量覆盖输入和输出：

```bash
CLOUDSTREAM_SOURCE_REPO=/path/to/cloudstream \
CLOUDSTREAM_COMMIT=<commit> \
CLOUDSTREAM_OUTPUT_DIR=/tmp/cloudstream-runtime \
./scripts/build_cloudstream_runtime.sh
```

脚本会校验：

- 固定提交的 SHA-256；
- `BasePlugin`、`ExtractorApi`、`JsInterpreterKt` 等必要类；
- JAR 不含 AndroidX、Coil、协程副本以及 Cloudstream UI/Service/Receiver/Data Binding；
- `LoadResponse.Companion.addTrailer$default` 和
  `M3u8Helper.Companion.generateM3u8$default` 等关键 Kotlin ABI。

## 更新 Kototoro 运行时

先比较新产物，再明确替换仓库内 JAR：

```bash
cmp build/cloudstream-runtime/library-jvm-1.0.1.jar \
  app/libs/cloudstream3-library-jvm-1.0.1.jar
```

升级到新提交时，需要同步更新 `app/build.gradle` 中的文件名、来源提交注释和
`cloudstreamSourceSha256`。不要只替换 JAR，否则 `prepareCloudstreamRuntimeJar` 应当因哈希不一致而失败。

Kototoro 的 `prepareCloudstreamRuntimeJar` 仍有两项宿主职责：

1. 排除 `Youtube*.class`，使用 Kototoro 的 Android 版 YouTube 实现。
2. 验证最终 JAR 没有宿主依赖副本，并检查必要运行时类仍然存在。

纯 JVM 库不包含部分 Android 插件 ABI。Kototoro 在
`app/src/main/kotlin/com/lagradost/cloudstream3/` 下提供最小宿主兼容层，包括 `Plugin`、`VideoClickAction`、
`DataStore` 和 `TextUtil`。兼容层只实现插件实际需要的 Android 行为，不复制 Cloudstream 应用壳。

## R8 与验证清单

Cloudstream 插件由外部 JAR 动态加载，R8 无法从 Kototoro 的静态调用图推导完整成员集合。因此正式构建必须保留
`com.lagradost.cloudstream3.**` 的类和成员。仅保证编译通过不足以证明兼容性。

每次更新运行时至少执行：

```bash
./gradlew :app:prepareCloudstreamRuntimeJar
./gradlew :app:compileDebugKotlin --no-daemon
./gradlew :app:assembleNightly --no-daemon
```

随后检查 nightly mapping 中关键类和 `$default` 方法未被移除或改签名，并在真机覆盖以下路径：

1. 插件加载和首页搜索。
2. 详情页及 trailer 解析。
3. `loadLinks`、M3U8 提取和多请求头链接。
4. 实际播放、暂停及 HLS 分片持续读取。

Debug 正常而 nightly 失败时，应首先比较 R8 后的 ABI 和依赖解析结果，不要先假设是设备 ABI 或继续扩大排除列表。
