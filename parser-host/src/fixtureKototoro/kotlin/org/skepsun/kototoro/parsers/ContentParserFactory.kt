@file:OptIn(InternalParsersApi::class)

package org.skepsun.kototoro.parsers

import org.skepsun.kototoro.parsers.model.ContentParserSource
import org.skepsun.kototoro.parsers.site.fixture.FixtureParser

/** Entry point the host detects the Kototoro architecture by (`ContentParserFactoryKt.newParser`). */
fun newParser(source: ContentParserSource, context: ContentLoaderContext): ContentParser = FixtureParser(context, source)
