package org.skepsun.kototoro.scrobbling.common.domain

import org.skepsun.kototoro.scrobbling.anilist.data.AniListRepository
import org.skepsun.kototoro.scrobbling.common.data.ScrobblerRepository
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import org.skepsun.kototoro.scrobbling.kitsu.data.KitsuRepository
import org.skepsun.kototoro.scrobbling.mal.data.MALRepository
import org.skepsun.kototoro.scrobbling.simkl.data.SimklRepository
import org.skepsun.kototoro.scrobbling.shikimori.data.ShikimoriRepository
import org.skepsun.kototoro.scrobbling.bangumi.data.BangumiRepository
import org.skepsun.kototoro.scrobbling.mangaupdates.data.MangaUpdatesRepository
import javax.inject.Inject
import javax.inject.Provider

class ScrobblerRepositoryMap @Inject constructor(
    private val shikimoriRepository: Provider<ShikimoriRepository>,
    private val aniListRepository: Provider<AniListRepository>,
    private val malRepository: Provider<MALRepository>,
    private val kitsuRepository: Provider<KitsuRepository>,
    private val bangumiRepository: Provider<BangumiRepository>,
    private val mangaUpdatesRepository: Provider<MangaUpdatesRepository>,
    private val simklRepository: Provider<SimklRepository>,
) {

    operator fun get(scrobblerService: ScrobblerService): ScrobblerRepository = when (scrobblerService) {
        ScrobblerService.SHIKIMORI -> shikimoriRepository
        ScrobblerService.ANILIST -> aniListRepository
        ScrobblerService.MAL -> malRepository
        ScrobblerService.KITSU -> kitsuRepository
        ScrobblerService.BANGUMI -> bangumiRepository
        ScrobblerService.MANGAUPDATES -> mangaUpdatesRepository
        ScrobblerService.SIMKL -> simklRepository
    }.get()
}
