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
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import org.skepsun.kototoro.reader.core.ZoomMode
import org.skepsun.kototoro.reader.domain.TapGridArea
import org.skepsun.kototoro.reader.ui.tapgrid.ReaderTapGridConfigGrid
import org.skepsun.kototoro.reader.ui.tapgrid.TapAction
import org.skepsun.kototoro.reader.ui.tapgrid.TapActions
import org.skepsun.kototoro.reader.ui.tapgrid.TapGridConfig

internal enum class DesktopReaderPanel {
    CHAPTERS,
    BOOKMARKS,
    OPTIONS,
    MORE;

    /** The panel heading; resolved per call so an already-open panel follows a language switch. */
    val title: String
        get() = when (this) {
            CHAPTERS -> desktopText("reader.panel.chapters")
            BOOKMARKS -> desktopText("reader.panel.bookmarks")
            OPTIONS -> desktopText("reader.panel.options")
            MORE -> desktopText("reader.panel.more")
        }
}

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
                TextButton(onClose, modifier = Modifier.testTag("reader-panel-close")) {
                    Text(desktopText("common.close"))
                }
            }
            if (panel == DesktopReaderPanel.BOOKMARKS) {
                Text(desktopText("reader.bookmarksCount", state.bookmarks.size),
                    color = MaterialTheme.colors.onSurface.copy(alpha = .7f))
                if (state.bookmarks.isEmpty()) Text(desktopText("reader.bookmarksEmpty"), fontSize = 13.sp)
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.bookmarks, key = { it.pageId }) { bookmark ->
                        val chapter = state.content?.chapters?.firstOrNull { it.id == bookmark.chapterId }
                        val current = state.chapter?.id == bookmark.chapterId && state.pageIndex == bookmark.page
                        Surface(color = if (current) MaterialTheme.colors.primary.copy(alpha = .2f)
                            else MaterialTheme.colors.surface, shape = RoundedCornerShape(10.dp)) {
                            Column(Modifier.fillMaxWidth().testTag("reader-bookmark:${bookmark.pageId}")
                                .clickable(enabled = enabled) { controller.openBookmark(bookmark); onClose() }
                                .padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(chapter?.title ?: desktopText("reader.chapterMissing"), maxLines = 2,
                                    overflow = TextOverflow.Ellipsis)
                                Text(desktopText("common.pageOf", bookmark.page + 1, (bookmark.percent * 100).toInt()),
                                    color = MaterialTheme.colors.primary, fontSize = 12.sp)
                                if (current) Text(desktopText("reader.currentPosition"), fontSize = 11.sp)
                            }
                        }
                    }
                }
            } else Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val settings = state.readerSettings
                // Android keeps these in its reader settings, opened from the options panel's settings button.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(desktopText("reader.autoAcrossChapters"), modifier = Modifier.weight(1f))
                    Switch(settings.automaticChapter, { controller.readerSettings(settings.copy(automaticChapter = it)) },
                        enabled = enabled, modifier = Modifier.testTag("reader-auto-chapter"))
                }
                OutlinedButton({ controller.reloadPage() }, enabled = enabled,
                    modifier = Modifier.testTag("reader-reload")) { Text(desktopText("reader.reload")) }
                Divider()
                var speed by remember(settings.autoScrollSpeed) { mutableFloatStateOf(settings.autoScrollSpeed) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(desktopText("reader.autoScrollSpeed"), fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f))
                    Text("×${"%.1f".format(org.skepsun.kototoro.reader.core.ReaderAutoScroll.speedMultiplier(speed))}",
                        color = MaterialTheme.colors.primary)
                }
                Slider(speed, { speed = it }, valueRange = 0f..1f, enabled = enabled,
                    onValueChangeFinished = { controller.readerSettings(settings.copy(autoScrollSpeed = speed)) },
                    modifier = Modifier.fillMaxWidth().testTag("reader-option-autoscroll-speed"))
                Divider()
                TapGridOptions(controller, settings, enabled)
                Divider()
                SuperResolutionOptions(controller, settings, enabled)
                Divider()
                Text(desktopText("reader.shortcuts"), fontWeight = FontWeight.SemiBold)
                Text(desktopText("reader.shortcutsHelp"), fontSize = 13.sp,
                    color = MaterialTheme.colors.onSurface.copy(alpha = .7f))
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

