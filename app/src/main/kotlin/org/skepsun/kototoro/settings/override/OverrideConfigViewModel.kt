package org.skepsun.kototoro.settings.override

import android.content.Context
import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import okio.buffer
import okio.sink
import org.skepsun.kototoro.core.model.parcelable.ParcelableContent
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.core.ui.BaseViewModel
import org.skepsun.kototoro.core.ui.model.ContentOverride
import org.skepsun.kototoro.core.util.MimeTypes
import org.skepsun.kototoro.core.util.ext.MutableEventFlow
import org.skepsun.kototoro.core.util.ext.call
import org.skepsun.kototoro.core.util.ext.isFileUri
import org.skepsun.kototoro.core.util.ext.openSource
import org.skepsun.kototoro.core.util.ext.require
import org.skepsun.kototoro.core.util.ext.toMimeTypeOrNull
import org.skepsun.kototoro.core.util.ext.toUriOrNull
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.util.md5
import java.io.File
import javax.inject.Inject

private const val DIR_COVERS = "covers"

@HiltViewModel
class OverrideConfigViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val dataRepository: ContentDataRepository,
) : BaseViewModel() {

    private val manga = savedStateHandle.require<ParcelableContent>(AppRouter.KEY_MANGA).manga

    val data = MutableStateFlow<Pair<Content, ContentOverride>?>(null)
    val onSaved = MutableEventFlow<Unit>()

    init {
        launchLoadingJob(Dispatchers.Default) {
            data.value = manga to (dataRepository.getOverride(manga.id) ?: emptyOverride())
        }
    }

    fun save(title: String?) {
        launchLoadingJob(Dispatchers.Default) {
            val override = checkNotNull(data.value).second.let {
                it.copy(
                    title = title,
                    coverUrl = it.coverUrl?.cachedFile(),
                )
            }
            dataRepository.setOverride(manga, override)
            onSaved.call(Unit)
        }
    }

    fun updateCover(coverUri: String?) {
        val snapshot = data.value ?: return
        data.value = snapshot.first to snapshot.second.copy(
            coverUrl = coverUri,
        )
    }

    private suspend fun String.cachedFile(): String {
        val uri = toUriOrNull()
        if (uri == null || uri.isFileUri()) {
            return this
        }
        val cacheDir = context.getExternalFilesDir(DIR_COVERS) ?: return this
        val cr = context.contentResolver
        val ext = cr.getType(uri)?.toMimeTypeOrNull()?.let {
            MimeTypes.getExtension(it)
        }
        val fileName = buildString {
            append(this@cachedFile.md5())
            if (!ext.isNullOrEmpty()) {
                append('.')
                append(ext)
            }
        }
        return withContext(Dispatchers.IO) {
            val dest = File(cacheDir, fileName)
            cr.openSource(uri).use { source ->
                dest.sink().buffer().use { sink ->
                    sink.writeAll(source)
                }
            }
            dest
        }.toUri().toString()
    }

    private fun emptyOverride() = ContentOverride(null, null, null)
}
