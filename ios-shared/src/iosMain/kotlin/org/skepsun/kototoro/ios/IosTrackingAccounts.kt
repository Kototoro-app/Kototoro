package org.skepsun.kototoro.ios

import io.ktor.client.HttpClient
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.HttpRequestTimeoutException
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import org.skepsun.kototoro.core.library.SourceTracking
import org.skepsun.kototoro.core.library.requireTrackingCatalogIdentity
import org.skepsun.kototoro.scrobbling.common.data.ScrobblingEntity
import org.skepsun.kototoro.tracking.discovery.*
import org.skepsun.kototoro.tracking.domain.TrackingChapter
import org.skepsun.kototoro.tracking.domain.resolveAbsoluteChapterNumber

class IosTrackingAccount internal constructor(val service: Int, val username: String, val userId: String)

class IosTrackingProgress internal constructor(internal val value: TrackingEntry, val exists: Boolean) {
    val status: String get() = value.status
    val progress: Int get() = value.progress
    val score: Float get() = value.score
    val note: String get() = value.note
}

class IosTrackingCollectionItem internal constructor(value: TrackingCollectionItem) {
    val item = IosTrackingItem(value.item)
    val progress = value.entry.progress
    val status = value.entry.status
}

/** Swift persists only session JSON in Keychain; passwords and authorization codes are never retained. */
class IosTrackingAccounts internal constructor(private val library: IosLibrary, client: HttpClient) {
    private val api = TrackingAccountApi(client, trackingClients)
    private val sessions = mutableMapOf<Int, TrackingSession>()
    private val mutex = Mutex()

    @Throws(Exception::class)
    suspend fun restore(serialized: String) = mutex.withLock {
        val session = Json.decodeFromString<TrackingSession>(serialized)
        require(PublicTrackingSite.entries.any { it.id == session.service } && session.accessToken.isNotBlank()
            && session.userId.toLongOrNull()?.let { it > 0 } == true)
        sessions[session.service] = session
    }

    @Throws(Exception::class)
    suspend fun serialized(service: Int): String? = mutex.withLock {
        sessions[service]?.let { Json.encodeToString(it) }
    }

    @Throws(Exception::class)
    suspend fun accounts(): List<IosTrackingAccount> = mutex.withLock {
        sessions.values.map { IosTrackingAccount(it.service, it.username, it.userId) }
    }

    @Throws(Exception::class)
    fun authorizationUrl(service: Int, state: String, verifier: String): String = api.authorizationUrl(service, state, verifier)

    @Throws(Exception::class)
    suspend fun authorize(service: Int, code: String, verifier: String) = mutex.withLock {
        sessions[service] = friendly { api.authorize(service, code, verifier) }
    }

    @Throws(Exception::class)
    suspend fun login(service: Int, username: String, password: String) = mutex.withLock {
        sessions[service] = friendly { api.login(service, username, password) }
    }

    @Throws(Exception::class)
    suspend fun connectToken(service: Int, token: String) = mutex.withLock {
        sessions[service] = friendly { api.connectToken(service, token) }
    }

    @Throws(Exception::class)
    suspend fun disconnect(service: Int) = mutex.withLock { sessions.remove(service); Unit }

    @Throws(Exception::class)
    suspend fun progress(content: IosSourceContent, item: IosTrackingItem): IosTrackingProgress =
        authenticated(item.service) { session ->
            val entry = api.entry(session, item.value.remoteId, accountKind(content.mediaKind, item.remoteType))
            IosTrackingProgress(entry ?: TrackingEntry(item.value.remoteId), entry != null)
        }

    @Throws(Exception::class)
    suspend fun collection(service: Int, mediaKind: String, status: String, page: Int): List<IosTrackingCollectionItem> =
        authenticated(service) { api.collection(it, mediaKind, status, page).map(::IosTrackingCollectionItem) }

    @Throws(Exception::class)
    suspend fun isTracked(content: IosSourceContent, item: IosTrackingItem): Boolean =
        library.database.getScrobblingDao().findByLocalManga(item.service, content.value.id)?.targetId == item.value.remoteId

    @Throws(Exception::class)
    suspend fun stopTracking(content: IosSourceContent, service: Int) =
        library.database.getScrobblingDao().deleteByLocalManga(service, content.value.id)

