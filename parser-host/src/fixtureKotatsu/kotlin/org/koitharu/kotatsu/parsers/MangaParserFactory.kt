@file:OptIn(InternalParsersApi::class)

package org.koitharu.kotatsu.parsers

import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.fixture.FixtureParser

/** Entry point the host detects the Kotatsu architecture by (`MangaParserFactoryKt.newParser`). */
fun newParser(source: MangaParserSource, context: MangaLoaderContext): MangaParser = FixtureParser(context, source)
