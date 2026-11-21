package org.skepsun.kototoro.desktop.app

import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.skepsun.kototoro.community.domain.CommunityThread
import org.skepsun.kototoro.community.domain.CommunityWorkIdentity
import org.skepsun.kototoro.community.domain.CommunityWorkSummary
import org.skepsun.kototoro.community.external.CommunityExternalSearchApi
import org.skepsun.kototoro.community.nostr.CommunityProfiles
import org.skepsun.kototoro.community.nostr.CommunityReportType
import org.skepsun.kototoro.community.nostr.NostrKeyPair
import org.skepsun.kototoro.community.nostr.NostrRelayClient
import org.skepsun.kototoro.community.snapshot.CommunitySnapshotException
import org.skepsun.kototoro.community.snapshot.CommunitySnapshotFetcher
import org.skepsun.kototoro.community.ui.CommunityCommentRow
import org.skepsun.kototoro.community.ui.CommunityCommentsViewState
import org.skepsun.kototoro.community.ui.CommunityDialog
import org.skepsun.kototoro.community.ui.CommunityDialogActions
import org.skepsun.kototoro.community.ui.CommunityRatingUiState
import org.skepsun.kototoro.community.ui.CommunitySuggestionsUiState
import org.skepsun.kototoro.community.ui.NicknameStatus
import org.skepsun.kototoro.core.library.community.CommunityCommentItem
import org.skepsun.kototoro.core.library.community.CommunityIdentityStore
import org.skepsun.kototoro.core.library.community.CommunityService
import org.skepsun.kototoro.core.library.community.CommunitySnapshots
import org.skepsun.kototoro.core.library.community.CommunitySwitches
import org.skepsun.kototoro.core.source.SourceContent
import org.skepsun.kototoro.core.source.SourcePreferenceEdit
import org.skepsun.kototoro.core.source.SourcePreferenceValue
import org.skepsun.kototoro.core.source.SourcePreferences
import org.skepsun.kototoro.desktop.runtime.DesktopCommunityBlocklist
import org.skepsun.kototoro.desktop.runtime.DesktopCommunitySnapshotCache
import org.skepsun.kototoro.desktop.runtime.DesktopRelayDns
import org.skepsun.kototoro.desktop.runtime.OkHttpNostrRelayConnector
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import org.skepsun.kototoro.desktop.runtime.DesktopServicesSettings
import org.skepsun.kototoro.desktop.runtime.desktopCommunityFold
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.Closeable
import java.nio.file.Path
import java.time.Duration
import java.util.Locale

/**
 * The community on Windows and macOS (community plan §7–8): Android's community view models over the shared
 * [CommunityService], with its own state flows so ratings and comments never hold the controller's operation gate.
 *
 * The secret key lives in the system credential store ([desktopCommunityCredentialStore]); only the public key is kept
 * in the `desktop_community` preferences beside the reader's blocks. Relays, the index and the AniList/Bangumi
 * searches go through one Java-engine client that follows the proxy page's selector. The outbox is retried by
 * [start]'s timer, Windows' counterpart of Android's WorkManager job.
 */
