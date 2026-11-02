# 源注册表冷启动耗时实测（2026-10，issue #574 后续）

面向 issue #574 新回复（"重启应用后来源列表仍要几分钟才加载"）的实测记录：把冷启动拆成可归因的阶段，
量化 `@Broken` 全 DEX 扫描的真实成本，并记录已落地的两项优化。数值来自一台 root 过的真机
（M332BF / Android 17 / debug 2.3.0，安装 40 个 Mihon + 15 个 Aniyomi + 1 个 Tsundoku + 2 个 IReader
扩展，1 个 JAR 插件：95 个内容源、989 个类）。

## 结论先行

1. **JAR 的 `@Broken` 全类扫描不是"分钟级"来源**。它线性于类数量，实测约 0.03–0.16 ms/类：
   989 类 25–141 ms，3 000 类 251 ms，10 766 类 1 531–1 707 ms。即使按上报者 1 356 源的 jar
   外推（同族 766 类/95 源 ≈ 8 类/源，1 356 源 ≈ 1.1 万类），量级也只有 ~2 s。
2. 真机上占大头的其实是**扩展 APK 生态扫描**：`getInstalledPackages` + `PackageArchiveInfo` +
   每扩展一个 classloader，约 20–25 ms/扩展，四个生态合计 1–1.3 s（并行后墙钟仍 ~1 s）。
   本地受管 APK（Drive 同步还原的场景）走 `LocalApkExtensionSupport.getLocalArchivePackageInfoOrNull`，
   每次按包名查都会重扫整个目录（O(包数 × APK 数)），是仍需上报者 logcat 才能定案的嫌疑点。
3. 已落地的两项优化把 JAR 部分从冷启动的关键路径上基本移除：**11 755 类 / 2 个 JAR 的场景下
   `GlobalExtensionManager.initialize` 从 1 645 ms 降到 51 ms（−97%）**。

## 计时日志（现已内置）

```
I/KototoroInit: Scanned <jar> classes=<n> scan_ms=<ms> broken=<k>
I/KototoroInit: Broken scan cache hit for <jar> broken=<k>
I/KototoroInit: Loaded <jar> architecture=<ARCH> sources=<n>
I/KototoroInit: JAR load finished jars=<n> plugins=<n> total_ms=<ms>
I/KototoroInit: GlobalExtensionManager.initialize done manga=… content=… jars=… total_ms=<ms>
I/ExtensionRuntime: <ecosystem> results=<n> load_ms=<ms> total_ms=<ms>
```

`load_ms` 是 `loadResults`（包扫描 + APK 解析 + classloader）耗时，`total_ms - load_ms` 是
`processResults`（源包装、可信签名校验）耗时 —— 两者混在一起时无法判断瓶颈，这是本次新增的关键信息。

## 实测数据

### 真机单 JAR（989 类，95 源）

| 场景 | scan | JAR load | `initialize` 总耗时 |
| --- | --- | --- | --- |
| 缓存未命中（首次/升级后） | 62–97 ms | 113–134 ms | 121–149 ms |
| 缓存命中（后续冷启动） | 0 | 14–19 ms | 27–31 ms |

`am start -W` 的 `TotalTime` 在缓存命中时 1 447–1 626 ms，第一个带来源的 `browse_state` 出现在进程启动后约 1.3 s。

### 扫描成本随类数线性增长（合成 JAR，同一进程内首扫）

| 类数 | scan_ms |
| --- | --- |
| 989 | 25–141 |
| 3 000 | 251 |
| 10 766 | 1 531–1 707 |

合成 JAR 用 `d8` 生成（每类 3 个方法 + `@Deprecated`），真实解析器类更"重"，因此这是偏乐观的估计；
但同族真实 JAR（766 类/95 源）与 989 类的量级一致，说明线性外推是安全的。

### 扩展 APK 扫描才是当前真机启动主项

