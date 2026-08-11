# Issue Submission Guide / Kototoro 问题提报指南

Welcome to submit bug reports or feature requests for Kototoro! Given the complexity of the reader, Media3 video engine, dynamic source plugins, and networking stacks, please follow these guidelines to help us **identify and fix your issue as quickly as possible**.

欢迎向 Kototoro 提交 Bug 报告或功能请求！由于本应用涉及底层阅读器、Media3 视频引擎、动态图源插件以及网络请求库，为了让我们能**最快速度排查并解决您的报错**，请在提交 Issue 时遵循以下规范：

## 📝 Pre-submission Checklist / 提交 Issue 前的必做检查项

1. **Read Docs & FAQ / 查阅文档与 FAQ：** Many connection or source-visibility issues are already solved in our [FAQ](./faq.md) and [Troubleshooting](./troubleshooting.md) guides. / 大量关于源不可见、无法连接的问题都已在常见问题解答和疑难解答中给出了解决方案。
2. **Ensure Plugins Are Updated / 确认插件最新：** If a manga, novel, or video source fails to load, **verify your extension plugins are fully updated first**. Upstream sites change frequently; server-side structural changes do not mean the core app intends to crash. / 如果是某个源加载失败，请**先确认源扩展插件已经升级到了最新版**。不同源的上游网站结构随时会变，源插件损坏不一定代表 App 核心出现了 Bug。
3. **Avoid Duplicates / 不要重复提交：** Use GitHub Search to check if an issue already exists. / 使用 GitHub 的搜索功能查看是否已经有人提交过相同报错的 Issue。

## 🐛 What makes a great Bug Report? / 一份优秀的 Bug 报告应包含什么？

When opening an issue prefixed with `[Bug]`, please provide the following four key components:
当您发起以 "[Bug]" 为标题前缀的 Issue 时，请尽可能提供以下四类信息：

### 1. Reproduction Steps / 明确的触发步骤
"How exactly did it crash?" / “怎么点崩溃的？”
- *Example:* "Browse -> Search -> Typed 'Test' -> Switched to Mihon Source tab -> Clicked the first item -> Instant crash."
- *例*：“浏览 -> 点击搜索框 -> 输入 '测试' -> 切换到 Mihon 图源页签 -> 点击第一本漫画 -> 立即闪退。”

### 2. Environment Information / 环境信息
- **Kototoro Version / 版本号**: (e.g., `v0.8.6`, `Nightly 12345`)
- **Android OS & Device Model / Android 版本与设备型号**: (e.g., `Android 13 / Snapdragon 8 Gen 2 / Xiaomi 13`. GPU crashes are highly tied to specific processor drivers! / 某些底层 GPU 崩溃高度依赖特定处理器的驱动！)
- **Scope / 该问题的范围**: Is it isolated to one specific source, or does it happen globally? / 是某个特定图源独有，还是所有功能都崩？

### 3. Visuals (Screenshots or Screen Recording) / 直观展示 (截图或录屏)
If the UI is corrupted or frozen, append a short screen record. It is significantly more efficient than text descriptions alone.
如果界面错位或遇到卡死问题，请尽可能附上一段简短的屏幕录像，这比文字描述更高效。

### 4. The Core: Submit System Logs (Logcat) / 核心骨：提交底层日志 (Logcat)
**This is the ultimate lifeline for solving immediate "black screens", "video playback errors", or "NullPointerExceptions" without popup dialogs!**
**这是解决诸如“闪退黑屏”、“视频无法播放”、“解析报空指针”的唯一救命稻草！**

---

## 🛠️ [IMPORTANT] How to get Logcat on Release Versions? / 重点：如何获取 Release 版本的 Logcat？

By default, officially installed Release versions of the app do not have Android's Developer Debuggable flag enabled. If a silent codec or OpenGL rendering failure occurs, the in-app crash reporter may not show a popup. Therefore, **you MUST grab system-level Logcat.**
很多情况下，您安装的正式发布版（Release 版本）并未开启 Android 的开发者 Debug 开关。当 App 发生 MediaCodec 或 OpenGL 渲染故障时，内置崩溃收集器可能不会弹窗。此时，**您必须抓取系统级的 Logcat 日志**。

