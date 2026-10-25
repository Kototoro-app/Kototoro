package org.skepsun.kototoro.ios

import kotlinx.coroutines.flow.first
import org.skepsun.kototoro.space.domain.CustomSpace

/**
 * Custom Spaces on iOS, in the shared `space_definitions` table and Android's stored format (`CustomSpace`).
 * iOS sources map to Android source kinds by ecosystem (see [sourceKind]).
 */
class IosSpaces internal constructor(private val library: IosLibrary) {
    private val dao get() = library.database.getSpaceDefinitionDao()
    private fun now() = kotlin.time.Clock.System.now().toEpochMilliseconds()

    suspend fun all(): List<IosCustomSpace> = dao.observeAll().first().map { IosCustomSpace(CustomSpace.from(it)) }

    /** Adds a space; false at Android's limit of [CustomSpace.MAX]. */
    suspend fun create(title: String, contentTypes: List<String>, languages: List<String>, sourceKinds: List<String>): Boolean {
        if (dao.countActive() >= CustomSpace.MAX || title.isBlank() || contentTypes.isEmpty()) return false
        val time = now()
        val sortKey = (all().maxOfOrNull { it.value.sortKey } ?: 2) + 1
        dao.insert(CustomSpace(CustomSpace.ID_PREFIX + randomId(), title.trim(), sortKey, true, contentTypes.toSet(),
            languages.toSet(), sourceKinds.toSet()).toEntity(time, time))
        return true
    }

    suspend fun update(space: IosCustomSpace, title: String, contentTypes: List<String>, languages: List<String>,
                       sourceKinds: List<String>, enabled: Boolean) {
        val existing = dao.find(space.id) ?: return
        dao.update(space.value.copy(title = title.trim(), contentTypes = contentTypes.toSet(), languages = languages.toSet(),
            sourceKinds = sourceKinds.toSet(), enabled = enabled).toEntity(existing.createdAt, now()))
    }

    suspend fun delete(space: IosCustomSpace) = dao.markDeleted(space.id, now())

    private fun randomId(): String = platform.Foundation.NSUUID().UUIDString.lowercase()

    companion object {
        /** Android's source kind for an iOS source: by ecosystem (CMS sites are TVBox JSON sources there). */
        fun sourceKind(source: IosSource): String = when (source.ecosystem) {
            "MIHON" -> "MIHON"
            "ANIYOMI" -> "ANIYOMI"
            "TSUNDOKU" -> "TSUNDOKU"
            "CMS" -> "JSON_TVBOX"
            "LOCAL" -> "NATIVE"
            else -> "EXTERNAL"
        }

        /** The source kinds iOS can run, with titles, for the space editor. */
        val sourceKinds: List<String> = listOf("MIHON", "ANIYOMI", "TSUNDOKU", "EXTERNAL", "JSON_TVBOX", "NATIVE")
    }
}

class IosCustomSpace internal constructor(internal val value: CustomSpace) {
    val id: String get() = value.id
    val title: String get() = value.title
    val enabled: Boolean get() = value.enabled
    val contentTypes: List<String> get() = value.contentTypes.sorted()
    val languages: List<String> get() = value.languages.sorted()
    val sourceKinds: List<String> get() = value.sourceKinds.sorted()
    /** MANGA, NOVEL or VIDEO when the space holds one kind; null when it spans several. */
    val mediaKind: String? get() = value.mediaKind

    fun admits(source: IosSource): Boolean =
        value.admits(source.contentType, source.locale, IosSpaces.sourceKind(source))
}
