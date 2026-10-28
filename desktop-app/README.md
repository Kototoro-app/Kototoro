# Desktop application (Windows / macOS / Linux)

## Linux 服务器开发

Linux 沿用 Windows/macOS 的同一个 JVM `desktop-app`、Controller、数据库及来源运行时；
Android 与桌面共享的呈现继续放在 `core-ui/commonMain`，遵循
[CMP 共享 UI 指导](../docs/architecture/shared-ui-guidelines.md)，不新增 Linux 页面副本。
提供源码运行、离线冒烟、自带 JVM 的应用目录及 `.deb` 本地预览包。
浏览器桥与自动更新尚未适配；播放走发行版的 libmpv、超分在阅读器里按需下载官方 ncnn-vulkan 程序，
两者都不随包分发（见下文「Linux 打包与产物自检」）。

需要可供 Gradle 发现的 JDK 21、X11 图形会话，以及现有锁定哈希的 29 个兼容 JAR。
从已下载的官方 Windows 桌面 ZIP 中提取共享 JVM 运行时；所有同名副本都必须匹配锁定哈希，
不提取 Windows JVM、EXE 或 DLL：

```bash
python3 "desktop-app/prepare-compatibility.py" "/absolute/path/Kototoro-windows.zip"
./gradlew -PwithDesktopApp \
    "-PmihonCompatibilityDirectory=build/desktop-compatibility" \
    :desktop-app:compileKotlin --console=plain --no-configuration-cache
bash "desktop-app/run-linux.sh" \
    "-PdesktopDataDirectory=/absolute/path/linux-debug-data"
```

默认数据目录沿用 `~/.kototoro`；开发时建议显式指定独立目录。
首个位置参数可以指定已有兼容目录，之后的参数传给 Gradle；例如
`bash "desktop-app/run-linux.sh" "/absolute/path/compatibility" --debug-jvm`。
调试器通过 SSH 隧道连接服务器 `localhost:5005`。
人工调试可连接已有 X11 桌面，或使用通过 SSH 隧道访问的 VNC/noVNC 会话。
只有 Wayland 环境时需提供 XWayland/X11 显示；启动脚本不会创建远程桌面服务。

无桌面的服务器使用 Xvfb 与 `xauth` 运行既有离线探针：

```bash
bash "desktop-app/smoke-linux.sh"
# 有 X11 显示时直接使用，否则自动通过 xvfb-run 启动虚拟显示。
# 如将 Xvfb 解包在项目 build/linux-tools 中，可仅对这条命令添加路径：
PATH="$PWD/build/linux-tools/usr/bin:$PATH" bash "desktop-app/smoke-linux.sh"
```

冒烟入口复用 `DesktopAppTest`，验证浏览、搜索、详情、收藏、阅读、返回、偏好保存及第二进程恢复，
并验证生产窗口关闭、数据库释放；使用临时数据和离线 fixture，不依赖在线来源或 Windows 浏览器桥。
探针日志与截图输出到 `desktop-app/build/reports/desktop-smoke`，JUnit 报告位于
`desktop-app/build/reports/tests/test/index.html`。测试失败时先检查探针日志，不能只看构建成功与否。
Xvfb 结果不代表 GPU 播放、Wayland、高 DPI 或实际桌面性能已通过验收。

网络受限时在 Gradle 命令或脚本末尾追加：
`-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7890 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7890`。
开发辅助下载、解包工具、测试数据和截图保留在忽略的 `build/` 目录，Gradle 依赖继续使用常规缓存。

2026-10-27 已在 Ubuntu 24.04 x86_64、JDK 21、Xvfb 下完成 `:desktop-app:compileKotlin`，
冒烟入口的两项测试均通过（无跳过），并检查了详情与重启后阅读截图；运行时提取的四项 Python
回归测试通过。生产窗口在 Xvfb 中无法创建 OpenGL 上下文后成功回退到软件渲染。
本次未验证人工远程桌面会话、真实在线来源、GPU 播放或其他操作系统。

### Linux 打包与产物自检

打包沿用 Compose 官方的 `jpackage` 流程，必须在 Linux 主机执行，使用 JDK 21；
生成 `.deb` 还需系统提供 `dpkg-deb`、`fakeroot` 等 Debian 打包工具。
启用 `withLinuxDistribution` 后，兼容 JAR 的成员与哈希必须全部通过现有锁定校验。

```bash
# 仅生成自带 JVM 的应用目录，不需要显示服务。
./gradlew -PwithDesktopApp -PwithLinuxDistribution \
    "-PmihonCompatibilityDirectory=build/desktop-compatibility" \
    :desktop-app:createDistributable --console=plain --no-configuration-cache

# 生成 .deb 并检查包内内容；已有桌面会话时可省略 xvfb-run。
xvfb-run -a -s "-screen 0 1600x1200x24 -nolisten tcp" \
    ./gradlew -PwithDesktopApp -PwithLinuxDistribution \
    "-PmihonCompatibilityDirectory=build/desktop-compatibility" \
    :desktop-app:linuxDebContentsCheck --console=plain --no-configuration-cache
```

