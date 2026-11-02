package org.skepsun.kototoro.notes.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.core.app.ShareCompat
import androidx.core.content.FileProvider
import org.skepsun.kototoro.BuildConfig
import org.skepsun.kototoro.R
import org.skepsun.kototoro.bookmarks.domain.extractNovelBookmarkPreview
import org.skepsun.kototoro.core.util.ext.toFileNameSafe
import org.skepsun.kototoro.notes.domain.BookNoteItem
import org.skepsun.kototoro.parsers.model.Content
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object BookNotesExportHelper {

    fun generateMarkdown(context: Context, manga: Content, notes: List<BookNoteItem>): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val sb = StringBuilder()

        sb.appendLine(context.getString(R.string.book_notes_markdown_title, manga.title))
        sb.appendLine()
        if (manga.authors.isNotEmpty()) {
            sb.appendLine(
                context.getString(
                    R.string.book_notes_markdown_author,
                    manga.authors.joinToString(" / "),
                ),
            )
        }
        sb.appendLine(context.getString(R.string.book_notes_markdown_source))
        sb.appendLine(context.getString(R.string.book_notes_markdown_time, dateFormat.format(Date())))
        sb.appendLine(context.getString(R.string.book_notes_markdown_total, notes.size))
        sb.appendLine()
        sb.appendLine("---")
        sb.appendLine()

        val grouped = notes.groupBy { it.chapterTitle }
        grouped.forEach { (chapter, chapterNotes) ->
            sb.appendLine("## $chapter")
            sb.appendLine()
            chapterNotes.forEach { note ->
                val timeStr = if (note.createdAt > 0) dateFormat.format(Date(note.createdAt)) else ""
                when (note) {
                    is BookNoteItem.NovelHighlight -> {
                        sb.appendLine("> ${note.text}")
                        sb.appendLine()
                        if (!note.note.isNullOrBlank()) {
                            sb.appendLine(context.getString(R.string.book_notes_markdown_thought, note.note))
                            sb.appendLine()
                        }
                        if (timeStr.isNotBlank()) {
                            sb.appendLine("*$timeStr*")
                            sb.appendLine()
                        }
                    }
                    is BookNoteItem.BookmarkEntry -> {
                        val progressStr = if (note.percent > 0) {
                            context.getString(
                                R.string.book_notes_markdown_progress,
                                (note.percent * 100).toInt(),
                            )
                        } else {
                            ""
                        }
                        sb.appendLine(
                            context.getString(
                                R.string.book_notes_markdown_bookmark,
                                note.page + 1,
                                progressStr,
                            ),
                        )
                        val preview = extractNovelBookmarkPreview(note.imageUrl)
                        if (preview.isNotBlank()) {
                            sb.appendLine()
                            sb.appendLine("> $preview")
                        }
                        sb.appendLine()
                        if (timeStr.isNotBlank()) {
                            sb.appendLine("*$timeStr*")
                            sb.appendLine()
                        }
                    }
                    is BookNoteItem.MangaCropNote -> {
                        sb.appendLine("P${note.page + 1}")
                        sb.appendLine()
                        if (!note.note.isNullOrBlank()) {
                            sb.appendLine(context.getString(R.string.book_notes_markdown_thought, note.note))
                            sb.appendLine()
                        }
                        if (timeStr.isNotBlank()) {
                            sb.appendLine("*$timeStr*")
                            sb.appendLine()
                        }
                    }
                    is BookNoteItem.VideoNote -> {
                        val duration = org.skepsun.kototoro.video.ui.compose.formatPlaybackTime(note.positionMs)
                        sb.appendLine("⏱ $duration")
                        sb.appendLine()
                        if (!note.quoteText.isNullOrBlank()) {
                            sb.appendLine("> ${note.quoteText}")
                            sb.appendLine()
                        }
                        if (!note.note.isNullOrBlank()) {
                            sb.appendLine(context.getString(R.string.book_notes_markdown_thought, note.note))
                            sb.appendLine()
                        }
                        if (timeStr.isNotBlank()) {
                            sb.appendLine("*$timeStr*")
                            sb.appendLine()
                        }
                    }
                }
            }
        }

        return sb.toString()
    }

    fun copyToClipboard(context: Context, manga: Content, notes: List<BookNoteItem>) {
        val md = generateMarkdown(context, manga, notes)
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("notes_export", md))
        Toast.makeText(context, R.string.book_notes_copied_markdown, Toast.LENGTH_SHORT).show()
    }

    fun shareMarkdownFile(context: Context, manga: Content, notes: List<BookNoteItem>) {
        try {
            val md = generateMarkdown(context, manga, notes)
            val exportDir = File(context.cacheDir, "notes_export").apply { mkdirs() }
            val safeTitle = manga.title.toFileNameSafe().ifBlank { "book" }.take(50)
            val file = File(
                exportDir,
                context.getString(R.string.book_notes_export_filename, safeTitle),
            )
            file.writeText(md, Charsets.UTF_8)

            val uri = FileProvider.getUriForFile(
                context,
                "${BuildConfig.APPLICATION_ID}.files",
                file,
            )

            ShareCompat.IntentBuilder(context)
                .setType("text/markdown")
                .setStream(uri)
                .setChooserTitle(context.getString(R.string.book_notes_share_title, manga.title))
                .startChooser()
        } catch (e: Exception) {
            Toast.makeText(
                context,
                context.getString(R.string.book_notes_share_failed, e.message.orEmpty()),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    fun writeMarkdownToUri(context: Context, uri: Uri, manga: Content, notes: List<BookNoteItem>): Boolean {
        return try {
            val md = generateMarkdown(context, manga, notes)
            context.contentResolver.openOutputStream(uri)?.use { output ->
                output.write(md.toByteArray(Charsets.UTF_8))
            }
            Toast.makeText(context, R.string.book_notes_saved, Toast.LENGTH_SHORT).show()
            true
        } catch (e: Exception) {
            Toast.makeText(
                context,
                context.getString(R.string.book_notes_save_failed, e.message.orEmpty()),
                Toast.LENGTH_SHORT,
            ).show()
            false
        }
    }
}