    /** Explicit user action: preserve the source and independently enable account tracking. */
    @Throws(Exception::class)
    suspend fun update(content: IosSourceContent, item: IosTrackingItem, status: String, progress: Int,
        score: Float, note: String): IosTrackingProgress = authenticated(item.service) { session ->
        require(item.mediaKind == content.mediaKind) { "作品类型不匹配" }
        requireTrackingCatalogIdentity(library.database.getTrackingSiteDao().findItem(item.service, item.value.remoteId),
            item.value.toEntity())
        val kind = accountKind(content.mediaKind, item.remoteType)
        val old = api.entry(session, item.value.remoteId, kind)
        if (item.service == 7 && kind != "VIDEO_MOVIE") require(progress >= (old?.progress ?: 0)) { "SIMKL 当前只支持增加已看集数" }
        val updated = api.update(session, kind,
            (old ?: TrackingEntry(item.value.remoteId)).copy(status = status, progress = progress, score = score, note = note), old != null)
        library.saveDetails(content)
        SourceTracking(library.database).bind(content.value, item.value.toEntity(), useMetadata = false)
        save(content, item.service, updated)
        IosTrackingProgress(updated, true)
    }

    /** Read from the same stored chapter identity as the reader; never infer progress from a title string. */
    @Throws(Exception::class)
    suspend fun localProgress(content: IosSourceContent): Int {
        val history = library.records.progress(content.value.id) ?: return 0
        val chapters = content.value.chapters.orEmpty().map { TrackingChapter(it.id, it.number, it.branch, it.title, it.url) }
        val chapter = chapters.firstOrNull { it.id == history.chapterId } ?: return 0
        return resolveAbsoluteChapterNumber(chapters, chapter)
    }

    /** Called after reading only for sites explicitly opted into automatic progress updates. */
    @Throws(Exception::class)
    suspend fun syncProgress(content: IosSourceContent, service: Int): Boolean = authenticated(service) { session ->
        val local = localProgress(content)
        if (local <= 0) return@authenticated false
        val stored = library.database.getScrobblingDao().findByLocalManga(service, content.value.id) ?: return@authenticated false
        val kind = accountKind(content.mediaKind, stored.mediaType)
        val target = if (kind == "VIDEO_MOVIE") 1 else local
        if (target <= stored.chapter) return@authenticated false
        val remote = api.entry(session, stored.targetId, kind) ?: return@authenticated false
        if (target <= remote.progress) return@authenticated false
        // Progress-only writes preserve the remote status, score and notes.
        val updated = api.advance(session, kind, remote, target)
        save(content, service, updated)
        true
    }

    private suspend fun save(content: IosSourceContent, service: Int, entry: TrackingEntry) {
        val item = SourceTracking(library.database).bindings(content.value.id)
            .firstOrNull { it.service == service && it.remoteId == entry.remoteId }
        val entity = ScrobblingEntity(service, entry.entryId.toInt(), content.value.id,
            entry.remoteId, api.wireStatus(service, entry.status, content.mediaKind), entry.progress, entry.note, entry.score / 10f,
            if (service == 7) when { item?.siteUrl?.contains("/movies/") == true -> "movies"
                item?.siteUrl?.contains("/tv/") == true -> "tv"; else -> "anime" }
            else if (content.mediaKind == "VIDEO") "anime" else "manga", item?.title, item?.coverUrl, item?.siteUrl)
        library.database.useWriterConnection { connection -> connection.immediateTransaction {
            library.database.getScrobblingDao().deleteByLocalManga(service, content.value.id)
            library.database.getScrobblingDao().upsert(entity)
        } }
    }

    private fun accountKind(kind: String, remoteType: String?): String = when (remoteType) {
        "tv" -> "VIDEO_TV"; "movies" -> "VIDEO_MOVIE"; else -> kind
    }

    private suspend fun <T> authenticated(service: Int, action: suspend (TrackingSession) -> T): T = friendly { mutex.withLock {
        val session = sessions[service] ?: error("请先连接 ${trackingSiteTitle(service)} 账号")
        try {
            action(session)
        } catch (error: ResponseException) {
            if (error.response.status.value != 401) throw error
            if (session.refreshToken == null) throw IllegalStateException("授权已过期，请重新连接账号", error)
            val refreshed = api.refresh(session)
            sessions[service] = refreshed
            action(refreshed)
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
    } }

    private suspend fun <T> friendly(action: suspend () -> T): T = try {
        action()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: HttpRequestTimeoutException) {
        throw IllegalStateException("连接超时，请检查网络后重试", error)
    } catch (error: ResponseException) {
        val status = error.response.status.value
        throw IllegalStateException(when (status) {
            400, 422 -> "网站未接受当前信息，请检查账号或追踪内容后重试"
            401, 403 -> "授权无效或已过期，请重新连接账号"
            429 -> "请求过于频繁，请稍后重试"
            else -> "网站暂时无法响应（HTTP $status），请稍后重试"
        }, error)
    }
}