应用入口为 `desktop-app/build/compose/binaries/main/app/Kototoro/bin/Kototoro`，
`.deb` 输出在 `desktop-app/build/compose/binaries/main/deb/`。包名为 `kototoro`，
本地预览默认包版本为 `0.1.0`，可用 `-PdesktopPackageVersion=2.3.2` 显式指定。
打包复用 Android 的现有应用图标，不附带 Windows 浏览器桥、播放和超分二进制。
Windows `.ico`、macOS `.icns`、Linux `.deb` 图标与运行窗口图标都从
`app/src/main/ic_launcher-playstore.png` 生成，不维护桌面端图标副本；探针会检查生产窗口的
AWT 图标尺寸，`:desktop-app:linuxDebContentsCheck` 同时校验 `.deb` 图标与该 Android 源文件一致。

`:desktop-app:linuxDistributionTest` 会复制应用到含中文与空格的目录，清除子进程的开发 Java
配置，并使用包内 JVM 验证 classpath 顺序、来源导入与第二进程恢复、数据库、备份、图片解码、
错误退出及生产窗口启动/关闭。`.deb` 使用该测试通过的同一应用目录；日志和自检报告位于
`desktop-app/build/reports/linux-distribution/`。
`:desktop-app:linuxDebContentsCheck` 进一步解包 `.deb`，比对受测目录中的文件字节、图标和执行权限，
检查桌面入口，再从解包后的启动器执行运行时自检；报告位于 `desktop-app/build/reports/linux-deb/`。

可直接通过应用入口检查运行时（此模式不需要显示服务）：

```bash
"desktop-app/build/compose/binaries/main/app/Kototoro/bin/Kototoro" \
    --data-dir "/absolute/path/linux-runtime-check-data" \
    --check-runtime "/absolute/path/linux-runtime-check.properties"
```

