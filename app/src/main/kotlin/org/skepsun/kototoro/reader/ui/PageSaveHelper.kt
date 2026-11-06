package org.skepsun.kototoro.reader.ui

import android.content.ContentResolver
import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.ActivityResultCaller
import androidx.activity.result.ActivityResultLauncher
import androidx.core.net.toFile
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.IOException
import okio.Path.Companion.toPath
import okio.Source
import okio.buffer
import okio.openZip
import okio.sink
import okio.source
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.LocalizedAppContext
import org.skepsun.kototoro.core.image.BitmapDecoderCompat
import org.skepsun.kototoro.core.os.OpenDocumentTreeHelper
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.util.ContentZipArchive
import org.skepsun.kototoro.core.util.MimeTypes
import org.skepsun.kototoro.core.util.ext.isContentZipUri
import org.skepsun.kototoro.core.util.ext.isFileUri
import org.skepsun.kototoro.core.util.ext.isZipUri
import org.skepsun.kototoro.core.util.ext.toExportFileName
import org.skepsun.kototoro.core.util.ext.toFileOrNull
import org.skepsun.kototoro.core.util.ext.writeAllCancellable
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentPage
import org.skepsun.kototoro.reader.domain.PageLoader
import java.io.File
import javax.inject.Provider
import kotlin.coroutines.resume

