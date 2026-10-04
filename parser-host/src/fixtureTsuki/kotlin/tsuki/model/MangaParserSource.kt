package tsuki.model

enum class MangaParserSource(
    override val locale: String,
    override val contentType: ContentType,
    override val title: String,
) : MangaSource {
    FIXTURE_SHARED("ko", ContentType.MANGA, "Fixture Shared (tsuki)"),
    FIXTURE_TSUKI_ONLY("fr", ContentType.MANGA, "Fixture Tsuki"),
}