报告中的 `browser=unsupported` 表示当前预览包没有系统 WebView 桥，不表示这类能力已通过验证；
`upscale.native=on-demand` 表示超分用的是官方 ncnn-vulkan 程序、由用户在阅读器里按需下载（`upscale.realcugan`/
`upscale.realesrgan` 写程序路径或 `unavailable`，两个都装好并用样张真实放大成功时才写 `upscale=ok`）。
`player.embedded=x11-window` 只说明 Linux 上 AWT 画布会成为 X11 窗口（Wayland 会话经 XWayland）、可以交给 mpv 的
`wid` 输出，播放器是否可用另看 `player`/`player.library`：发行版装了 libmpv（Debian/Ubuntu 的 `libmpv2`，或
`/usr/lib/<multiarch>` 下的 `libmpv.so.2`、`LD_LIBRARY_PATH`、`ldconfig` 缓存）时为 `ok`，没有时是 `unavailable`
并给出 `player.hint`，此时视频页仍可退到外部 mpv。`.deb` 可生成不等于已经完成跨发行版
兼容性验收；当前构建主机为 Ubuntu 24.04 x86_64。打包参考
[Compose 官方文档](https://kotlinlang.org/docs/multiplatform/compose-native-distribution.html)。

## macOS 本机开发

Windows 与 macOS 使用同一个 `desktop-app` 入口、Compose 页面、Controller、数据库与源运行时；
系统能力在现有平台服务边界适配，不复制 UI 或业务实现。macOS 当前提供本机开发与未签名本地预览。
浏览器桥、内嵌视频、原生 ncnn 超分及应用自动更新尚未适配；依赖网页 JS 或人机验证的源暂不可用。
视频可通过已安装的 mpv 外部播放；此路径不提供应用内播放进度回写。

运行需要 JDK 21 和与 Windows 相同、通过 SHA-256 验证的 29 个兼容运行时 JAR。
已有兼容目录可直接使用；也可以从已下载的 Windows 桌面 ZIP 中提取纯 JVM 产物，
脚本只接受锁定哈希的 JAR，不提取 Windows JVM、EXE 或 DLL：

```bash
python3 "desktop-app/prepare-compatibility.py" "/absolute/path/Kototoro-windows.zip"
bash "desktop-app/run-macos.sh"
# 或指定已有兼容目录，以及独立调试数据目录：
bash "desktop-app/run-macos.sh" "/absolute/path/compatibility" \
    "-PdesktopDataDirectory=/absolute/path/debug-data"
```

默认数据目录为 `~/Library/Application Support/Kototoro`。命令行 `--data-dir` 或
Gradle 的 `desktopDataDirectory` 可以覆盖；已有 `~/.kototoro` 不自动迁移。
在 IDE 中调试同一 `DesktopMainKt`，使用 JDK 21，并将兼容 JAR 加入运行 classpath；
也可运行 `bash "desktop-app/run-macos.sh" "/absolute/path/compatibility" --debug-jvm`，
然后在 IDE 中用 Remote JVM Debug 连接 `localhost:5005`。

生成自带 JVM 的本机 `.app`（将最后的 task 改为 `:desktop-app:packageDmg` 可生成 DMG）：

```bash
./gradlew -PwithDesktopApp -PwithMacosDistribution \
    "-PmihonCompatibilityDirectory=build/desktop-compatibility" \
    :desktop-app:createDistributable --console=plain
```

输出位于 `desktop-app/build/compose/binaries/main/app/Kototoro.app`。
可对实际打包产物执行本地存储、兼容运行时、备份和图片解码检查：

```bash
"desktop-app/build/compose/binaries/main/app/Kototoro.app/Contents/MacOS/Kototoro" \
    --data-dir "/absolute/path/runtime-check-data" --check-runtime "/absolute/path/runtime-check.properties"
```

检查报告明确记录未适配能力为 `unsupported`；Windows 打包检查继续要求浏览器、播放器与超分组件全部通过。
macOS 本地预览尚未签名、公证，不代表完成公开发行准备。打包参考
[Compose 官方文档](https://kotlinlang.org/docs/multiplatform/compose-native-distribution.html)。

## Windows

Windows/JVM 的 Compose Desktop 应用入口，复用 `desktop-runtime` 的共享 Room v84 数据库、
`core-source` JSON 契约和 `source-host` Mihon 执行器。提供扩展仓库、JAR 安装/更新/导入、来源浏览/搜索、详情、
收藏、章节图片阅读、历史、图书馆备份及标准源设置，以及可选的网页/HTML/JavaScript 调试面板。

## 平板布局与阅读界面

主导航直接使用 `core-ui` 中从 Android 提取的平板导航栏，默认 80dp，提供主页、收藏、浏览、历史、订阅和更多；下载、扩展与仓库、
备份和网页调试集中在“更多”。来源列表只在浏览页显示，导入本地扩展后回到浏览。书库封面采用
2:3 竖版网格；窗口达到 1000dp 时，详情将作品信息和可搜索、排序的章节列表分成两栏，
较窄窗口使用横向封面与操作区，章节随页面滚动。导航图标直接由 Android 现有 vector 资源生成
SVG，构建遇到不支持的变换会失败；没有引入另一套图标依赖。
详情页的收藏按钮与 Android 一样是双向的：未收藏时显示“加入收藏”，已收藏后变为“取消收藏”，
点击即用共享 `SourceLibrary.removeFavourite` 把作品移出书架，提示同步更新。
简介下方是 Android 详情的信息区：状态、评分、别名、标签与“复制链接”按钮同源取自本次详情应答，
缺项直接省略而不是留空；标签是只读胶囊（桌面暂无标签检索页），复制按钮把 Android 分享的链接放进
系统剪贴板，之后按钮显示“已复制链接”，换作品后复原。

主页直接调用 Android / Windows 共用的 `HomeScreenLayout`、`HomeHeroPager`、`HomeHeroCardSurface`
和 `HomeHeroStandardContent`，统一懒列表、宽屏居中、内容面板断点、双栏、快捷入口与 Hero 动画。
窗口缩窄后快捷入口随页面滚动；Hero 在主页视口内裁剪，不覆盖顶部工具栏。来源数据、图片加载和
操作回调保留桌面适配。主页分区装配、订阅页容器、详情内层内容仍由桌面实现（界面栈已统一为
Material 3），当前没有宣称全应用已经共用一套完整 UI；后续继续提取 Android 呈现，避免独立重写桌面页面。

鼠标左键按住封面即可左右拖动主页历史、推荐和 Hero，以及订阅更新横栏、顶部分类/来源筛选横栏；
快速拖动松手后继续滚动，Hero 自动吸附到页面。在历史封面上纵向拖动会滚动主页，轻点仍打开作品。
共用 `core-ui` 的鼠标手势实现，触摸滑动和滚轮继续沿用 Compose 原有处理。

阅读器独占内容窗口，隐藏主导航和来源栏。顶部返回、章节标题和选项按钮、底部进度滑条、
书签与自动翻页浮动按钮以及右侧缩放控件直接复用 `core-ui` 的 Android 组件，悬浮在完整阅读视口上。
页码滑条松手后跳转；点击标题打开共享章节面板。目录主体、章节卡片、分支筛选和分组标题直接
复用 Android 的 Compose，支持章节标题/编号/卷号搜索、倒序、列表/网格、定位当前章节和鼠标拖动。
定位会清除搜索并切回当前章的分支；点击当前章直接关闭面板，保留原页码和阅读进度。
点击阅读区中央或按 H 显示/隐藏控制栏，隐藏时显示章节与页码信息，阅读视口尺寸保持不变。
选项按钮打开共享 Android 阅读设置面板，齿轮进入更多阅读设置。F11/“排版”页全屏开关控制实际桌面窗口，退出全屏
恢复此前的浮动/最大化状态，返回详情也退出全屏。章节搜索拥有编辑键，不会在输入 H 或方向键
时翻页。面板开关不替换阅读场景，复用原 Controller、reader-core、缓存和历史。

小说“阅读设置”也使用共享面板：主题色卡、字号快捷调整、排版卡片和标签页来自 `core-ui`，
支持字号、行距、衬线字体、版心宽度及实际窗口全屏。设置沿用已有偏好键，重启恢复；
Esc 或遮罩关闭面板并回到正文。Windows 提供段落连续滚动与分页阅读两种模式：分页模式按当前窗口和字号
实测每段高度切页，复用 `reader-novel` 的 `splitNovelPageLineRanges`，段落不会被切断，超出一页的插图独占一页；
滚轮、PageUp/PageDown/空格翻页，Home/End 到章首末，翻到本章边界后与连续模式一样进入相邻章。
两种模式切换时停在同一段落。Android 的亮度、分页动画、双页、翻译和 TTS 仍由 Android 宿主处理。
小说上下栏同样使用共享组件：返回、章节标题、选项、进度底座、上一章/下一章、渐变与显隐动画。
正文点击或 H 切换控件，隐藏时的章节/段落状态可点击恢复控件；显隐保持正文视口与滚动锚点。
滑条按段落定位并保存进度，控制栏颜色跟随阅读主题；分页模式下状态改为当前页/总页，进度按当前页的
首段与末段写入原历史，因此两种模式互相恢复。
小说章节目录也复用共享面板、搜索框、分卷/分组标题和章节行，可反转顺序、定位当前章节。
详情页的章节行有溢出菜单：标为已读把原历史移到该章，标为未读回到同分支上一章（首章直接清空记录，
不写入指向空的进度），已下载的章节还能删除本地下载；三者复用阅读器写入的同一份历史与下载存储，
无鼠标长按也用同一入口。
“选择章节”把详情目录切到批量模式（也可靠按住章节行进入）：点行只切换选中而不跳转，行内的下载与
溢出菜单隐藏，底栏按当前筛选全选/清除，并批量下载、标为已读（历史落在所选最后一章）或删除本地下载；
退出模式后点击恢复导航与行内按钮。
定位会清除筛选；选择当前章直接回到原正文位置，选择其他章沿用原章节加载和历史记录。
小说右下书签按钮或 B 保存/移除当前段落，长按按钮打开书签列表，可按正文摘要或章节标题搜索，
跳回同章或跨章位置，并单独删除书签。卡片、删除图标及搜索框来自共享 UI；正文摘要和定位沿用
共享书签表，重启保留。Android 和 Windows 共用摘要解析与正文匹配，历史 HTML/base64 摘要也可搜索和定位。
分页或段落划分变化后优先寻找原正文；正文已变化时提示无法定位，重复摘要按章节进度选择近似位置。
详情页也按 Android 的“章节/书签”标签组织内容：书签标签对图片类作品用页面缩略图网格展示，
点卡片回到该作者位置，卡片内可单独删除，“全部删除”清空本作品的全部书签；小说没有页面图像，
改用文本卡片（章节、正文摘要、百分比）跳转与删除。缩略图走阅读器自己的图片管线与磁盘索引，
按 400k 像素/1024px 上限解码，因此已读过的页面通常离线即可显示；删除复用共享 `SourceLibrary`
的逐条事务，取消收藏或换源后失效的书签会按原规则失效。
详情目录的批量模式也提供安卓的“书签”命令：选中章节后一次写入章节级书签（`pageId` 复用章节 id，
记录整章而不带阅读位置，共享 `SourceLibrary.addChapterBookmark`），全部选中章节已有书签时按钮翻转为
“删除书签”并按章移除；这类书签在书签标签里显示为“章节书签”卡片，缩略图取该章第一页。
安卓的书签多选删除也已接入：书签标签右上角的“选择书签”进入多选（对应 Android 的长按），
卡片出现勾选框，可“全选/清除选择”后“删除所选”；删除逐条走同一事务，卡片已被别处删掉时只是跳过，
退出标签或删空后自动离开多选状态，并提示“已删除 N 个书签”。
没有文本摘要的旧书签仍按原页/段落索引恢复，无法保证跨阅读引擎精确对应。

收藏与历史提供标题/别名/作者搜索和右侧筛选抽屉，可按收藏分类、多个来源、未读/阅读中/已读完
组合筛选，并按默认顺序、标题、最近阅读或进度排序。抽屉带滚动条，Esc 或点击遮罩关闭；空结果
可一键清除条件。两页的条件在当前运行期间分别保留，输入只筛选共享数据库的快照，不重新请求
来源列表；可见封面仍走原图片请求与缓存。抽屉底部依次对应 Android 的四个筛选组：标签组用对话框
按标签在此库中的作品数降序列出（带作品数、可搜索、可清除），出版状态组只列出此库实际存在的状态
（连载中/已完结/已停更/暂停更新/即将推出/不可用，取自作品自身状态），下载组为“全部/已下载/未下载”
单选（已下载指该作品至少有一章存在本地下载记录，与下载页同一份队列），快速筛选组是 Android 的宏芯片：
新章节（更新检查器记下的未读新章数）、收藏/非收藏（仅历史页，收藏页作品全是收藏）、18+/全年龄
（取保存时写入的 nsfw 标志，两者同选必然为空）。各组都是组内任意命中即可，
与其他筛选组按“且”组合，筛选立即生效，已选条件计入顶栏筛选计数。标签来自收藏保存时写入的标签行，
列表项没有标签的作品不参与标签筛选。收藏卡片沿用 Android 的新章节角标：`tracks.chapters_new` 大于 0
时在封面右上角画一个强调色数字角标，已读完的作品不画（Android 的计数器在完成时归零），历史页不画；
角标的读屏文案为“N 个新章节”。

收藏页顶栏的“分类管理”对应 Android 的分类界面：对话框里可新建分类、重命名、上移/下移调整
标签顺序、删除分类（连同该分类下的收藏记录，与 Android 的删除语义一致，作品本身不删除），
改动立即写库并重新加载快照，标签栏与卡片归属同步更新；删除正被筛选的分类会退回“全部”。
分类的增删改查在 `core-library` 的 `SourceLibrary`，桌面与后续平台共用。

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

共享组件使用 Material3；桌面外壳同为纯 Material 3，不再有 Material 2 桥接——原 M2 的
`Colors`/`Typography.h4–caption` 角色、`Divider`、`Card`/`Surface` 的 `elevation`、
`DropdownMenuItem` 位置参数和 `AlertDialog.buttons` 槽都已换成对应的 M3 API。
颜色与主题偏好仍由平台解析。作品封面的边框、书脊、标题渐变，以及内容源网格卡片和尺寸规则
已进一步从 Android 提取到 `core-ui`，两端调用同一实现。Android 保留图片缓存、共享转场、TV 焦点、
徽标和进度适配；桌面提供自己的图片、数据和动作。最新视觉参考为用户 2026-10-04 提供的五张平板截图。

Backdrop 已恢复为固定 2.0.0 的 Android/JVM KMP 模块，保留两处本地性能修补。玻璃绘制、调参模型和
镜片参数保护也进入 `core-ui`；Windows 可在“更多 → 界面风格”选择 iOS 玻璃，搜索控件调用同一渲染器。
默认保持 Material 3，主题与界面风格选择会持久化。桌面只捕获独立画布，完整动态作品背景仍待接入。
主页/订阅整页、详情内层内容、完整书库徽标/进度及小说阅读器正文
仍有桌面实现，后续按组件继续提取。书库筛选的标签、出版状态、下载与快速筛选（宏）四个组已对齐 Android
（组内“或”、跨组“且”）；Android 的追踪阅读状态组在桌面按“阅读进度”近似（进度为空/进行中/已完成
对应计划中/阅读中/已读完，完成阈值同为 99.9%），其中仅来自追踪器的“重读中/搁置/弃坑”需要桌面尚无的
追踪状态持久化，暂不可选；Android 的“已完结”宏也因为桌面“阅读进度=已读完”读的是同一份进度而不再重复；
“已读完”按当前分支的共享历史进度判断，不表示所有分支均已阅读。
收藏卡片的新章节角标、筛选抽屉与卡片徽标共用共享的角标胶囊/颜色，卡片本身仍由桌面的
`ContentGrid` 负责。

## 扩展仓库

“更多 → 扩展与仓库”沿用 Android 的“来源／仓库／扩展包”标签页。标签栏与仓库卡片由
`core-ui/commonMain` 提供，Android 和 Windows/macOS 调用同一组件。来源列表统一管理所有来源，
包括 TVBox/CMS；仓库列表按配置中已有的来源地址聚合导入站点，支持刷新和确认后批量移除。
右上角“添加”统一处理扩展仓库、TVBox/CMS 链接，以及本地 JAR/APK/CS3/JSON 文件。
扩展仓库链接支持完整 `index.pb`、新版 `index.json`、旧版 `index.min.json` 或仓库根地址。
根地址依次请求 `index.pb`、`index.json`、`index.min.json`，仅在 HTTP 404 时回退；带查询参数时需提供完整索引地址。
旧版数组（Aniyomi、早期 Keiyoushi/Tsundoku 仓库）没有仓库头，名称取自地址的 owner/仓库名，条目只有 APK。
支持 gzip、相对扩展列表地址和相对 JAR 地址，JSON 的 64 位编号与版本保持整数精度。
仓库列表保存到应用偏好，在仓库卡片选择“浏览扩展”，再按扩展名称、来源名称或语言搜索并安装/更新。

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
已安装列表支持卸载、检查/全部更新、启动自动更新及不再使用的旧文件清理。仓库列表里每个仓库旁边
有“忘记该仓库”：只删除保存的索引条目并清空右侧目录，已安装的扩展保留（与 Android 一致，扩展
仍可正常使用，只是不再从该仓库检查更新）；重新添加同一地址即可恢复。删除不触碰任何 JAR 文件。

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

程序按平台取同一 release 的资产：Windows 随包（`*.exe`），Linux 下载官方的 `*-ubuntu.zip` 并补上可执行位，
macOS 上游没有包、该段显示“平台不支持”。Anime4K 离屏渲染走 mpv 的 GPU 输出，需要桌面会话（无显示的 CI 请用 Xvfb）。

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
./Kototoro.exe --check-update "E:/tmp/update-check.properties"
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
同名参数 `-PdesktopVersionName`（默认取 `app/build.gradle` 的 versionName）与 `-PdesktopChannel`
（`release`/`nightly`）写入更新面板显示并参与版本比较，CI 直接传发布资产的版本名与频道。
`-PwindowsWixDirectory` 可指定 MSI 内容检查所用 WiX 3 的 `bin` 目录。

### Linux

`linux-build.yml` 手动（`workflow_dispatch`）或由其他工作流 `uses:` 调用，只构建和验证 Linux 桌面端，
不发布任何产物；`.deb` 版本取 Android 的 versionName 的三段值。它分两个 job：

- `Linux player and upscaling`：不准备兼容 JAR，只装 `libmpv2`、Xvfb 与 Mesa lavapipe，跑 `:desktop-player:test`
  和 `:desktop-runtime:test`。`LC_NUMERIC` 设为 `en_US.UTF-8`，让 mpv 真的走到 `MpvLocale` 要修的那条路径；
  `vulkaninfo` 里找不到 `llvmpipe` 直接失败，否则 ncnn 超分只会在测试里以看不懂的方式失败。
- `Linux package`：一次调用生成应用目录、用该目录打 `.deb`，再由 `linuxDebContentsCheck` 解包执行运行时自检；
  另外用真实阅读器 UI 走一遍超分的安装/放大/记忆。`.deb` 作为 Actions artifact 保留 14 天。

兼容 JAR 与 Windows 同源：仓库变量 `WINDOWS_COMPATIBILITY_URL` 有值时用该 ZIP，否则用 JDK 25 从固定的
Suwayomi/TachiyomiAzIOS 提交重建，最终 29 个 JAR 仍逐个核对 `windows-runtime-pins.properties`。
`.github/scripts/prepare-linux-ci.sh` 只做这两件事（兼容 JAR 与两个 ncnn release ZIP），WebView2 SDK、
WiX 等 Windows 专用步骤不搬过来；离线复现时用下面两条，CI 里对应的就是同一条脚本：

```bash
bash ./.github/scripts/prepare-linux-ci.sh --source-java-home /path/to/jdk-25
bash ./.github/scripts/prepare-linux-ci.sh --upscalers-only
bash ./.github/scripts/prepare-linux-ci.sh --compatibility-archive build/desktop-compatibility.zip --upscalers-dir /path/to/zips
```

Linux job 用的是 Xvfb + 软件渲染的 Ubuntu 24.04 x86_64，不代表真实 GPU 播放、Wayland 会话或跨发行版验收；
macOS 桌面端目前没有任何 CI。

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
更新追踪/其他设置与授权、Mihon/Aniyomi 外部备份导入、扩展文件和图片仍待接入。
界面明确显示当前范围，完整 Windows 备份迁移尚未完成。

同一页面的“云同步（WebDAV）”填服务器地址、远程目录、账号与每个命名空间保留的数量，可测试连接、
上传当前图书馆、刷新远端列表、下载备份并删除。上传复用本页导出，远端文件名、分类、最新备份判定与
旧文件保留规则都来自 `core-net`，与安卓的 `kototoro-v3-work-v1-<时间>.zip` 命名一致；服务器返回的
XML 与 HTTP 日期也由共享读取器解析，不再各端各写一套。下载的备份先落到数据目录下的暂存文件，再交给
本页原有的预览与合并，因此远端恢复与本地恢复走同一条事务路径，暂存文件在打开预览后立即删除。
“自动备份间隔”填大于 0 的小时数后，应用内计时器按安卓周期备份的规则执行：距上次成功上传满该间隔才上传，
首次没有基准时启动即上传一次；间隔与上次上传时间都保存在同一偏好命名空间，改间隔或手动上传会重排计时，
上传失败保留旧基准并退避十分钟再试，退出应用即停止，不与手动上传并发。
当前是手动触发，没有安卓的周期调度；Google Drive 与系统同步仍待接入。

## 章节下载

作品详情的章节标题栏有“批量下载”菜单，作用范围与 Android 的对话框一致但用固定档位代替数字
输入：全部章节、未读章节（当前阅读位置之后的章节）、某个翻译分支的全部章节、前 10/25/50 章。
每个条目显示实际会入队的章数，已完成的章节不重复入队，队列为空的条目直接隐藏。阅读顺序取
未筛选的全部章节，所以即使列表正筛在某个分支上，菜单仍然按整部作品计算；进入分支的判定用
列表下标而不是章节号，源给出重复或缺失话号时也不会错位。

作品详情的每个章节提供“下载”。侧栏“下载”显示等待、下载中、暂停、失败和完成状态，可暂停、
继续或校验已下载章节。队列归当前窗口所有，退出会取消正在执行的来源调用；重启后未完成章节
保持暂停，需手动继续。完成后可直接“离线阅读”，复用本地作品、章节页列表和原图片文件，
不需要重新访问详情/页列表。下载不写阅读进度，实际进入阅读器后沿用原 history 规则。

每页下载成功后保存完整请求、扩展哈希、图片身份和尺寸；已下载引用独立于 256 条阅读索引，
不会因该索引淘汰而丢失。离线打开仍校验 SHA/大小，缺失或损坏时显示错误，可联网后校验/继续修复。
扩展哈希变化时，继续下载获取新页列表；已保存章节仍是下载时快照。manifest 复用既有偏好
store，按章节发布清单，每 64 页保存一个内容寻址块，发现索引按身份分片；总下载和单章请求
数据不再共用一个 4 MiB snapshot。每个底层文件仍受原大小上限约束，超限明确失败。
旧版平面索引自动迁移，保留原记录；发布失败不替换上次成功状态。

下载页顶部显示已保存章节、已完成章节、页数与图片占用（按每页 artifact 的字节求和，来自已保存
记录，重启后仍准确）。每章可选，批量栏提供全选、清除选择、暂停选中、继续选中、重试失败和移除
选中：暂停只作用于等待/下载中的章节，继续只作用于已暂停章节，重试只作用于失败章节，移除跳过
仍在下载的章节。移除先取消并等待其协程结束，再删发现索引项，最后删章节清单——顺序反了会让清单
在下次启动时被重新索引；清单消失后它引用的页面块变成不可达元数据，可在“下载占用 / 回收旧记录”
里回收。图片文件本身不随移除删除：它们由图片 store 按内容寻址并被阅读索引共享。
下载页的“下载占用 / 回收旧记录”会预览元数据占用和可回收数量，确认后仅回收未被任何
磁盘章节清单引用的旧页面块。取消不更改文件；确认时重新核对引用和文件身份。清单损坏
或数据不完整时停止回收，章节、图片和其他设置保留。

已完成章节可以“导出 CBZ”，相当于 Android 把下载写进可见下载目录：选择文件夹后生成
`作品 - 章节名.cbz`，图片按页序编号（`0001.png` 等）存为不压缩条目（图片本身已压缩），
文件名去掉 Windows 禁止的字符并把长度限制在 80 字符。导出逐页复核 SHA 和大小，命中
缺失/损坏的图片会失败且不留下半个压缩包；同一章节再次导出覆盖原文件。导出只读存储里的
内容寻址文件，不复制第二份图片，也不会因为导出而改写章节清单。
批量栏还有“导出全部已完成章节”：选一个文件夹后把所有已完成章节一次写成各自的 CBZ，
跳过未完成的队列项，某一章因图片缺失/损坏失败只计数、不中断其余章节，最后提示
“已导出 N 章”或“已导出 N 章，M 章失败”。

下载页的“自动回收未引用块”默认开启：应用启动时和队列空闲时各扫一次，自动回收不再被任何
章节清单引用的页面块，因此不必等用户手动点“回收旧记录”。回收只动元数据块，图片文件由图片
store 按内容寻址并与阅读索引共享，不会被自动删除；关闭开关后完全交回手动。扫描发现清单损坏
或数据不完整时整体放弃，不会误删。

下载页还显示图片总磁盘预算（对应 Android 的图片缓存上限）：同一条“图片缓存”行给出目录实际占用、
其中已保存章节占用的字节、当前上限，以及带几档预设（512 MB、1 GB、2 GB、4 GB、8 GB、不限制）的
上限菜单和“清理缓存”。预算由后台循环按 10 分钟节流执行，切换上限或点清理时立即执行；回收顺序是
最久未使用的 `*.img` 优先，`verifiedImagePath` 命中校验会把文件时间刷新为最近使用时间，因此阅读
索引里的图片会自然沉到队尾。已保存章节引用的 blob 永不删除，离线阅读不受影响；两分钟（手动清理
五秒）内刚发布的文件不动，避免删掉正在读的页面；一小时前的 `.image-*.part` 残留直接清掉。读取时
仍复核大小和 SHA-256，被清掉的缓存只是重新下载。尚无整部作品批量导出。

## 应用内更新

“更多 → 关于与更新”显示当前版本与发布频道，并提供检查、下载与“重启并更新”。版本名、MSI 三段版本和
频道由构建写入 `desktop-version.properties`（`:desktop-app:prepareDesktopVersion`，CI 用
`-PdesktopVersionName`/`-PdesktopChannel` 覆盖），运行时不再猜测自己是什么版本。窗口打开时每 6 小时
自动检查一次（可关闭），与追踪/推荐任务分属不同协程：慢仓库或下载不会推迟另一项，反之亦然。

检查读取 GitHub Release：稳定频道只看 `Kototoro-app/Kototoro` 的非预发布版本，nightly 频道看
`Kototoro-app/Kototoro-Nightly` 的 `nightly-<日期>` 标签；比较复用 Android 的版本语义（alpha/beta/rc 权重、
`N<日期>` 形式），发布包里的其他平台资产（如 arm64）不会被误判。

下载使用发布附带的 `.sha256`，流式写入 `<数据目录>/updates` 下的 `.part` 文件，核对大小与 SHA-256，
ZIP 必须包含 `Kototoro.exe`（MSI 校验复合文档头），校验通过才原子改名；随后解压到暂存目录并核对镜像
结构（EXE + `app/` + `runtime/`）。便携版整目录覆盖，MSI 安装改用 `msiexec`；两者都由一个等待本进程
退出的 PowerShell 脚本执行（日志在 `updates/update.log`），完成后重启应用。程序目录与
`%LOCALAPPDATA%/Kototoro` 用户数据分离，覆盖不触碰收藏、历史与下载。

“自动下载”和“退出时安装”可分别开启；从构建目录（Gradle/IDE）启动的副本没有可替换的镜像，面板会说明
不支持自更新。签名校验（Authenticode）与 S3/代理镜像仍待接入。

安装好的镜像可以用 `--check-update <文件>` 自查而不用打开窗口：报告写入
`version.name/package/channel/parsed`、`install.supported/kind/root/executable`、`channel.newest`（该频道
当前发布的最新版本与 tag）和 `status`——`available`（有可安装的新版本，附带版本、包名、大小、SHA-256 地址）、
`upToDate`（发布的就是本副本或更旧）、`unpublished`（该频道还没有可安装的发布）、`failed`（问不到，带
`error`）。退出码 0 表示问到了发布频道，1 表示问不到或本副本没有版本标记。`--channel` 可临时改看另一个
频道，此时 `channel.newest` 会如实说明那边发布了什么、是否比本副本新。打包脚本与 Windows CI 用它核对
镜像自称的版本/频道与发布资产名一致，且该副本能认出自己的安装布局。

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
  恢复位置、调整窗口或刚到边界不会自行跨章；目标加载失败保留原章节。已接邻页和下一章预取，其余格式区域解码仍待。
  已接漫画整章下载、暂停/继续/失败恢复及跨进程离线阅读；批量管理、导出/删除和容量管理仍待。
  章节已加载图片保留到离开/跨章，尚无长章节的容量淘汰；当前不等于完整阅读器验收。
- 标准 PreferenceScreen 控件已接入，扩展 listener 拒绝、revision 和磁盘保存失败保留现有协议语义。
  自定义/unsupported 控件没有伪造可用行为。
- 浏览器调试面板提供可见窗口、网址与 HTML/JS 操作；扩展的 WebView 已接请求头/UA、POST、HTTP(S) base URL 与 SDK Cookie
  互通，并验证主 Looper 回调、停止/取消恢复和跨进程 Cookie 恢复/清除。桌面源 GET/HEAD 挑战交互已
  接入；真实挑战/代表源、其他方法和完整 WebView API 仍待。WebView2 profile 的子进程句柄可能在
  桥退出后短暂保留，测试采用最长 10 秒有界等待。
- 其他备份节与外部格式导入/同步/筛选预设与自定义控件、完整图片格式/磁盘保留管理、完整源生态、Windows 实际安装/升级/卸载和 S3/签名、完整平台能力及 iOS 原生/真机门槛继续推进。
  离线验证不等于真实站点、Cloudflare 或全量扩展兼容率验证。