| 生态 | 扩展数 | `load_ms` | `processResults` |
| --- | --- | --- | --- |
| IReader | 2 | 158–284 ms | 1 ms |
| Tsundoku | 1 | 792–1 163 ms | ~30 ms |
| Aniyomi | 15 | 945–1 230 ms | 2–12 ms |
| Mihon | 40 | 1 087–1 347 ms | 1–60 ms |

四者并行，墙钟约 1 s；其中 Tsundoku 只有 1 个扩展却要 ~1.1 s，说明成本不在"扩展数量"，而在
`getInstalledPackages` / `PackageArchiveInfo` / `Injekt` 初始化这类固定开销或多个生态重复扫描同一批 APK。

## 已落地的两项优化

1. **扫描前按包去重 JAR**（`JarExtensionLoader.loadFromDirectory`）：同包新旧两个 JAR 的类与源完全相同，
   旧的那个在加载后才被 `dropDuplicatePackages` 丢掉，等于白扫一整个 DEX。现在扫描前就用
   `deduplicateJarPackages { it.name }` 过滤，比较器与被删除逻辑完全一致，胜者不变。
   设备验证：放入 `org.skepsun.kototoro.parsers.v134.jar` 后日志出现
   `Skipped duplicate JAR package before scan: org.skepsun.kototoro.parsers.jar`，`jars=1`。
2. **`@Broken` 扫描结果持久化**（`core/extensions/JarBrokenSourceCache.kt`）：以
   `v1:<文件名>|<字节数>|<lastModified>` 为键存到 `jar_broken_sources` 偏好，JAR 未变就直接复用；
   空结果同样是有效命中；`retainOnly` 清理已删除 JAR 的条目；DEX 解析失败不写缓存以便下次重试；
   用 `commit` 而不是 `apply`，避免进程被强杀时丢失刚写入的扫描结果（实测 `apply` 会丢）。

效果：11 755 类 / 2 个 JAR 时 `initialize` 1 645 ms → 51 ms；单 JAR 121–149 ms → 27–31 ms。

## 仍需上报者 logcat 才能定案的部分

- 若其 1 356 源 jar 的类数量级与上表一致，JAR 侧最多贡献 2–8 s，解释不了"几分钟"。
- 剩余嫌疑集中在 APK/本地档案路径（`getLocalArchivePackageInfoOrNull` 的 O(包数 × APK 数) 重扫、
  `InstalledExtensionSignatureValidator` 的每包回退）与启动期 Drive/网络校验。
- 需要 `KototoroInit` / `ExtensionRuntime` 两个 TAG 的 logcat（现在是决定性证据：哪一段的 `total_ms`
  吃掉了几分钟，一眼可见），以及扩展/本地 APK 数量。

## 受管 APK 目录的按包查询（O(包 × APK) → O(1)）

`LocalApkExtensionSupport.getLocalArchivePackageInfoOrNull` 原本每次调用都会走一遍受管目录并对**每个** APK
执行 `PackageManager.getPackageArchiveInfo`。而调用方是按"行/包"问的：

- 扩展列表与引导向导的 `isAlreadyInstalled`（每个仓库扩展一行，Mihon 仓库上千行）；
- `InstalledExtensionSignatureValidator.getManagedPackageInfo`（每个包 × 4 个生态）。

于是成本变成 O(行数 × APK 数)。真机实测 40 KB 扩展 APK 的解析成本约 **4.2 ms/个**（60 个副本一次遍历
243–264 ms），60 个受管 APK × 300 行 ≈ 18 000 次解析 ≈ 75 s，与"列表要几分钟"的量级吻合。

修复后：

- 常见布局（`<包名>.apk`、`<包名>/<包名>.apk`）直接按候选路径解析 **1 个** APK；
- 其他布局回落到按目录的索引，索引以 `路径 + 大小 + mtime` 指纹失效，目录每次变化只解析一次。

