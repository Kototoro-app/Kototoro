package org.skepsun.kototoro.source.host

import kotlinx.serialization.Serializable
import org.skepsun.kototoro.core.source.*
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.*
import java.security.MessageDigest

data class FileSourcePreferenceSnapshot(val namespace: String, val byteSize: Long, val sha256: String)

/** One process owns the directory. Atomic UTF-8 snapshots never use extension names as filesystem paths. */
class FileSourcePreferenceStore(root: Path, private val maximumBytes: Int = 4 * 1024 * 1024) :
    SourcePreferenceStore, Closeable {
    val directory: Path
    private val lockChannel: FileChannel
    private val fileLock: FileLock
    private val preferences = mutableMapOf<String, Preferences>()
    private var closed = false

    init {
        require(maximumBytes in 1 until Int.MAX_VALUE)
        val absolute = root.toAbsolutePath().normalize()
        Files.createDirectories(absolute)
        require(Files.isDirectory(absolute, NOFOLLOW_LINKS)) { "Preference directory must be a real directory" }
        directory = absolute.toRealPath()
        lockChannel = FileChannel.open(directory.resolve(".owner.lock"), CREATE, WRITE, NOFOLLOW_LINKS)
        try {
            fileLock = lockChannel.tryLock() ?: throw IOException("Preference directory already has an owner")
        } catch (error: Throwable) {
            try { lockChannel.close() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
            throw error
        }
    }

    @Synchronized
    override fun open(namespace: String): SourcePreferences {
        check(!closed) { "Preference store is closed" }
        require(namespace.isNotEmpty() && namespace.length <= 4096) { "Invalid preference namespace" }
        return preferences.getOrPut(namespace) { Preferences(namespace) }
    }

    /** Read actual disk state, including namespaces not opened in this process. Unknown documents fail closed. */
    @Synchronized
    fun snapshots(checkpoint: () -> Unit = {}): List<FileSourcePreferenceSnapshot> {
        check(!closed) { "Preference store is closed" }
        return Files.list(directory).use { files ->
            files.filter { it.fileName.toString().matches(Regex("[0-9a-f]{64}\\.json")) }.iterator().asSequence().map { path ->
                checkpoint()
                val bytes = snapshotBytes(path)
                val document = decodeDocument(bytes)
                require(path.fileName.toString() == namespaceHash(document.namespace) + ".json")
                checkpoint()
                FileSourcePreferenceSnapshot(document.namespace, bytes.size.toLong(), digest(bytes))
            }.toList()
        }
    }

    @Synchronized
    fun readStored(snapshot: FileSourcePreferenceSnapshot): Map<String, SourcePreferenceValue> {
        check(!closed) { "Preference store is closed" }
        val bytes = checkedSnapshot(snapshot)
        val document = decodeDocument(bytes)
        require(document.namespace == snapshot.namespace)
        return copyValues(document.values)
    }

    /** Caller owns reference analysis and user confirmation; changed snapshots are never removed. */
    @Synchronized
    fun removeSnapshot(snapshot: FileSourcePreferenceSnapshot): Boolean {
        check(!closed) { "Preference store is closed" }
        val path = directory.resolve(namespaceHash(snapshot.namespace) + ".json")
        if (!Files.exists(path, NOFOLLOW_LINKS)) return false
        val document = decodeDocument(checkedSnapshot(snapshot))
        require(document.namespace == snapshot.namespace)
        Files.delete(path)
        preferences.remove(snapshot.namespace)?.let { it.retired = true; it.listeners.clear() }
        return true
    }

    private fun checkedSnapshot(snapshot: FileSourcePreferenceSnapshot): ByteArray {
        val path = directory.resolve(namespaceHash(snapshot.namespace) + ".json")
        val bytes = snapshotBytes(path)
        if (bytes.size.toLong() != snapshot.byteSize || digest(bytes) != snapshot.sha256) {
            throw IOException("Preference snapshot changed")
        }
        return bytes
    }

    private fun snapshotBytes(path: Path): ByteArray {
        require(Files.isRegularFile(path, NOFOLLOW_LINKS)) { "Invalid preference snapshot" }
        return Files.newInputStream(path, NOFOLLOW_LINKS).use { it.readNBytes(maximumBytes + 1) }.also {
            require(it.size <= maximumBytes) { "Preference snapshot exceeds configured limit" }
        }
    }

    private fun decodeDocument(bytes: ByteArray): Document {
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        return SourceProtocolJson.decodeFromString<Document>(text).also {
            require(it.version == 1 && it.namespace.isNotEmpty()) { "Invalid preference identity/version" }
        }
    }

    private fun namespaceHash(namespace: String) = digest(namespace.toByteArray(Charsets.UTF_8))
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        preferences.values.forEach { it.listeners.clear() }
        preferences.clear()
        try { fileLock.release() } finally { lockChannel.close() }
    }

    private inner class Preferences(private val namespace: String) : SourcePreferences {
        private val hash = namespaceHash(namespace)
        private val path = directory.resolve("$hash.json")
        private var values: Map<String, SourcePreferenceValue> = read()
        val listeners = linkedSetOf<SourcePreferenceListener>()
        var retired = false

        private fun checkActive() {
            check(!closed && !retired) { "Preference snapshot is closed or removed" }
        }

        override fun snapshot(): Map<String, SourcePreferenceValue> = synchronized(this@FileSourcePreferenceStore) {
            checkActive()
            copyValues(values)
        }

        override fun edit(edit: SourcePreferenceEdit): Boolean {
            val changed: Set<String>
            val callbacks: List<SourcePreferenceListener>
            val persisted: Boolean
            synchronized(this@FileSourcePreferenceStore) {
                checkActive()
                val next = if (edit.clear) mutableMapOf() else copyValues(values).toMutableMap()
                for ((key, value) in edit.changes) {
                    if (value == null) next.remove(key) else next[key] = copyValue(value)
                }
                val bytes = SourceProtocolJson.encodeToString(Document(1, namespace, next)).toByteArray(Charsets.UTF_8)
                require(bytes.size <= maximumBytes) { "Preference snapshot exceeds configured limit" }
                val previous = if (edit.clear) emptyMap() else values
                changed = edit.changes.keys.filterTo(linkedSetOf()) { previous[it] != next[it] }
                // Android commit publishes memory even if its disk write fails. A later edit retries the full snapshot.
                values = next
                persisted = try { write(bytes); true } catch (_: IOException) { false }
                callbacks = listeners.toList()
            }
            // No store monitor is held while executing extension callbacks (which can edit preferences themselves).
            if (changed.isNotEmpty() || edit.clear) callbacks.forEach {
                it.onChanged(SourcePreferenceChange(changed.toSet(), edit.clear))
            }
            return persisted
        }

        override fun addListener(listener: SourcePreferenceListener) = synchronized(this@FileSourcePreferenceStore) {
            checkActive()
            listeners.add(listener)
            Unit
        }

        override fun removeListener(listener: SourcePreferenceListener) = synchronized(this@FileSourcePreferenceStore) {
            listeners.remove(listener)
            Unit
        }

        private fun read(): Map<String, SourcePreferenceValue> {
            if (!Files.exists(path, NOFOLLOW_LINKS)) return emptyMap()
            val document = decodeDocument(snapshotBytes(path))
            require(document.namespace == namespace) { "Invalid preference identity/version" }
            return copyValues(document.values)
        }

        private fun write(bytes: ByteArray) {
            if (Files.exists(path, NOFOLLOW_LINKS) && !Files.isRegularFile(path, NOFOLLOW_LINKS)) {
                throw IOException("Invalid preference snapshot")
            }
            val temporary = Files.createTempFile(directory, ".prefs-", ".part")
            var failure: Throwable? = null
            try {
                FileChannel.open(temporary, WRITE).use { channel ->
                    val buffer = ByteBuffer.wrap(bytes)
                    while (buffer.hasRemaining()) channel.write(buffer)
                    channel.force(true)
                }
                Files.move(temporary, path, ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (error: Throwable) {
                failure = error
                throw error
            } finally {
                // Only this operation's staging file is removed; persisted snapshots are never cleared on failure.
                try { Files.deleteIfExists(temporary) } catch (cleanup: Throwable) {
                    if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
                }
            }
        }
    }

    @Serializable
    private data class Document(val version: Int, val namespace: String, val values: Map<String, SourcePreferenceValue>)

    private fun copyValues(values: Map<String, SourcePreferenceValue>) = values.mapValues { copyValue(it.value) }
    private fun copyValue(value: SourcePreferenceValue): SourcePreferenceValue =
        if (value is SourcePreferenceValue.TextSet) value.copy(values = value.values.toSet()) else value
}
