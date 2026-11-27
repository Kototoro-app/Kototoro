package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.skepsun.kototoro.reader.novel.compose.TranslationTextEditorDialog
import org.skepsun.kototoro.reader.ui.compose.ReaderOptionsPageList
import org.skepsun.kototoro.reader.ui.compose.design.ReaderOptionGroup
import org.skepsun.kototoro.reader.ui.compose.design.ReaderOptionSwitchRow

@Composable
internal fun DesktopMangaTranslationOptions(
    controller: DesktopController,
    state: DesktopAppState,
    showTranslation: Boolean,
    onShowTranslation: (Boolean) -> Unit,
) {
    val settings by controller.novelTranslation.settings.collectAsState()
    val translations by controller.mangaTranslation.pages.collectAsState()
    val scope = rememberCoroutineScope()
    val bookId = state.content?.id
    val page = state.pages.getOrNull(state.pageIndex)?.let { state.readerImages[it.id] }
    val translated = page?.let { translations[it.path] }?.takeIf { it.target == settings.target }
    var busy by remember { mutableStateOf(false) }
    var configure by remember { mutableStateOf(false) }
    var configureOcr by remember { mutableStateOf(false) }
    var selecting by remember(page?.path) { mutableStateOf<Int?>(null) }
    var error by remember(page?.path) { mutableStateOf<String?>(null) }

    fun execute(operation: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                operation()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.localizedMessage ?: AndroidStrings["desktop_translation_invalid_response"]
            } finally {
                busy = false
            }
        }
    }

    ReaderOptionsPageList {
        item {
            ReaderOptionGroup(title = AndroidStrings["novel_reader_translation_section"]) {
                ReaderOptionSwitchRow(
                    label = AndroidStrings["novel_reader_translation_enabled"],
                    checked = showTranslation,
                    onCheckedChange = onShowTranslation,
                )
                FlowRow(Modifier.padding(horizontal = 12.dp)) {
                    TextButton(enabled = !busy, onClick = { configure = true }) {
                        Text(AndroidStrings["desktop_translation_settings"])
                    }
                    TextButton(enabled = !busy, onClick = { configureOcr = true }) {
                        Text(AndroidStrings["desktop_ocr_settings"])
                    }
                    TextButton(enabled = page != null && !busy, onClick = {
                        val source = page ?: return@TextButton
                        if (settings.isConfigured) {
                            execute {
                                controller.mangaTranslation.translateLocally(source, settings, settings.localOcr, bookId = bookId)
                                onShowTranslation(true)
                            }
                        } else {
                            configure = true
                        }
                    }) { Text(AndroidStrings["desktop_ocr_translate_local"]) }
                    TextButton(enabled = page != null && !busy, onClick = {
                        val source = page ?: return@TextButton
                        if (settings.isConfigured) {
                            execute { controller.mangaTranslation.translate(source, settings, bookId = bookId); onShowTranslation(true) }
                        } else {
                            configure = true
                        }
                    }) { Text(AndroidStrings["desktop_translation_manga_translate"]) }
                }
                Text(AndroidStrings["desktop_translation_manga_model_help"], Modifier.padding(12.dp))
                if (busy) Text(AndroidStrings["translating"], Modifier.padding(12.dp))
                error?.let { Text(it, Modifier.padding(12.dp)) }
            }
        }
        translated?.bubbles?.forEachIndexed { index, bubble ->
            item(key = index) {
                TextButton(enabled = !busy, onClick = { selecting = index }) {
                    Text("${index + 1}. ${bubble.text}", Modifier.padding(horizontal = 12.dp))
                }
            }
        }
    }
    if (configure) DesktopTranslationSettingsDialog(settings, onDismiss = { configure = false }) { next ->
        execute { controller.novelTranslation.configure(next) }
        configure = false
    }
    if (configureOcr) DesktopLocalOcrDialog(settings.localOcr, onDismiss = { configureOcr = false }) { next ->
        execute { controller.novelTranslation.configureLocalOcr(next) }
        configureOcr = false
    }
    val index = selecting
    val bubble = index?.let { translated?.bubbles?.getOrNull(it) }
    if (page != null && index != null && bubble != null) {
        var draft by remember(index, bubble) { mutableStateOf(bubble.text) }
        TranslationTextEditorDialog(
            title = AndroidStrings["reader_translation_edit_bubble"],
            value = draft,
            onValueChange = { draft = it },
            saveLabel = AndroidStrings["reader_translation_retranslate_bubble"],
            dismissLabel = AndroidStrings["cancel"],
            canSave = draft.isNotBlank() && !busy,
            header = { Text(translated?.translations?.get(index.toString()).orEmpty()) },
            onSave = {
                execute {
                    controller.mangaTranslation.retranslate(page, settings, index, draft, bookId = bookId)
                    onShowTranslation(true)
                }
                selecting = null
            },
            onDismiss = { selecting = null },
        )
    }
}


@Composable
private fun DesktopLocalOcrDialog(
    initial: DesktopLocalOcrSettings,
    onDismiss: () -> Unit,
    onSave: (DesktopLocalOcrSettings) -> Unit,
) {
    var draft by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(AndroidStrings["desktop_ocr_settings"]) },
        text = {
            Column {
                Text(AndroidStrings["desktop_ocr_help"])
                OutlinedTextField(
                    value = draft.modelDirectory,
                    onValueChange = { draft = draft.copy(modelDirectory = it) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(AndroidStrings["desktop_ocr_model_folder"]) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(draft.copy(modelDirectory = draft.modelDirectory.trim())) }) {
                Text(AndroidStrings["save"])
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(AndroidStrings["cancel"]) } },
    )
}