/**
 * Android's reader actions: the shared tap-grid configuration grid. A click edits an area's tap action, a long press or
 * right click its long-tap action, chosen in the list below the grid.
 */
@Composable
private fun TapGridOptions(controller: DesktopController, settings: DesktopReaderSettings, enabled: Boolean) {
    var selector by remember { mutableStateOf<Pair<TapGridArea, Boolean>?>(null) }
    fun update(next: Map<TapGridArea, TapActions>) = controller.readerSettings(settings.copy(tapGrid = next))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(AndroidStrings["reader_actions"], fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        TextButton({ selector = null; update(TapGridConfig.defaults) },
            enabled = enabled && settings.tapGrid != TapGridConfig.defaults,
            modifier = Modifier.testTag("reader-option-tap-reset")) { Text(AndroidStrings["reset"]) }
        TextButton({ selector = null; update(TapGridConfig.disabled) },
            enabled = enabled && settings.tapGrid != TapGridConfig.disabled,
            modifier = Modifier.testTag("reader-option-tap-disable")) { Text(AndroidStrings["disable_all"]) }
    }
    Text(desktopText("reader.tapGridHint"), fontSize = 12.sp,
        color = MaterialTheme.colors.onSurface.copy(alpha = .7f))
    val onSurface = MaterialTheme.colors.onSurface
    ReaderTapGridConfigGrid(settings.tapGrid, AndroidStrings["tap_action"], AndroidStrings["long_tap_action"],
        { tapActionTitle(it) },
        onTap = { area -> if (enabled) selector = area to false },
        onLongTap = { area -> if (enabled) selector = area to true },
        modifier = Modifier.fillMaxWidth().height(300.dp).testTag("reader-option-tap-grid"),
        dividerColor = onSurface.copy(alpha = .3f),
        textStyle = androidx.compose.ui.text.TextStyle(color = onSurface, fontSize = 11.sp, lineHeight = 14.sp),
        cellModifier = { area ->
            Modifier.testTag("reader-option-tap:${area.name}").pointerInput(area, enabled) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (enabled && event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                            selector = area to true
                            event.changes.forEach { it.consume() }
                        }
                    }
                }
            }
        })
    val (area, long) = selector ?: return
    val current = TapGridConfig.action(settings.tapGrid, area, long)
    Text("${tapAreaTitle(area)} · ${AndroidStrings[if (long) "long_tap_action" else "tap_action"]}", fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp, modifier = Modifier.testTag("reader-option-tap-selector"))
    (listOf(null) + TapAction.entries).forEach { action ->
        RadioOption(tapActionTitle(action), action == current, enabled, "reader-option-tap-action:${action?.name ?: "NONE"}") {
            update(TapGridConfig.with(settings.tapGrid, area, long, action))
            selector = null
        }
    }
}

/** Android's names of the reader actions. */
internal fun tapActionTitle(action: TapAction?): String = AndroidStrings[when (action) {
    TapAction.PAGE_NEXT -> "next_page"
    TapAction.PAGE_PREV -> "prev_page"
    TapAction.CHAPTER_NEXT -> "next_chapter"
    TapAction.CHAPTER_PREV -> "prev_chapter"
    TapAction.TOGGLE_UI -> "toggle_ui"
    TapAction.SHOW_MENU -> "show_menu"
    null -> "none"
}]

private fun tapAreaTitle(area: TapGridArea): String = when (area) {
    TapGridArea.TOP_LEFT -> desktopText("reader.tapArea.topLeft")
    TapGridArea.TOP_CENTER -> desktopText("reader.tapArea.topCenter")
    TapGridArea.TOP_RIGHT -> desktopText("reader.tapArea.topRight")
    TapGridArea.CENTER_LEFT -> desktopText("reader.tapArea.centerLeft")
    TapGridArea.CENTER -> desktopText("reader.tapArea.center")
    TapGridArea.CENTER_RIGHT -> desktopText("reader.tapArea.centerRight")
    TapGridArea.BOTTOM_LEFT -> desktopText("reader.tapArea.bottomLeft")
    TapGridArea.BOTTOM_CENTER -> desktopText("reader.tapArea.bottomCenter")
    TapGridArea.BOTTOM_RIGHT -> desktopText("reader.tapArea.bottomRight")
}

