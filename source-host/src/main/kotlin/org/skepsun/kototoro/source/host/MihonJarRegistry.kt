package org.skepsun.kototoro.source.host

import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.core.source.LoadedMihonExtension
import org.skepsun.kototoro.core.source.MihonJarIdentity
import org.skepsun.kototoro.core.source.MihonSourceDescriptor
import org.skepsun.kototoro.core.source.SourceEcosystem
import org.skepsun.kototoro.core.source.SourceRef
import java.io.Closeable
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.WeakHashMap
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** The embedding platform initializes the pinned compatibility runtime before constructing this registry. */
class MihonJarRegistry(
    private val compatibilityLoader: ClassLoader,
    private val inspector: MihonJarInspector = MihonJarInspector(),
) : Closeable {
    private data class Extension(
        val info: LoadedMihonExtension,
        val loader: URLClassLoader,
        val sources: Map<String, Any>,
        var activeCalls: Int = 0,
        var retired: Boolean = false,
        val states: MutableMap<String, WeakHashMap<Any, Any>> = mutableMapOf(),
    )

    private val extensions = linkedMapOf<String, Extension>()
    private val retirementFailures = mutableListOf<Exception>()
    private var closed = false

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
        val metadata = inspector.inspect(path)
        inspector.verify(metadata, expected)
        val previous = extensions[metadata.packageName]
        if (previous != null && !replace) throw SourceJarException(SourceJarFailure.ALREADY_LOADED)
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
                val registeredKeys = extensions.values.filter { it !== previous }.flatMap { it.sources.keys }.toSet()
                if (keys.distinct().size != keys.size || keys.any { it in registeredKeys }) {
                    throw SourceJarException(SourceJarFailure.DUPLICATE_SOURCE)
                }
                val info = LoadedMihonExtension(metadata, descriptors)
                beforePublish(info)
                extensions[metadata.packageName] = Extension(info, loader, keys.zip(instances).toMap())
                if (previous != null) {
                    try { retire(previous) } catch (error: Exception) { retirementFailures += error }
                }
                return info
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

    @Synchronized
    private fun acquire(sourceName: String): SourceLease {
        requireOpen()
        val extension = extensions.values.firstOrNull { sourceName in it.sources }
            ?: throw org.skepsun.kototoro.core.source.SourceUnavailableException(sourceName)
        extension.activeCalls++
        return SourceLease(
            extension.sources.getValue(sourceName), extension.info.sources.first { it.source.name == sourceName },
            extension.loader, { retain(extension) }, { release(extension) },
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
        if (extension.retired && extension.activeCalls == 0) extension.loader.close()
    }

    private fun retire(extension: Extension) {
        extension.retired = true
        if (extension.activeCalls == 0) extension.loader.close()
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
