# Issue #682 排查：本地导入 zip 内文件夹名乱码

- 上游 issue：<https://github.com/Kototoro-app/Kototoro/issues/682>
- 排查基线：`origin/devel` = `794b2d9`（含宿主 shim `1b19187` 之前的产品代码）
- 排查结论：**根因已确认，且当前基线已包含上游针对 #682 的修复，无需再改产品代码**；本文只记录结论与"如果回到修复前应该怎么改"。

## 1. 结论

压缩包里的 `01 春天`、`02夏天` 这些 **文件夹名本身是按 GBK 存的，且没有置 ZIP 的"名字是 UTF-8"标志（general purpose bit 11 / EFS flag）**。Kototoro 侧用来列 zip 的读取器（`java.util.zip.ZipFile`、`ZipInputStream`、okio 的 `openZip`）在无标志时**并不按 ZIP 规范回退 IBM437**，而是统一按 UTF-8 解码，于是 GBK 字节被解成 `01 ����` 这类替换字符。

所以这不是"UTF-8 名字被按 CP437/GBK 解码"，方向正好相反：**是 GBK 字节被按 UTF-8 解码**。

本地导入没有"解压"环节。zip 原样复制成一个本地作品，乱码出现在 `LocalMangaParser` 为"以 zip 根为容器的本地作品"生成**章节标题**时——章节就是 zip 里的一级文件夹，标题直接取了 `ZipEntry` 的名字（`LocalMangaParser.kt:380`）。修复只应改**展示标题**；章节 id、相对路径、zip URI fragment 必须继续使用压缩包自己的（乱码）拼写，否则按 entry 名查找页面会失败。

## 2. 导入链路（数据从哪来、在哪里显示）

1. `app/src/main/kotlin/org/skepsun/kototoro/local/data/importer/SingleContentImporter.kt:90-93`
   用户选中 zip 后，`publishFile` 把文件**原样复制**到本地库根目录，不解压；随后
   `SingleContentImporter.kt:264-269` 立即用 `LocalContentParser` 解析一次（`withDetails = false`）。
2. 打开详情时 `app/src/main/kotlin/org/skepsun/kototoro/local/data/LocalMangaRepository.kt:184`
   重新 `LocalContentParser(manga.url.toUri()).getContent(withDetails = true)`。
3. `app/src/main/kotlin/org/skepsun/kototoro/local/data/input/LocalMangaParser.kt:1255-1262`
   （纯文件）或 `:1266-1288`（`file://`/`content://`）用 okio `FileSystem.SYSTEM.openZip(...)` 打开压缩包。
4. `LocalMangaParser.kt:350-354` 用 `fileSystem.listRecursively(rootPath)` 列条目；okio 给出的名字已经是
   UTF-8 解码后的 `01 ����/001.jpg`，图片条目的父目录（`path.parent`）成为"章节目录"。
5. `LocalMangaParser.kt:380-381` 生成 `ContentChapter.title`：
   `entryNames.value.display(p.name).userFriendlyName()`。**这里是显示名唯一的修复点。**
6. 同名路径仍以压缩包拼写保留：`LocalMangaParser.kt:379`（id）、`:386`（章节 url）、`:552-565`（页面 entry），
   以及 `:1000` / `:1231` 的 zip URI 拼接，全部继续使用乱码拼写，只用于查找，不用于展示。

## 3. 根因细节

- ZIP 规范（APPNOTE 4.4.4）规定：general purpose bit 11 置位时 entry 名是 UTF-8；不置位时规范的建议是
  IBM437，但**实际由写入方决定**。中文 Windows 下的打包工具常写 GBK/GB18030 且不置位。
- Android 的 `java.util.zip.ZipFile`、`ZipInputStream` 默认字符集是 UTF-8（`ZipFile(File, int, Charset)` 才可指定），
  对无标志条目也按 UTF-8 解，且对非法字节是宽松的（坏字节 → U+FFFD），okio 的 `openZip` 同样按 UTF-8 解 entry 名。
  因此 `01 春天` 的 GBK 字节 `01 0xB4 0xBA 0xCC 0xEC` 显示成 `01 ����`。
  （本机用 JDK 17 复现同一归档时，桌面 `ZipFile`/`ZipInputStream` 反而更严格，直接抛
  `invalid CEN header` / `malformed input`；Android 运行时不会抛，所以用户看到的是乱码而不是"打不开"。
  这也意味着修复不能依赖 `java.util.zip` 的默认解码，必须读原始字节。）
