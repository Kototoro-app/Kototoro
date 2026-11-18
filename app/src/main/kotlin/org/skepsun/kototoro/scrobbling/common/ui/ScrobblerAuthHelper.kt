package org.skepsun.kototoro.scrobbling.common.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.core.util.ext.processLifecycleScope
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import org.skepsun.kototoro.scrobbling.tmdb.data.TmdbRepository
import org.skepsun.kototoro.scrobbling.common.domain.ScrobblerRepositoryMap
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerUser
import org.skepsun.kototoro.scrobbling.kitsu.ui.KitsuAuthActivity
import org.skepsun.kototoro.scrobbling.mangabaka.ui.MangaBakaAuthActivity
import javax.inject.Inject

class ScrobblerAuthHelper @Inject constructor(
    private val repositoriesMap: ScrobblerRepositoryMap,
) {

    fun isAuthorized(scrobbler: ScrobblerService) = repositoriesMap[scrobbler].isAuthorized

    fun getCachedUser(scrobbler: ScrobblerService): ScrobblerUser? {
        return repositoriesMap[scrobbler].cachedUser
    }

    suspend fun getUser(scrobbler: ScrobblerService): ScrobblerUser {
        return repositoriesMap[scrobbler].loadUser()
    }

    @SuppressLint("UnsafeImplicitIntentLaunch")
    fun startAuth(context: Context, scrobbler: ScrobblerService) = runCatching {
        if (scrobbler == ScrobblerService.MANGABAKA) {
            context.startActivity(Intent(context, MangaBakaAuthActivity::class.java))
        } else if (scrobbler == ScrobblerService.KITSU) {
            launchKitsuAuth(context)
        } else if (scrobbler == ScrobblerService.MANGAUPDATES) {
            context.startActivity(Intent(context, org.skepsun.kototoro.scrobbling.mangaupdates.ui.MangaUpdatesAuthActivity::class.java))
        } else if (scrobbler == ScrobblerService.TMDB) {
            launchTmdbAuth(context)
        } else {
            val repository = repositoriesMap[scrobbler]
            val intent = Intent(Intent.ACTION_VIEW)
            intent.data = repository.oauthUrl.toUri()
            context.startActivity(intent)
        }
    }

    /** TMDB's sign-in page needs a request token fetched first; the page opens once it arrives. */
    private fun launchTmdbAuth(context: Context) {
        val appContext = context.applicationContext
        val repository = repositoriesMap[ScrobblerService.TMDB] as TmdbRepository
        processLifecycleScope.launch(Dispatchers.IO) {
            val url = runCatchingCancellable { repository.createAuthorizationUrl() }
            withContext(Dispatchers.Main) {
                url.onSuccess {
                    appContext.startActivity(Intent(Intent.ACTION_VIEW, it.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.onFailure {
                    Toast.makeText(appContext, it.message ?: it.javaClass.simpleName, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun launchKitsuAuth(context: Context) {
        context.startActivity(Intent(context, KitsuAuthActivity::class.java))
    }
}
