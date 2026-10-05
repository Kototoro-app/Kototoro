 Windows desktop application

Windows/JVM 的 Compose Desktop 应用入口，复用 `desktop-runtime` 的共享 Room v84 数据库、
`core-source` JSON 契约和 `source-host` Mihon 执行器。提供扩展仓库、JAR 安装/更新/导入、来源浏览/搜索、详情、
收藏、章节图片阅读、历史、图书馆备份及标准源设置，以及可选的网页/HTML/JavaScript 调试面板。

## 平板布局与阅读界面

主导航直接使用 `core-ui` 中从 Android 提取的平板导航栏，默认 80dp，提供收藏、浏览、历史和更多；下载、扩展与仓库、
备份和网页调试集中在“更多”。来源列表只在浏览页显示，导入本地扩展后回到浏览。书库封面采用
2:3 竖版网格；窗口达到 1000dp 时，详情将作品信息和可搜索、排序的章节列表分成两栏，
较窄窗口使用横向封面与操作区，章节随页面滚动。导航图标直接由 Android 现有 vector 资源生成
SVG，构建遇到不支持的变换会失败；没有引入另一套图标依赖。

阅读器独占内容窗口，隐藏主导航和来源栏。顶部返回、章节标题和选项按钮、底部进度滑条、
书签与自动翻页浮动按钮以及右侧缩放控件直接复用 `core-ui` 的 Android 组件，悬浮在完整阅读视口上。
页码滑条松手后跳转；点击标题打开章节侧面板，提供搜索、倒序和当前章节标记。
点击阅读区中央或按 H 显示/隐藏控制栏，隐藏时显示章节与页码信息，阅读视口尺寸保持不变。
选项按钮打开共享 Android 阅读设置面板，齿轮进入更多阅读设置。F11/“排版”页全屏开关控制实际桌面窗口，退出全屏
恢复此前的浮动/最大化状态，返回详情也退出全屏。章节搜索拥有编辑键，不会在输入 H 或方向键
时翻页。面板开关不替换阅读场景，复用原 Controller、reader-core、缓存和历史。

小说“阅读设置”也使用共享面板：主题色卡、字号快捷调整、排版卡片和标签页来自 `core-ui`，
支持字号、行距、衬线字体、版心宽度及实际窗口全屏。设置沿用已有偏好键，重启恢复；
Esc 或遮罩关闭面板并回到正文。Windows 当前按段落连续滚动，Android 的亮度、分页动画、
双页、翻译和 TTS 仍由 Android 宿主处理。
小说上下栏同样使用共享组件：返回、章节标题、选项、进度底座、上一章/下一章、渐变与显隐动画。
正文点击或 H 切换控件，隐藏时的章节/段落状态可点击恢复控件；显隐保持正文视口与滚动锚点。
滑条按段落定位并保存进度，控制栏颜色跟随阅读主题；Android 仍按原阅读引擎的页码定位。
小说章节目录也复用共享面板、搜索框、分卷/分组标题和章节行，可反转顺序、定位当前章节。
定位会清除筛选；选择当前章直接回到原正文位置，选择其他章沿用原章节加载和历史记录。
小说右下书签按钮或 B 保存/移除当前段落，长按按钮打开书签列表，可按正文摘要或章节标题搜索，
跳回同章或跨章位置，并单独删除书签。卡片、删除图标及搜索框来自共享 UI；正文摘要和定位沿用
共享书签表，重启保留。Android 和 Windows 共用摘要解析与正文匹配，历史 HTML/base64 摘要也可搜索和定位。
分页或段落划分变化后优先寻找原正文；正文已变化时提示无法定位，重复摘要按章节进度选择近似位置。
没有文本摘要的旧书签仍按原页/段落索引恢复，无法保证跨阅读引擎精确对应。

收藏与历史提供标题/别名/作者搜索和右侧筛选抽屉，可按收藏分类、多个来源、未读/阅读中/已读完
组合筛选，并按默认顺序、标题、最近阅读或进度排序。抽屉带滚动条，Esc 或点击遮罩关闭；空结果
可一键清除条件。两页的条件在当前运行期间分别保留，输入只筛选共享数据库的快照，不重新请求
来源列表；可见封面仍走原图片请求与缓存。

阅读器右下书签按钮或 B 保存当前页；长按书签按钮打开书签面板，可跨章节跳回保存的位置。连续模式保存
整数像素滚动偏移，重启后仍可恢复；单/双页模式通过原阅读布局定位。沿用共享书签表和备份格式，
没有新增 schema。书签面板当前提供章节、页码及进度，不含 Android 的缩略图与批量管理。

## 共享 Android 平板 UI

以用户提供的最新平板截图和 Android 设计令牌为参考，主界面采用柔和的色调
画布、蓝色强调、胶囊搜索框/按钮和心形收藏图标；书架标题与搜索操作在同一行。
阅读器保持独立深色画布，顶部/底部控制组使用圆角浮动容器。更多页可切换跟随系统、浅色、深色，
外观沿用原偏好 store 保存，重启恢复；来源设置与阅读器设置不受影响。