internal class DesktopCommunity(
    root: Path,
    private val preferences: SourcePreferences,
    database: org.skepsun.kototoro.core.db.MangaDatabase,
    proxy: org.skepsun.kototoro.desktop.runtime.DesktopProxy,
    rewriteGitHubUrl: (String) -> String,
    private val switches: () -> DesktopServicesSettings,
    private val saveSwitches: (DesktopServicesSettings) -> Unit,
    private val scope: CoroutineScope,
    private val credentials: DesktopDriveCredentialStore = desktopCommunityCredentialStore(root),
) : Closeable {
    /** Relays and the index: the proxy page's route, credentials included, and DoH for relay names when direct. */
    private val okHttp = OkHttpClient.Builder()
        .proxySelector(proxy.selector)
        .proxyAuthenticator(proxy.authenticator)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val relayHttp = okHttp.newBuilder().dns(DesktopRelayDns(okHttp)).build()

    /** AniList and Bangumi through Ktor's Java engine, which the shared search API needs; same proxy and credentials. */
    private val http = HttpClient(Java) {
        engine {
            config {
                proxy(proxy.selector)
                authenticator(DesktopProxyCredentials(proxy))
                connectTimeout(Duration.ofSeconds(10))
            }
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            // Bangumi's search timed out at 20 s on one search in nine during the evaluation (plan §3.6).
            requestTimeoutMillis = 45_000
        }
    }

    private val identities = object : CommunityIdentityStore {
        @Volatile private var cached: NostrKeyPair? = null

        override val hasIdentity: Boolean get() = publicKey() != null

        override fun keys(): NostrKeyPair? {
            cached?.let { return it }
            val publicKey = publicKey() ?: return null
            val secret = runCatching { credentials.read() }.getOrNull() ?: return null
            // A key the credential store holds for another identity (an older profile) is not this reader's.
            return NostrKeyPair.fromSecretHex(secret)?.takeIf { it.publicKeyHex == publicKey }?.also { cached = it }
        }

        override fun store(keys: NostrKeyPair) {
            credentials.write(keys.secretKeyHex)
            preferences.edit(SourcePreferenceEdit(changes = mapOf(KEY_PUBLIC to SourcePreferenceValue.Text(keys.publicKeyHex))))
            cached = keys
        }

        override fun clear() {
            cached = null
            runCatching { credentials.clear() }
            preferences.edit(SourcePreferenceEdit(changes = mapOf(KEY_PUBLIC to null)))
        }

        private fun publicKey(): String? =
            (preferences.snapshot()[KEY_PUBLIC] as? SourcePreferenceValue.Text)?.value?.takeIf(String::isNotBlank)
    }

    val service = CommunityService(
        database = database,
        identities = identities,
        blocklist = DesktopCommunityBlocklist(preferences),
        snapshots = CommunitySnapshots(
            fetch = { path -> fetchSnapshot(rewriteGitHubUrl(CommunitySnapshotFetcher.RAW_BASE_URL + path)) },
            cache = DesktopCommunitySnapshotCache(root.resolve("community/snapshot")),
            io = Dispatchers.IO,
        ),
        relayClient = NostrRelayClient(OkHttpNostrRelayConnector(relayHttp)),
        searchApi = CommunityExternalSearchApi(http),
        switches = { switches().let { CommunitySwitches(it.communityEnabled, it.communityNsfwRatings) } },
        fold = ::desktopCommunityFold,
        io = Dispatchers.IO,
    )

    // Services page

    private val mutableDialog = MutableStateFlow<CommunityDialog?>(null)
    val dialog: StateFlow<CommunityDialog?> = mutableDialog.asStateFlow()

    /** A short confirmation the services page shows after a copy; cleared once it has been read. */
    private val mutableNotice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = mutableNotice.asStateFlow()

    fun clearNotice() {
        mutableNotice.value = null
    }

    /**
     * Android's community switch: the first time, the reader sees what becomes public and picks a new identity or a
     * recovery key; the feature is on only after that. Turning it off keeps the identity for next time.
     */
    fun setEnabled(enabled: Boolean) {
        when {
            !enabled -> saveSwitches(switches().copy(communityEnabled = false))
            identities.hasIdentity -> io {
                if (identities.keys() != null) turnOn() else mutableDialog.value = CommunityDialog.Restore()
            }
            else -> mutableDialog.value = CommunityDialog.Onboarding
        }
    }

    fun setNsfwRatings(enabled: Boolean) = saveSwitches(switches().copy(communityNsfwRatings = enabled))

    fun showRecoveryKey() = io {
        mutableDialog.value = service.recoveryKey()?.let(CommunityDialog::RecoveryKey) ?: CommunityDialog.Restore()
    }

    fun showNickname() {
        mutableDialog.value = CommunityDialog.Nickname(busy = true)
        io {
            if (identities.keys() == null) {
                mutableDialog.value = CommunityDialog.Restore()
                return@io
            }
            val result = attempt { service.nickname().orEmpty() }
            mutableDialog.value = CommunityDialog.Nickname(
                saved = result.getOrDefault(""),
                status = NicknameStatus.FAILED.takeIf { result.isFailure },
            )
        }
    }

    val dialogActions = object : CommunityDialogActions {
        override fun dismiss() {
            mutableDialog.value = null
        }

        override fun createIdentity() = io {
            val created = attempt { service.createIdentity() }
            if (created.isFailure) {
                mutableDialog.value = null
                mutableNotice.value = desktopText("community.credentialStoreFailed")
                return@io
            }
            turnOn()
            mutableDialog.value = service.recoveryKey()?.let(CommunityDialog::RecoveryKey)
        }

        override fun startRestore() {
            mutableDialog.value = CommunityDialog.Restore()
        }

        override fun restore(recoveryKey: String) = io {
            val restored = attempt { service.restoreIdentity(recoveryKey) }
            when {
                restored.isFailure -> {
                    mutableDialog.value = null
                    mutableNotice.value = desktopText("community.credentialStoreFailed")
                }
                restored.getOrNull() == null -> mutableDialog.value = CommunityDialog.Restore(invalid = true)
                else -> {
                    turnOn()
                    mutableDialog.value = null
                }
            }
        }

        override fun saveNickname(nickname: String) {
            val dialog = mutableDialog.value as? CommunityDialog.Nickname ?: return
            val value = nickname.trim()
            if (dialog.busy || value == dialog.saved || value.isEmpty() || value.length > CommunityProfiles.MAX_NAME_LENGTH) return
            mutableDialog.value = dialog.copy(busy = true, status = null)
            io {
                val result = attempt { service.setNickname(value) }
                mutableDialog.value = when (result.getOrNull()) {
                    true -> CommunityDialog.Nickname(value, status = NicknameStatus.PUBLISHED)
                    false -> CommunityDialog.Nickname(value, status = NicknameStatus.PENDING)
                    null -> dialog.copy(status = NicknameStatus.FAILED)
                }
            }
        }

        override fun copyRecoveryKey(key: String) {
            val copied = runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(key), null) }
            mutableNotice.value = if (copied.isSuccess) AndroidStrings["community_recovery_key_copied"]
                else desktopText("community.copyFailed")
        }
    }

    private fun turnOn() {
        saveSwitches(switches().copy(communityEnabled = true))
        flushSoon()
    }

    // Details page

    private data class Target(val content: SourceContent, val identity: CommunityWorkIdentity, val summary: CommunityWorkSummary?)

    private val mutableRating = MutableStateFlow<CommunityRatingUiState?>(null)
    /** The open work's rating card; null while the community is off or nothing is open. */
    val rating: StateFlow<CommunityRatingUiState?> = mutableRating.asStateFlow()
    @Volatile private var target: Target? = null
    private var ratingJob: Job? = null
    private var myStarsJob: Job? = null
    @Volatile private var boundKey: String? = null

    /**
     * Follows the details page: what is already known (titles, the reader's tracker bindings, earlier searches) answers
     * at once; the AniList/Bangumi searches can take tens of seconds and only replace it when they found another key.
     * Nothing runs, and no tracker site is searched, unless the reader turned the community on.
     */
    fun bindDetails(content: SourceContent?) {
        val key = content?.takeIf { switches().communityEnabled }?.let { "${it.source.name}:${it.id}" }
        if (key == boundKey) return
        boundKey = key
        ratingJob?.cancel()
        myStarsJob?.cancel()
        target = null
        mutableRating.value = null
        // A comment list belongs to the work it was opened on.
        closeComments()
        if (content == null || key == null) return
        myStarsJob = scope.launch {
            service.observeMyRating(content.id).collect { stars -> mutableRating.update { it?.copy(myStars = stars) } }
        }
        ratingJob = scope.launch {
            val known = service.identity(content, resolve = false)
            publishRating(content, known, null, loading = true)
            val summary = service.summary(known)
            publishRating(content, known, summary, loading = false)
            refreshFresh(content, known)
            val resolved = attempt { service.identity(content, resolve = true) }.getOrNull() ?: return@launch
            if (resolved.keys != known.keys) {
                publishRating(content, resolved, service.summary(resolved) ?: summary, loading = false)
            }
        }
    }

    private fun publishRating(content: SourceContent, identity: CommunityWorkIdentity, summary: CommunityWorkSummary?, loading: Boolean) {
        target = Target(content, identity, summary)
        mutableRating.update { current ->
            CommunityRatingUiState(
                isLoading = loading,
                rating = summary?.rating,
                myStars = current?.myStars,
                comments = summary?.comments ?: 0,
                similar = summary?.similar.orEmpty().filter { !it.nsfw || switches().communityNsfwRatings },
                refusal = service.refusal(identity),
                isPublishing = current?.isPublishing ?: false,
                freshRatings = current?.freshRatings ?: 0,
                myRatingFresh = current?.myRatingFresh ?: false,
            )
        }
    }

    /** The ratings the snapshot does not hold yet, the reader's own among them; nothing changes when relays are down. */
    private suspend fun refreshFresh(content: SourceContent, identity: CommunityWorkIdentity) {
        val fresh = attempt { service.freshRatings(content.id, identity) }.getOrNull() ?: return
        if (target?.content?.id != content.id) return
        mutableRating.update { it?.copy(freshRatings = fresh.count, myRatingFresh = fresh.includesMine) }
    }

    fun rate(stars: Int) = publishRatingChange { target -> service.rate(target.content.id, target.identity, stars) }

    fun withdraw() = publishRatingChange { target -> service.withdraw(target.content.id) }

    private fun publishRatingChange(block: suspend (Target) -> Unit) {
        val target = target ?: return
        scope.launch {
            mutableRating.update { it?.copy(isPublishing = true) }
            try {
                attempt { block(target) }
            } finally {
                mutableRating.update { it?.copy(isPublishing = false, refusal = service.refusal(target.identity)) }
            }
            refreshFresh(target.content, target.identity)
        }
    }

    // Comments

    private val mutableComments = MutableStateFlow<CommunityCommentsViewState?>(null)
    /** The open comment list; null while it is closed. */
    val comments: StateFlow<CommunityCommentsViewState?> = mutableComments.asStateFlow()
    @Volatile private var commentItems: Map<String, CommunityCommentItem> = emptyMap()
    private var commentsJob: Job? = null

    fun openComments() {
        val target = target ?: return
        mutableComments.value = CommunityCommentsViewState(refusal = service.refusal(target.identity))
        reloadComments()
    }

    fun closeComments() {
        commentsJob?.cancel()
        mutableComments.value = null
        commentItems = emptyMap()
    }

    fun reloadComments() {
        val target = target ?: return
        commentsJob?.cancel()
        commentsJob = scope.launch {
            mutableComments.update { it?.copy(isLoading = true) }
            val page = attempt { service.comments(target.identity, target.summary) }.getOrNull()
            if (page != null) commentItems = page.threads.flatMap { listOf(it.root) + it.replies }.associateBy { it.comment.id }
            mutableComments.update { current ->
                current?.copy(
                    isLoading = false,
                    threads = page?.threads?.map { thread ->
                        CommunityThread(thread.root.toRow(), thread.replies.map { it.toRow() })
                    } ?: current.threads,
                    relaysAnswered = page?.relaysAnswered ?: current.relaysAnswered ?: 0,
                )
            }
        }
    }

    fun replyTo(row: CommunityCommentRow?) = mutableComments.update { it?.copy(replyTo = row) }

    fun post(text: String, spoiler: Boolean) {
        val target = target ?: return
        val parent = mutableComments.value?.replyTo?.let { commentItems[it.id]?.event }
        scope.launch {
            mutableComments.update { it?.copy(isPosting = true) }
            val refusal = attempt {
                service.postComment(target.identity, text.trim(), parent, spoiler, Locale.getDefault().language)
            }.getOrNull()
            mutableComments.update { it?.copy(isPosting = false, refusal = refusal ?: it.refusal, replyTo = null) }
            reloadComments()
        }
    }

    fun delete(row: CommunityCommentRow) = changeComments { commentItems[row.id]?.let { service.deleteComment(it.event) } }

    fun report(row: CommunityCommentRow, type: CommunityReportType) =
        changeComments { commentItems[row.id]?.let { service.report(it.event, type) } }

    fun block(row: CommunityCommentRow) = changeComments { service.block(row.pubkey) }

    private fun changeComments(block: suspend () -> Unit) {
        scope.launch {
            attempt { block() }
            reloadComments()
        }
    }

    // Suggestions page

    private val mutableChart = MutableStateFlow(CommunitySuggestionsUiState())
    val chart: StateFlow<CommunitySuggestionsUiState> = mutableChart.asStateFlow()
    private var chartJob: Job? = null

    /** Loads the chart of [window] (Android's `CommunitySuggestionsViewModel`); empty while the community is off. */
    fun loadChart(window: String = mutableChart.value.window) {
        chartJob?.cancel()
        if (!switches().communityEnabled) {
            mutableChart.value = CommunitySuggestionsUiState()
            return
        }
        mutableChart.update { it.copy(window = window) }
        chartJob = scope.launch {
            val page = service.chart(window)
            mutableChart.value = CommunitySuggestionsUiState(true, page.window, page.entries, page.isFallback)
        }
    }

    // Outbox

    private var outboxJob: Job? = null

    /**
     * Retries what the outbox still holds: soon after start, then every ten minutes while anything is left. An event
     * that was refused waits its own backoff inside [CommunityService.flushOutbox], so a tick costs nothing then.
     */
    fun start() {
        outboxJob?.cancel()
        outboxJob = scope.launch {
            delay(STARTUP_DELAY_MILLIS)
            while (isActive) {
                if (switches().communityEnabled && identities.hasIdentity) attempt { service.flushOutbox() }
                delay(OUTBOX_INTERVAL_MILLIS)
            }
        }
    }

    private fun flushSoon() {
        scope.launch { attempt { service.flushOutbox() } }
    }

    override fun close() {
        outboxJob?.cancel()
        http.close()
        okHttp.dispatcher.executorService.shutdown()
        okHttp.connectionPool.evictAll()
    }

    /** One snapshot file through the GitHub mirror; null when the index has no such file. */
    private suspend fun fetchSnapshot(url: String): String? = withContext(Dispatchers.IO) {
        okHttp.newCall(Request.Builder().url(url).build()).execute().use { response ->
            when {
                response.code == 404 -> null
                response.isSuccessful -> response.body.string()
                else -> throw CommunitySnapshotException(response.code)
            }
        }
    }

    private fun io(block: suspend () -> Unit) {
        scope.launch { block() }
    }

    private inline fun <T> attempt(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Result.failure(error)
    }

    private fun CommunityCommentItem.toRow() = CommunityCommentRow(
        id = comment.id,
        pubkey = comment.pubkey,
        author = author,
        content = comment.content,
        createdAt = comment.createdAt,
        spoiler = comment.spoiler,
        isMine = isMine,
        isPending = isPending,
    )

    private companion object {
        const val KEY_PUBLIC = "public_key"
        const val STARTUP_DELAY_MILLIS = 15_000L
        const val OUTBOX_INTERVAL_MILLIS = 10 * 60_000L
    }
}

/**
 * The proxy page's credentials for the JDK client: it asks a `java.net.Authenticator` where OkHttp asks its own. Only an
 * HTTP proxy challenges; a server's own challenge gets nothing from here.
 */
private class DesktopProxyCredentials(
    private val proxy: org.skepsun.kototoro.desktop.runtime.DesktopProxy,
) : java.net.Authenticator() {
    override fun getPasswordAuthentication(): java.net.PasswordAuthentication? {
        val value = proxy.settings.value
        if (requestorType != RequestorType.PROXY || value.type != org.skepsun.kototoro.desktop.runtime.DesktopProxyType.HTTP ||
            (value.login.isBlank() && value.password.isEmpty())) return null
        return java.net.PasswordAuthentication(value.login, value.password.toCharArray())
    }
}