/** Android's names for its page-turn animations. */
internal fun animationTitle(animation: org.skepsun.kototoro.core.prefs.ReaderAnimation): String = when (animation) {
    org.skepsun.kototoro.core.prefs.ReaderAnimation.NONE -> desktopText("reader.animation.none")
    org.skepsun.kototoro.core.prefs.ReaderAnimation.DEFAULT -> desktopText("reader.animation.default")
    org.skepsun.kototoro.core.prefs.ReaderAnimation.ADVANCED -> desktopText("reader.animation.advanced")
    org.skepsun.kototoro.core.prefs.ReaderAnimation.SIMULATION -> desktopText("reader.animation.simulation")
}

internal fun modeTitle(mode: DesktopReaderMode): String = when (mode) {
    DesktopReaderMode.SINGLE -> desktopText("reader.mode.single")
    DesktopReaderMode.DOUBLE -> desktopText("reader.mode.double")
    DesktopReaderMode.CONTINUOUS -> desktopText("reader.mode.continuous")
}

/**
 * Page super-resolution, as Android's reader offers it: RealCUGAN 2x (with noise levels) or Real-ESRGAN models, run by
 * the official ncnn-vulkan programs on the GPU. A model needs its program, which is downloaded (or picked) once.
 */

/**
 * The model's name in the interface language. `DesktopUpscaleModel.title` stays English because desktop-runtime has
 * no catalogs; only the models whose Android name is not already English need a key.
 */
private fun desktopUpscaleModelName(model: DesktopUpscaleModel): String = when (model) {
    DesktopUpscaleModel.REALESRGAN_4X_ANIME -> desktopText("upscale.model.REALESRGAN_4X_ANIME")
    DesktopUpscaleModel.REALESR_ANIMEVIDEO_2X -> desktopText("upscale.model.REALESR_ANIMEVIDEO_2X")
    DesktopUpscaleModel.ANIME4K_C -> desktopText("upscale.model.ANIME4K_C")
    DesktopUpscaleModel.ANIME4K_CA -> desktopText("upscale.model.ANIME4K_CA")
    else -> model.title
}
@Composable
private fun SuperResolutionOptions(controller: DesktopController, settings: DesktopReaderSettings, enabled: Boolean) {
    val upscale = settings.upscale
    val error by controller.session.upscaleError.collectAsState()
    Text(desktopText("reader.upscale"), fontWeight = FontWeight.SemiBold)
    RadioOption(desktopText("reader.upscaleOff"), upscale.model == null, enabled, "reader-option-upscale:OFF") {
        controller.readerSettings(settings.copy(upscale = upscale.copy(model = null)))
    }
    DesktopUpscaleModel.entries.forEach { model ->
        RadioOption(desktopUpscaleModelName(model), upscale.model == model, enabled, "reader-option-upscale:${model.name}") {
            controller.readerSettings(settings.copy(upscale = upscale.copy(model = model)))
        }
    }
    val model = upscale.model ?: return
    if (model == DesktopUpscaleModel.REALCUGAN_2X) {
        Text(desktopText("reader.denoise"), fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(
                -1 to desktopText("reader.denoise.conservative"),
                0 to desktopText("reader.denoise.none"),
                1 to "1x",
                2 to "2x",
                3 to "3x",
            ).forEach { (level, title) ->
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
        Text(if (installed) desktopText("reader.upscaleAnime4kReady")
            else desktopText("reader.upscaleAnime4kMissing"), fontSize = 12.sp,
            color = MaterialTheme.colors.onSurface.copy(alpha = .7f))
    } else if (installed) {
        Text(desktopText("reader.upscaleToolInstalled", tool.title), fontSize = 12.sp,
            color = MaterialTheme.colors.onSurface.copy(alpha = .7f))
    } else {
        Text(desktopText("reader.upscaleToolNeeded", tool.title), fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ controller.installUpscaler(tool) }, enabled = enabled, modifier = Modifier.testTag("reader-upscaler-install")) {
                Text(desktopText("reader.downloadAndInstall"))
            }
            OutlinedButton({
                val chooser = javax.swing.JFileChooser().apply {
                    fileFilter = javax.swing.filechooser.FileNameExtensionFilter(tool.asset, "zip")
                }
                if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
                    controller.installUpscalerArchive(tool, chooser.selectedFile.toPath())
                }
            }, enabled = enabled) { Text(desktopText("reader.chooseArchive")) }
        }
    }
    error?.let { Text(desktopText("reader.upscaleFailed", it), fontSize = 12.sp,
        color = MaterialTheme.colors.error) }
}
