package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.skepsun.kototoro.desktop.runtime.DesktopUpscaleModel
import org.skepsun.kototoro.reader.core.ZoomMode

internal enum class DesktopReaderPanel(val title: String) { CHAPTERS("章节"), BOOKMARKS("书签"), OPTIONS("阅读设置") }

/** Same-window panels keep the reader scene mounted and route every change through its original controller. */
@Composable
internal fun DesktopReaderSidePanel(controller: DesktopController, state: DesktopAppState,
    panel: DesktopReaderPanel, enabled: Boolean, modifier: Modifier, onClose: () -> Unit) {
    Surface(modifier.width(340.dp).fillMaxHeight().testTag("reader-panel"), shape = RoundedCornerShape(16.dp),
        elevation = 12.dp) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(panel.title, fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClose, modifier = Modifier.testTag("reader-panel-close")) { Text("关闭") }
            }
            if (panel == DesktopReaderPanel.BOOKMARKS) {
                Text("${state.bookmarks.size} 个书签", color = MaterialTheme.colors.onSurface.copy(alpha = .7f))
                if (state.bookmarks.isEmpty()) Text("在底部点击“添加书签”，保存当前阅读位置。", fontSize = 13.sp)
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.bookmarks, key = { it.pageId }) { bookmark ->
                        val chapter = state.content?.chapters?.firstOrNull { it.id == bookmark.chapterId }
                        val current = state.chapter?.id == bookmark.chapterId && state.pageIndex == bookmark.page
                        Surface(color = if (current) MaterialTheme.colors.primary.copy(alpha = .2f)
                            else MaterialTheme.colors.surface, shape = RoundedCornerShape(10.dp)) {
                            Column(Modifier.fillMaxWidth().testTag("reader-bookmark:${bookmark.pageId}")
                                .clickable(enabled = enabled) { controller.openBookmark(bookmark); onClose() }
                                .padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(chapter?.title ?: "章节已不存在", maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text("第 ${bookmark.page + 1} 页 · ${(bookmark.percent * 100).toInt()}%",
                                    color = MaterialTheme.colors.primary, fontSize = 12.sp)
                                if (current) Text("当前阅读位置", fontSize = 11.sp)
                            }
                        }
                    }
                }
            } else if (panel == DesktopReaderPanel.CHAPTERS) {
                var query by remember { mutableStateOf("") }
                var reversed by remember { mutableStateOf(false) }
                OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text("搜索章节") },
                    modifier = Modifier.fillMaxWidth().testTag("reader-chapter-query"))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${state.content?.chapters?.size ?: 0} 章", modifier = Modifier.weight(1f))
                    TextButton({ reversed = !reversed }) { Text(if (reversed) "倒序" else "正序") }
                }
                val chapters = remember(state.content?.chapters, query, reversed) {
                    state.content?.chapters.orEmpty().filter { it.title.orEmpty().contains(query, true) }
                        .let { if (reversed) it.asReversed() else it }
                }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(chapters, key = { it.id }) { chapter ->
                        val selected = chapter.id == state.chapter?.id
                        Surface(color = if (selected) MaterialTheme.colors.primary.copy(alpha = .2f)
                            else MaterialTheme.colors.surface, shape = RoundedCornerShape(10.dp)) {
                            Column(Modifier.fillMaxWidth().testTag("reader-chapter:${chapter.id}")
                                .clickable(enabled = enabled) { controller.read(chapter); onClose() }.padding(12.dp)) {
                                Text(chapter.title ?: "第 ${chapter.number} 章", maxLines = 2,
                                    overflow = TextOverflow.Ellipsis)
                                if (selected) Text("正在阅读", fontSize = 11.sp, color = MaterialTheme.colors.primary)
                            }
                        }
                    }
                }
            } else Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val settings = state.readerSettings
                Text("阅读方式", fontWeight = FontWeight.SemiBold)
                DesktopReaderMode.entries.forEach { mode ->
                    RadioOption(modeTitle(mode), mode == settings.mode, enabled, "reader-option-mode:${mode.name}") {
                        controller.readerSettings(settings.copy(mode = mode))
                    }
                }
                Divider()
                Text("页面适配", fontWeight = FontWeight.SemiBold)
                ZoomMode.entries.forEach { mode ->
                    RadioOption(fitTitle(mode), mode == settings.fitMode,
                        enabled && settings.mode != DesktopReaderMode.CONTINUOUS, "reader-option-fit:${mode.name}") {
                        controller.readerSettings(settings.copy(fitMode = mode))
                    }
                }
                Divider()
                SuperResolutionOptions(controller, settings, enabled)
                Divider()
                Text("快捷键", fontWeight = FontWeight.SemiBold)
                Text("← / → 翻页\nPageUp / PageDown 翻页或滚动\nHome / End 首末页\nB 添加或移除书签\n" +
                    "Ctrl + 滚轮 缩放\nH 收起或显示工具栏\nF11 全屏\nEsc 关闭面板或返回详情",
                    fontSize = 13.sp, color = MaterialTheme.colors.onSurface.copy(alpha = .7f))
            }
        }
    }
}

