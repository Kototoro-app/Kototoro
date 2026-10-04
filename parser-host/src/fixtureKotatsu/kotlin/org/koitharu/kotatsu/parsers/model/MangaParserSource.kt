package org.koitharu.kotatsu.parsers.model

enum class MangaParserSource(
    override val locale: String,
    override val contentType: ContentType,
    val title: String,
) : MangaSource {
    FIXTURE_SHARED("ja", ContentType.MANGA, "Fixture Shared (kotatsu)"),
    FIXTURE_KOTATSU_ONLY("ru", ContentType.MANGA, "Fixture Kotatsu"),
}