1000dp 及以上窗口，从浏览、收藏或历史打开作品时，在原列表上显示 380dp 右侧预览，提供阅读、
收藏、章节和完整详情入口。窄窗口及“完整详情”使用原自适应详情组件；关闭、Esc 和宽屏遮罩
返回原列表，保留搜索草稿、筛选和网格滚动位置。阅读后返回详情仍保留来源页面上下文。
详情柔化背景只绘制作品封面，复用原请求与磁盘缓存；图片缺失或失败采用主题色画布，不阻碍阅读。

`core-ui` 是 Android/JVM 的 Compose Multiplatform 模块。Android 与 Windows 共同消费原 Android
平板导航、完整预览卡片内容、详情双栏布局、600/1000dp 窗口规则、预览宽度、界面尺寸令牌及字体/圆角映射。
预览包含模糊封面头部、标签/评分、阅读/详情/收藏操作、可展开简介和主分支最新/最初章节；短窗口让头部
随正文滚动，避免操作区被挤出。Android 的资源/Coil/HTML/本地化/玻璃表面和 Windows 的缓存/模型/窗口操作
通过适配层接入，不在共享组件里获取 Context、Hilt、文件或业务仓库。

共享组件使用 Material3，桌面尚未迁移的屏幕通过同一主题角色的 Material 2 桥接继续运行。
颜色与主题偏好仍由平台解析。作品封面的边框、书脊、标题渐变，以及内容源网格卡片和尺寸规则
已进一步从 Android 提取到 `core-ui`，两端调用同一实现。Android 保留图片缓存、共享转场、TV 焦点、
徽标和进度适配；桌面提供自己的图片、数据和动作。最新视觉参考为用户 2026-10-04 提供的五张平板截图。

Backdrop 已恢复为固定 2.0.0 的 Android/JVM KMP 模块，保留两处本地性能修补。玻璃绘制、调参模型和
镜片参数保护也进入 `core-ui`；Windows 可在“更多 → 界面风格”选择 iOS 玻璃，搜索控件调用同一渲染器。
默认保持 Material 3，主题与界面风格选择会持久化。桌面只捕获独立画布，完整动态作品背景仍待接入。
主页/订阅整页、详情内层内容、完整书库徽标/进度及小说阅读器正文
仍有桌面实现，后续按组件继续提取。书库筛选尚未覆盖 Android 的标签、
追踪状态和下载条件；“已读完”按当前分支的共享历史进度判断，不表示所有分支均已阅读。

## 扩展仓库

“更多 → 扩展与仓库”可输入完整 `index.pb`、新版 `index.json`、旧版 `index.min.json` 或仓库根地址，点击“添加 / 读取仓库”。
根地址依次请求 `index.pb`、`index.json`、`index.min.json`，仅在 HTTP 404 时回退；带查询参数时需提供完整索引地址。
旧版数组（Aniyomi、早期 Keiyoushi/Tsundoku 仓库）没有仓库头，名称取自地址的 owner/仓库名，条目只有 APK。
支持 gzip、相对扩展列表地址和相对 JAR 地址，JSON 的 64 位编号与版本保持整数精度。
仓库列表保存到应用偏好，点击仓库名称刷新，再按扩展名称、来源名称或语言搜索并安装/更新。

有 `resources.jarUrl` 时下载 JVM JAR；只发布 APK 的条目下载 APK 后在本机由 `dex-convert` 转成 JVM JAR
（同时修复 R8 内联构造器留下的 JVM 校验问题），按钮显示“安装（转换 APK）”。清单决定生态：
`tachiyomi.extension`（Mihon 漫画）、`tachiyomi.novelextension`（Tsundoku 小说）、`tachiyomi.animeextension`
（Aniyomi 动漫）；同时声明多类的扩展被拒绝。kototoro-parsers / kotatsu-parsers / UMA 插件 JAR 由“导入扩展 JAR”
接入（DEX-only 插件同样先转换）。

新下载与手动导入的 JAR 复制到数据目录 `extensions/<SHA-256>.jar`，以后可移动原导入文件；
此前版本保存的外部文件引用仍需保留。下载检查包名、版本、API、JAR 结构与字节码，再构造来源并
持久化记录，最后替换旧版本；失败保留已安装来源，正在执行的旧请求持有旧加载器直到结束。
禁止降级。索引上限 16 MiB，JAR 上限 64 MiB；取消下载关闭流并清理应用拥有的临时文件。
索引 signingKey 变化会拒绝刷新，但它不构成 JAR 签名验证，SHA 用于本地身份与恢复校验。
旧版本 JAR 当前保留，卸载、仓库删除、自动更新与产物清理仍待实现。

