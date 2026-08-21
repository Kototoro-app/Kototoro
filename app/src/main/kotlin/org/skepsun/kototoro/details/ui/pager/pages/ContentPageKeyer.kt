package org.skepsun.kototoro.details.ui.pager.pages

import coil3.key.Keyer
import coil3.request.Options
import org.skepsun.kototoro.parsers.model.ContentPage

class ContentPageKeyer : Keyer<ContentPage> {

    override fun key(data: ContentPage, options: Options) = data.url
}
