package org.skepsun.kototoro.reader.translate.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import org.skepsun.kototoro.reader.translate.domain.normalizeReaderTranslationLanguageTag
import org.skepsun.kototoro.reader.translate.domain.resolveReaderTranslationSourceLanguage
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** [ChapterTranslationStore.Record.status]: every page is rendered and the chapter counts as translated. */
internal const val STATUS_DONE = "done"

/** [ChapterTranslationStore.Record.status]: some pages are rendered, the chapter is not finished. */
internal const val STATUS_PARTIAL = "partial"

/**
 * Language pair used by the chapter translate job and by the "translated" badge query.
 *
 * Both sides must derive the pair the same way, otherwise a finished job would never light up the badge.
 */
fun resolveChapterTranslationLanguagePair(
    preferredSourceLanguage: String?,
    contentLanguage: String?,
    targetLanguage: String?,
): Pair<String, String> {
    val source = resolveReaderTranslationSourceLanguage(preferredSourceLanguage, contentLanguage)
    val target = targetLanguage.normalizeReaderTranslationLanguageTag() ?: "zh"
    return source to target
}

/**
 * Durable per-chapter record of finished background translations.
 *
 * The rendered pages themselves live in the evictable page cache, so the chapter list needs an
 * independent, cheap signal to decide whether a chapter was already downloaded + translated for the
 * current language pair. One small JSON index per content keeps lookups to a single file read, and the
 * in-memory cache makes chapter list rendering allocation-free after the first read.
 */
