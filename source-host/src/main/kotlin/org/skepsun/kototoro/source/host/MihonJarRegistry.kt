package org.skepsun.kototoro.source.host

import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.skepsun.kototoro.core.source.LoadedMihonExtension
import org.skepsun.kototoro.core.source.MihonJarIdentity
import org.skepsun.kototoro.core.source.MihonJarMetadata
import org.skepsun.kototoro.core.source.MihonSourceDescriptor
import org.skepsun.kototoro.core.source.SourceEcosystem
import org.skepsun.kototoro.core.source.SourceRef
import java.io.Closeable
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean
import java.util.WeakHashMap
import java.util.zip.ZipFile
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * The embedding platform initializes the pinned compatibility runtime before constructing this registry.
 *
 * With a [catalogDirectory], each verified extension's source list is cached under its SHA-256. A later [load] of the
 * same artifact registers the sources from that catalog; the JAR is hashed and the extension constructed when one of
 * its sources is first called, so extensions whose sources go unused (switched off) never run at start-up.
 */
class MihonJarRegistry(
    private val compatibilityLoader: ClassLoader,
    private val inspector: MihonJarInspector = MihonJarInspector(),
    private val catalogDirectory: Path? = null,
) : Closeable {
    private class Constructed(val loader: URLClassLoader, val sources: Map<String, Any>)

    /** [constructed] is null until the first call when the sources came from a catalog. */
    private class Extension(val info: LoadedMihonExtension, val path: Path, @Volatile var constructed: Constructed?) {
        val keys: Set<String> = info.sources.mapTo(LinkedHashSet()) { it.source.name }
        var activeCalls: Int = 0
        var retired: Boolean = false
        val states: MutableMap<String, WeakHashMap<Any, Any>> = mutableMapOf()

        fun closeIfIdle() {
            if (retired && activeCalls == 0) synchronized(this) { constructed?.loader?.close() }
        }
    }

    private val extensions = linkedMapOf<String, Extension>()
    private val retirementFailures = mutableListOf<Exception>()
    private var closed = false

    /** Reads immutable APK artwork without constructing the source or making site requests. */
    @Synchronized
    internal fun icon(packageName: String): ByteArray? {
        requireOpen()
        val extension = extensions[packageName] ?: return null
        return ZipFile(extension.path.toFile()).use { zip ->
            val entry = zip.getEntry(org.skepsun.kototoro.dex.ApkIcon.ENTRY)
                ?: return org.skepsun.kototoro.dex.ApkIcon.read(zip)
            if (entry.size > 4 * 1024 * 1024) return null
            zip.getInputStream(entry).use { it.readNBytes(4 * 1024 * 1024 + 1) }
                .takeIf { it.size <= 4 * 1024 * 1024 }
        }
    }

    @Synchronized
    fun load(path: Path, expected: MihonJarIdentity): LoadedMihonExtension {
        return install(path, expected, false) {}
    }

    /** Builds and validates before replacing; failed persistence/construction leaves the old registry entry alive. */
    @Synchronized
    fun replace(path: Path, expected: MihonJarIdentity, beforePublish: (LoadedMihonExtension) -> Unit): LoadedMihonExtension {
        return install(path, expected, true, beforePublish)
    }

    private fun install(path: Path, expected: MihonJarIdentity, replace: Boolean,
        beforePublish: (LoadedMihonExtension) -> Unit): LoadedMihonExtension {
        requireOpen()
        // An install or update always constructs the extension; a start-up load may use the catalog.
        val cached = if (replace) null else readCatalog(expected)
        val metadata = if (cached != null) null else inspector.inspect(path).also { inspector.verify(it, expected) }
        val previous = extensions[expected.packageName]
        if (previous != null && !replace) throw SourceJarException(SourceJarFailure.ALREADY_LOADED)
        val extension = cached?.let { Extension(it, path, null) } ?: run {
            val (constructed, descriptors) = construct(path, requireNotNull(metadata))
            Extension(LoadedMihonExtension(metadata, descriptors), path, constructed)
        }
        try {
            val registeredKeys = extensions.values.filter { it !== previous }.flatMap { it.keys }.toSet()
            if (extension.keys.size != extension.info.sources.size || extension.keys.any { it in registeredKeys }) {
                throw SourceJarException(SourceJarFailure.DUPLICATE_SOURCE)
            }
            beforePublish(extension.info)
        } catch (error: Throwable) {
            try { extension.constructed?.loader?.close() } catch (closeError: Exception) { error.addSuppressed(closeError) }
            // As before: a failed publication (e.g. persisting the record) surfaces as an installation failure.
            when (error) {
                is SourceJarException -> throw error
                is Exception, is LinkageError -> throw SourceJarException(SourceJarFailure.CONSTRUCTION_FAILED, error)
                else -> throw error
            }
        }
        if (cached == null) writeCatalog(extension.info)
        extensions[extension.info.metadata.packageName] = extension
        if (previous != null) {
            try { retire(previous) } catch (error: Exception) { retirementFailures += error }
        }
        return extension.info
    }

    /** Runs the extension's entry points and describes their sources; any failure closes the class loader. */
    private fun construct(path: Path, metadata: MihonJarMetadata): Pair<Constructed, List<MihonSourceDescriptor>> {
        val anime = metadata.ecosystem == SourceEcosystem.ANIYOMI
        val catalogueType = compatibilityType(
            if (anime) "eu.kanade.tachiyomi.animesource.AnimeCatalogueSource" else "eu.kanade.tachiyomi.source.CatalogueSource")
        val factoryType = compatibilityType(
            if (anime) "eu.kanade.tachiyomi.animesource.AnimeSourceFactory" else "eu.kanade.tachiyomi.source.SourceFactory")
        val loader = URLClassLoader(arrayOf(path.toAbsolutePath().toUri().toURL()), compatibilityLoader)
        try {
            val instances = mutableListOf<Any>()
            withContextLoader(loader) {
                for (entryClass in metadata.entryClasses) {
                    val type = loader.loadClass(entryClass)
                    // A parent-owned class must never stand in for the extension's declared entry point.
                    if (type.classLoader !== loader) throw SourceJarException(SourceJarFailure.INVALID_ARCHIVE)
                    val instance = type.getDeclaredConstructor().newInstance()
                    if (factoryType.isInstance(instance)) {
                        val expanded = factoryType.getMethod("createSources").invoke(instance) as? List<*>
                            ?: throw SourceJarException(SourceJarFailure.NO_SOURCES)
                        instances.addAll(expanded.map { it ?: throw SourceJarException(SourceJarFailure.NO_SOURCES) })
                    } else {
                        instances += instance
                    }
                }
                if (instances.isEmpty()) throw SourceJarException(SourceJarFailure.NO_SOURCES)
                if (instances.any { !catalogueType.isInstance(it) }) {
                    throw SourceJarException(SourceJarFailure.NO_SOURCES)
                }
                val descriptors = instances.map { instance ->
                    val id = instance.javaClass.getMethod("getId").invoke(instance) as Long
                    val name = instance.javaClass.getMethod("getName").invoke(instance) as String
                    val language = instance.javaClass.getMethod("getLang").invoke(instance) as String
                    val latest = instance.javaClass.getMethod("getSupportsLatest").invoke(instance) as Boolean
                    // Same identities as the Android hosts: MIHON_<id> manga, TSUNDOKU_<id> novels, ANIYOMI_<id> anime.
                    val (prefix, kind) = when (metadata.ecosystem) {
                        SourceEcosystem.TSUNDOKU -> "TSUNDOKU_" to "NOVEL"
                        SourceEcosystem.ANIYOMI -> "ANIYOMI_" to "VIDEO"
                        else -> "MIHON_" to "MANGA"
                    }
                    MihonSourceDescriptor(
                        SourceRef(prefix + id, language, if (metadata.isNsfw) "HENTAI_$kind" else kind),
                        id, name, latest,
                    )
                }
                val keys = descriptors.map { it.source.name }
                if (keys.distinct().size != keys.size) throw SourceJarException(SourceJarFailure.DUPLICATE_SOURCE)
                return Constructed(loader, keys.zip(instances).toMap()) to descriptors
            }
        } catch (error: Throwable) {
            try { loader.close() } catch (closeError: Exception) { error.addSuppressed(closeError) }
            val cause = if (error is InvocationTargetException) error.targetException else error
            when (cause) {
                is SourceJarException -> throw cause
                is Exception, is LinkageError -> throw SourceJarException(SourceJarFailure.CONSTRUCTION_FAILED, cause)
                else -> throw cause
            }
        }
    }

    /**
     * The extension behind a catalogued entry, constructed on its first call. The artifact must still match the
     * catalogued identity and produce the same sources; a failure is reported to that call and the next one retries.
     */
    private fun constructedOf(extension: Extension): Constructed = extension.constructed ?: synchronized(extension) {
        extension.constructed ?: run {
            val start = System.nanoTime()
            val expected = extension.info.metadata
            val metadata = inspector.inspect(extension.path)
            inspector.verify(metadata, MihonJarIdentity(expected.packageName, expected.versionCode, expected.sha256))
            val (constructed, descriptors) = construct(extension.path, metadata)
            if (descriptors != extension.info.sources) {
                constructed.loader.close()
                throw SourceJarException(SourceJarFailure.CONSTRUCTION_FAILED)
            }
            extension.constructed = constructed
            System.err.println("[source-host] extension ${expected.packageName} (on first use): " +
                "${(System.nanoTime() - start) / 1_000_000} ms")
            constructed
        }
    }

    private fun catalogFile(sha256: String): Path? = catalogDirectory?.resolve("${sha256.lowercase()}.json")

    private fun readCatalog(expected: MihonJarIdentity): LoadedMihonExtension? {
        val file = catalogFile(expected.sha256)?.takeIf { Files.isRegularFile(it) } ?: return null
        return try {
            val info = catalogJson.decodeFromString(LoadedMihonExtension.serializer(), Files.readString(file))
            info.takeIf {
                it.metadata.packageName == expected.packageName && it.metadata.versionCode == expected.versionCode &&
                    it.metadata.sha256.equals(expected.sha256, ignoreCase = true) && it.sources.isNotEmpty()
            }
        } catch (_: Exception) {
            null // A damaged catalog only costs a full load, which rewrites it.
        }
    }

    private fun writeCatalog(info: LoadedMihonExtension) {
        val file = catalogFile(info.metadata.sha256) ?: return
        try {
            Files.createDirectories(file.parent)
            val temporary = Files.createTempFile(file.parent, "catalog-", ".tmp")
            try {
                Files.writeString(temporary, catalogJson.encodeToString(LoadedMihonExtension.serializer(), info))
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } finally {
                Files.deleteIfExists(temporary)
            }
        } catch (_: Exception) {
            // Best effort: without a catalog the next start constructs the extension, as before.
        }
    }

    @Synchronized
    fun installed(): List<LoadedMihonExtension> {
        requireOpen()
        return extensions.values.map { it.info }
    }

    /** Unregisters sources and closes JAR handles. It neither deletes artifacts nor destroys the VM. */
    @Synchronized
    fun unload(packageName: String): Boolean {
        requireOpen()
        val extension = extensions.remove(packageName) ?: return false
        retire(extension)
        return true
    }

    /** Kept inside the JVM host: source objects never cross the common/native protocol boundary. */
    internal fun <T> withSource(sourceName: String, block: (Any) -> T): T {
        return acquire(sourceName).use { lease -> withContextLoader(lease.loader) { block(lease.instance) } }
    }

    internal suspend fun <T> withSourceSuspending(sourceName: String, block: suspend (SourceLease) -> T): T {
        val lease = acquire(sourceName)
        try {
            return withContext(SourceClassLoader(lease.loader)) { block(lease) }
        } finally {
            lease.close()
        }
    }

    /** Additional leases keep a cancelled, uncooperative extension callback's JAR open until it completes. */
    internal class SourceLease(
        val instance: Any,
        val descriptor: MihonSourceDescriptor,
        val loader: ClassLoader,
        private val retainCall: () -> Closeable,
        private val releaseCall: () -> Unit,
        private val stateCall: (Any, () -> Any) -> Any,
    ) : Closeable {
        private val released = AtomicBoolean()
        fun retain(): Closeable = retainCall()
        @Suppress("UNCHECKED_CAST")
        fun <T : Any> state(owner: Any, create: () -> T): T = stateCall(owner, create) as T
        override fun close() { if (released.compareAndSet(false, true)) releaseCall() }
    }

    /** Leases the extension under the registry lock, then constructs it (if catalogued) outside that lock. */
    private fun acquire(sourceName: String): SourceLease {
        val extension = synchronized(this) {
            requireOpen()
            extensions.values.firstOrNull { sourceName in it.keys }
                ?.also { it.activeCalls++ }
                ?: throw org.skepsun.kototoro.core.source.SourceUnavailableException(sourceName)
        }
        val constructed = try { constructedOf(extension) } catch (error: Throwable) {
            release(extension)
            throw error
        }
        return SourceLease(
            constructed.sources.getValue(sourceName), extension.info.sources.first { it.source.name == sourceName },
            constructed.loader, { retain(extension) }, { release(extension) },
            { owner, create -> sourceState(extension, sourceName, owner, create) },
        )
    }

    @Synchronized
    private fun sourceState(extension: Extension, sourceName: String, owner: Any, create: () -> Any): Any =
        extension.states.getOrPut(sourceName) { WeakHashMap() }.getOrPut(owner, create)

    @Synchronized
    private fun retain(extension: Extension): Closeable {
        check(extension.activeCalls > 0)
        extension.activeCalls++
        val released = AtomicBoolean()
        return Closeable { if (released.compareAndSet(false, true)) release(extension) }
    }

    @Synchronized
    private fun release(extension: Extension) {
        extension.activeCalls--
        extension.closeIfIdle()
    }

    private fun retire(extension: Extension) {
        extension.retired = true
        extension.closeIfIdle()
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        val failures = retirementFailures.toMutableList()
        extensions.values.forEach { extension ->
            try { retire(extension) } catch (error: Exception) { failures += error }
        }
        extensions.clear()
        if (failures.isNotEmpty()) {
            val failure = failures.first()
            failures.drop(1).forEach(failure::addSuppressed)
            throw failure
        }
    }

    private fun compatibilityType(name: String): Class<*> = try {
        Class.forName(name, false, compatibilityLoader)
    } catch (error: ClassNotFoundException) {
        throw SourceJarException(SourceJarFailure.API_UNAVAILABLE, error)
    } catch (error: LinkageError) {
        throw SourceJarException(SourceJarFailure.API_UNAVAILABLE, error)
    }

    private fun requireOpen() {
        if (closed) throw SourceJarException(SourceJarFailure.CLOSED)
    }

    private inline fun <T> withContextLoader(loader: ClassLoader, block: () -> T): T {
        val thread = Thread.currentThread()
        val previous = thread.contextClassLoader
        thread.contextClassLoader = loader
        return try { block() } finally { thread.contextClassLoader = previous }
    }

    private class SourceClassLoader(private val loader: ClassLoader) :
        ThreadContextElement<ClassLoader?>, AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<SourceClassLoader>
        override fun updateThreadContext(context: CoroutineContext): ClassLoader? {
            val thread = Thread.currentThread()
            return thread.contextClassLoader.also { thread.contextClassLoader = loader }
        }
        override fun restoreThreadContext(context: CoroutineContext, oldState: ClassLoader?) {
            Thread.currentThread().contextClassLoader = oldState
        }
    }
}

private val catalogJson = Json { ignoreUnknownKeys = true }
