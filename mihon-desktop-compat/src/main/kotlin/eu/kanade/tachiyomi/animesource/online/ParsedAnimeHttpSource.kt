package eu.kanade.tachiyomi.animesource.online

import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * A simple implementation for sources from a website that uses JSoup to parse HTML.
 */
abstract class ParsedAnimeHttpSource : AnimeHttpSource() {

    /**
     * Parse the response from the site using JSoup.
     */
    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animeList = document.select(popularAnimeSelector()).map { popularAnimeFromElement(it) }
        val hasNextPage = popularAnimeNextPageSelector()?.let { document.select(it).first() != null } ?: false
        return AnimesPage(animeList, hasNextPage)
    }

    protected abstract fun popularAnimeSelector(): String

    protected abstract fun popularAnimeFromElement(element: Element): SAnime

    protected abstract fun popularAnimeNextPageSelector(): String?

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animeList = document.select(searchAnimeSelector()).map { searchAnimeFromElement(it) }
        val hasNextPage = searchAnimeNextPageSelector()?.let { document.select(it).first() != null } ?: false
        return AnimesPage(animeList, hasNextPage)
    }

    protected abstract fun searchAnimeSelector(): String

    protected abstract fun searchAnimeFromElement(element: Element): SAnime

    protected abstract fun searchAnimeNextPageSelector(): String?

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animeList = document.select(latestUpdatesSelector()).map { latestUpdatesFromElement(it) }
        val hasNextPage = latestUpdatesNextPageSelector()?.let { document.select(it).first() != null } ?: false
        return AnimesPage(animeList, hasNextPage)
    }

    protected abstract fun latestUpdatesSelector(): String

    protected abstract fun latestUpdatesFromElement(element: Element): SAnime

    protected abstract fun latestUpdatesNextPageSelector(): String?

    override fun animeDetailsParse(response: Response): SAnime {
        return animeDetailsParse(response.asJsoup())
    }

    protected abstract fun animeDetailsParse(document: Document): SAnime

    override fun episodeListParse(response: Response): List<SEpisode> {
        return documentToEpisodes(response.asJsoup())
    }

    protected open fun documentToEpisodes(document: Document): List<SEpisode> {
        return document.select(episodeListSelector()).map { episodeFromElement(it) }
    }

    protected abstract fun episodeListSelector(): String

    protected abstract fun episodeFromElement(element: Element): SEpisode

    override fun videoListParse(response: Response): List<Video> {
        return videoListParse(response.asJsoup())
    }

    protected abstract fun videoListParse(document: Document): List<Video>

    protected fun Response.asJsoup() = org.jsoup.Jsoup.parse(body!!.string(), request.url.toString())
}