@Singleton
class ChapterTranslationStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    @Volatile
    private var cache: MutableMap<Long, Map<Long, Record>> = HashMap(4)

    private val mutex = Mutex()

    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

    /** Emits after every write so chapter lists can re-read [preparedChapterIds]. */
    val changes: SharedFlow<Unit> = _changes

    private val directory: File
        get() = File(context.filesDir, DIRECTORY_NAME)

    /**
     * Records a finished translation together with the recipe that produced it.
     *
     * The version increases per chapter so an older translation stays identifiable ("v1, MLKit, ja->zh")
     * after the user re-translates with new settings; [HISTORY_LIMIT] keeps that trail bounded.
     */
    suspend fun markPrepared(
        mangaId: Long,
        chapterId: Long,
        key: ChapterTranslationKey,
        pages: Int,
    ) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val index = readIndexUnlocked(mangaId).toMutableMap()
                val previous = index[chapterId]
                val entry = HistoryEntry(
                    fingerprint = key.fingerprint,
                    engineLabel = key.engineLabel,
                    sourceLang = key.sourceLang,
                    targetLang = key.targetLang,
                    pages = pages,
                    preparedAt = System.currentTimeMillis(),
                    version = (previous?.version ?: 0) + 1,
                )
                index[chapterId] = Record(
                    sourceLang = key.sourceLang,
                    targetLang = key.targetLang,
                    pages = pages,
                    preparedAt = entry.preparedAt,
                    fingerprint = key.fingerprint,
                    engineLabel = key.engineLabel,
                    version = entry.version,
                    history = (listOf(entry) + previous?.history.orEmpty()).take(HISTORY_LIMIT),
                    status = STATUS_DONE,
                    donePages = pages,
                )
                writeIndexUnlocked(mangaId, index)
                cache[mangaId] = index
            }
        }
        _changes.tryEmit(Unit)
    }

    /**
     * Records how far a chapter translation got, without claiming the chapter is finished.
     *
     * Both the background job and the interactive reader call this while they render pages, so the chapter
     * list can draw a determinate ring for the job in flight and keep a static partial ring for a chapter the
     * user translated only halfway before leaving.
     */
    suspend fun markProgress(
        mangaId: Long,
        chapterId: Long,
        key: ChapterTranslationKey,
        donePages: Int,
        totalPages: Int,
    ) {
        if (mangaId == 0L || chapterId == 0L || totalPages <= 0) return
        val changed = withContext(Dispatchers.IO) {
            mutex.withLock {
                val index = readIndexUnlocked(mangaId).toMutableMap()
                val previous = index[chapterId]
                // A finished translation of the same recipe must not be demoted by a job that re-renders
                // pages it already produced. A job with other settings is a different translation, so its
                // progress is recorded and the row shows the ring instead of a badge.
                if (
                    previous != null &&
                    previous.status == STATUS_DONE &&
                    previous.fingerprint == key.fingerprint
                ) {
                    return@withLock false
                }
                val done = donePages.coerceIn(0, totalPages)
                if (
                    previous != null &&
                    previous.donePages == done &&
                    previous.pages == totalPages &&
                    previous.fingerprint == key.fingerprint
                ) {
                    return@withLock false
                }
                index[chapterId] = Record(
                    sourceLang = key.sourceLang,
                    targetLang = key.targetLang,
                    pages = totalPages,
                    preparedAt = previous?.preparedAt ?: System.currentTimeMillis(),
                    fingerprint = key.fingerprint,
                    engineLabel = key.engineLabel,
                    version = previous?.version ?: 0,
                    history = previous?.history.orEmpty(),
                    status = STATUS_PARTIAL,
                    donePages = done,
                )
                writeIndexUnlocked(mangaId, index)
                cache[mangaId] = index
                true
            }
        }
        if (changed) {
            _changes.tryEmit(Unit)
        }
    }

    suspend fun removeChapter(mangaId: Long, chapterId: Long) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val index = readIndexUnlocked(mangaId)
                if (!index.containsKey(chapterId)) return@withLock
                val updated = index.filterKeys { it != chapterId }
                if (updated.isEmpty()) {
                    indexFile(mangaId).delete()
                    cache.remove(mangaId)
                } else {
                    writeIndexUnlocked(mangaId, updated)
                    cache[mangaId] = updated
                }
            }
        }
        _changes.tryEmit(Unit)
    }

    suspend fun removeContent(mangaId: Long) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                cache.remove(mangaId)
                indexFile(mangaId).delete()
            }
        }
        _changes.tryEmit(Unit)
    }

    /**
     * Translation state of every chapter of [mangaId] relative to [key].
     *
     * [existingChapterIds] lets records of deleted downloads be dropped instead of lighting up a
     * "translated" badge for a chapter that is no longer on disk.
     */
    suspend fun chapterStates(
        mangaId: Long,
        key: ChapterTranslationKey?,
        existingChapterIds: Set<Long>? = null,
    ): Map<Long, ChapterTranslationState> {
        if (mangaId == 0L) return emptyMap()
        val index = withContext(Dispatchers.IO) {
            mutex.withLock {
                readIndexUnlocked(mangaId)
            }
        }
        if (index.isEmpty()) return emptyMap()
        val result = HashMap<Long, ChapterTranslationState>(index.size)
        for ((chapterId, record) in index) {
            if (existingChapterIds != null && chapterId !in existingChapterIds) continue
            val state = resolveChapterTranslationState(record, key)
            // A partial record from another recipe says nothing about the current settings.
            if (!state.isPrepared && state.isStale) continue
            result[chapterId] = state
        }
        if (existingChapterIds != null && index.keys.any { it !in existingChapterIds }) {
            pruneStaleChapters(mangaId, existingChapterIds)
        }
        return result
    }

    suspend fun peekPreparedChapterIds(mangaId: Long): Set<Long> {
        if (mangaId == 0L) return emptySet()
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                readIndexUnlocked(mangaId)
                    .filterValues { it.status == STATUS_DONE }
                    .keys
                    .toSet()
            }
        }
    }

    private suspend fun pruneStaleChapters(mangaId: Long, existingChapterIds: Set<Long>) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val index = readIndexUnlocked(mangaId)
                val pruned = index.filterKeys { it in existingChapterIds }
                if (pruned.size == index.size) return@withLock
                if (pruned.isEmpty()) {
                    indexFile(mangaId).delete()
                    cache.remove(mangaId)
                } else {
                    writeIndexUnlocked(mangaId, pruned)
                    cache[mangaId] = pruned
                }
            }
        }
    }

    private fun readIndexUnlocked(mangaId: Long): Map<Long, Record> {
        cache[mangaId]?.let { return it }
        val file = indexFile(mangaId)
        val parsed = runCatching {
            if (!file.isFile) return@runCatching emptyMap()
            val json = JSONObject(file.readText())
            val chapters = json.optJSONObject(KEY_CHAPTERS) ?: return@runCatching emptyMap()
            val result = HashMap<Long, Record>(chapters.length())
            for (chapterKey in chapters.keys()) {
                val chapterId = chapterKey.toLongOrNull() ?: continue
                val obj = chapters.optJSONObject(chapterKey) ?: continue
                val historyArray = obj.optJSONArray(KEY_HISTORY)
                val history = buildList {
                    for (i in 0 until (historyArray?.length() ?: 0)) {
                        val item = historyArray?.optJSONObject(i) ?: continue
                        add(
                            HistoryEntry(
                                fingerprint = item.optString(KEY_FINGERPRINT),
                                engineLabel = item.optString(KEY_ENGINE_LABEL),
                                sourceLang = item.optString(KEY_SOURCE_LANG),
                                targetLang = item.optString(KEY_TARGET_LANG),
                                pages = item.optInt(KEY_PAGES),
                                preparedAt = item.optLong(KEY_PREPARED_AT),
                                version = item.optInt(KEY_ENTRY_VERSION),
                            ),
                        )
                    }
                }
                val pages = obj.optInt(KEY_PAGES)
                val status = obj.optString(KEY_STATUS).takeIf { it.isNotEmpty() } ?: STATUS_DONE
                result[chapterId] = Record(
                    sourceLang = obj.optString(KEY_SOURCE_LANG),
                    targetLang = obj.optString(KEY_TARGET_LANG),
                    pages = pages,
                    preparedAt = obj.optLong(KEY_PREPARED_AT),
                    fingerprint = obj.optString(KEY_FINGERPRINT),
                    engineLabel = obj.optString(KEY_ENGINE_LABEL),
                    version = obj.optInt(KEY_VERSION_NUMBER),
                    history = history,
                    status = status,
                    donePages = obj.optInt(KEY_DONE_PAGES, if (status == STATUS_DONE) pages else 0),
                )
            }
            result
        }.onFailure {
            it.printStackTraceDebug()
        }.getOrDefault(emptyMap())
        cache[mangaId] = parsed
        return parsed
    }

    private fun writeIndexUnlocked(mangaId: Long, index: Map<Long, Record>) {
        val file = indexFile(mangaId)
        file.parentFile?.mkdirs()
        val chapters = JSONObject()
        for ((chapterId, record) in index) {
            chapters.put(
                chapterId.toString(),
                JSONObject()
                    .put(KEY_SOURCE_LANG, record.sourceLang)
                    .put(KEY_TARGET_LANG, record.targetLang)
                    .put(KEY_PAGES, record.pages)
                    .put(KEY_PREPARED_AT, record.preparedAt)
                    .put(KEY_FINGERPRINT, record.fingerprint)
                    .put(KEY_ENGINE_LABEL, record.engineLabel)
                    .put(KEY_VERSION_NUMBER, record.version)
                    .put(KEY_STATUS, record.status)
                    .put(KEY_DONE_PAGES, record.donePages)
                    .put(KEY_HISTORY, JSONArray().apply { record.history.forEach { put(it.toJson()) } }),
            )
        }
        val json = JSONObject()
            .put(KEY_VERSION, VERSION)
            .put(KEY_CHAPTERS, chapters)
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    private fun indexFile(mangaId: Long): File = File(directory, "$mangaId.json")

    data class Record(
        val sourceLang: String,
        val targetLang: String,
        val pages: Int,
        val preparedAt: Long,
        val fingerprint: String = "",
        val engineLabel: String = "",
        val version: Int = 0,
        val history: List<HistoryEntry> = emptyList(),
        val status: String = STATUS_DONE,
        val donePages: Int = 0,
    )

    /** One finished translation of a chapter, kept so the user can see what produced the current pixels. */
    data class HistoryEntry(
        val fingerprint: String,
        val engineLabel: String,
        val sourceLang: String,
        val targetLang: String,
        val pages: Int,
        val preparedAt: Long,
        val version: Int,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put(KEY_FINGERPRINT, fingerprint)
            .put(KEY_ENGINE_LABEL, engineLabel)
            .put(KEY_SOURCE_LANG, sourceLang)
            .put(KEY_TARGET_LANG, targetLang)
            .put(KEY_PAGES, pages)
            .put(KEY_PREPARED_AT, preparedAt)
            .put(KEY_ENTRY_VERSION, version)
    }

    private companion object {
        private const val DIRECTORY_NAME = "chapter-translations"
        private const val VERSION = 1
        private const val KEY_VERSION = "version"
        private const val KEY_CHAPTERS = "chapters"
        private const val KEY_SOURCE_LANG = "source_lang"
        private const val KEY_TARGET_LANG = "target_lang"
        private const val KEY_PAGES = "pages"
        private const val KEY_PREPARED_AT = "prepared_at"
        private const val KEY_FINGERPRINT = "fingerprint"
        private const val KEY_ENGINE_LABEL = "engine_label"
        private const val KEY_VERSION_NUMBER = "version_number"
        private const val KEY_ENTRY_VERSION = "entry_version"
        private const val KEY_HISTORY = "history"
        private const val KEY_STATUS = "status"
        private const val KEY_DONE_PAGES = "done_pages"
        private const val HISTORY_LIMIT = 5
    }
}

/**
 * Classifies a stored record against the recipe the user has configured now.
 *
 * Records written before recipes were fingerprinted stay "prepared" and are not flagged stale: an older
 * translation is not wrong, its recipe is simply unknown.
 */
internal fun resolveChapterTranslationState(
    record: ChapterTranslationStore.Record,
    key: ChapterTranslationKey?,
): ChapterTranslationState {
    val matchesRecipe = key == null || record.fingerprint.isEmpty() || record.fingerprint == key.fingerprint
    val isPrepared = record.status == STATUS_DONE
    return ChapterTranslationState(
        isPrepared = isPrepared,
        isStale = !matchesRecipe,
        version = record.version,
        engineLabel = record.engineLabel,
        pagesDone = record.donePages,
        pagesTotal = record.pages,
    )
}
