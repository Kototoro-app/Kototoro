package eu.kanade.tachiyomi.source

/**
 * Marker interface for novel (text-based) sources.
 *
 * Detection is via the [Source.isNovelSource] default method and the text API is
 * [Source.fetchPageText]; neither requires this interface. It is kept for source
 * compatibility with existing extensions that declare `: HttpSource(), NovelSource`. New
 * sources just set `isNovelSource()` and override [Source.fetchPageText].
 *
 * The default override here mirrors the real Tsundoku source-api (extensions-lib 1.4/1.6
 * novel ABI), whose `NovelSource.isNovelSource()` default returns true; compiled with
 * `-Xjvm-default=all-compatibility` this emits the `NovelSource$-CC.$default$isNovelSource`
 * bridge that NovelSourcery extensions reference from their own `isNovelSource()` overrides.
 */
interface NovelSource : Source {
    override fun isNovelSource(): Boolean = true
}
