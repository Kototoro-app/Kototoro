package org.koitharu.kotatsu.parsers.util

import okhttp3.HttpUrl
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaSource

public interface LinkResolver {
    public val link: HttpUrl
    public suspend fun getSource(): MangaSource?
    public suspend fun getManga(): Manga?
}