### Method 1: Using a PC (Recommended & Most Reliable) / 方法一：使用电脑辅助 (推荐，最可靠)

1. **Enable USB Debugging on your phone / 开启手机的 USB 调试**:
   - Go to `Settings -> About Phone -> Tap "Build Number" 7 times` to enable Developer Options.
   - Go back to `Settings -> Developer Options -> Enable "USB Debugging"`.
   - 依次进入 手机设置 -> 关于手机 -> 连续点击“版本号” 7 次，开启开发者选项。退回设置 -> 开发者选项 -> 开启 **USB 调试**。
2. Connect your phone to a PC (Windows/Mac/Linux) via USB. Download [Android SDK Platform-Tools](https://developer.android.com/tools/releases/platform-tools) and extract it to any folder. / 将手机连接至电脑并下载 Android 平台工具解压。
3. **Execute the following commands in the terminal / 在电脑的命令行工具中执行以下命令**:
   ```bash
   # 1. First, find Kototoro's process ID (PID) / 先查出 Kototoro 进程的 PID
   adb shell pidof org.skepsun.kototoro
   
   # (Assuming the command returned 12345 / 假设上面的命令返回了 12345)
   # 2. Tell adb to filter crashes just for this PID and export to a file / 告诉 adb 过滤抓取特定 PID 的日志输出到文件
   adb logcat --pid=12345 > kototoro_crash.log
   ```
4. **Reproduce the Crash / 重现崩溃**: While the terminal is waiting, pick up your phone and reproduce the bug to generate the logs. / 在命令行处于等待状态时，拿起手机重复一次引发崩溃或 Bug 的操作。
5. **Finish capturing / 结束抓取**: Press `Ctrl + C` on your keyboard, then drag and drop the `kototoro_crash.log` text file directly into your GitHub Issue! / 操作完后，按下键盘 Ctrl + C，将生成的 log 文件拖拽上传到 GitHub Issue 中即可！

> **⚠️ CRITICAL (For Video Player Issues) / 核心提醒（针对视频底层问题）**:
> For black screens, decoder failures, HLS errors, or enhancement-pipeline failures, include Media3, MediaCodec, and Kototoro player logs:
> 对黑屏、解码失败、HLS 错误或画质增强管线故障，请同时保留 Media3、MediaCodec 和 Kototoro 播放日志：
> `adb logcat | grep -E "Media3Player|MediaCodec|VideoPlayerActivity" > kototoro_video.log`

### Method 2: Without a PC (Using Shizuku/Bugjaeger on device) / 方法二：使用全平台无需电脑的方法

If you don't have access to a computer, you can use local apps that support adb environments:
如果您手边没有电脑，可以使用支持原生 adb 环境的本机工具：
1. Download a Logcat viewer app such as [Bugjaeger](https://play.google.com/store/apps/details?id=eu.sisik.hackend) (via Wireless Debugging) or any Shizuku-enabled Logcat reader. / 下载并在手机上配置好 Bugjaeger (需开启“无线调试”) 或是配置了 Shizuku 环境的任意 Logcat 日志查看器。
2. Set the Logcat filter keyword to `kototoro`, `Media3Player`, or `MediaCodec`. / 在日志管理界面设定过滤关键词为 `kototoro`、`Media3Player` 或 `MediaCodec`。
3. Keep the tool running in the background and reproduce the bug. / 在工具保持后台抓取时，复现 Bug。
4. **Save the caught hundreds of lines of text as a `.txt` file and upload it.** Do NOT just screenshot 3 lines of logging, as context is lost. / 将截取的数百行文本**保存为 .txt 文件并上传**（请勿直接截图几行日志，日志不全会导致无从下手）。

Thank you for your effort in helping Kototoro get better!
感谢您对帮助 Kototoro 变得更好所付出的努力！
