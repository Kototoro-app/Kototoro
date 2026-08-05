package org.skepsun.kototoro.cloudstream.runtime

internal class CloudstreamLinkSessionCache<K, L, S>(
	private val ttlMillis: Long,
	private val nowMillis: () -> Long = System::currentTimeMillis,
) {
	private val sessions = HashMap<K, Session<L, S>>()

	@Synchronized
	fun prepare(key: K, clearCache: Boolean): Snapshot<L, S> {
		val now = nowMillis()
		val session = sessions.getOrPut(key) { Session(lastCachedTimestamp = now) }
		if (clearCache || now - session.lastCachedTimestamp > ttlMillis) {
			session.links.clear()
			session.subtitles.clear()
			session.saturated = false
		}
		return Snapshot(
			links = session.links.values.toList(),
			subtitles = session.subtitles.values.toList(),
			saturated = session.saturated,
		)
	}

	@Synchronized
	fun addLink(key: K, identity: String, link: L): Boolean {
		val now = nowMillis()
		val session = sessions.getOrPut(key) { Session(lastCachedTimestamp = now) }
		if (session.links.putIfAbsent(identity, link) != null) return false
		session.lastCachedTimestamp = now
		return true
	}

	@Synchronized
	fun addSubtitle(key: K, identity: String, subtitle: S): Boolean {
		val now = nowMillis()
		val session = sessions.getOrPut(key) { Session(lastCachedTimestamp = now) }
		if (session.subtitles.putIfAbsent(identity, subtitle) != null) return false
		session.lastCachedTimestamp = now
		return true
	}

	@Synchronized
	fun finish(key: K) {
		val now = nowMillis()
		val session = sessions.getOrPut(key) { Session(lastCachedTimestamp = now) }
		session.saturated = session.links.isNotEmpty()
		session.lastCachedTimestamp = now
	}

	private data class Session<L, S>(
		val links: LinkedHashMap<String, L> = LinkedHashMap(),
		val subtitles: LinkedHashMap<String, S> = LinkedHashMap(),
		var lastCachedTimestamp: Long,
		var saturated: Boolean = false,
	)

	data class Snapshot<L, S>(
		val links: List<L>,
		val subtitles: List<S>,
		val saturated: Boolean,
	)
}
