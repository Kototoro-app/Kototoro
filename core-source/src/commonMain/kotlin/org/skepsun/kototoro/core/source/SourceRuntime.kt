package org.skepsun.kototoro.core.source

/** Parser operations and host-owned image artifacts; cookies and browser clearance remain platform responsibilities. */
interface SourceRuntime {
    suspend fun getSources(): List<SourceRef>
    suspend fun describe(sourceName: String): SourceDescriptor
    suspend fun getFilterOptions(sourceName: String): SourceFilterOptions
    suspend fun getDynamicFilters(sourceName: String): SourceDynamicFilters = throw SourceOperationUnsupportedException()
    suspend fun getPreferences(sourceName: String): SourcePreferenceScreen = throw SourceOperationUnsupportedException()
    suspend fun updatePreference(
        sourceName: String, revision: String, nodeId: String, value: SourcePreferenceValue,
    ): SourcePreferenceUpdate = throw SourceOperationUnsupportedException()
    suspend fun getList(sourceName: String, offset: Int, order: String?, filter: SourceFilter?): List<SourceContent>
    suspend fun getDetails(content: SourceContent, fetchMode: SourceDetailsFetchMode): SourceContent
    suspend fun getPages(chapter: SourceChapter, nextChapterUrl: String?): List<SourcePage>
    suspend fun getPageUrl(page: SourcePage): String
    /** The chapter as text (novels); null when the source has no text for it. */
    suspend fun getChapterContent(chapter: SourceChapter, nextChapterUrl: String?): SourceChapterContent? =
        throw SourceOperationUnsupportedException()
    suspend fun fetchImage(page: SourcePage): SourceImageArtifact = throw SourceOperationUnsupportedException()
    suspend fun fetchCover(content: SourceContent, large: Boolean = false): SourceCoverArtifact =
        throw SourceOperationUnsupportedException()
    suspend fun getRelated(content: SourceContent): List<SourceContent>
}

class SourceUnavailableException(val sourceName: String) : Exception("Source unavailable: $sourceName")

class SourceInvalidArgumentException : Exception("Invalid source argument")

class SourceOperationUnsupportedException : Exception("Unsupported source operation")
