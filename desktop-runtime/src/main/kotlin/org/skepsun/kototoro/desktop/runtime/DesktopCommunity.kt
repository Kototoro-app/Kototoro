package org.skepsun.kototoro.desktop.runtime

import org.skepsun.kototoro.community.domain.CommunityCommentFilter
import org.skepsun.kototoro.core.library.community.CommunityBlocklist
import org.skepsun.kototoro.core.library.community.CommunitySnapshotCache
import org.skepsun.kototoro.core.source.SourcePreferenceEdit
import org.skepsun.kototoro.core.source.SourcePreferenceValue
import org.skepsun.kototoro.core.source.SourcePreferences
import org.skepsun.kototoro.core.text.ChineseScript
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.text.Normalizer
import java.util.Locale

/** The platform fold of community plan §3.2, the same steps as Android's: NFKC, traditional to simplified, lowercase. */
fun desktopCommunityFold(text: String): String =
    ChineseScript.toSimplified(Normalizer.normalize(text, Normalizer.Form.NFKC)).lowercase(Locale.ROOT)

/**
 * The downloaded community index under the data root (`community/snapshot`), each file under its snapshot path with
 * the hash of what was stored beside it, as Android keeps it under `files/community/snapshot`.
 */
class DesktopCommunitySnapshotCache(private val root: Path) : CommunitySnapshotCache {
    override fun read(path: String): String? = resolve(path)?.takeIf(Files::isRegularFile)?.let(Files::readString)

    override fun readHash(path: String): String? = resolve(path + HASH_SUFFIX)?.takeIf(Files::isRegularFile)?.let(Files::readString)

    override fun write(path: String, text: String, hash: String) {
        val file = resolve(path) ?: return
        Files.createDirectories(file.parent)
        val temp = file.resolveSibling(".${file.fileName}.part")
        Files.writeString(temp, text)
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        Files.writeString(file.resolveSibling(file.fileName.toString() + HASH_SUFFIX), hash)
    }

    override fun clear() {
        if (!Files.exists(root)) return
        Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }

    /** A manifest path is relative and never climbs out of the cache; anything else is not stored. */
    private fun resolve(path: String): Path? = root.resolve(path).normalize().takeIf { it.startsWith(root.normalize()) }

    private companion object {
        const val HASH_SUFFIX = ".sha256"
    }
}

/**
 * Android's `CommunityLocalStore`: authors the reader blocked and comments they reported, hidden at once and kept on
 * this device under Android's own keys.
 */
class DesktopCommunityBlocklist(private val preferences: SourcePreferences) : CommunityBlocklist {
    override val filter: CommunityCommentFilter
        get() = CommunityCommentFilter(blockedPubkeys = values(KEY_BLOCKED), blockedEvents = values(KEY_REPORTED))

    override fun block(pubkey: String) = add(KEY_BLOCKED, pubkey)

    override fun hide(eventId: String) = add(KEY_REPORTED, eventId)

    private fun values(key: String): Set<String> =
        (preferences.snapshot()[key] as? SourcePreferenceValue.TextSet)?.values.orEmpty()

    @Synchronized
    private fun add(key: String, value: String) {
        preferences.edit(SourcePreferenceEdit(changes = mapOf(key to SourcePreferenceValue.TextSet(values(key) + value))))
    }

    private companion object {
        const val KEY_BLOCKED = "blocked_pubkeys"
        const val KEY_REPORTED = "reported_events"
    }
}
