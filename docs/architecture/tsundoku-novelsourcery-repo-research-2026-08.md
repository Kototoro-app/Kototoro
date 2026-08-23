# Tsundoku / NovelSourcery 小说扩展生态调研（2026-08）

> 调研目标（T0.3）：定位真实的「Tsundoku / NovelSourcery 小说扩展」生态，确定扩展仓库、
> 常见扩展包、AndroidManifest 的 feature/metadata 键、extensions-lib 版本演进，为离线
> 1.4/1.6 fixture 提供可复现依据。
>
> 调研方式：GitHub REST API + raw.githubusercontent + Web 检索 + 真实 APK 离线分析
> （`aapt2 dump`，不运行任何 Gradle）。所有 APK 只下载到 `/tmp/`，未进入仓库。

## 1. TL;DR（结论摘要）

- **官方不提供扩展仓库**。Tsundoku 官方 FAQ 明确声明「Tsundoku does not have, associate,
  or provide any repositories or extensions」，扩展生态全部为第三方社区仓库。
- **小说扩展生态 = NovelSourcery**（GitHub org `NovelSourcery`）：
  - 编译产物仓库 `NovelSourcery/extensions`（含 `index.json` / `index.min.json` /
    `index.pb` / `apk/` / `jar/` + CDN 分发）；
  - 源码仓库 `NovelSourcery/extensions-source`（fork 自 keiyoushi 的 Mihon 扩展体系打
    造成「小说版」，用 `build.gradle` 的 `libVersion = '1.4' | '1.6'` 区分 ABI 标记）。
- **feature 键已被实测确认**：真实 APK 的 `uses-feature` 为 `tachiyomi.novelextension`，
  与 Kototoro 计划约定的完全一致。
- **metadata 键（真实 APK 实测）**：`tachiyomi.novelextension.class` / `.nsfw` / `.novel`，
  以及较新的 `tachiyomix.name` / `tachiyomix.contentWarning` / `tachiyomix.extensionLib`。
- **extensions-lib 版本**：宿主（tsundoku `ExtensionLoader`）只接受 `1.4` 与 `1.6`
  （`SUPPORTED_LIB_VERSIONS = listOf(1.4, 1.6)`）；NovelSourcery 索引 138 个扩展中
  **127 个 1.4、11 个 1.6**，全部 `isNovel=True`。
- **1.4 vs 1.6 正文表达**：
  - 1.4 时代（tsundoku v0.1.0 source-api）：`Source.isNovelSource` 属性（默认 false）+
    独立接口 `NovelSource { suspend fun fetchPageText(page): String }`；正文以
    `Page.text` 为承载（`ParsedNovelSource.pageListParse` 返回
    `listOf(Page(0).also { it.text = content })`）；宿主经跨 classloader 的反射助手
    `Source.fetchNovelPageText()` 调用。
  - 1.6（当前 main）：`fetchPageText(page)` 直接上移到 `Source` 接口（默认抛
    `UnsupportedOperationException`），`NovelSource` 降级为 deprecated 标记接口，
    宿主直接 `source.fetchPageText(page)` 调用；`Page.text` 仍保留作二进制兼容与
    缓存载体。新增 suspend `getMangaUpdate` / `getPopularManga` / `getLatestUpdates` /
    `getSearchManga`，`getMangaDetails` / `getChapterList` 标记 deprecated。
- **工程现实**：NovelSourcery 当前「1.4 标记」的 APK（如 bakatsuki）实际也携带现代
  source 面（DEX 内含 `fetchPageText` / `isNovelSource` / `getMangaUpdate`），因为
  `core/`（`KeiSource`）是统一的最新实现；1.4/1.6 差别主要是**声明式元数据 + 版本号**，
  真正的二进制 ABI 分水岭在 tsundoku 自身的历史 source-api（v0.1.0 → main）之间。

## 2. 生态仓库地址

