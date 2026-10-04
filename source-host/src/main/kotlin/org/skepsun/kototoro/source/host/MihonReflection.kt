package org.skepsun.kototoro.source.host

import kotlinx.coroutines.suspendCancellableCoroutine
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED

/** Invokes the embedding runtime's ABI without shipping a second copy of its model classes. */
internal object MihonReflection {
    fun call(target: Any, name: String, vararg arguments: Any?): Any? =
        invoke(method(target, name, arguments), target, arguments)

    fun optional(target: Any, name: String, vararg arguments: Any?): Any? = try {
        call(target, name, *arguments)
    } catch (_: NoSuchMethodException) {
        null
    } catch (_: NoSuchMethodError) {
        null
    } catch (_: AbstractMethodError) {
        null
    } catch (_: UninitializedPropertyAccessException) {
        null
    }

    suspend fun callSuspending(
        lease: MihonJarRegistry.SourceLease,
        name: String,
        vararg arguments: Any?,
        onCompleted: () -> Unit = {},
        onResult: (Any?) -> Any? = { it },
        onDiscarded: (Any?) -> Unit = {},
    ): Any? =
        suspendCancellableCoroutine { continuation ->
            if (!continuation.isActive) {
                onCompleted()
                return@suspendCancellableCoroutine
            }
            val hold = lease.retain()
            val completed = AtomicBoolean()
            val callback = object : Continuation<Any?> {
                override val context = continuation.context
                override fun resumeWith(result: Result<Any?>) {
                    if (!completed.compareAndSet(false, true)) return
                    val thread = Thread.currentThread()
                    val previous = thread.contextClassLoader
                    var delivered = result
                    var owned: Any? = null
                    var transformed = false
                    fun fail(error: Throwable) {
                        delivered.exceptionOrNull()?.let { if (it !== error) it.addSuppressed(error) }
                            ?: run { delivered = Result.failure(error) }
                    }
                    thread.contextClassLoader = lease.loader
                    try {
                        if (result.isSuccess) {
                            try {
                                owned = onResult(result.getOrNull())
                                transformed = true
                                delivered = Result.success(owned)
                            } catch (error: Throwable) { fail(error) }
                        }
                        // Completion also restores filters and releases the source gate if mapping fails.
                        try { onCompleted() } catch (error: Throwable) { fail(error) }
                    } finally {
                        thread.contextClassLoader = previous
                        try { hold.close() } catch (error: Throwable) { fail(error) }
                    }
                    if (delivered.isSuccess) {
                        continuation.resume(delivered.getOrNull(), onCancellation = { cause, value, _ ->
                            try { onDiscarded(value) } catch (cleanup: Throwable) { cause.addSuppressed(cleanup) }
                        })
                    } else {
                        if (transformed) try { onDiscarded(owned) } catch (cleanup: Throwable) { fail(cleanup) }
                        continuation.resumeWith(delivered)
                    }
                }
            }
            try {
                val parameters = arrayOf(*arguments, callback)
                val returned = invoke(method(lease.instance, name, parameters), lease.instance, parameters)
                if (returned !== COROUTINE_SUSPENDED) callback.resumeWith(Result.success(returned))
            } catch (error: Throwable) {
                callback.resumeWith(Result.failure(error))
            }
        }

    fun create(loader: ClassLoader, name: String, vararg arguments: Any?): Any =
        createIn(loader, "eu.kanade.tachiyomi.source.model", name, *arguments)

    /** A model class of one of the ABIs ([NativeAbi]) by simple name. */
    fun createIn(loader: ClassLoader, modelPackage: String, name: String, vararg arguments: Any?): Any {
        val type = Class.forName("$modelPackage.$name", true, loader)
        val constructor = type.constructors.firstOrNull { matches(it.parameterTypes, arguments) }
            ?: throw NoSuchMethodException("$name constructor")
        return try { constructor.newInstance(*arguments) } catch (error: InvocationTargetException) {
            throw error.targetException
        }
    }

    private fun method(target: Any, name: String, arguments: Array<out Any?>): Method {
        // Walk overrides first, including protected HttpSource.imageRequest used to obtain page headers.
        var type: Class<*>? = target.javaClass
        while (type != null) {
            val candidate = type.declaredMethods.filter {
                it.name == name && matches(it.parameterTypes, arguments)
            }.minByOrNull { if (it.isBridge) 1 else 0 }
            if (candidate != null) return candidate.also { it.isAccessible = true }
            type = type.superclass
        }
        // Kotlin may emit ONLY a bridge forwarding to a suspend interface default (Suwayomi HttpSource).
        return target.javaClass.methods.filter {
            it.name == name && matches(it.parameterTypes, arguments)
        }.minByOrNull { if (it.isBridge) 1 else 0 } ?: throw NoSuchMethodException(name)
    }

    private fun matches(types: Array<Class<*>>, arguments: Array<out Any?>): Boolean =
        types.size == arguments.size && types.indices.all { index ->
            val type = types[index]
            val argument = arguments[index]
            if (argument == null) !type.isPrimitive else when (type) {
                java.lang.Integer.TYPE -> argument is Int
                java.lang.Long.TYPE -> argument is Long
                java.lang.Float.TYPE -> argument is Float
                java.lang.Double.TYPE -> argument is Double
                java.lang.Boolean.TYPE -> argument is Boolean
                else -> type.isInstance(argument)
            }
        }

    private fun invoke(method: Method, target: Any, arguments: Array<out Any?>): Any? = try {
        method.invoke(target, *arguments)
    } catch (error: InvocationTargetException) {
        throw error.targetException
    }
}