class PageSaveHelper @AssistedInject constructor(
    @Assisted activityResultCaller: ActivityResultCaller,
    @LocalizedAppContext private val context: Context,
    private val settings: AppSettings,
    private val pageLoaderProvider: Provider<PageLoader>,
) : ActivityResultCallback<Uri?> {

    private val savePageRequest = activityResultCaller.registerForActivityResult(PageSaveContract(), this)
    private val cropPageRequest = activityResultCaller.registerForActivityResult(PageCropContract(), this)
    private val pickDirectoryRequest = OpenDocumentTreeHelper(activityResultCaller, this)

    private var continuation: CancellableContinuation<Uri>? = null

    override fun onActivityResult(result: Uri?) {
        continuation?.also { cont ->
            if (result != null) {
                cont.resume(result)
            } else {
                cont.cancel()
            }
        }
    }

    suspend fun save(tasks: Collection<Task>): Collection<Uri> = when (tasks.size) {
        0 -> emptySet()
        1 -> setOf(saveImpl(tasks.first()))
        else -> saveImpl(tasks)
    }

    suspend fun saveToTempFile(task: Task): File {
        val pageLoader = getPageLoader()
        val pageUrl = pageLoader.getPageUrl(task.page).toUri()
        val pageUri = pageLoader.loadPage(task.page, force = false)
        val proposedName = task.getFileBaseName(context.resources, settings.pagesSaveMangaTitleLength) + "." +
            getPageExtension(pageUrl, pageUri)
        val destination = File(checkNotNull(context.getExternalFilesDir(TEMP_DIR)), proposedName)
        copyImpl(pageUri, destination.toUri())
        return destination
    }

    private suspend fun saveImpl(task: Task): Uri {
        val pageLoader = getPageLoader()
        val pageUrl = pageLoader.getPageUrl(task.page).toUri()
        val pageUri = pageLoader.loadPage(task.page, force = false)
        val outputFormat = getOutputFormat(pageUrl, pageUri)
        val proposedName = task.getFileBaseName(context.resources, settings.pagesSaveMangaTitleLength) +
            "." + outputFormat.extension
        val tempSource = saveToTempFile(task)
        val tempOutput = createTempOutputFile(outputFormat.extension)
        val sourceBounds = getImageBounds(tempSource)
        return try {
            val cropped = cropPageRequest.launchAndAwait(
                PageCropRequest(
                    source = tempSource.toUri(),
                    destination = tempOutput.toUri(),
                    compressFormat = outputFormat.compressFormat,
                    compressQuality = CROP_QUALITY,
                    sourceWidth = sourceBounds.first,
                    sourceHeight = sourceBounds.second,
                ),
            )
            val destination = getDefaultFileUri(task.manga, proposedName)?.uri ?: run {
                val defaultUri = getMangaSaveDir(task.manga, create = false)?.uri
                    ?.buildUpon()
                    ?.appendPath(proposedName)
                    ?.toString()
                savePageRequest.launchAndAwait(defaultUri ?: proposedName)
            }
            copyImpl(cropped, destination)
            destination
        } finally {
            tempSource.delete()
            tempOutput.delete()
        }
    }

    suspend fun cropAndAnnotate(task: Task): Uri? {
        val pageLoader = getPageLoader()
        val pageUrl = pageLoader.getPageUrl(task.page).toUri()
        val pageUri = pageLoader.loadPage(task.page, force = false)
        val outputFormat = getOutputFormat(pageUrl, pageUri)
        val tempSource = saveToTempFile(task)
        val tempOutput = createTempOutputFile(outputFormat.extension)
        val sourceBounds = getImageBounds(tempSource)
        val chapterIndex = task.manga.chapters?.indexOfFirst { it.id == task.chapterId }?.coerceAtLeast(0) ?: 0
        return try {
            cropPageRequest.launchAndAwait(
                PageCropRequest(
                    source = tempSource.toUri(),
                    destination = tempOutput.toUri(),
                    compressFormat = outputFormat.compressFormat,
                    compressQuality = CROP_QUALITY,
                    sourceWidth = sourceBounds.first,
                    sourceHeight = sourceBounds.second,
                    isAnnotationMode = true,
                    mangaId = task.manga.id,
                    chapterId = task.chapterId,
                    chapterIndex = chapterIndex,
                    page = task.pageNumber - 1,
                ),
            )
        } finally {
            tempSource.delete()
            tempOutput.delete()
        }
    }

    private suspend fun saveImpl(tasks: Collection<Task>): Collection<Uri> {
        val pageLoader = getPageLoader()
        // A picked directory takes every page as is; the default one may split them per manga.
        val pickedDir = if (getDefaultSaveDir() == null) {
            val defaultUri = getMangaSaveDir(tasks.first().manga, create = false)?.uri
            DocumentFile.fromTreeUri(context, pickDirectoryRequest.launchAndAwait(defaultUri))
                ?: throw IOException("Cannot get destination directory")
        } else {
            null
        }
        val mangaDirs = HashMap<Long, DocumentFile>()

        val result = ArrayList<Uri>(tasks.size)
        for (task in tasks) {
            val destinationDir = pickedDir ?: mangaDirs.getOrPut(task.manga.id) {
                getMangaSaveDir(task.manga, create = true) ?: throw IOException("Cannot get destination directory")
            }
            val pageUrl = pageLoader.getPageUrl(task.page).toUri()
            val pageUri = pageLoader.loadPage(task.page, force = false)
            val proposedName = task.getFileBaseName(context.resources, settings.pagesSaveMangaTitleLength)
            val ext = getPageExtension(pageUrl, pageUri)
            val mime = requireNotNull(MimeTypes.getMimeTypeFromExtension("_.$ext")) {
                "Unknown type of $proposedName"
            }
            val destination = destinationDir.createFile(mime.toString(), proposedName)
            copyImpl(pageUri, destination?.uri ?: throw IOException("Cannot create destination file"))
            result.add(destination.uri)
        }
        return result
    }

    private suspend fun getPageExtension(url: Uri, fileUri: Uri): String {
        val name = requireNotNull(
            if (url.isZipUri() || url.isContentZipUri()) {
                url.fragment?.substringAfterLast(File.separatorChar)
            } else {
                url.lastPathSegment
            },
        ) { "Invalid page url: $url" }
        var extension = name.substringAfterLast('.', "")
        if (extension.length !in 2..4) {
            extension = fileUri.toFileOrNull()?.let { file -> getImageExtension(file) } ?: EXTENSION_FALLBACK
        }
        return extension
    }

    private suspend fun getOutputFormat(url: Uri, fileUri: Uri): OutputFormat {
        val ext = getPageExtension(url, fileUri)
        return if (ext.equals("jpg", true) || ext.equals("jpeg", true)) {
            OutputFormat("jpg", Bitmap.CompressFormat.JPEG)
        } else {
            OutputFormat("png", Bitmap.CompressFormat.PNG)
        }
    }

    private fun createTempOutputFile(extension: String): File {
        val dir = checkNotNull(context.getExternalFilesDir(TEMP_DIR))
        return File.createTempFile("crop_", ".$extension", dir)
    }

    private suspend fun <I> ActivityResultLauncher<I>.launchAndAwait(input: I): Uri {
        continuation?.cancel()
        return withContext(Dispatchers.Main) {
            try {
                suspendCancellableCoroutine { cont ->
                    continuation = cont
                    launch(input)
                }
            } finally {
                continuation = null
            }
        }
    }

    private suspend fun getPageLoader() = withContext(Dispatchers.Main.immediate) {
        pageLoaderProvider.get()
    }

    /** The configured directory, or null when the user is asked for a destination every time. */
    private fun getDefaultSaveDir(): DocumentFile? = if (settings.isPagesSavingAskEnabled) {
        null
    } else {
        settings.getPagesSaveDir(context)
    }

    private fun getDefaultFileUri(manga: Content, proposedName: String): DocumentFile? {
        if (getDefaultSaveDir() == null) {
            return null
        }
        val dir = getMangaSaveDir(manga, create = true) ?: return null
        val mime = MimeTypes.getMimeTypeFromExtension(proposedName)?.toString() ?: return null
        return dir.createFile(mime, proposedName.substringBeforeLast('.'))
    }

    /**
     * The configured directory, or its folder for [manga] when per-manga folders are on. Without
     * [create] a missing folder falls back to the directory itself, so a picker opened there and then
     * cancelled leaves no empty folder behind.
     */
    private fun getMangaSaveDir(manga: Content, create: Boolean): DocumentFile? {
        val root = settings.getPagesSaveDir(context) ?: return null
        if (!settings.isPagesSavePerMangaFolder) {
            return root
        }
        val name = manga.title.toExportFileName(MANGA_FOLDER_NAME_LENGTH)
        return root.findFile(name)?.takeIf { it.isDirectory }
            ?: if (create) root.createDirectory(name) else root
    }

    private fun getSource(uri: Uri): Source = when {
        uri.isFileUri() -> uri.toFile().source()
        uri.isZipUri() -> FileSystem.SYSTEM.openZip(uri.schemeSpecificPart.toPath())
            .source(requireNotNull(uri.fragment).toPath())

        uri.isContentZipUri() -> ContentZipArchive.openEntry(context.contentResolver, uri).source()
        uri.scheme == ContentResolver.SCHEME_CONTENT -> context.contentResolver.openInputStream(uri)?.source()
            ?: throw IOException("Input stream is null: $uri")

        else -> throw IllegalArgumentException("Bad uri $uri: unsupported scheme")
    }

    private suspend fun copyImpl(source: Uri, destination: Uri) = withContext(Dispatchers.IO) {
        runInterruptible {
            context.contentResolver.openOutputStream(destination) ?: throw IOException("Output stream is null")
        }.sink().buffer().use { sink ->
            getSource(source).use { input ->
                sink.writeAllCancellable(input)
            }
        }
    }

    private suspend fun getImageExtension(file: File): String? = runInterruptible(Dispatchers.IO) {
        MimeTypes.getExtension(BitmapDecoderCompat.probeMimeType(file))
    }

    private suspend fun getImageBounds(file: File): Pair<Int, Int> = runInterruptible(Dispatchers.IO) {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(file.absolutePath, options)
        options.outWidth to options.outHeight
    }

    private data class OutputFormat(
        val extension: String,
        val compressFormat: Bitmap.CompressFormat,
    )

    data class Task(
        val manga: Content,
        val chapterId: Long,
        val pageNumber: Int,
        val page: ContentPage,
    ) {

        /** `Title - Chapter 105 - Page 14`; a name already taken gets a suffix from the document provider. */
        fun getFileBaseName(resources: Resources, mangaTitleLength: Int): String {
            val chapter = manga.findChapterById(chapterId)
            val chapterLabel = chapter?.let { ch ->
                val number = ch.numberString()
                val volume = ch.volumeString()
                when {
                    number != null && volume != null ->
                        resources.getString(R.string.chapter_volume_number, volume, number)

                    number != null -> resources.getString(R.string.chapter_number, number)
                    !ch.title.isNullOrBlank() -> ch.title?.toExportFileName(CHAPTER_TITLE_LENGTH)
                    else -> null
                }
            }
            return listOfNotNull(
                manga.title.toExportFileName(mangaTitleLength.coerceAtLeast(0)),
                chapterLabel,
                resources.getString(R.string.reader_page_label, pageNumber),
            ).joinToString(" - ").toExportFileName()
        }
    }

    @AssistedFactory
    interface Factory {

        fun create(activityResultCaller: ActivityResultCaller): PageSaveHelper
    }

    private companion object {

        private const val CROP_QUALITY = 95
        private const val EXTENSION_FALLBACK = "png"
        private const val TEMP_DIR = "pages"
        private const val MANGA_FOLDER_NAME_LENGTH = 100
        private const val CHAPTER_TITLE_LENGTH = 48
    }
}