| 仓库 | 说明 | 分支/URL |
|---|---|---|
| `NovelSourcery/extensions` | **小说扩展 APK 产物 + 索引**（用户实际添加的仓库） | <https://github.com/NovelSourcery/extensions> |
| `NovelSourcery/extensions-source` | 扩展源码（Fork keiyoushi/extensions 改造为小说版） | <https://github.com/NovelSourcery/extensions-source> |
| `tsundoku-otaku/tsundoku` | Tsundoku 主应用（宿主/阅读器） | <https://github.com/tsundoku-otaku/tsundoku> |
| `tsundoku-otaku/extensions-lib` | 宿主扩展桩 `extensions-lib`（含 `index/index.proto`，即索引 protobuf schema） | <https://github.com/tsundoku-otaku/extensions-lib> |
| `tsundoku-otaku/tsundoku-preview` | 宿主 preview 构建发布 | <https://github.com/tsundoku-otaku/tsundoku-preview> |
| `tsundoku-otaku/tsundoku-nightly` | 宿主 nightly 构建发布 | <https://github.com/tsundoku-otaku/tsundoku-nightly> |
| `adly98/tachiyomi-novel-extensions` | 2023 年小说 fork 前身（`lib/novelsource` 用 NovelToManga 把正文渲染成位图，走 manga 管线）——仅作历史参考 | <https://github.com/adly98/tachiyomi-novel-extensions> |
| `wasu-code/novel-compat-shosetsu` | Shosetsu / IReader 扩展在 Tsundoku 下的兼容层（旁支生态） | <https://github.com/wasu-code/novel-compat-shosetsu> |

关键事实：**tsundoku-otaku org 本身没有 `extensions` 仓库**（org 仅
`tsundoku` / `tsundoku-preview` / `tsundoku-nightly` / `extensions-lib` /
`tsundoku-otaku.github.io`）；小说扩展发行集中在 `NovelSourcery` org。

## 3. 推荐列表 URL（用户粘贴 / 程序抓取）

- **用户「加仓库」URL**（README 官方推荐，返回 legacy JSON 的 `index.min.json`）：
  `https://raw.githubusercontent.com/novelsourcery/extensions/repo/index.min.json`
- **新版 protobuf 索引**（`repo.json` 的 `index_v2`，供现代客户端）：
  `https://github.com/NovelSourcery/extensions/raw/repo/index.pb`
- **完整 JSON 索引**（`main` 分支，人类可读，与 proto 同构）：
  `https://raw.githubusercontent.com/NovelSourcery/extensions/main/index.json`
- **CDN 直链**（索引里 `resources.apkUrl` 的形态，`@repo` 表示 repo 分支）：
  `https://cdn.jsdelivr.net/gh/novelsourcery/extensions@repo/apk/<file>.apk`

注意：`repo` 分支上的 `index.min.json`（785 B）当前只有 2 条「诱饵」条目
（`Outdated App` / `Update to Mihon 0.3.1+`，指向 `eu.kanade.tachiyomi.extension.*`
legacy 包名），用于强制旧版本客户端升级——**它不是真实列表**。真实列表以
`index.json` / `index.pb` 为准。

### 索引格式（三套并存）

