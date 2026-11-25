package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.core.source.SourceContent
import org.skepsun.kototoro.core.ui.downloads.QueuedTranslationChapter
import org.skepsun.kototoro.core.ui.downloads.TranslationQueueChaptersDialog
import org.skepsun.kototoro.reader.novel.compose.TranslationTextEditorDialog
import org.skepsun.kototoro.reader.ui.compose.ReaderOptionsPageList
import org.skepsun.kototoro.reader.ui.compose.design.ReaderOptionGroup
import org.skepsun.kototoro.reader.ui.compose.design.ReaderOptionSwitchRow
import java.nio.file.Files
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import javax.swing.JFileChooser

@Composable
internal fun DesktopNovelTranslationOptions(
    controller: DesktopController,
    content: SourceContent,
    novel: DesktopNovel,
    translation: DesktopChapterTranslation,
    paragraphs: List<DesktopTranslationParagraph>,
    showTranslation: Boolean,
    onShowTranslation: (Boolean) -> Unit,
    bilingual: Boolean,
    onBilingual: (Boolean) -> Unit,
) {
    val service = controller.novelTranslation
    val queue = controller.novelTranslationQueue
    val settings by service.settings.collectAsState()
    val tasks by queue.items.collectAsState()
    val paused by queue.paused.collectAsState()
    val scope = rememberCoroutineScope()
    val chapterId = novel.chapter.id
    val bookId = content.id
    val chapterTask = tasks.firstOrNull { it.key == DesktopTranslationQueue.Key(bookId, chapterId) }
    val waiting = tasks.filter { it.key.bookId == bookId && it.status == DesktopTranslationQueue.Status.QUEUED }
    val active = tasks.firstOrNull { it.key.bookId == bookId && it.status == DesktopTranslationQueue.Status.RUNNING }
    var configure by remember { mutableStateOf(false) }
    var editChapter by remember(bookId, chapterId) { mutableStateOf(false) }
    var glossary by remember(bookId) { mutableStateOf<String?>(null) }
    var selectChapters by remember(bookId) { mutableStateOf(false) }
    var showQueue by remember(bookId) { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun runEdit(operation: suspend () -> Unit) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { operation() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.localizedMessage ?: AndroidStrings["desktop_translation_save_failed"]
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
                ReaderOptionSwitchRow(
                    label = AndroidStrings["novel_translation_bilingual"],
                    checked = bilingual,
                    onCheckedChange = onBilingual,
                )
                FlowRow(Modifier.padding(horizontal = 12.dp)) {
                    TextButton(onClick = { configure = true }) { Text(AndroidStrings["desktop_translation_settings"]) }
                    TextButton(onClick = {
                        if (settings.isConfigured) {
                            onShowTranslation(true)
                            queue.enqueue(content, listOf(novel.chapter))
                        } else configure = true
                    }) { Text(AndroidStrings["novel_reader_translation_start"]) }
                    if (translation.machine.isNotEmpty()) {
                        TextButton(
                            enabled = chapterTask?.status !in setOf(DesktopTranslationQueue.Status.RUNNING,
                                DesktopTranslationQueue.Status.QUEUED),
                            onClick = {
                                if (settings.isConfigured) queue.enqueue(content, listOf(novel.chapter), retranslate = true)
                                else configure = true
                            },
                        ) { Text(AndroidStrings["reader_translation_retranslate_current_chapter"]) }
                    }
                    TextButton(onClick = { editChapter = true }, enabled = translation.machine.isNotEmpty()) {
                        Text(AndroidStrings["novel_translation_edit_paragraphs"])
                    }
                    TextButton(onClick = {
                        scope.launch {
                            try {
                                glossary = withContext(Dispatchers.IO) {
                                    service.glossary(bookId).joinToString("\n") { "${it.original} = ${it.translation}" }
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                error = e.localizedMessage
                            }
                        }
                    }) { Text(AndroidStrings["novel_translation_edit_glossary"]) }
                }
            }
        }
        item {
            ReaderOptionGroup(title = AndroidStrings["desktop_translation_queue"]) {
                FlowRow(Modifier.padding(horizontal = 12.dp)) {
                    TextButton(onClick = {
                        if (settings.isConfigured) selectChapters = true else configure = true
                    }) { Text(AndroidStrings["desktop_translation_add_chapters"]) }
                    TextButton(onClick = { showQueue = true }) { Text(AndroidStrings["translation_queue_pending_chapters"]) }
                    TextButton(onClick = { queue.setPaused(!paused) }, enabled = paused || active != null || waiting.isNotEmpty()) {
                        Text(AndroidStrings[if (paused) "resume" else "pause"])
                    }
                    if (active != null) TextButton(onClick = { queue.cancel(active.key) }) {
                        Text(AndroidStrings["desktop_translation_cancel_current"])
                    }
                }
                if (active != null) {
                    Text("${active.title} · ${active.completed}/${active.total}", Modifier.padding(12.dp))
                }
                tasks.lastOrNull { it.key.bookId == bookId && it.status == DesktopTranslationQueue.Status.FAILED }
                    ?.let { Text("${it.title} · ${it.error.orEmpty()}", Modifier.padding(12.dp)) }
            }
        }
    }
    if (configure) DesktopTranslationSettingsDialog(settings, onDismiss = { configure = false }) { next ->
        runEdit { service.configure(next) }
        configure = false
    }
    if (selectChapters) {
        var selected by remember { mutableStateOf(emptySet<Long>()) }
        AlertDialog(
            onDismissRequest = { selectChapters = false },
            title = { Text(AndroidStrings["desktop_translation_add_chapters"]) },
            text = {
                LazyColumn(Modifier.heightIn(max = 440.dp)) {
                    items(content.chapters.orEmpty(), key = { it.id }) { chapter ->
                        androidx.compose.foundation.layout.Row {
                            Checkbox(checked = chapter.id in selected, onCheckedChange = { checked ->
                                selected = if (checked) selected + chapter.id else selected - chapter.id
                            })
                            Text(chapter.title ?: desktopText("common.chapterTitle", chapter.number), Modifier.padding(12.dp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = selected.isNotEmpty(), onClick = {
                    queue.enqueue(content, content.chapters.orEmpty().filter { it.id in selected })
                    selectChapters = false
                }) { Text(AndroidStrings["add"]) }
            },
            dismissButton = {
                TextButton(onClick = { selectChapters = false }) { Text(AndroidStrings["cancel"]) }
            },
        )
    }
    if (showQueue) TranslationQueueChaptersDialog(
        title = AndroidStrings["translation_queue_pending_chapters"],
        chapters = waiting.map { QueuedTranslationChapter(it.key.chapterId, it.title) },
        emptyLabel = AndroidStrings["translation_queue_no_pending_chapters"],
        closeLabel = AndroidStrings["close"],
        moveUpLabel = AndroidStrings["move_up"],
        moveDownLabel = AndroidStrings["move_down"],
        cancelLabel = AndroidStrings["cancel"],
        onMove = { id, delta -> queue.move(DesktopTranslationQueue.Key(bookId, id), delta) },
        onCancel = { id -> queue.cancel(DesktopTranslationQueue.Key(bookId, id)) },
        onDismiss = { showQueue = false },
    )
    glossary?.let { draft ->
        val rows = draft.lineSequence().filter(String::isNotBlank).map { line ->
            val parts = line.split("=", limit = 2).map(String::trim)
            if (parts.size == 2 && parts.all(String::isNotBlank)) DesktopGlossaryTerm(parts[0], parts[1]) else null
        }.toList()
        TranslationTextEditorDialog(
            title = AndroidStrings["novel_translation_edit_glossary"],
            value = draft,
            onValueChange = { glossary = it },
            saveLabel = AndroidStrings["save"],
            dismissLabel = AndroidStrings["cancel"],
            canSave = rows.none { it == null },
            header = { Text(AndroidStrings["novel_translation_glossary_format"]) },
            onSave = {
                runEdit { service.saveGlossary(bookId, rows.filterNotNull()) }
                glossary = null
            },
            onDismiss = { glossary = null },
        )
    }
    if (editChapter && paragraphs.isNotEmpty()) {
        var index by remember(chapterId) {
            mutableStateOf(paragraphs.indexOfFirst { it.index >= novel.firstVisible }.coerceAtLeast(0))
        }
        val paragraph = paragraphs[index]
        val value = translation.forParagraph(paragraph.key).orEmpty()
        var draft by remember(paragraph.key, value) { mutableStateOf(value) }
        TranslationTextEditorDialog(
            title = AndroidStrings.format("novel_translation_edit_paragraph_at", index + 1, paragraphs.size),
            value = draft,
            onValueChange = { draft = it },
            saveLabel = AndroidStrings["save"],
            dismissLabel = AndroidStrings["close"],
            canSave = draft.isNotBlank() && draft.trim() != value,
            header = { Text(paragraph.text) },
            actions = {
                FlowRow {
                    TextButton(onClick = { index-- }, enabled = index > 0 && draft == value) {
                        Text(AndroidStrings["novel_translation_previous_paragraph"])
                    }
                    TextButton(onClick = { index++ }, enabled = index < paragraphs.lastIndex && draft == value) {
                        Text(AndroidStrings["novel_translation_next_paragraph"])
                    }
                    TextButton(onClick = {
                        runEdit { service.edit(bookId, chapterId, settings.target, paragraph.key, null) }
                    }, enabled = paragraph.key in translation.edits) { Text(AndroidStrings["restore"]) }
                    TextButton(onClick = {
                        val chooser = JFileChooser().apply { selectedFile = java.io.File("translation.txt") }
                        if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
                            val file = chooser.selectedFile
                            runEdit {
                                val body = paragraphs.joinToString("\n\n") { translation.forParagraph(it.key) ?: it.text }
                                Files.writeString(file.toPath(), body, CREATE_NEW, WRITE)
                            }
                        }
                    }) { Text(AndroidStrings["export"]) }
                }
            },
            onSave = { runEdit { service.edit(bookId, chapterId, settings.target, paragraph.key, draft.trim()) } },
            onDismiss = { editChapter = false },
        )
    }
    error?.let { message ->
        AlertDialog(
            onDismissRequest = { error = null },
            title = { Text(AndroidStrings["error"]) },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { error = null }) { Text(AndroidStrings["close"]) } },
        )
    }
}

@Composable
internal fun DesktopTranslationSettingsDialog(
    initial: DesktopTranslationSettings,
    onDismiss: () -> Unit,
    onSave: (DesktopTranslationSettings) -> Unit,
) {
    var draft by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(AndroidStrings["desktop_translation_settings"]) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(AndroidStrings["desktop_translation_config_help"])
                DesktopTranslationField(draft.endpoint, "desktop_translation_endpoint") { draft = draft.copy(endpoint = it) }
                DesktopTranslationField(draft.model, "desktop_translation_model") { draft = draft.copy(model = it) }
                DesktopTranslationField(draft.target, "desktop_translation_target") { draft = draft.copy(target = it) }
                DesktopTranslationField(draft.apiKey, "desktop_translation_api_key", secret = true) { draft = draft.copy(apiKey = it) }
            }
        },
        confirmButton = {
            val normalized = draft.copy(endpoint = draft.endpoint.trim(), model = draft.model.trim(),
                target = draft.target.trim(), apiKey = draft.apiKey.trim())
            TextButton(enabled = normalized.isConfigured, onClick = { onSave(normalized) }) { Text(AndroidStrings["save"]) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(AndroidStrings["cancel"]) } },
    )
}

@Composable
private fun DesktopTranslationField(value: String, label: String, secret: Boolean = false, change: (String) -> Unit) {
    OutlinedTextField(
        value, change, modifier = Modifier.fillMaxWidth(), singleLine = true,
        label = { Text(AndroidStrings[label]) },
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
    )
}