来源筛选面板消费共享的原生控件树：复选、包含/排除、选项、文本、排序方向、可清除排序和嵌套
分组。选项按原生索引提交，同名标签不会混淆；恢复默认保留来源的默认值。草稿仅在“应用”成功
后生效，取消或失败保留原列表，分页和再次搜索沿用已应用条件。“热门”与“最新”清除查询/筛选，
仅对支持的来源显示“最新”；切换来源清空旧条件。请求等待网页验证时暂时收起面板，验证取消或
失败后保留草稿。不支持的扩展控件明确提示，当前没有筛选预设或跨重启保存条件。

浏览、收藏、历史卡片与详情显示来源封面；详情优先使用来源的大图 URL。请求通过扩展自己的
imageRequest/client，章节上下文不适用时回退到通用 headers。缺失封面保留标题占位，失败可单独
重试；封面加载不阻止列表、详情与阅读操作。Session 共享同一来源/作品/URL 的正在执行请求，
最后一个使用者离开时取消请求，退出前结束封面任务。

封面文件复用已有 SHA-256 图片 store；256 条内存路径及最多 256 条持久化记录供切换界面和离线
重启复用，磁盘记录按写入时间淘汰。恢复时检查大小和 SHA-256，损坏/缺失重新获取；来源 locale
元数据缺失不影响原 source name + content ID 的缓存身份。最多并行三个封面任务，同一来源仍遵循
host 执行顺序。此限制不等于所有图片 blob 的磁盘容量/清理或完整 HTTP cache 生命周期已完成。

阅读器使用共享 `reader-core` 分页场景，支持单页/双页、从左向右/从右向左和同分支上一章/下一章。
图片头尺寸用于宽图单独显示，窗口尺寸决定 Fit 排版；双页的起始页用于恢复进度，完成百分比包含
最后一张可见页。模式和方向保存到同一私有偏好存储，重启后恢复。已加载章节图片可以回看而无需
重复请求，“重新加载”会重新获取当前页组。图片请求失败时保留此前页面和历史，允许再次尝试。

支持适应整页、宽度、高度及原始尺寸，Fit 模式随偏好保存。当前页组可通过按钮、Ctrl+加/减号、
Ctrl+滚轮或触摸捏合缩放到 100%–500%；双击在基准与 200% 间切换，Ctrl+0 或“重置视图”恢复
基准阅读起点。拖动与普通滚轮平移，适应宽度/高度或原始尺寸在 100% 时的溢出也可平移。
相机范围复用 reader-core，放大和平移不改写阅读进度、不再次请求已加载图片；翻页/跨章/改变
模式后重置相机。窗口改变尺寸时保留用户倍率并重新限界，未操作的视图重新对齐阅读起点。
相机位移/倍率不跨重启保存，双页始终以完整页组记录历史，不因放大只露出一页而改变归属。

阅读模式可切换到按宽度连续滚动，复用共享 `VerticalReaderScene` 的页面几何和视口进度。
滚轮、拖动、滚动条及上下键移动视口；PageUp/PageDown、Space/Shift+Space 翻动视口，Home/End
跳到首末页。按实际可见范围加载图片，缓存页面回看无需再次请求，失败页可重试。
连续模式保存首页索引、页内像素偏移与末个可见页，较小窗口重启也恢复该锚点；可见页全部加载
并完成排版后才提交进度。跳转目标失败保留最后验证的位置，导航和退出先写入待保存进度。
加载任务由原 Controller 持有，改变需求、跨章或离开时取消；连续模式暂不提供分页相机缩放。

分页和连续模式共用最多 256 条持久化页面索引，跨章或离线重启可复用原图片 store。
缓存身份包含扩展 JAR 哈希及完整 SourcePage 请求上下文；扩展更新、请求 URL/headers/原生
上下文改变后重新获取。读取索引先核对文件大小与 SHA-256，损坏或缺失时走原来源请求。
“重新加载”绕过索引，失败不覆盖此前成功记录。索引按写入时间淘汰，原 blob 保留供现有节点
使用；该限制不等于图片目录总容量清理。像素仍由可见 Compose 节点持有，转换后的 Bitmap
独立于临时 Skia Image，临时 Image 在转换完成后释放。