启动阶段不受影响（启动路径本来就只走一遍目录）：带 60 个受管 APK 时 `mihon load_ms` 修复前 1 199–1 200 ms，
修复后 1 179–1 221 ms；日志新增 `Indexed <eco> managed APK archives apks=… parsed=… ms=…` 用于确认只建一次。
单元测试 `LocalArchivePackageIndexTest` 直接计数解析次数：60 个 APK / 300 行 → 60 次解析（旧实现为 18 000 次）。

## 1452 源规模的 browse_state / catalog 重建是否超线性（合成 JAR 实测）

上报者的规模是 1 452 个 JAR 源。为了验证"重启后来源列表要几分钟"是否来自
`BrowseSourcesTrace`/`UnifiedSourceCatalog` 的按源重建，在真机上把注册表放大到同一量级再测。

**合成插件**（可复用的放大方法，产物 25 KB，2 个类）：

```java
// bench/BenchSource.java —— 1400 个常量实现 ContentSource（name() 即源名）
public enum BenchSource implements ContentSource {
    S0000, S0001, /* ... */ S1399;
    public String getName() { return name(); }
    public String getLocale() { return "en"; }
    public ContentType getContentType() { return ContentType.MANGA; }
}
// org/skepsun/kototoro/parsers/ContentParserFactoryKt.java —— 探针只读枚举参数类型
public static Object newParser(BenchSource source, ContentLoaderContext context) { return null; }
```

编译用 `parser-api/build/classes/kotlin/android/main` + `android.jar`，`d8 --min-api 26` 生成
`classes.dex` 后打成一个 JAR，放进 `files/plugins/` 即可（`Loaded … architecture=KOTOTORO sources=1400`）。
源行由 `assimilateNewSources` 首次插入（`new=1400 durationMs=259`），再把 `sources.enabled` 置 1
即复现上报者"1 400+ 源全部启用"的状态。

**冷启动（bench jar + 95 源真实 jar = registry 1 495 源，browse 1 493 项）**

| 事件 | 时刻 | 相对进程启动 |
| --- | --- | --- |
| `Start proc` | 12:54:59.701 | 0 |
| `JAR load finished jars=2 plugins=2 total_ms=33`（缓存命中） | 55:01.825 | 2.12 s |
| `initialize done … content=1495 total_ms=147` | 55:01.934 | 2.23 s |
| `Displayed … +2s413ms` | 55:02.072 | 2.37 s |
| `core_snapshot sources=1493` | 55:02.493 | 2.79 s |
| `browse_state emitted size=1493` | 55:02.600 | **2.90 s** |

browse 列表从 `core_snapshot` 到 `browse_state` 只花 **约 110–130 ms**（1 493 项，含类型统计）。

**catalog 重建（`UnifiedSourceCatalog.buildSourceItems`，新增 `ms=` 日志）**

| 可用源 | 首次构建 | 稳定值 | 每源成本 |
| --- | --- | --- | --- |
| 297（仅真实 jar + APK 扩展） | 387 ms（扩展仍在加载） | **126 ms** | 0.42 ms |
| 1 697（bench jar 全量） | **467 ms** | — | 0.28 ms |

源数 ×5.7，耗时 ×3.7 —— 含固定开销的**线性**，没有 O(n²) 的按源重扫。

**结论**：1 452 源规模下 registry/catalog/browse 重建是线性的，冷启动到 `browse_state` 约 2.9 s，
解释不了"几分钟"。放大列表真正付出的代价在 Compose 渲染侧：浏览页一次性铺 1 493 个源时出现
`Skipped 32/44 frames` 与单帧 `Davey! duration=794–1181 ms` 的卡顿（秒级，不是分钟级）。
因此上报者的"几分钟"仍应归结为修复前的未缓存 `@Broken` 全 DEX 扫描（含同包旧 JAR 被白扫，两处均已修）
或 APK/本地档案路径；`KototoroInit` + `UnifiedSourceCatalog` 的 logcat 是最短定位路径。