- 无 EFS 标志只是"压缩包没有声明字符集"，不是错误本身；错误是**读取方用单一 UTF-8 猜所有压缩包**。
- 这个标志是逐 entry 的，不能只看第一个 entry 或压缩包扩展名；需要读中央目录里的原始字节来判断。
- 若名字确实是 UTF-8 但没置位，现有读取器已经能正确显示，不应再"修"（否则会二次损坏）。现有测试覆盖了该情形。

## 4. 受影响文件:行号

| 文件:行 | 角色 |
| --- | --- |
| `app/src/main/kotlin/org/skepsun/kototoro/local/data/importer/SingleContentImporter.kt:90-93` | 导入 zip：原样复制，无解压步骤 |
| `app/src/main/kotlin/org/skepsun/kototoro/local/data/input/LocalMangaParser.kt:350-354` | 列条目：okio 以 UTF-8 解出乱码路径，文件夹成为章节 |
| `app/src/main/kotlin/org/skepsun/kototoro/local/data/input/LocalMangaParser.kt:380-381` | **显示名修复点**：标题改用 `display()` 后的名字 |
| `app/src/main/kotlin/org/skepsun/kototoro/local/data/input/LocalMangaParser.kt:346,1065-1069` | 原始名来源：普通文件 `ZipEntryNames.read(file)`；SAF 用 `archive.entryNames` |
| `app/src/main/kotlin/org/skepsun/kototoro/local/data/input/LocalMangaParser.kt:1255-1262,1266-1288` | 用 okio `openZip` 打开 zip（UTF-8 解码的来源之一） |
| `app/src/main/kotlin/org/skepsun/kototoro/local/data/input/LocalMangaParser.kt:810,856` | 另外两处 `ZipInputStream`（封面/ComicInfo），只用于 entry 查找 |
| `app/src/main/kotlin/org/skepsun/kototoro/core/util/ContentZipArchive.kt:49-52` | SAF 随机访问时复用同一 channel 读原始 entry 名 |
| `app/src/main/kotlin/org/skepsun/kototoro/core/util/zip/ZipEntryNames.kt:56` | 候选字符集：UTF-8、GB18030、Shift_JIS、IBM437 |
| `app/src/main/kotlin/org/skepsun/kototoro/core/util/zip/ZipEntryNames.kt:62-69` | `read(File)` / `read(SeekableByteChannel)` 入口 |
| `app/src/main/kotlin/org/skepsun/kototoro/core/util/zip/ZipEntryNames.kt:71-100` | 读中央目录 `ZipArchiveEntry.rawName`，建立"乱码名 → 正确名"映射 |
| `app/src/main/kotlin/org/skepsun/kototoro/core/util/zip/ZipEntryNames.kt:107-127` | 字符集判定：严格解码；CJK 候选必须真的产出 CJK 才接受 |
| `app/src/main/kotlin/org/skepsun/kototoro/core/util/zip/ZipEntryNames.kt:130-164` | 按 `/` 分量拆分/映射，保证文件夹名单独修复 |
| `app/src/main/kotlin/org/skepsun/kototoro/core/util/zip/ZipEntryNames.kt:175-192` | `NonClosingChannel`：SAF fd 的生命周期仍归 `ContentZipArchive` |
| `app/src/test/kotlin/org/skepsun/kototoro/core/util/zip/ZipEntryNamesTest.kt:18-82` | 8 个回归用例（GBK 无标志、往返、ASCII、UTF-8 有/无标志、CP437、目录、非 zip） |

## 5. 建议的最小修法（只给方案，不实施）

