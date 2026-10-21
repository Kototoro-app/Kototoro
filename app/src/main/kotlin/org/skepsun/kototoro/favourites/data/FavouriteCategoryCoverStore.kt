package org.skepsun.kototoro.favourites.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.edit
import dagger.Reusable
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.core.util.ext.observeChanges
import java.io.File
import javax.inject.Inject

/**
 * Custom artwork for one favourite category, stored next to the app's own files.
 *
 * Like [FavouriteCategoryDisplayPreferences] this is local display state: the picked image
 * is copied into the app's storage so the grant behind a document Uri cannot expire, and the
 * mapping lives in preferences. Category rows keep showing the automatic cover stack when no
 * custom cover is set (or when the file disappeared), so nothing here can break a category.
 */
@Reusable
class FavouriteCategoryCoverStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Custom covers that still exist on disk, keyed by category id. */
    fun observeCoverFiles(): Flow<Map<Long, File>> = prefs.observeChanges()
        .onStart { emit(null) }
        .map { readCoverFiles() }
        .flowOn(Dispatchers.IO)
        .distinctUntilChanged()

    fun getCoverFile(categoryId: Long): File? = prefs.getString(keyOf(categoryId), null)
        ?.let(::File)
        ?.takeIf { it.isFile }

    /** Whether [uri] still opens; called before the pick is accepted so errors surface early. */
    suspend fun canReadImage(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.read() != -1 } == true
        }.getOrDefault(false)
    }

    /**
     * Copies [uri] into the app storage and remembers it for [categoryId]. Returns false when
     * the document could not be read, leaving the previous cover untouched.
     */
    suspend fun setCover(categoryId: Long, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val directory = coverDirectory()
        if (!directory.isDirectory && !directory.mkdirs()) {
            return@withContext false
        }
        val previous = getCoverFile(categoryId)
        // A unique name per replacement keeps Coil's file cache from serving the old artwork.
        val target = File(directory, "${categoryId}_${System.currentTimeMillis()}")
        val copied = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } != null && target.length() > 0L
        }.getOrDefault(false)
        if (!copied) {
            target.delete()
            return@withContext false
        }
        normalize(target)
        prefs.edit { putString(keyOf(categoryId), target.absolutePath) }
        if (previous != null && previous.absolutePath != target.absolutePath) {
            previous.delete()
        }
        true
    }

    suspend fun clearCover(categoryId: Long) = withContext(Dispatchers.IO) {
        val previous = getCoverFile(categoryId)
        prefs.edit { remove(keyOf(categoryId)) }
        previous?.delete()
        Unit
    }

    /**
     * Forgets custom covers that can no longer be shown: the file is gone, or the category
     * itself was deleted. [existingCategoryIds] must be the full category list, never a
     * Space-filtered one, otherwise a hidden category would lose its artwork.
     */
    suspend fun pruneCoverFiles(existingCategoryIds: Set<Long>) = withContext(Dispatchers.IO) {
        val all = prefs.all
        val stale = all.keys.filter { key ->
            if (!key.startsWith(KEY_COVER_PREFIX)) {
                return@filter false
            }
            val categoryId = key.removePrefix(KEY_COVER_PREFIX).toLongOrNull()
            categoryId == null ||
                categoryId !in existingCategoryIds ||
                (all[key] as? String)?.let(::File)?.isFile != true
        }
        if (stale.isEmpty()) {
            return@withContext
        }
        prefs.edit {
            for (key in stale) {
                (all[key] as? String)?.let(::File)?.delete()
                remove(key)
            }
        }
    }

    private fun readCoverFiles(): Map<Long, File> {
        val result = LinkedHashMap<Long, File>()
        for ((key, value) in prefs.all) {
            if (!key.startsWith(KEY_COVER_PREFIX)) continue
            val categoryId = key.removePrefix(KEY_COVER_PREFIX).toLongOrNull() ?: continue
            val file = (value as? String)?.let(::File)?.takeIf { it.isFile } ?: continue
            result[categoryId] = file
        }
        return result
    }

    /**
     * Category covers are drawn small; a full-resolution photo would only waste storage and
     * decode time. Re-encodes oversized images down to [MAX_SIDE_PX].
     */
    private fun normalize(file: File) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeFile(file.path, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return // not decodable here; let the image loader report it
        }
        val longestSide = maxOf(bounds.outWidth, bounds.outHeight)
        if (longestSide <= MAX_SIDE_PX) return
        var sampleSize = 1
        while (longestSide / (sampleSize * 2) >= MAX_SIDE_PX) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bitmap = runCatching { BitmapFactory.decodeFile(file.path, options) }.getOrNull() ?: return
        val format = if (bitmap.hasAlpha()) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        val temp = File(file.parentFile, file.name + TEMP_SUFFIX)
        val written = runCatching {
            temp.outputStream().use { output -> bitmap.compress(format, JPEG_QUALITY, output) }
        }.getOrDefault(false)
        bitmap.recycle()
        if (written && temp.length() > 0L) {
            // Replace only once the re-encoded copy is complete, so a failed compress cannot
            // truncate the artwork that was just copied.
            temp.renameTo(file) || runCatching {
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }.isSuccess
        } else {
            temp.delete()
        }
    }

    private fun coverDirectory(): File = File(context.filesDir, DIRECTORY_NAME)

    private fun keyOf(categoryId: Long): String = KEY_COVER_PREFIX + categoryId

    private companion object {

        const val PREFS_NAME = "favourite_category_covers"
        const val KEY_COVER_PREFIX = "cover:"
        const val DIRECTORY_NAME = "favourite_category_covers"
        const val MAX_SIDE_PX = 1024
        const val JPEG_QUALITY = 90
        const val TEMP_SUFFIX = ".tmp"
    }
}
