package org.skepsun.kototoro.scrobbling.mangabaka.data

import androidx.room.withTransaction
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.scrobbling.common.data.ScrobblerRepository
import org.skepsun.kototoro.scrobbling.common.data.ScrobblerStorage
import org.skepsun.kototoro.scrobbling.common.data.ScrobblingEntity
import org.skepsun.kototoro.scrobbling.common.data.deleteScrobblingByManga
import org.skepsun.kototoro.scrobbling.common.data.findScrobblingByManga
import org.skepsun.kototoro.scrobbling.common.data.upsertScrobblingForManga
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerContent
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerContentInfo
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerUser
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest

class MangaBakaRepository(
    private val api: MangaBakaApi,
    private val storage: ScrobblerStorage,
    val auth: MangaBakaAuth,
    private val db: MangaDatabase,
) : ScrobblerRepository {
    private val mutex = Mutex()
    private val service = ScrobblerService.MANGABAKA

    override val oauthUrl: String get() = auth.beginAuthorization()
    override val isAuthorized: Boolean get() = !storage.accessToken.isNullOrBlank()
    override val cachedUser: ScrobblerUser? get() = storage.user

    override suspend fun authorize(code: String?) = mutex.withLock {
        require(!code.isNullOrBlank())
        val tokens = if (code.startsWith("mb-")) MangaBakaAuth.Tokens(code.trim(), null) else {
            // Only our auth Activity supplies this internal code/state pair.
            val parts = code.split('\n', limit = 2)
            require(parts.size == 2)
            auth.exchange(parts[0], parts[1])
        }
        val candidate = tokens.accessToken
        // Validate first; a rejected PAT must not replace a working session.
        val profile = api.request("v1/my/profile", candidate)["data"]!!.jsonObject
        require(profile.text("auth_type") == if (candidate.startsWith("mb-")) "pat" else "oauth") {
            "Unexpected MangaBaka authentication type"
        }
        if (profile.text("auth_type") == "oauth") {
            val scopes = profile.array("scopes").map { it.jsonPrimitive.content }.toSet()
            require(scopes.containsAll(setOf("library.read", "library.write"))) {
                "MangaBaka library permission required"
            }
        }
        val remoteUserId = profile.text("id") ?: throw IOException("Missing MangaBaka user ID")
        val previousUserId = storage["remote_user_id"]
        require(previousUserId == null || previousUserId == remoteUserId) {
            "Sign out of the previous MangaBaka account before switching accounts"
        }
        storage.refreshToken = tokens.refreshToken
        storage.accessToken = candidate
        storeProfile(profile)
        Unit
    }

    override suspend fun loadUser(): ScrobblerUser = authenticated { token ->
        storeProfile(api.request("v1/my/profile", token)["data"]!!.jsonObject)
    }

    private fun storeProfile(profile: JsonObject): ScrobblerUser {
        val id = profile.text("id") ?: throw IOException("Missing MangaBaka user ID")
        storage["remote_user_id"] = id
        storage["rating_step"] = profile["rating_steps"]?.jsonPrimitive?.intOrNull?.toString()
        // The common display model uses Long IDs; MangaBaka account IDs are opaque strings.
        val displayId = ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(id.toByteArray())).long
        return ScrobblerUser(
            id = displayId, nickname = profile.text("nickname") ?: profile.text("preferred_username") ?: "MangaBaka",
            avatar = profile.text("picture"), service = service,
        ).also { storage.user = it }
    }

    override fun logout() {
        storage.clear()
    }

    override suspend fun unregister(mangaId: Long) = mutex.withLock {
        db.deleteScrobblingByManga(service.id, mangaId)
    }

    override suspend fun findContent(query: String, offset: Int, isAnime: Boolean): List<ScrobblerContent> =
        if (isAnime) emptyList() else api.search(query, offset)

    override suspend fun getContentInfo(id: Long): ScrobblerContentInfo = api.details(id)

    override suspend fun createRate(mangaId: Long, content: ScrobblerContent) = mutex.withLock {
        val previousBinding = db.findScrobblingByManga(service.id, mangaId)
        authenticated { token ->
            val entry = api.entry(content.id, token) ?: run {
                api.request("v1/my/library/${content.id}", token, "POST", buildJsonObject {
                    put("state", "plan_to_read")
                })
                api.entry(content.id, token) ?: throw IOException("MangaBaka library entry was not created")
            }
            // Linking an existing remote entry never resets its progress, score or privacy.
            val linked = entry.copy(content = content).toEntity(mangaId)
            db.upsertScrobblingForManga(linked)
            if (previousBinding != null && previousBinding.targetId != content.id) {
                db.getScrobblingDao().delete(previousBinding)
            }
            db.getScrobblingDao().findAllByTargetId(service.id, content.id)
                .filter { it != linked && (it.mangaId == 0L || it.mangaId == mangaId) }
                .forEach { db.getScrobblingDao().delete(it) }
        }
        Unit
    }

    override suspend fun updateRate(rateId: Int, mangaId: Long, chapter: Int) = mutex.withLock {
        val entity = requireNotNull(db.findScrobblingByManga(service.id, mangaId))
        authenticated { token ->
            api.patch(entity.targetId, token, mangaBakaProgressPatch(chapter))
            db.upsertScrobblingForManga(entity.copy(chapter = chapter))
        }
        Unit
    }

    override suspend fun updateRate(
        rateId: Int, mangaId: Long, rating: Float, status: String?, comment: String?,
    ) = mutex.withLock {
        val entity = requireNotNull(db.findScrobblingByManga(service.id, mangaId))
        val previous = MangaBakaLibraryEntry(
            entity.id, entity.targetId, entity.status, entity.chapter, entity.rating, entity.comment,
        )
        val changes = mangaBakaInfoPatch(previous, rating, status, comment, storage["rating_step"]?.toIntOrNull() ?: 1)
        authenticated { token ->
            api.patch(entity.targetId, token, changes)
            val confirmed = api.entry(entity.targetId, token)
                ?: throw IOException("MangaBaka library entry disappeared")
            db.upsertScrobblingForManga(confirmed.toEntity(mangaId).copy(
                mediaType = entity.mediaType, remoteTitle = entity.remoteTitle,
                remoteCoverUrl = entity.remoteCoverUrl, remoteUrl = entity.remoteUrl,
            ))
        }
        Unit
    }

    suspend fun syncLibraryFromRemote(): Int = mutex.withLock {
        val entries = authenticated(api::library)
        // Fetch every page before applying changes. Failure must not erase the previous snapshot.
        db.withTransaction {
            val dao = db.getScrobblingDao()
            val existing = dao.findAllByScrobbler(service.id)
            val byTarget = existing.groupBy { it.targetId }
            entries.forEach { entry ->
                val owners = byTarget[entry.seriesId].orEmpty()
                if (owners.isEmpty()) dao.upsert(entry.toEntity(0L))
                else owners.forEach { old ->
                    val updated = entry.toEntity(old.mangaId)
                    if (old.id != updated.id || old.mediaType != updated.mediaType) dao.delete(old)
                    dao.upsert(updated)
                }
            }
            val remoteIds = entries.mapTo(hashSetOf()) { it.seriesId }
            existing.filter { it.targetId !in remoteIds }.forEach { dao.delete(it) }
        }
        entries.size
    }

    private fun MangaBakaLibraryEntry.toEntity(mangaId: Long) = ScrobblingEntity(
        scrobbler = service.id, id = id, mangaId = mangaId, targetId = seriesId,
        status = state, chapter = chapter, rating = rating, comment = note,
        mediaType = content?.mediaType.orEmpty(), remoteTitle = content?.name,
        remoteCoverUrl = content?.cover, remoteUrl = content?.url,
    )

    private suspend fun <T> authenticated(block: suspend (String) -> T): T {
        val token = storage.accessToken ?: throw MangaBakaHttpException(401)
        return try {
            block(token)
        } catch (e: MangaBakaHttpException) {
            if (e.statusCode != 401 || token.startsWith("mb-") || storage.refreshToken.isNullOrBlank()) throw e
            block(auth.refresh(token))
        }
    }
}