修复前基线的最小改法就是当前基线里的这套实现（上游 commit
[`1c7d0313d`](https://github.com/Kototoro-app/Kototoro/commit/1c7d0313d30e3bfd0563d87ae822e7a17e441e4e)，
"fix(local): read the entry names an imported archive actually stores"）：

1. 新增 `core/util/zip/ZipEntryNames.kt`：用 commons-compress 读一次中央目录，取每个 entry 的 `rawName` 原始字节；
   按 `/` 拆成路径分量，只保留含非 ASCII 的分量；按 UTF-8 → GB18030 → Shift_JIS → IBM437 顺序**严格**解码，
   非 UTF-8/非规范默认的候选必须解出 CJK 字符才接受（避免把 `Café` 一类 Latin-1 名字误判成中文）。
   建立 `archiveName（UTF-8 有损解码）→ displayName` 映射；纯 UTF-8 或全 ASCII 的普通压缩包返回 `EMPTY`，零开销。
   不直接把 `ZipFile`/`ZipInputStream` 的字符集参数设成 GBK：归档之间可能是 GBK、Shift_JIS 或规范默认的 IBM437，
   无法从扩展名或单个 entry 得知，而且 okio 的读取路径不暴露字符集；必须先读原始字节再判定。
2. 只在 `LocalMangaParser` 的 `ContentChapter.title` 处应用 `display()`（`:380`）；
   `id`、`relativePath`、`url`/fragment、页面 entry 查找**保持压缩包拼写**。这是"不改写压缩包、不破坏读取"的关键。
3. SAF 路径复用同一次打开：`ContentZipArchive` 暴露 `entryNames`（`:49-52`），
   底层用 `NonClosingChannel` 包装 fd，读取器关闭时不关掉归档持有者。
4. 用 `ZipEntryNamesTest` 锁定字符集探测的 8 种情形（尤其"无标志的 UTF-8 不修"和"Latin-1 不误判"）。

**当前基线的状态**：以上 1-4 已全部存在（`ZipEntryNames.kt` 全文、`LocalMangaParser.kt:346/380/1065` 的接线、
`ContentZipArchive.kt:49`、`ZipEntryNamesTest.kt`），且与上游 `1c7d0313d` 的实现一致。因此本 ticket 的结论是
**已修复，不建议再改产品代码**，也不要再加第二套解码/重命名逻辑。

可选、但属于本 issue 之外的后续（需要产品决策，本次不做）：

- `ZipEntryNames.kt:73-76`：只要任一 entry 置了 EFS 就整体跳过修复。混合标志的异常压缩包仍会漏修，
  若要覆盖需按 entry 而不是按归档判断。
- 修复前生成的 `index.json` 可能缓存了旧标题。zip 作为作品根时不生成 index（`shouldGenerateIndex` 只看目录），
  所以 #682 的场景不受影响；目录型作品如遇到旧缓存需要重建索引，不应在本 ticket 顺手改。

## 6. 验证

- 单元测试：
  `JAVA_HOME=/usr/lib/jvm/temurin-17 ./gradlew :app:testDebugUnitTest --tests "org.skepsun.kototoro.core.util.zip.ZipEntryNamesTest" -PnoNative -Pandroid.aapt2FromMavenOverride=/root/tools/aapt2 --max-workers=2 --no-daemon --console=plain`
  → `BUILD SUCCESSFUL`；`TEST-org.skepsun.kototoro.core.util.zip.ZipEntryNamesTest.xml`：
  `tests=8, failures=0, errors=0, skipped=0`。
- 额外做了一个最小复现实验（不写入仓库）：用 GBK 字节、EFS=0 的中央目录构造归档后，
  JDK 17 的 `java.util.zip.ZipFile` / `ZipInputStream` 在桌面直接抛 `invalid CEN header` / `malformed input`；
  commons-compress 读到的 `rawName` 仍是原始字节 `30 31 20 B4 BA CC EC 2F 30 30 31 2E 6A 70 67`。
  这既证实了"乱码来自读取方按 UTF-8 解 GBK 名"，也说明修复必须读原始字节，现有 `ZipEntryNames` 的取数方式成立。
- 未做设备端导入复测（本 ticket 只排查、且基线已有修复）；如需真机确认，应使用 issue 提到的原始压缩包，
  导入后看章节标题是否显示为 `01 春天`，并确认点开章节能正常出图（验证"标题改名但 entry 查找仍用原名"）。
