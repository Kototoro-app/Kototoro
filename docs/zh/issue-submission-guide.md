# Kototoro 问题提报指南

欢迎向 Kototoro 提交 Bug 报告或功能请求！

由于本应用涉及底层阅读器、Media3 视频硬解码引擎、动态图源扩展插件以及网络请求库，为了让我们能**以最快速度排查并解决您的报错**，请在提交 Issue 时遵循以下规范：

---

## 提交 Issue 前的必做检查项

1. **查阅文档与 FAQ**：大量关于源不可见、无法连接、无法翻译的问题，都已在 [常见问题解答](./faq.md) 和 [问题排查指南](./troubleshooting.md) 中给出了明确答案。
2. **确认插件最新**：如果是某个具体源加载失败，请**先确认源扩展插件已经升级到了最新版**。不同源的上游网站结构随时会变，某个源抓取失效通常属于上游变更，不一定代表 App 核心出现了 Bug。
3. **避免重复提报**：先使用 GitHub 的 Issue 搜索功能，查看是否已经有人提交过相同报错的 Issue。

---

## 一份优秀的 Bug 报告应包含什么？

当您发起以 `[Bug]` 为标题前缀的 Issue 时，请尽可能提供以下四类信息：

### 1. 明确的触发步骤
- *例*：“浏览 -> 点击搜索框 -> 输入 '测试' -> 切换到 Mihon 图源页签 -> 点击第一本漫画 -> 立即闪退。”

### 2. 环境信息
- **Kototoro 版本号**：（例如 `v2.4.0` 或 `Nightly 20261120`）
- **Android 系统版本与设备型号**：（例如 `Android 14 / 骁龙 8 Gen 3 / 小米 14`。涉及 GPU/OpenGL 渲染或超分辨率时硬件信息尤为重要）
- **该问题的范围**：是某个特定图源独有，还是全局所有页面都会复现？

### 3. 直观展示（截图或录屏）
如果界面错位、排版异常或遇到卡死问题，请尽可能附上一段简短的屏幕录像或多张截图。

### 4. 核心：提交底层系统日志 (Logcat)
**这是排查“闪退闪退黑屏”、“视频无法播放”、“解析报空指针”的关键凭证。**

---

## 如何获取 Release 版本的 Logcat 日志？

很多情况下，您安装的正式发布版（Release 版本）并未开启 Android 的开发者 Debug 开关。当 App 发生底层崩溃或媒体渲染故障时，内置崩溃收集器可能无法弹窗。此时，**您必须抓取系统级的 Logcat 日志**。

### 方法一：使用电脑通过 USB 调试抓取（推荐）

1. **开启手机的 USB 调试**：
   - 依次进入：`手机设置 -> 关于手机 -> 连续点击“版本号” 7 次`，开启开发者选项。
   - 返回：`设置 -> 开发者选项 -> 开启 USB 调试`。
2. 将手机连接至电脑并解压 [Android SDK Platform-Tools](https://developer.android.com/tools/releases/platform-tools)。
3. 在电脑的终端中执行：
   ```bash
   # 1. 查询 Kototoro 进程的 PID
   adb shell pidof org.skepsun.kototoro
   
   # 2. 过滤抓取该 PID 的日志输出到文件（假设 PID 是 12345）
   adb logcat --pid=12345 > kototoro_crash.log
   ```
4. 保持命令行运行，在手机上复现一次崩溃或 Bug。
5. 按 `Ctrl + C` 结束抓取，将生成的 `kototoro_crash.log` 文件直接拖拽上传到 GitHub Issue 中。

> **针对视频播放问题的核心提醒**：  
> 若为黑屏、解码失败或画质增强管线故障，请执行：  
> `adb logcat | grep -E "Media3Player|MediaCodec|VideoPlayerActivity" > kototoro_video.log`

### 方法二：在手机上直接抓取（使用 Shizuku 或 Bugjaeger）

1. 在手机上安装支持 adb 环境的工具（如开启无线调试的 Bugjaeger，或配合 Shizuku 的 Logcat 查看器）。
2. 设定过滤关键词为 `kototoro`、`Media3Player` 或 `MediaCodec`。
3. 保持后台抓取，复现 Bug。
4. **将捕获的日志保存为 `.txt` 文件上传**（请勿直接截图两三行日志，缺少上下文会导致无法排查）。

---

感谢您对帮助 Kototoro 变得更好所付出的努力！
