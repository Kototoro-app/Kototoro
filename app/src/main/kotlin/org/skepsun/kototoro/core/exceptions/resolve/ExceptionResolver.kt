package org.skepsun.kototoro.core.exceptions.resolve

import android.content.Context
import android.widget.Toast
import androidx.activity.result.ActivityResultCaller
import androidx.annotation.StringRes
import androidx.collection.MutableScatterMap
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.async
import org.skepsun.kototoro.R
import org.skepsun.kototoro.browser.BrowserActivity
import org.skepsun.kototoro.browser.cloudflare.CloudFlareActivity
import org.skepsun.kototoro.core.exceptions.CloudFlareProtectedException
import org.skepsun.kototoro.core.exceptions.EmptyContentException
import org.skepsun.kototoro.core.exceptions.InteractiveActionRequiredException
import org.skepsun.kototoro.core.exceptions.ProxyConfigException
import org.skepsun.kototoro.core.exceptions.UnsupportedSourceException
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.nav.router
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.SourceSettings
import org.skepsun.kototoro.core.ui.dialog.buildAlertDialog
import org.skepsun.kototoro.core.util.ext.isHttpUrl
import org.skepsun.kototoro.core.util.ext.findInteractiveActionRequiredException
import org.skepsun.kototoro.core.util.ext.restartApplication
import org.skepsun.kototoro.details.ui.pager.EmptyContentReason
import org.skepsun.kototoro.parsers.exception.AuthRequiredException
import org.skepsun.kototoro.parsers.exception.NotFoundException
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.scrobbling.common.domain.ScrobblerAuthRequiredException
import org.skepsun.kototoro.scrobbling.common.ui.ScrobblerAuthHelper
import org.skepsun.kototoro.settings.sources.auth.SourceAuthActivity
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.core.parser.ParserContentRepository
import org.skepsun.kototoro.core.parser.logUnavailable
import org.skepsun.kototoro.parsers.ContentParserCredentialsAuthProvider
import org.skepsun.kototoro.core.model.isLocal
import java.security.cert.CertPathValidatorException
import javax.inject.Inject
import javax.inject.Provider
import javax.net.ssl.SSLException
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class ExceptionResolver private constructor(
    private val host: Host,
    private val settings: AppSettings,
    private val mangaRepositoryFactory: ContentRepository.Factory,
    private val scrobblerAuthHelperProvider: Provider<ScrobblerAuthHelper>,
    private val captchaAutoResolveCoordinator: CaptchaAutoResolveCoordinator,
) {
    private val continuations = MutableScatterMap<String, Continuation<Boolean>>(1)

    private val browserActionContract = host.registerForActivityResult(BrowserActivity.Contract()) {
        handleActivityResult(BrowserActivity.TAG, it)
    }
    private val sourceAuthContract = host.registerForActivityResult(SourceAuthActivity.Contract()) {
        handleActivityResult(SourceAuthActivity.TAG, it)
    }
    private val cloudflareContract = host.registerForActivityResult(CloudFlareActivity.Contract()) {
        handleActivityResult(CloudFlareActivity.TAG, it)
    }

    fun showErrorDetails(e: Throwable, url: String? = null) {
        host.router.showErrorDialog(e, url)
    }

    suspend fun resolve(e: Throwable, tryAutoResolve: Boolean = true): Boolean = host.lifecycleScope.async {
        val interactiveAction = e.findInteractiveActionRequiredException()
        if (interactiveAction != null) {
            resolveBrowserAction(interactiveAction)
        } else when (e) {
            is CloudFlareProtectedException -> resolveCF(e, tryAutoResolve)
            is AuthRequiredException -> resolveAuthException(e.source)
            is SSLException,
            is CertPathValidatorException -> {
                showSslErrorDialog()
                false
            }

            is ProxyConfigException -> {
                host.router.openProxySettings()
                false
            }

            is NotFoundException -> {
                openInBrowser(e.url)
                false
            }

            is EmptyContentException -> {
                when (e.reason) {
                    EmptyContentReason.NO_CHAPTERS -> openAlternatives(e.manga)
                    EmptyContentReason.LOADING_ERROR -> Unit
                    EmptyContentReason.RESTRICTED -> host.router.openBrowser(e.manga)
                    else -> Unit
                }
                false
            }

            is UnsupportedSourceException -> {
                e.manga?.let { openAlternatives(it) }
                false
            }

            is ScrobblerAuthRequiredException -> {
                val authHelper = scrobblerAuthHelperProvider.get()
                if (authHelper.isAuthorized(e.scrobbler)) {
                    true
                } else {
                    host.withContext {
                        authHelper.startAuth(this, e.scrobbler).onFailure(::showErrorDetails)
                    }
                    false
                }
            }

            else -> false
        }
    }.await()

    private suspend fun resolveBrowserAction(
        e: InteractiveActionRequiredException
    ): Boolean = suspendCoroutine { cont ->
        continuations[BrowserActivity.TAG] = cont
        browserActionContract.launch(e)
    }

    private suspend fun resolveCF(e: CloudFlareProtectedException, tryAutoResolve: Boolean): Boolean {
        val autoResolveEnabled = tryAutoResolve &&
            (host.context?.let { !SourceSettings(it, e.source).isCaptchaAutoResolveDisabled } ?: true)
        if (autoResolveEnabled && captchaAutoResolveCoordinator.resolve(e.source, e)) {
            return true
        }
        return suspendCoroutine { cont ->
            continuations[CloudFlareActivity.TAG] = cont
            cloudflareContract.launch(e)
        }
    }

    private suspend fun resolveAuthException(source: ContentSource): Boolean {
        if (isCredentialBased(source)) {
            host.router.openSourceSettings(source)
            return false
        }
        return suspendCoroutine { cont ->
            continuations[SourceAuthActivity.TAG] = cont
            sourceAuthContract.launch(source)
        }
    }

    private fun isCredentialBased(source: ContentSource): Boolean {
        if (source.isLocal) return false
        val creation = mangaRepositoryFactory.createWithDiagnostics(source)
        val repo = creation.repository
        if (repo !is ParserContentRepository) {
            creation.logUnavailable("ExceptionResolver", "credential_auth_check_skipped")
            return false
        }
        return repo.getAuthProvider() is ContentParserCredentialsAuthProvider
    }

    fun getResolveStringId(e: Throwable): Int {
        if (e is AuthRequiredException && isCredentialBased(e.source)) {
            return R.string.sign_in_in_settings
        }
        return Companion.getResolveStringId(e)
    }

    private fun openInBrowser(url: String) {
        host.router.openBrowser(url, null, null)
    }

    private fun openAlternatives(manga: Content) {
        host.router.openAlternatives(manga)
    }

    private fun handleActivityResult(tag: String, result: Boolean) {
        continuations.remove(tag)?.resume(result)
    }

    private fun showSslErrorDialog() {
        val ctx = host.context ?: return
        if (settings.isSSLBypassEnabled) {
            Toast.makeText(ctx, R.string.operation_not_supported, Toast.LENGTH_SHORT).show()
            return
        }
        buildAlertDialog(ctx) {
            setTitle(R.string.ignore_ssl_errors)
            setMessage(R.string.ignore_ssl_errors_summary)
            setPositiveButton(R.string.apply) { _, _ ->
                settings.isSSLBypassEnabled = true
                Toast.makeText(ctx, R.string.settings_apply_restart_required, Toast.LENGTH_LONG).show()
                ctx.restartApplication()
            }
            setNegativeButton(android.R.string.cancel, null)
        }.show()
    }

    class Factory @Inject constructor(
        private val settings: AppSettings,
        private val mangaRepositoryFactory: ContentRepository.Factory,
        private val scrobblerAuthHelperProvider: Provider<ScrobblerAuthHelper>,
        private val captchaAutoResolveCoordinator: CaptchaAutoResolveCoordinator,
    ) {

        fun create(fragment: Fragment) = ExceptionResolver(
            host = Host.FragmentHost(fragment),
            settings = settings,
            mangaRepositoryFactory = mangaRepositoryFactory,
            scrobblerAuthHelperProvider = scrobblerAuthHelperProvider,
            captchaAutoResolveCoordinator = captchaAutoResolveCoordinator,
        )

        fun create(activity: FragmentActivity) = ExceptionResolver(
            host = Host.ActivityHost(activity),
            settings = settings,
            mangaRepositoryFactory = mangaRepositoryFactory,
            scrobblerAuthHelperProvider = scrobblerAuthHelperProvider,
            captchaAutoResolveCoordinator = captchaAutoResolveCoordinator,
        )
    }

    private sealed interface Host : ActivityResultCaller, LifecycleOwner {

        val context: Context?

        val router: AppRouter

        val fragmentManager: FragmentManager

        inline fun withContext(block: Context.() -> Unit) {
            context?.apply(block)
        }

        class ActivityHost(val activity: FragmentActivity) : Host,
            ActivityResultCaller by activity,
            LifecycleOwner by activity {

            override val context: Context
                get() = activity

            override val router: AppRouter
                get() = activity.router

            override val fragmentManager: FragmentManager
                get() = activity.supportFragmentManager
        }

        class FragmentHost(val fragment: Fragment) : Host,
            ActivityResultCaller by fragment {

            override val context: Context?
                get() = fragment.context

            override val router: AppRouter
                get() = fragment.router

            override val fragmentManager: FragmentManager
                get() = fragment.childFragmentManager

            override val lifecycle: Lifecycle
                get() = fragment.viewLifecycleOwner.lifecycle
        }
    }

    companion object {

        @StringRes
        fun getResolveStringId(e: Throwable) = when (e) {
            is CloudFlareProtectedException -> R.string.captcha_solve
            is ScrobblerAuthRequiredException,
            is AuthRequiredException -> R.string.sign_in

            is NotFoundException -> if (e.url.isHttpUrl()) R.string.open_in_browser else 0
            is UnsupportedSourceException -> if (e.manga != null) R.string.alternatives else 0
            is SSLException,
            is CertPathValidatorException -> R.string.fix

            is ProxyConfigException -> R.string.settings

            is InteractiveActionRequiredException -> R.string._continue

            is EmptyContentException -> when (e.reason) {
                EmptyContentReason.RESTRICTED -> if (e.manga.publicUrl.isHttpUrl()) R.string.open_in_browser else 0
                EmptyContentReason.NO_CHAPTERS -> R.string.alternatives
                else -> 0
            }

            else -> if (e.findInteractiveActionRequiredException() != null) R.string._continue else 0
        }

        fun canResolve(e: Throwable) = getResolveStringId(e) != 0
    }
}
