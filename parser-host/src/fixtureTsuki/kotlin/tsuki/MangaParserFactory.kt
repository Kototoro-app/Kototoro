package tsuki

import tsuki.model.MangaParserSource
import tsuki.site.fixture.FixtureParser

/** Entry point the host detects the Tsuki (UMA) architecture by (`tsuki.MangaParserFactoryKt.newParser`). */
fun newParser(source: MangaParserSource, context: MangaLoaderContext): MangaParser = FixtureParser(context, source)
