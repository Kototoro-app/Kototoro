package org.skepsun.kototoro.desktop.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import org.skepsun.kototoro.core.prefs.ReaderMode
import org.skepsun.kototoro.desktop.runtime.DesktopUpscaleModel
import org.skepsun.kototoro.reader.domain.ReaderColorFilter
import org.skepsun.kototoro.reader.ui.colorfilter.ReaderColorCorrectionLabels
import org.skepsun.kototoro.reader.ui.compose.ComposeReaderOptionsCallbacks
import org.skepsun.kototoro.reader.ui.compose.ComposeReaderOptionsState
import org.skepsun.kototoro.reader.ui.compose.ReaderDisplayOptionsPage
import org.skepsun.kototoro.reader.ui.compose.ReaderLayoutOptionsPage
import org.skepsun.kototoro.reader.ui.compose.ReaderMangaQuickLayer
import org.skepsun.kototoro.reader.ui.compose.ReaderOptionsDetailTabs
import org.skepsun.kototoro.reader.ui.compose.ReaderOptionsFeatures
import org.skepsun.kototoro.reader.ui.compose.ReaderOptionsStrings
import org.skepsun.kototoro.reader.ui.compose.ReaderOptionsTab
import org.skepsun.kototoro.reader.ui.compose.design.ReaderPanelIcons
import org.skepsun.kototoro.reader.ui.compose.design.ReaderQuickAction
import org.skepsun.kototoro.reader.ui.compose.panel.ReaderOptionsPanelHost
import org.skepsun.kototoro.reader.ui.compose.panel.ReaderPanelSurfaceMode
import org.skepsun.kototoro.reader.ui.compose.rememberMangaReaderPanelColors

private const val COLOR_SAVE_DELAY_MS = 250L

/** The quick actions the Windows reader offers, with Android's ids, icons and labels. */
internal enum class DesktopQuickAction(val label: String) {
    CHAPTERS("chapters_and_pages"), BOOKMARK("bookmark_add"), AUTO_SCROLL("automatic_scroll");

    val icon get() = when (this) {
        CHAPTERS -> ReaderPanelIcons.Grid
        BOOKMARK -> ReaderPanelIcons.Bookmark
        AUTO_SCROLL -> ReaderPanelIcons.Timer
    }
}

/** Android's reader options the Windows engine supports; the rest of the panel's rows are hidden. */
internal val DesktopReaderOptionsFeatures = ReaderOptionsFeatures(
    modes = listOf(ReaderMode.STANDARD, ReaderMode.REVERSED, ReaderMode.VERTICAL, ReaderMode.WEBTOON),
    doublePageFoldable = false,
    doublePageSensitivity = false,
    splitPages = false,
    chapterTitleAtBottom = false,
    sceneRenderers = false,
    performance = false,
    // Colour correction applies to every work and is saved as it changes.
    saveColorFilter = false,
    saveColorFilterForManga = false,
)

/**
 * Android's reader options panel (the shared quick layer and the layout / display tabs) over the Windows reader.
 * Settings Android keeps elsewhere — super-resolution models, reader actions, auto-scroll speed — open from the
 * panel's settings button, as Android's opens the reader settings.
 */