1. **新版 protobuf**（`index.pb` / `index.pb.gz`，约 8 KB）——schema 见
   `tsundoku-otaku/extensions-lib` 的 [`index/index.proto`](https://github.com/tsundoku-otaku/extensions-lib/blob/main/index/index.proto)：
   `Index{name, badgeLabel, signingKey, contact, oneof extensionList|extensionListUrl}`，
   `Extension{name, packageName, resources{apkUrl, iconUrl}, extensionLib, versionCode,
   versionName, contentWarning, sources[{id,name,language,homeUrl,mirrorUrls,message}]}`，
   `ContentWarning{0=UNSPECIFIED,1=SAFE,2=MIXED,3=NSFW}`（注：proto 与 Mihon 的
   `index.min.json` protobuf 字段大多兼容但**不完全相同**，需按本 schema 解析）。
2. **JSON 同构版**（`index.json`，127 KB，138 项）：`extensionList.extensions[]`，字段
   直接对应 proto；额外有 `resources.jarUrl`（扩展 jar 直链）与顶层 `isNovel: true`。
3. **legacy JSON**（`index.min.json`）：旧式 `{pkg, apk, lang, code, version, nsfw,
   sources[]}`，当前仅含诱饵条目。

`repo.json`（<https://raw.githubusercontent.com/NovelSourcery/extensions/main/repo.json>）：
签名密钥指纹 `4281820d4866bb71bed3dec5224aad9cf4633d44a113682cfb0c3b1cfd71702d`，
官网 <https://novelsourcery.github.io>。

## 4. 代表性扩展包（2026-08 索引快照，共 138 个）

版本/内容分布：`extensionLib` 1.4 ×127、1.6 ×11；`contentWarning` SAFE ×135、
NSFW ×3；语言 en 100 / ar 16 / tr 7 / id 6 / fr 3 / es 2 / all / ko / pt / th 各 1。

包名约定：**`eu.kanade.tachiyomi.novelextension.{lang}.{slug}`**（小说版把
`tachiyomi.extension` 段换成 `tachiyomi.novelextension`；Mihon 漫画为
`eu.kanade.tachiyomi.extension.*`）。

### 1.6（11 个，全部 en）

| packageName | versionName | 备注 |
|---|---|---|
| `eu.kanade.tachiyomi.novelextension.en.novelfull` | 1.6.11 | 多源 theme `readnovelfull`，已下载实测 |
| `eu.kanade.tachiyomi.novelextension.en.allnovel` | 1.6.12 | |
| `eu.kanade.tachiyomi.novelextension.en.allnovelfull` | 1.6.12 | |
| `eu.kanade.tachiyomi.novelextension.en.freewebnovel` | 1.6.14 | |
| `eu.kanade.tachiyomi.novelextension.en.libread` | 1.6.14 | |
| `eu.kanade.tachiyomi.novelextension.en.lightnovelplus` | 1.6.11 | |
| `eu.kanade.tachiyomi.novelextension.en.lightnovelworld` | 1.6.2 | |
| `eu.kanade.tachiyomi.novelextension.en.novelarrow` | 1.6.4 | |
| `eu.kanade.tachiyomi.novelextension.en.novelfire` | 1.6.10 | |
| `eu.kanade.tachiyomi.novelextension.en.novellive` | 1.6.10 | |
| `eu.kanade.tachiyomi.novelextension.en.readnovelfull` | 1.6.11 | |

### 1.4 代表（en / ar / tr 各取一部分）

| packageName | versionName |
|---|---|
| `eu.kanade.tachiyomi.novelextension.en.bakatsuki` | 1.4.2（**已下载实测**） |
| `eu.kanade.tachiyomi.novelextension.en.webnovel` | 1.4.3 |
| `eu.kanade.tachiyomi.novelextension.en.royalroad` | 1.4.3 |
| `eu.kanade.tachiyomi.novelextension.en.scribblehub` | 1.4.4 |
| `eu.kanade.tachiyomi.novelextension.en.wattpad` | 1.4.2 |
| `eu.kanade.tachiyomi.novelextension.en.foxaholic` | 1.4.4 |
| `eu.kanade.tachiyomi.novelextension.ar.arnovel` | 1.4.5 |
| `eu.kanade.tachiyomi.novelextension.ar.kolnovel` | 1.4.4 |
| `eu.kanade.tachiyomi.novelextension.tr.noveltr` | 1.4.3（tr 组） |

### NSFW（`contentWarning=CONTENT_WARNING_NSFW`，均为 1.4）

- `eu.kanade.tachiyomi.novelextension.en.foxaholic18` 1.4.4
- `eu.kanade.tachiyomi.novelextension.en.konkon` 1.4.4
- `eu.kanade.tachiyomi.novelextension.en.noveldex` 1.4.4

## 5. 真实 APK manifest 实测（aapt2 dump，/d1/android-sdk/build-tools/36.0.0/aapt2）

两支 APK 均从 `https://cdn.jsdelivr.net/gh/novelsourcery/extensions@repo/apk/…` 下载，
仅存于 `/tmp/`：

| 字段 | 1.4 fixture 候选（bakatsuki） | 1.6 fixture 候选（novelfull） |
|---|---|---|
| 文件 | `tsundoku-en.bakatsuki-v1.4.2-release.apk`（101,342 B） | `tsundoku-en.novelfull-v1.6.11-release.apk`（117,594 B） |
| package | `eu.kanade.tachiyomi.novelextension.en.bakatsuki` | `eu.kanade.tachiyomi.novelextension.en.novelfull` |
| versionCode / versionName | `2` / `1.4.2` | `11` / `1.6.11` |
| minSdk / targetSdk / compileSdk | 21 / 34 / 34 | 21 / 34 / 34 |
| uses-feature | **`tachiyomi.novelextension`** | **`tachiyomi.novelextension`** |
| application label | `Tsundoku: Baka-Tsuki` | `Tsundoku: NovelFull` |
| meta-data `tachiyomi.novelextension.class` | `.BakaTsuki`（相对类名） | `.NovelFull` |
| meta-data `tachiyomi.novelextension.nsfw` | `0`（int） | `0` |
| meta-data `tachiyomi.novelextension.novel` | `1`（int） | `1` |
| meta-data `tachiyomix.name` | `Baka-Tsuki`（string） | `NovelFull` |
| meta-data `tachiyomix.contentWarning` | `0`（int） | `0` |
| meta-data `tachiyomix.extensionLib` | `1.4`（**float**） | `1.6`（**float**） |

验证命令（可复现）：

```bash
AAPT2=/d1/android-sdk/build-tools/36.0.0/aapt2
$AAPT2 dump badging  /tmp/tsundoku-en.bakatsuki-v1.4.2-release.apk
$AAPT2 dump xmltree --file AndroidManifest.xml /tmp/tsundoku-en.novelfull-v1.6.11-release.apk | grep -A2 meta-data
```

DEX 观察（`unzip -p <apk> classes.dex | strings`）：两支 APK 都内联现代 source 面
`fetchPageText` / `isNovelSource` / `getMangaUpdate` + `KeiSource`（`keiyoushi/source/
KeiSource`），「1.4」与「1.6」主要是元数据标记（详见 §6.3）。1.4 甚至带上 `fetchPageText`，
所以它们能在只认 `<Source>.fetchPageText` 的现代宿主里直接跑（不会 AbstractMethodError）。

### 宿主侧契约（tsundoku `ExtensionLoader`，main 分支）

- feature：`tachiyomi.extension`（manga）/ `tachiyomi.novelextension`（novel）；
  `isNovelExtension = pkgInfo.reqFeatures.any { it.name == EXTENSION_FEATURE_NOVEL }`；
- 受支持 lib 版本：`SUPPORTED_LIB_VERSIONS = listOf(1.4, 1.6)`（不在其中直接 `LoadResult.Error`）；
- metadata：`tachiyomi.novelextension.class`（必填，缺失即错误）、
  `tachiyomi.novelextension.nsfw`、`tachiyomi.novelextension.novel`（未信任时判断用）、
  `tachiyomix.name`（显示名，缺失回退 app label 去 `Tsundoku:`/`Tachiyomi:` 前缀）、
  `tachiyomix.contentWarning`、`tachiyomix.extensionLib`（float，缺失时回退从
  `versionName` 里取 `substringBeforeLast('.')`）；
- 信任：按包签名指纹与信任库比对，不信任则 `Extension.Untrusted`（按 `$metaNs.novel`
  标记 isNovel）；NSFW 门控：`tachiyomix.contentWarning > 0 || $metaNs.nsfw == 1`。

## 6. extensions-lib 1.4 vs 1.6：source-api 关键差异

以 tsundoku 自身的 source-api 历史为据：1.4 时代 = `v0.1.0` tag（首个 release，
2026-03，KMP 布局 `source-api/src/commonMain/...`）；1.6 时代 = 当前 `main`。

### 6.1 1.4 时代（`v0.1.0`）

- `Source` 上已有 **`val isNovelSource: Boolean = false`**（KDoc 同旨：小说源要返回文本）；
- **`NovelSource` 是独立接口**，不在 `Source` 上：
  `interface NovelSource { suspend fun fetchPageText(page: Page): String }`；
- 宿主调用点**不是**接口直调，而是跨 classloader 反射助手：
  `suspend fun Source.fetchNovelPageText(page)` —— 先 `is NovelSource` 直转，失败再
  `getMethod("fetchPageText", Page::class, Continuation::class)` 反射 + suspendCoroutine；
- 正文承载：`ParsedNovelSource.pageListParse` 返回
  **`listOf(Page(0).also { it.text = content })`** —— 正文直接放进 **`Page.text`**；
- `Page.text` 为 **body property（非构造参数）+ `@Transient`**，保留对上游
  `Page(index, url, imageUrl, uri)` 4 参数构造的二进制兼容；
- 已迁移部分 suspend：`getMangaDetails` / `getChapterList` / `getPageList`；旧
  Observable `fetchMangaDetails` / `fetchChapterList` / `fetchPageList` 保留（deprecated）。
- 宿主 `HttpPageLoader`（v0.1.0）：`source.isNovelSource()` 判定 → 无图 URL 的页面走
  `fetchNovelPageText(page)` → 写回 `page.text`。

**结论（1.4 时代正文表达）**：`Page.text` 是**传输/缓存载体**（解析阶段直接塞入），
`fetchPageText` 是**获取机制**（分离的 `NovelSource` 接口，宿主经反射 helper 调用）。

### 6.2 1.6 时代（main）

- **`fetchPageText(page): String` 直接上移到 `Source` 接口**（默认抛
  `UnsupportedOperationException("Not a novel source")`，KDoc `@since extensions-lib 1.5`）；
- `isNovelSource` 仍是 `Source` 属性；`NovelSource` 降级为 **deprecated 标记接口**
  （仅兼容既存 `: HttpSource(), NovelSource` 声明）；
- `supportsLatest` 从 `CatalogueSource` 移到 `Source`（注释明言是为了不让预-1.6 的小说扩展
  编译产物 `AbstractMethodError`）；
- 新增 suspend 面：`getPopularManga` / `getLatestUpdates` / `getSearchManga` /
  `getMangaUpdate(manga, chapters, fetchDetails, fetchChapters)`（`@since tachiyomix 1.6`）；
- `getMangaDetails` / `getChapterList` deprecated（「in 1.6」，仅 `CatalogueSource`
  的 `getMangaUpdate` 默认实现调用）；fork-only 的
  `getChapterList(manga, RefreshContext)`（`@since extensions-lib 1.6 (tsundoku fork only)`）
  也在，负责向后兼容；
- `Page.text` 原样保留（仍是 bincompat body property）；
- 宿主 `HttpPageLoader`（main）：`source.isNovelSource()` → 无图 URL 页面**直接**
  `source.fetchPageText(page)`（不再反射）→ `page.text`，可选 `TextSplitter` 自动切页。

**结论（1.6 时代正文表达）**：`Page.text` 仍是最终载体；但获取从「分离接口 + 反射」
收敛为「`Source` 接口直接分发」，并且 `Page` 生命周期里 `text` 保持 @Transient、
宿主总是重新 `fetchPageText`（`HttpPageLoader` 注释：page-list 缓存里的 text 恒为 null）。

### 6.3 工程现实：NovelSourcery 的 1.4 / 1.6 是怎么来的

- 每个扩展源码目录 `src/<lang>/<slug>/build.gradle` 声明
  **`libVersion = '1.4'` 或 `'1.6'`**（另含 `extName`、`extClass`、`baseUrl`、
  `isNsfw`、`isNovel = true`、`themePkg`、`overrideVersionCode`）；
- 多源 theme `lib-multisrc/<theme>/build.gradle.kts` 里写
  `keiyoushi { baseVersionCode = N; libVersion = "1.4"|"1.6" }`；
- `common.gradle`：`versionName = "$libVersion.$versionCode"`、namespace 固定
  `eu.kanade.tachiyomi.novelextension`、`applicationIdSuffix = lang.slug`、minsdk 21；
- `compiler/` 模块 + CI（`ext-bootstrap.py` / `build_push.yml`）生成 manifest 占位符与
  索引。manifest 模板在 `core/src/main/AndroidManifest.xml`（`uses-feature`
  `tachiyomi.novelextension` + 上述 6 个 meta-data 占位符）。
- 因此**1.4 与 1.6 APK 都从同一份现代 core 编译**，二进制面一致（都含
  `fetchPageText`/`getMangaUpdate`）；差异是声明的 `tachiyomix.extensionLib` 与版本号。
  真·ABI 断代（分离 `NovelSource` + 反射 vs `Source.fetchPageText` 直调）发生在 tsundoku
  v0.1.0 → main 之间。这对 fixture 的策略影响：见 §7。

## 7. 离线 fixture 构建建议

> 目标：Kototoro 测试不依赖在线仓库，离线验证「feature/metadata 识别、ABI 白名单、
> 1.4 single / 1.4 factory / 1.6 suspend 加载路径」。**自建 tiny APK，不提交第三方 APK。**

### 7.1 Manifest 声明（对照真实 APK 实测值逐项命中）

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-feature android:name="tachiyomi.novelextension" />
    <application android:label="TSUNDOKU_FIXTURE">
        <meta-data android:name="tachiyomi.novelextension.class" android:value=".FixtureSource" />
        <meta-data android:name="tachiyomi.novelextension.nsfw"    android:value="0" />
        <meta-data android:name="tachiyomi.novelextension.novel"   android:value="1" />
        <meta-data android:name="tachiyomix.name"          android:value="Fixture" />
        <meta-data android:name="tachiyomix.contentWarning" android:value="0" />
        <meta-data android:name="tachiyomix.extensionLib"   android:value="1.4" /><!-- 或 1.6 -->
    </application>
</manifest>
```

- 关键点：**包名/namespace 前缀必须是 `eu.kanade.tachiyomi.novelextension`**（真实生态
  的 `.class` 是相对类名，宿主按 `pkgName + class` 解析；相对类名简化测试）；若用 FQCN
  如 `org.skepsun.kototoro.fixture.FixtureSource` 亦可，但会偏离真实生态形态；
- SDK：`minSdk 21`、`targetSdk 34`、`compileSdk 34`（与真实 APK 一致）；
- `versionCode` 递增整数、`versionName` 形如 `1.4.N` / `1.6.N`（宿主 fallback 解析 lib
  版本的路径也吃这个格式）；
- 签名：任意自签 key 即可（zip-aligned + v2 签名）；宿主的信任指纹校验会把它们判为
  Untrusted——单测里若经 `TrustExtension`，测试需预置指纹或断言
  `LoadResult.Untrusted(isNovel=true)` 分支（这也是一个值得覆盖的路径）。

### 7.2 二进制 ABI 面（决定 1.4 / 1.6 / manga 三档 fixture 的 Kotlin 源码形状）

- **1.6 fixture**：实现 `Source` 的现代面——`isNovelSource = true` +
  `override suspend fun fetchPageText(page: Page): String`；`getPageList` 返回
  `listOf(Page(0, url = "fixture://ch/1"))`；可再覆写 `getMangaUpdate` 验证新 API 直调；
- **1.4 fixture**：两种做法都「真实」——
  a）**历史形态**：实现分离的 `NovelSource { fetchPageText }`（v0.1.0 面），宿主若走反射
  助手则兼容、若当前 Kototoro 走接口直调则它会 AbstractMethodError——这正好用来做
  「1.4 二进制的错误路径」测试；
  b）**现实形态**：像 NovelSourcery 的 1.4 APK 一样，也实现现代 `Source.fetchPageText`
  但 `tachiyomix.extensionLib = 1.4`——验证「元数据 1.4 + 现代面」的组合被接受；
- **manga 对照 fixture**：`uses-feature tachiyomi.extension` + 同款 meta-data（用
  `tachiyomi.extension.class` 键），用于分类器回归（不误判小说）；双 feature 混合用例
  生成 `AMBIGUOUS`/拒绝路径（与计划 T2A.2 呼应）；
- **factory 形态**（1.4 factory）：`tachiyomi.novelextension.class` 指向实现
  `SourceFactory` 的类（`createSources(): List<Source>`），返回 2~3 个源验证多源隔离。

### 7.3 可复用素材（源码模板 + 索引 fixture）

- 真实源码模板：`extensions-source/core/src/main/kotlin/eu/kanade/tachiyomi/source/
  NovelSource.kt`（KeiSource 体系）与 `src/en/bakatsuki/`（单源简单样例）、
  `src/en/novelfull/`（themePkg 多源样例）；
- 索引 fixture：裁剪 `index.json` 前若干条（已存 `/tmp/ns-index.json` 供复现）；protobuf
  fixture 可用 `index.pb` + `extensions-lib` 的 `index.proto` 生成我们自己的最小序列
  （覆盖未知字段/相对 URL/多 mirrorUrls 等边界，呼应计划 T0.2）；
- 下载位点：`repo` 分支 `apk/` 目录（145 个 APK）+ jsdelivr CDN，测试内不引用。

### 7.4 区分「宿主读取正文」的最终形态（给实现的一点备忘）

按当前宿主（main）：`HttpPageLoader` 对小说源**只认 `Page.url` + `fetchPageText`**，
`Page.text` 只是运行时缓存/排序字段；`getPageList` 返回的页面里 text 会被忽略。Kototoro
侧 `TsundokuNovelRepository` 应把「列表（Page 元数据）」与「正文（fetchPageText）」两
步分开建模，并对 `fetchPageText` 抛错做源级隔离（计划 §6.4 §6.5）。

## 8. 法律 / 许可注意事项

- `tsundoku-otaku/*`（tsundoku、extensions-lib、preview、nightly）、
  `NovelSourcery/extensions-source`、`adly98/tachiyomi-novel-extensions` 均为
  **Apache-2.0**（已核对 LICENSE 首页）。
- `NovelSourcery/extensions`（编译产物仓库）**无 LICENSE 文件**（main 与 repo 分支均
  404）——该仓库装的是社区编译的 APK/索引/签名，**不视作可再分发素材**；只用它做
  生态情报与「下载后在只读环境验证」，**不把其中 APK 提交进 Kototoro 仓库**。
- fixture 一律**自建**（自己写 manifest + 极小 Kotlin 源 + 自签），不捆绑第三方站点
  解析器逻辑；测试 fixture 对真实站点的引用只保留 URL 常量、不落内容。
- 若将来需要把裁剪后的索引 protobuf 存入仓库：索引本身是事实性元数据（包名/版本/
  URL），Apache-2.0 生态内可引用；但**签名指纹/publicKey 属于仓库的信任锚**，提交前
  与实现讨论是否纳入（避免把第三方信任根固化进我们的仓库）。
- 商用站点可用性/条款（webnovel、wattpad、royalroad 等）不在本调研范围内；Kototoro
  不内置这些站点，仅按用户自选仓库方式接入。

## 9. 未确认项

- `extensions-lib` 版本号 1.4/1.6 与「KDoc 的 `@since extensions-lib 1.5`」对不上
  （v0.1.0 首个 release 已含 `isNovelSource`；KDoc 的 1.5 出处未在 tags/历史里验证）。
  标注：**未确认**——不影响 fixture（宿主只认 1.4/1.6 两个值）。
- NovelSourcery 1.6 分支的 CI 细节（`build_push.yml` 何时切 `libVersion=1.6`）未深读；
  「1.6 只用于 en 且集中在 multisrc theme」是本快照观察，未确认是否为固定规则。
- `tachiyomi.novelextension.factory` 或其它旧式 factory metadata 键在真实 APK 中
  **未出现**（NovelSourcery 用 `extClass` + `themePkg` 编译期展开，不做 manifest factory
  键）——是否仍被宿主支持：计划/T0.3 阶段需自行确认，本调研标注**未确认**。
- 抓取某指数的历史（2026-08-16 之后的增量发布行为）不做承诺，快照日期 2026-08-23。

## 10. 复现步骤（完整可复现清单）

```bash
# 1) 索引
curl -s --max-time 10 https://raw.githubusercontent.com/NovelSourcery/extensions/main/index.json -o /tmp/ns-index.json
curl -s --max-time 10 https://raw.githubusercontent.com/NovelSourcery/extensions/repo/index.min.json
curl -s --max-time 10 https://raw.githubusercontent.com/tsundoku-otaku/extensions-lib/main/index/index.proto
# 2) 下载真实 APK（仅 /tmp）
curl -sL --max-time 60 -o /tmp/tsundoku-en.bakatsuki-v1.4.2-release.apk \
  https://cdn.jsdelivr.net/gh/novelsourcery/extensions@repo/apk/tsundoku-en.bakatsuki-v1.4.2-release.apk
curl -sL --max-time 60 -o /tmp/tsundoku-en.novelfull-v1.6.11-release.apk \
  https://cdn.jsdelivr.net/gh/novelsourcery/extensions@repo/apk/tsundoku-en.novelfull-v1.6.11-release.apk
# 3) manifest 分析
/d1/android-sdk/build-tools/36.0.0/aapt2 dump badging /tmp/tsundoku-en.bakatsuki-v1.4.2-release.apk
/d1/android-sdk/build-tools/36.0.0/aapt2 dump xmltree --file AndroidManifest.xml /tmp/tsundoku-en.novelfull-v1.6.11-release.apk
# 4) 1.4 vs 1.6 source-api 对比
curl -s --max-time 10 https://raw.githubusercontent.com/tsundoku-otaku/tsundoku/v0.1.0/source-api/src/commonMain/kotlin/eu/kanade/tachiyomi/source/Source.kt
curl -s --max-time 10 https://raw.githubusercontent.com/tsundoku-otaku/tsundoku/main/source-api/src/main/kotlin/eu/kanade/tachiyomi/source/Source.kt
```

## 11. 主要 URL 索引

- 官方 FAQ（不提供扩展）：<https://tsundoku-otaku.github.io/docs/faq/browse/extensions>
- 扩展仓库（产物）：<https://github.com/NovelSourcery/extensions>
- 扩展仓库（源码）：<https://github.com/NovelSourcery/extensions-source>
- 索引 proto：<https://github.com/tsundoku-otaku/extensions-lib/blob/main/index/index.proto>
- 加仓库 URL：`https://raw.githubusercontent.com/novelsourcery/extensions/repo/index.min.json`
- 完整 JSON：<https://raw.githubusercontent.com/NovelSourcery/extensions/main/index.json>
- 1.6 novelfull APK 直链：<https://cdn.jsdelivr.net/gh/novelsourcery/extensions@repo/apk/tsundoku-en.novelfull-v1.6.11-release.apk>
- 宿主 loader：<https://github.com/tsundoku-otaku/tsundoku/blob/main/app/src/main/java/eu/kanade/tachiyomi/extension/util/ExtensionLoader.kt>
- 宿主正文读取：<https://github.com/tsundoku-otaku/tsundoku/blob/main/app/src/main/java/eu/kanade/tachiyomi/ui/reader/loader/HttpPageLoader.kt>
- 历史（1.4 前身）：<https://github.com/adly98/tachiyomi-novel-extensions>
- 计划文档：<https://github.com/tsundoku-otaku/tsundoku/blob/main/source-api/src/main/kotlin/eu/kanade/tachiyomi/source/Source.kt>
