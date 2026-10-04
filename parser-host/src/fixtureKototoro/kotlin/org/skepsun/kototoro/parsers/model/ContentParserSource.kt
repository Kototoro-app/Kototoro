package org.skepsun.kototoro.parsers.model

/** A plugin defines its own source enum; the host reads the constants reflectively. */
enum class ContentParserSource(
    override val locale: String,
    override val contentType: ContentType,
    val title: String,
) : ContentSource {
    FIXTURE_SHARED("en", ContentType.MANGA, "Fixture Shared"),
    FIXTURE_KOTOTORO_ONLY("zh", ContentType.NOVEL, "夹具小说"),
}