@Composable
internal fun DesktopReaderOptionsPanel(
    controller: DesktopController,
    state: DesktopAppState,
    fullscreen: Boolean,
    onToggleFullscreen: (() -> Unit)?,
    autoScroll: DesktopReaderAutoScroll,
    onDismiss: () -> Unit,
    onOpenChapters: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val settings = state.readerSettings
    val strings = remember { desktopReaderOptionsStrings() }
    val features = remember(onToggleFullscreen) {
        DesktopReaderOptionsFeatures.copy(fullscreen = onToggleFullscreen != null)
    }
    // Sliders report every step; colour edits show at once and are saved once they settle.
    var colorDraft by remember { mutableStateOf<DesktopReaderColorFilter?>(null) }
    LaunchedEffect(colorDraft) {
        val draft = colorDraft ?: return@LaunchedEffect
        delay(COLOR_SAVE_DELAY_MS)
        controller.readerSettings(controller.state.value.readerSettings.copy(colorFilter = draft)).join()
        colorDraft = null
    }
    val options = settings.copy(colorFilter = colorDraft ?: settings.colorFilter).toOptionsState(fullscreen)
    fun update(next: DesktopReaderSettings) = controller.readerSettings(next)
    val callbacks = ComposeReaderOptionsCallbacks(
        onDismiss = onDismiss,
        onModeChanged = { update(settings.withReaderMode(it)) },
        onAnimationChanged = { update(settings.copy(animation = it)) },
        onZoomModeChanged = { update(settings.copy(fitMode = it)) },
        onDoublePageChanged = { update(settings.copy(mode = if (it) DesktopReaderMode.DOUBLE else DesktopReaderMode.SINGLE)) },
        onDoublePageCoverChanged = { update(settings.copy(doublePageCover = it)) },
        onFullscreenChanged = { if (it != fullscreen) onToggleFullscreen?.invoke() },
        onPageNumbersChanged = { update(settings.copy(pageNumbers = it)) },
        onCropPagesChanged = {
            update(if (settings.mode == DesktopReaderMode.CONTINUOUS) settings.copy(cropContinuous = it)
                else settings.copy(cropPaged = it))
        },
        onSuperResolutionChanged = { enabled ->
            update(settings.copy(upscale = settings.upscale.copy(
                model = if (enabled) settings.upscale.model ?: DesktopUpscaleModel.entries.first() else null)))
        },
        onBackgroundChanged = { update(settings.copy(background = DesktopReaderBackground.valueOf(it.name))) },
        onColorFilterChanged = { colorDraft = it.toDesktop() },
        onOpenSettings = onOpenSettings,
    )
    val actions = remember { DesktopQuickAction.entries.map { ReaderQuickAction(it.name) } }
    ReaderOptionsPanelHost(
        colors = rememberMangaReaderPanelColors(settings.background.toShared()),
        surfaceMode = ReaderPanelSurfaceMode.Opaque,
        onDismissRequest = onDismiss,
        // A desktop window is a landscape tablet: the tabs are in view without dragging the sheet up.
        openExpanded = true,
        quickLayer = {
            ReaderMangaQuickLayer(
                state = options,
                callbacks = callbacks,
                strings = strings,
                features = features,
                actions = actions,
                actionIcon = { rememberVectorPainter(DesktopQuickAction.valueOf(it.id).icon) },
                actionLabel = { AndroidStrings[DesktopQuickAction.valueOf(it.id).label] },
                onAction = { action ->
                    onDismiss()
                    when (DesktopQuickAction.valueOf(action.id)) {
                        DesktopQuickAction.CHAPTERS -> onOpenChapters()
                        DesktopQuickAction.BOOKMARK -> controller.toggleBookmark()
                        DesktopQuickAction.AUTO_SCROLL -> autoScroll.active = !autoScroll.active
                    }
                },
            )
        },
        details = { dragModifier ->
            ReaderOptionsDetailTabs(
                tabs = listOf(
                    ReaderOptionsTab(strings.tabLayout) { ReaderLayoutOptionsPage(options, callbacks, strings, features) },
                    ReaderOptionsTab(strings.tabDisplay) { ReaderDisplayOptionsPage(options, callbacks, strings, features) },
                ),
                settingsDescription = strings.settings,
                onOpenSettings = onOpenSettings,
                dragModifier = dragModifier,
            )
        },
    )
}

/** The desktop settings as Android's panel state. */
internal fun DesktopReaderSettings.toOptionsState(fullscreen: Boolean) = ComposeReaderOptionsState(
    visible = true,
    mode = readerMode,
    animation = animation,
    zoomMode = fitMode,
    doublePage = mode == DesktopReaderMode.DOUBLE,
    doublePageCover = doublePageCover,
    fullscreen = fullscreen,
    pageNumbers = pageNumbers,
    cropPages = cropActive,
    superResolution = upscale.model != null,
    colorFilter = colorFilter.toShared(),
    background = background.toShared(),
)

/** Android's reading mode of the desktop settings: continuous is the webtoon mode. */
internal val DesktopReaderSettings.readerMode: ReaderMode get() = when {
    mode == DesktopReaderMode.CONTINUOUS -> ReaderMode.WEBTOON
    vertical -> ReaderMode.VERTICAL
    rightToLeft -> ReaderMode.REVERSED
    else -> ReaderMode.STANDARD
}