PNG/JPEG 的长页（高度超过 4096 或超过 419 万像素）使用可见区域分块，分页相机和连续模式
共同消费 reader-core 的 TileGrid、源坐标映射与采样规则。单块输出预算最多 2 MiB，最多两个
区域读取同时执行；离开视口后对应块退出 composition。放大选择更细采样，分块 gutter 用于
接缝裁剪，移动视口复用原下载文件。PNG/JPEG 尺寸直接读取文件头，避免为尺寸读取整份文件。
普通页面和暂未支持区域读取的格式走 Skia 完整页面输出，按共享 ImageDecodeSize 保持比例降采样，
最多 4194304 个 N32 像素（16 MiB）及单边 16384；普通小图保留原始像素，场景尺寸和进度仍用
原始页面几何。大 WebP 等格式的放大细节受输出采样限制，原下载文件保留。
页面和区域解码共用两个并发名额，输出 Bitmap 由可见 Compose 节点持有，避免转换时再复制整图。
原生 codec 的内部临时像素、所有可见节点/封面总量与 GPU 内存尚无全局预算。
区域读取遵循 [JDK ImageReadParam](https://docs.oracle.com/en/java/javase/21/docs/api/java.desktop/javax/imageio/ImageReadParam.html)；
该输出预算不是所有 provider 内部内存或应用峰值内存的证明，PNG 深处读取也可能扫描前面的行。

进入阅读器或单击页面后，可使用方向键翻页（RTL 下左键向前），PageUp/PageDown、Space/Shift+Space、
Home/End 和 Esc 返回详情。快捷键仅在阅读器焦点范围生效，Ctrl/Alt/Meta 组合不会触发翻页；
依据 [Compose Desktop 官方键盘事件 API](https://kotlinlang.org/docs/multiplatform/compose-desktop-keyboard.html)。

## 视频播放

视频来源（Aniyomi、kototoro-parsers 的视频源）在应用内用 libmpv 播放：mpv 渲染进内容区里的原生子窗口，
其余控件为 Compose。支持画质/线路切换（续播当前位置）、字幕与音轨、倍速、音量/静音、±10 秒、
上一集/下一集、剧集列表、窗口全屏与快捷键（空格/K、←→/J L、Shift 加大步长、↑↓、M、N、F/F11、Esc）。
流自带的请求头（Referer、User-Agent 等）同样用于字幕/音轨；播完自动进入下一集。观看位置按秒写入共享
历史（page = 已看秒数，pageCount = 时长），重新打开从历史恢复。

Windows 安装包随附 libmpv（LGPL 构建）与 RealCUGAN / Real-ESRGAN 的 ncnn-vulkan 官方程序，许可与来源见 resources 中的 THIRD_PARTY_PLAYBACK.md。开发运行时不自带 libmpv。查找顺序：`-Dkototoro.libmpv` / `KOTOTORO_LIBMPV` 指定文件、`kototoro.libmpv.dir`、
打包资源 `mpv/`、数据目录 `mpv/`（缺失时界面给出此路径与“打开文件夹”“重新检测”）、PATH。
没有 libmpv 时可复制视频地址，或在 PATH 有 `mpv.exe` 时交给外部 mpv（带请求头、字幕与起始位置）。
发行包可用 `-PwithWindowsDistribution -PlibmpvDirectory=<含 libmpv-2.dll 的目录>` 一并打入；
选择哪种构建（LGPL/GPL）与随包分发由发行方决定。排障可用 `-Dkototoro.mpv.log=<file>` 与
`-Dkototoro.mpv.options=key=value;...`。弹幕尚未移植。

“投屏”搜索局域网 DLNA 渲染器（电视、盒子），经本机中继补上站点要求的请求头（含 HLS 分段与密钥）；投屏时本机暂停，“结束投屏”后从电视的进度继续。首次使用时 Windows 防火墙可能询问是否允许 Kototoro 访问网络。

“画质增强”提供与 Android 相同的 Anime4K 快速/质量、仅修复与 FSR 1.0（可调锐度），在 mpv 的 GPU 输出上实时运行，选择会记住。

## 阅读器超分辨率

“阅读设置 → 超分辨率”可选 RealCUGAN 2x（含降噪档）、Real-ESRGAN 4x 动漫、AnimeVideo 2x（官方 ncnn-vulkan 程序，首次使用时下载并校验，约 45 MB，
也可选择已下载的官方压缩包），以及 Android 的六种 Anime4K 模式（需要 libmpv，离屏运行）。结果缓存在数据目录 `cache/upscale`，失败时显示原图。

Aniyomi 扩展若实现 `ConfigurableAnimeSource`，与 Mihon 来源一样在“来源设置”中显示原生控件，修改写入 `source_<id>`。

## 启动

```powershell
./desktop-app/run-windows.ps1 -CompatibilityDirectory "D:/tmp/kt-compat36" -JavaVersion 25
```

可选的 WebView2 桥和独立测试数据目录：

```powershell
./desktop-app/run-windows.ps1 -CompatibilityDirectory "D:/tmp/kt-compat36" -JavaVersion 25 `
    -BridgeExecutable "E:/kototoro_demo/kt-kmp/mihon-desktop-compat/build/bridge/kototoro-webview-bridge.exe" `
    -DataDirectory "E:/kototoro_demo/kt-kmp/build/desktop-preview" `
    -ImportJar "E:/kototoro_demo/kt-kmp/mihon-desktop-compat/build/fixtures/offline-desktop-fixture.jar"
```

外部 API 目录须满足 [compat 模块固定哈希](../mihon-desktop-compat/compatibility-pins.properties)。
`JavaVersion` 默认 21；本机验证使用已安装的 25。模块自身生成 Java 17 字节码，外部 SDK 要求 Java 21+。
桥可执行文件与 SDK DLL 的构建条件见 [compat README](../mihon-desktop-compat/README.md)。
没有桥时仍可使用不依赖 WebView 的源和阅读功能；浏览器调试页会显示配置提示。

浏览器调试页支持输入 HTTP(S) 地址并打开可见 WebView2 窗口、显示/隐藏已有窗口、加载离线 HTML、
对当前页面执行 JS，以及手动同步 Cookie。网页中的人工操作完成后点击“同步 Cookie”，共享 SDK
Cookie store 即可供来源请求使用。窗口显示只读地址栏和页面标题，可调整大小；关闭按钮隐藏窗口，
浏览器仍由 Session 持有，退出应用时释放。输入错误和导航取消保留已初始化的页面。

来源 GET/HEAD 请求收到 `cf-mitigated: challenge` 时，桌面 Session 会打开验证窗口，并显示
“已完成，继续请求”“取消验证”“显示验证窗口”。完成后自动同步网页 Cookie 并重试原请求一次；
验证仍未解决时保留错误，不循环重试。交互最多等待两分钟，操作取消与退出也会回收窗口。
POST 等请求暂不自动重发。当前以自有本地扩展验证流程，尚未进行真实 Cloudflare/代表站点验收。
SDK 客户端装配依赖固定版本的私有委托字段，详见 [compat 边界](../mihon-desktop-compat/README.md)。

默认数据目录为 `%LOCALAPPDATA%/Kototoro`，与 Android 数据独立；也可显式指定 `DataDirectory`。
导入与下载的扩展复制到应用管理的 `extensions/<SHA-256>.jar`，重启时按 package/version/hash 重新核对。
“扩展与仓库”的已安装列表可卸载扩展/插件、检查更新、全部更新，并可开启“启动时自动更新”；卸载与更新后会删除不再使用的旧文件。Legado 等其他生态入口仍待接入。

等价 Gradle 命令：

```powershell
./gradlew.bat -PwithDesktopApp "-PmihonCompatibilityDirectory=D:/tmp/kt-compat36" `
    -PmihonCompatibilityJavaVersion=25 :desktop-app:run
```

Gradle 属性 `desktopBridgeExecutable`、`desktopDataDirectory`、`desktopImportJar` 对应脚本可选参数。
模块默认关闭，只有 `-PwithDesktopApp` 才配置 Compose 桌面插件和 compat 模块；Android 默认构建不引入它们。
供本机开发使用的外部 SDK 仅加入 run/测试 classpath，不进入桌面模块普通 runtimeClasspath。
可显式构建带 JVM、已锁定兼容 JAR 和浏览器桥的 Windows 本地预览；S3 许可证/来源/notice
审计、签名和实际安装/升级/卸载验收仍待，不代表已获公开分发批准。

## Windows 本地预览打包

```powershell
./desktop-app/package-windows.ps1 -CompatibilityDirectory "D:/tmp/kt-compat36" -JavaVersion 25
```

构建机需 Java 21+ toolchain 与本机 WebView2 SDK 构建输入（见 compat README）；Compose 下载
WiX 到项目 ignored build 目录，不做全局安装。脚本验证完整选入的 29 个外部 JAR 的成员集合/SHA，
复制并编号，确保打包器排序后 AndroidCompat → server → Android 桩类的优先级仍正确。
默认 Android/source-host 发行不引入这些产物，桌面普通 runtimeClasspath 也保持原有范围。

输出在 `build/compose/binaries/main`：`app/Kototoro/Kototoro.exe`、
`portable/Kototoro-Windows-local-preview-0.1.0.zip` 和 `msi/Kototoro-0.1.0.msi`。
ZIP 解压整个目录后运行 EXE，无需系统 JDK或构建时的 API 目录；浏览器需要已安装的 Microsoft
Edge WebView2 Runtime。MSI 使用当前用户安装权限，将程序放在 `%LOCALAPPDATA%/Kototoro-App`，
与 `%LOCALAPPDATA%/Kototoro` 用户数据隔离；安装/注册表/快捷方式写入尚未在本轮实际执行。
安装目录选择关闭；MSI 和 ZIP 都直接使用经过移动目录运行测试的同一份应用目录。

```powershell
./Kototoro.exe --data-dir "E:/tmp/独立 preview"
./Kototoro.exe --data-dir "E:/tmp/runtime-check" --check-runtime "E:/tmp/runtime-check.properties"
```

运行时检查必须提供独立数据目录，执行本地 Room/备份格式/Skia/浏览器检查，不请求来源网站。
随包 `app/resources` 含输入产物名/大小/SHA 清单、外部运行时 pin 和本地预览范围说明，
JVM legal 目录保留；这些材料只证明输入身份，不替代 S3 组合产物审计。
打包脚本先跑移动目录的原生 EXE/独立 JVM/窗口生命周期测试，随后生成 MSI/ZIP 并逐文件核对 MSI
与应用目录的 SHA 及安装/数据目录隔离。jpackage 的构建元数据替换为已校验的包名标记，其他应用
文件要求完全一致。包装时使用 `--no-configuration-cache`；普通回归仍支持缓存。

## GitHub Actions 打包

`release.yml` 和 `nightly.yml` 复用 `windows-build.yml`，在 Android 构建/发布成功后对同一提交构建 Windows x64。
Release 附加 MSI、便携 ZIP 和 SHA-256 文件；Nightly 附加 ZIP 和 SHA-256，手动运行其他分支时只保存 Actions artifact。
Windows 构建或上传失败不会阻止已经独立完成的 Android APK 发布。

默认不需要额外下载地址。`prepare-windows-ci.ps1` 使用 Suwayomi 提交
`eb2dc0b19a9571b27c02bebc5c883e404b7bd7fb`，以及 TachiyomiAzIOS 提交
`b86cead7dd1387b09c71e7f4b15c1e020463e422` 中固定 SHA-256 的补丁与 Gradle init script，
通过 JDK 25.0.1 重建兼容运行时。构建时间固定为原始兼容产物的 `1790898154`，浅克隆保留原始 `r1`
版本元数据；最终 29 个 JAR 必须全部匹配现有 `windows-runtime-pins.properties`，否则停止 Windows 打包。
WebView2 SDK、播放器与超分组件继续使用各自固定版本和 SHA-256；分发包内置 JDK 21。

仓库变量 `WINDOWS_COMPATIBILITY_URL` 可选：设置后改用 HTTPS ZIP，仍逐个核对相同的 29 个 JAR。
本地可通过 `-CompatibilityArchive` 验证已有 ZIP，或运行源码准备：

```powershell
./.github/scripts/prepare-windows-ci.ps1 -SourceJavaHome "D:/Java/jdk-25"
```

CI 使用应用版本作为 MSI 的三段版本，可通过 `-PdesktopPackageVersion` 覆盖；本地默认仍为 `0.1.0`。
`-PwindowsWixDirectory` 可指定 MSI 内容检查所用 WiX 3 的 `bin` 目录。

## 图书馆备份

侧栏“备份与恢复”可导出收藏分类、收藏、阅读历史、书签、阅读统计、作品信息和来源排序，复用 core-backup 的
Kototoro JSON 模型与 Android ZIP 节名。先写完独立临时文件，再发布到选定文件名；已有文件明确拒绝覆盖。
恢复 Kototoro/Kotatsu ZIP 时先复制并校验文件，显示文件名、备份时间、记录数量及未支持的数据节。
取消不更改数据库，确认后在一个事务中合并；分类冲突重新映射，更新的本地收藏/历史及历史删除记录保留。
作品/标签身份冲突、缺少关联数据或进度错误时回滚整次恢复。允许 0/负数/完整 64 位作品与章节编号。
新分类使用本地编号；书签按作品与页面编号匹配，保留较新记录，页面归属冲突拒绝恢复。
统计按作品与会话开始时间匹配，时长/页数各取较大值，重复恢复不累加。书签预览显示实际书签数量。
仅有书签或统计的作品也包含在导出中，书签的外置标签格式与 Android 一致。

旧 Work 收藏/历史/统计按备份中的作品锚点转换到 manga_id；旧实体图谱仅显示为其他数据，不恢复实体体系。
没有备份作品锚点的旧统计跳过；当前统计缺少对应作品时整次恢复回滚。
恢复已存在作品时保留本地详情、opaque source data 和章节；新作品需在导入相应扩展后刷新详情。
即使扩展尚未安装，收藏与历史仍可显示。恢复预览持有独立文件副本，原文件后续变化不影响确认的数据；
取消、替换预览、恢复结束及窗口退出都会关闭该副本。

压缩文件上限 512 MiB、全部解压数据上限 1 GiB，导出使用同样限制。数据逐条编码/解码，
ZIP CRC、重复/无效节名、目录一致性、缺少索引、未来格式版本及无效支持节会在预览阶段拒绝。
分类与作品锚点映射仍按记录数占用内存，单个 JSON 记录的最大驻留未单独限制。
更新追踪/其他设置与授权、Mihon/Aniyomi 外部备份导入、扩展文件和图片、WebDAV 同步仍待接入。
界面明确显示当前范围，完整 Windows 备份迁移尚未完成。

## 章节下载

作品详情的每个章节提供“下载”。侧栏“下载”显示等待、下载中、暂停、失败和完成状态，可暂停、
继续或校验已下载章节。队列归当前窗口所有，退出会取消正在执行的来源调用；重启后未完成章节
保持暂停，需手动继续。完成后可直接“离线阅读”，复用本地作品、章节页列表和原图片文件，
不需要重新访问详情/页列表。下载不写阅读进度，实际进入阅读器后沿用原 history 规则。

每页下载成功后保存完整请求、扩展哈希、图片身份和尺寸；已下载引用独立于 256 条阅读索引，
不会因该索引淘汰而丢失。离线打开仍校验 SHA/大小，缺失或损坏时显示错误，可联网后校验/继续修复。
扩展哈希变化时，继续下载获取新页列表；已保存章节仍是下载时快照。manifest 复用既有偏好
store，按章节发布清单，每 64 页保存一个内容寻址块，发现索引按身份分片；总下载和单章请求
数据不再共用一个 4 MiB snapshot。每个底层文件仍受原大小上限约束，超限明确失败。
旧版平面索引自动迁移，保留原记录；发布失败不替换上次成功状态。尚无批量选择、导出、
下载删除或图片磁盘容量清理。
下载页的“下载占用 / 回收旧记录”会预览元数据占用和可回收数量，确认后仅回收未被任何
磁盘章节清单引用的旧页面块。取消不更改文件；确认时重新核对引用和文件身份。清单损坏
或数据不完整时停止回收，章节、图片和其他设置保留。当前没有自动后台回收或图片总量预算。

## 验证

```powershell
./gradlew.bat -PwithDesktopApp "-PmihonCompatibilityDirectory=D:/tmp/kt-compat36" `
    -PmihonCompatibilityJavaVersion=25 :mihon-desktop-compat:compileWebViewBridge :desktop-app:test :desktop-runtime:test
```

新增两进程备份 UI 探针验证预览/取消/确认、导出后读取、未安装扩展时的收藏/历史显示，以及
退出时关闭尚未确认的预览；书签与统计也经真实确认和第二个进程核对。
实际数据库测试核对原生 ZIP、手写 Android 格式、分类冲突、较新删除记录保护、错误回滚、
ZIP CRC、原文件变化与流式导出预算；另覆盖书签分页/完整主键、仅有书签/统计的作品、外置标签、
统计幂等合并与旧统计锚点。未执行真实用户备份或 Android 设备恢复。
UI 测试在独立 Windows JVM 中加载真实 API 和自有离线 JAR，点击浏览/搜索/详情/收藏/阅读/历史/
标准源设置，并在第二个进程验证源设置和用户状态恢复；本机已配置桥时也执行真实 WebView2 HTML/JS。
封面扩展探针验证自定义请求、headers 回退、client 解扰、失败重试、列表/详情/收藏/历史复用和
离线重启；取消后的来源操作等待迟到回调，原 response 关闭，临时图片文件没有残留。
独立阅读扩展提供五张自有彩色页面和两章，验证双页/RTL 的实际图片位置、宽图单独显示、键盘、
失败不推进历史、章节切换、回看复用与强制重载，以及第二个 JVM 的模式/方向/页组恢复。
相机探针核对实际 layer 放大与截图中白色页码移动，并注入鼠标拖动、Ctrl+滚轮、双击、键盘和
多指触摸，验证 Fit/原始尺寸溢出、RTL 起点、上下限、进度/请求不变，以及较小窗口重启恢复。
连续阅读探针在两个独立 JVM 中验证可见范围加载、失败重试、失败跳转不推进进度、缓存回看、
滚轮/键盘、首末页、跨章、立即离开时保存及较小窗口重启后的页内像素位置恢复。
分页与连续阅读的第二个 JVM 将自有图片来源置为离线，核对实际页面显示与零图片请求。
页面索引测试覆盖实际磁盘重开、同尺寸内容损坏、强制刷新/失败保留、扩展及请求身份隔离、
数量淘汰、损坏索引恢复与调用者取消；没有新增独立请求 scope。
独立长图探针通过真实扩展提供 24,000 像素页面，验证分页平移/连续滚动的实际中部像素、跨块
接缝、请求/历史不变；PNG/JPEG 区域解码另核对实际输出尺寸、颜色和超预算拒绝。
完整页面测试核对 PNG/JPEG/WebP 的真实 N32 输出尺寸、字节数/颜色、透明度、坏图失败和取消
等待不泄漏并发名额；PNG 仅含文件头时也可获取尺寸。新的两进程 UI 探针通过真实来源 client
传递 3000×2000 WebP，验证分页、缩放、连续模式、宽图单页及较小窗口离线重启的实际红蓝像素。
自动跨章探针验证同来源/同分支边界、单页/双页/RTL、连续模式键盘/滚轮，以及向前进入首页、
向后进入末页组/底部。章节列表或目标图片失败保留原章节和进度，失败后的滚轮不自动重复请求；
第二个离线 JVM 验证开关、模式与位置恢复。既有页面索引保存尺寸提示，帮助回看保持宽图独页；
提示不代表图片已加载，显示前仍核对文件大小/SHA。测试仅覆盖自有来源。
章节下载探针验证失败保留前两页、暂停实际 Rx/HTTP 图片调用、继续仅补剩余三页、下载不写历史，
并在较小窗口的第二个 JVM 中将页列表和图片来源都置为离线，仍渲染末页且无新增来源调用。
独立磁盘测试覆盖 257 页引用、索引淘汰后的重开/校验、同尺寸损坏修复、活动/等待任务取消、
扩展 revision 变更、存储容量失败及后台传输不占用前台阅读缓存锁。
存储测试另验证实际写入总量及单章超过 4 MiB、64 页块重开、旧数据迁移/失败重试、块/清单/
发现索引发布失败、失败内存快照不能跳过重试写入，以及损坏块不回退旧进度或影响健康章节。
回收测试覆盖实际文件清理、缓存视图失效、已变化文件拒绝删除、预览后重新引用的块保留、
未批准的新旧块不删除、未索引清单保护及损坏图阻止回收。实际 UI 验证预览/取消/确认，
回收后下载状态/历史不变，重启仍能离线显示末页。
独立筛选扩展使用实际 SDK 的缓存 FilterList、原生子类和同名 opaque 选项，验证全部标准控件、
分页/搜索、默认值恢复、失败不提交、重试、原生状态回收及来源切换。网页验证探针同时验证筛选
面板让出主窗口验证按钮并在取消后恢复草稿；不增加生产站点请求。
新增浏览器 UI 探针访问自有 127.0.0.1 页面，验证页面交互的 Cookie 写入 SDK、隐藏/重开、错误后
页面保留、切换页面取消导航，以及导航中退出时存储句柄最终释放。未构建桥时该项明确跳过。
另一个独立扩展 JAR 使用真实 SDK client、Rx HTTP adapter 和 shared source protocol；UI 测试验证
挑战提示、窗口重显、Cookie/UA 一致的请求恢复、主动取消、操作取消、旧提示不能确认新请求、
未解决挑战只重试一次及交互中退出。页面模拟 `cf-mitigated` 和自有 Cookie，不调用真实挑战服务。
测试使用 [Compose v2 官方 UI 测试 API](https://kotlinlang.org/docs/multiplatform/compose-test.html)。
`build/reports/desktop-smoke` 保存渲染截图与子进程日志。
另通过生产 `main` 创建真实原生窗口，覆盖启动中关闭与导入后关闭，再检查存储重开和 Windows 句柄释放。

## 生命周期与剩余工作

- 来源意外错误显示类别、诊断编号和数据目录内 `logs/source-errors.log` 的路径，按协议 requestId
  关联到相应操作。日志最多保留当前 Session 最近 16 项，每项限制长度和栈帧；不记录请求参数、
  异常消息、Cookie、原始网址或响应内容。写入失败不掩盖来源错误。
  Windows 随包浏览检查经过生产 Controller/Session/SourceProtocol，并要求离线扩展默认客户端
  保留真实 SDK CloudflareInterceptor，覆盖客户端装配及 streaming JSON 依赖。
- Session 持有 registry、兼容平台、浏览器和存储；Controller 串行执行操作并在退出时取消/等待任务。
  启动取消期间已获得但未交付的 Session 会关闭。Compose 正常返回后允许验证清理结果。
- 收藏/历史/书签/统计直接归属 `manga_id`，不恢复已移除的 Entity Graph / Work。沿用现有 Room Entity 映射，
  不新增 schema、DAO 或平行用户数据库。写入标签实体、保护已有 pin/createdAt，拒绝 ID 冲突。
- 章节图片通过扩展自己的 client/interceptor 获取；读取进度复用共享规则。
  卡片与详情封面已接来源 client、共享缓存与失败重试；分页阅读已接 reader-core 单页/双页/RTL、键盘与跨章按钮。
  缩放/拖动、四种 Fit、连续滚动和 PNG/JPEG 可见区域分块已接共享规则。自动跨章开关默认关闭并
  持久化；开启后，在已验证的章节边界继续翻页/滚动会切换同来源同分支章节，向后返回末页组或底部。
  恢复位置、调整窗口或刚到边界不会自行跨章；目标加载失败保留原章节。预测预取与其余格式区域解码仍待。
  已接漫画整章下载、暂停/继续/失败恢复及跨进程离线阅读；批量管理、导出/删除和容量管理仍待。
  章节已加载图片保留到离开/跨章，尚无长章节的容量淘汰；当前不等于完整阅读器验收。
- 标准 PreferenceScreen 控件已接入，扩展 listener 拒绝、revision 和磁盘保存失败保留现有协议语义。
  自定义/unsupported 控件没有伪造可用行为。
- 浏览器调试面板提供可见窗口、网址与 HTML/JS 操作；扩展的 WebView 已接请求头/UA、POST、HTTP(S) base URL 与 SDK Cookie
  互通，并验证主 Looper 回调、停止/取消恢复和跨进程 Cookie 恢复/清除。桌面源 GET/HEAD 挑战交互已
  接入；真实挑战/代表源、其他方法和完整 WebView API 仍待。WebView2 profile 的子进程句柄可能在
  桥退出后短暂保留，测试采用最长 10 秒有界等待。
- 其他备份节与外部格式导入/同步/筛选预设与自定义控件、完整图片格式/磁盘保留管理、扩展卸载与完整源生态、Windows 实际安装/升级/卸载和 S3/签名、完整平台能力及 iOS 原生/真机门槛继续推进。
  离线验证不等于真实站点、Cloudflare 或全量扩展兼容率验证。
