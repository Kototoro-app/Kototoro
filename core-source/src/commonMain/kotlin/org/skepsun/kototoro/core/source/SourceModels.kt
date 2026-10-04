package org.skepsun.kototoro.core.source

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Decimal strings keep 64-bit parser IDs exact across JSON consumers. */
object SourceLongSerializer : KSerializer<Long> {
    override val descriptor = PrimitiveSerialDescriptor("SourceLong", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Long) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): Long = decoder.decodeString().toLong()
}

/** Stable parser source name; locale and type are metadata, not identity. */
@Serializable
data class SourceRef(val name: String, val locale: String, val contentType: String)

@Serializable
data class SourceTag(val title: String, val key: String, val source: SourceRef)

@Serializable
data class SourceContent(
    @Serializable(with = SourceLongSerializer::class) val id: Long,
    val title: String,
    val altTitles: Set<String>,
    val url: String,
    val publicUrl: String,
    val rating: Float,
    val contentRating: String?,
    val coverUrl: String?,
    val tags: Set<SourceTag>,
    val state: String?,
    val authors: Set<String>,
    val source: SourceRef,
    val largeCoverUrl: String? = null,
    val description: String? = null,
    val chapters: List<SourceChapter>? = null,
    val sourceData: String? = null,
)

@Serializable
data class SourceChapter(
    @Serializable(with = SourceLongSerializer::class) val id: Long,
    val title: String?,
    val number: Float,
    val volume: Int,
    val url: String,
    val scanlator: String?,
    @Serializable(with = SourceLongSerializer::class) val uploadDate: Long,
    val branch: String?,
    val source: SourceRef,
    val sourceData: String? = null,
    val ebookFormats: List<String> = emptyList(),
)

@Serializable
data class SourceExternalTrack(val url: String, val lang: String, val headers: Map<String, String>? = null)

/** A resource a text chapter refers to; [headers] carry whatever the site needs to serve it. */
@Serializable
data class SourceContentImage(val url: String, val headers: Map<String, String> = emptyMap())

/** The text of a novel chapter as HTML (images may keep their original URLs) plus the images it uses. */
@Serializable
data class SourceChapterContent(val html: String, val images: List<SourceContentImage> = emptyList())

@Serializable
data class SourcePage(
    @Serializable(with = SourceLongSerializer::class) val id: Long,
    val url: String,
    val preview: String?,
    val source: SourceRef,
    val headers: Map<String, String>? = null,
    val externalSubtitleTracks: List<SourceExternalTrack> = emptyList(),
    val playbackLabel: String? = null,
    val playbackQuality: Int? = null,
    val requestContext: SourcePageRequestContext? = null,
    /** Alternative audio the player can add next to the stream (Aniyomi Video.audioTracks). */
    val externalAudioTracks: List<SourceExternalTrack> = emptyList(),
)

/** Enum names and BCP 47 language tags leave JVM enums and java.util.Locale at the adapter boundary. */
@Serializable
data class SourceFilter(
    val query: String? = null,
    val tags: Set<SourceTag> = emptySet(),
    val tagsExclude: Set<SourceTag> = emptySet(),
    val locale: String? = null,
    val originalLocale: String? = null,
    val states: Set<String> = emptySet(),
    val contentRating: Set<String> = emptySet(),
    val types: Set<String> = emptySet(),
    val demographics: Set<String> = emptySet(),
    val year: Int = 0,
    val yearFrom: Int = 0,
    val yearTo: Int = 0,
    val author: String? = null,
    val dynamicFilters: List<SourceFilterChange> = emptyList(),
)

@Serializable
data class SourceTagGroup(val title: String, val tags: Set<SourceTag>, val isExclusive: Boolean = false)

@Serializable
data class SourceFilterOptions(
    val availableTags: Set<SourceTag>,
    val tagGroups: List<SourceTagGroup>,
    val availableStates: Set<String>,
    val availableContentRating: Set<String>,
    val availableContentTypes: Set<String>,
    val availableDemographics: Set<String>,
    val availableLocales: Set<String>,
    val effectiveTagGroups: List<SourceTagGroup> = when {
        tagGroups.isNotEmpty() -> tagGroups
        availableTags.isNotEmpty() -> listOf(SourceTagGroup("Tags", availableTags))
        else -> emptyList()
    },
)

@Serializable
data class SourceFilterCapabilities(
    val isMultipleTagsSupported: Boolean,
    val isTagsExclusionSupported: Boolean,
    val isSearchSupported: Boolean,
    val isSearchWithFiltersSupported: Boolean,
    val isYearSupported: Boolean,
    val isYearRangeSupported: Boolean,
    val isOriginalLocaleSupported: Boolean,
    val isAuthorSearchSupported: Boolean,
)

@Serializable
enum class SourcePagingMode { OFFSET, PAGE_INDEX }

@Serializable
enum class SourceDetailsFetchMode { ALLOW_CACHE, FORCE_REFRESH }

@Serializable
data class SourceDescriptor(
    val source: SourceRef,
    val sortOrders: Set<String>,
    val defaultSortOrder: String,
    val filterCapabilities: SourceFilterCapabilities,
    val pagingMode: SourcePagingMode,
    val isDynamicFilteringSupported: Boolean = false,
    val isImageFetchingSupported: Boolean = false,
    val isPreferencesSupported: Boolean = false,
    val isCoverFetchingSupported: Boolean = false,
    /** The source can return a chapter as text (novels) through [SourceRuntime.getChapterContent]. */
    val isChapterContentSupported: Boolean = false,
)