internal fun DesktopReaderSettings.withReaderMode(target: ReaderMode): DesktopReaderSettings {
    val paged = if (mode == DesktopReaderMode.CONTINUOUS) DesktopReaderMode.SINGLE else mode
    return when (target) {
        ReaderMode.STANDARD -> copy(mode = paged, rightToLeft = false, vertical = false)
        ReaderMode.REVERSED -> copy(mode = paged, rightToLeft = true, vertical = false)
        ReaderMode.VERTICAL -> copy(mode = paged, rightToLeft = false, vertical = true)
        ReaderMode.WEBTOON, ReaderMode.CONTINUOUS_HORIZONTAL -> copy(mode = DesktopReaderMode.CONTINUOUS)
    }
}

internal fun DesktopReaderColorFilter.toShared(): ReaderColorFilter? =
    ReaderColorFilter(brightness, contrast, inverted, grayscale, book).takeUnless { it.isEmpty }

internal fun ReaderColorFilter?.toDesktop(): DesktopReaderColorFilter = this?.let {
    DesktopReaderColorFilter(it.brightness, it.contrast, it.isInverted, it.isGrayscale, it.isBookBackground)
} ?: DesktopReaderColorFilter()

internal fun DesktopReaderBackground.toShared() = org.skepsun.kototoro.core.prefs.ReaderBackground.valueOf(name)

/** Android's text for the shared reader options. */
internal fun desktopReaderOptionsStrings() = ReaderOptionsStrings(
    modeStandard = AndroidStrings["standard"],
    modeRightToLeft = AndroidStrings["right_to_left"],
    modeVertical = AndroidStrings["vertical"],
    modeWebtoon = AndroidStrings["webtoon"],
    modeContinuousHorizontal = AndroidStrings["continuous_horizontal"],
    tabLayout = AndroidStrings["reader_more_tab_layout"],
    tabDisplay = AndroidStrings["reader_more_tab_display"],
    settings = AndroidStrings["settings"],
    continuousHorizontalReversed = AndroidStrings["continuous_horizontal_reversed"],
    pagesAnimation = AndroidStrings["pages_animation"],
    animations = AndroidStrings.array("reader_animation"),
    scaleMode = AndroidStrings["scale_mode"],
    zoomModes = AndroidStrings.array("zoom_modes"),
    sectionTwoPages = AndroidStrings["reader_panel_section_two_pages"],
    doublePageLandscape = AndroidStrings["double_page_landscape"],
    doublePageFoldable = AndroidStrings["double_page_foldable"],
    doublePageCoverPage = AndroidStrings["double_page_cover_page"],
    twoPageScrollSensitivity = AndroidStrings["two_page_scroll_sensitivity"],
    cropPages = AndroidStrings["crop_pages"],
    splitDoublePages = AndroidStrings["split_double_pages"],
    fullscreenMode = AndroidStrings["fullscreen_mode"],
    showPagesNumbers = AndroidStrings["show_pages_numbers"],
    chapterTitleAtBottom = AndroidStrings["reader_chapter_title_at_bottom"],
    sectionRenderer = AndroidStrings["reader_panel_section_renderer"],
    sceneRendererWebtoon = AndroidStrings["reader_scene_renderer_webtoon"],
    sceneRendererPaged = AndroidStrings["reader_scene_renderer_paged"],
    sectionPerformance = AndroidStrings["reader_panel_section_performance"],
    optimize = AndroidStrings["reader_optimize"],
    reducePagePreloading = AndroidStrings["reader_reduce_page_preloading"],
    background = AndroidStrings["background"],
    backgrounds = AndroidStrings.array("reader_backgrounds"),
    save = AndroidStrings["save"],
    globally = AndroidStrings["globally"],
    thisManga = AndroidStrings["this_manga"],
    superResolution = AndroidStrings["reader_super_resolution"],
    imageServer = AndroidStrings["image_server"],
    automatic = AndroidStrings["automatic"],
    colorCorrection = ReaderColorCorrectionLabels(
        title = AndroidStrings["color_correction"],
        reset = AndroidStrings["reset"],
        invert = AndroidStrings["invert_colors"],
        grayscale = AndroidStrings["grayscale"],
        brightness = AndroidStrings["brightness"],
        contrast = AndroidStrings["contrast"],
        bookEffect = AndroidStrings["book_effect"],
    ),
)