@Composable
private fun RadioOption(title: String, selected: Boolean, enabled: Boolean, tag: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().testTag(tag).clickable(enabled = enabled, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = null, enabled = enabled)
        Text(title, modifier = Modifier.padding(start = 12.dp))
    }
}

internal fun modeTitle(mode: DesktopReaderMode): String = when (mode) {
    DesktopReaderMode.SINGLE -> "单页"
    DesktopReaderMode.DOUBLE -> "双页"
    DesktopReaderMode.CONTINUOUS -> "连续滚动"
}

/**
 * Page super-resolution, as Android's reader offers it: RealCUGAN 2x (with noise levels) or Real-ESRGAN models, run by
 * the official ncnn-vulkan programs on the GPU. A model needs its program, which is downloaded (or picked) once.
 */
@Composable
private fun SuperResolutionOptions(controller: DesktopController, settings: DesktopReaderSettings, enabled: Boolean) {
    val upscale = settings.upscale
    val error by controller.session.upscaleError.collectAsState()
    Text("超分辨率", fontWeight = FontWeight.SemiBold)
    RadioOption("关闭", upscale.model == null, enabled, "reader-option-upscale:OFF") {
        controller.readerSettings(settings.copy(upscale = upscale.copy(model = null)))
    }
    DesktopUpscaleModel.entries.forEach { model ->
        RadioOption(model.title, upscale.model == model, enabled, "reader-option-upscale:${model.name}") {
            controller.readerSettings(settings.copy(upscale = upscale.copy(model = model)))
        }
    }
    val model = upscale.model ?: return
    if (model == DesktopUpscaleModel.REALCUGAN_2X) {
        Text("降噪", fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(-1 to "保守", 0 to "无", 1 to "1x", 2 to "2x", 3 to "3x").forEach { (level, title) ->
                val selected = upscale.noise == level
                OutlinedButton({ controller.readerSettings(settings.copy(upscale = upscale.copy(noise = level))) },
                    enabled = enabled && !selected, contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.testTag("reader-option-noise:$level")) {
                    Text(if (selected) "✓$title" else title, fontSize = 12.sp)
                }
            }
        }
    }
    val installed = remember(model, enabled) { controller.session.superResolution.available(model) }
    val tool = model.tool
    if (tool == null) {
        Text(if (installed) "Anime4K 在显卡上离屏处理页面，与视频画质增强使用同一套着色器。"
            else "Anime4K 需要 libmpv（与视频播放相同）：把 libmpv-2.dll 放到数据目录的 mpv 文件夹。", fontSize = 12.sp,
            color = MaterialTheme.colors.onSurface.copy(alpha = .7f))
    } else if (installed) {
        Text("已安装 ${tool.title}。页面在显卡上处理，首次处理每页需要数秒，结果会缓存。", fontSize = 12.sp,
            color = MaterialTheme.colors.onSurface.copy(alpha = .7f))
    } else {
        Text("需要 ${tool.title} 的官方 Windows 程序（约 45 MB）。", fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ controller.installUpscaler(tool) }, enabled = enabled, modifier = Modifier.testTag("reader-upscaler-install")) {
                Text("下载安装")
            }
            OutlinedButton({
                val chooser = javax.swing.JFileChooser().apply {
                    fileFilter = javax.swing.filechooser.FileNameExtensionFilter(tool.asset, "zip")
                }
                if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
                    controller.installUpscalerArchive(tool, chooser.selectedFile.toPath())
                }
            }, enabled = enabled) { Text("选择压缩包") }
        }
    }
    error?.let { Text("超分失败，已显示原图：$it", fontSize = 12.sp, color = MaterialTheme.colors.error) }
}