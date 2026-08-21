package org.skepsun.kototoro.extensions.runtime

object ExternalExtensionSourceLoaderSupport {

    fun resolveSourceClassNames(
        pkgName: String,
        sourceClassNames: String,
    ): List<String> {
        return sourceClassNames.split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { className ->
                if (className.startsWith(".")) {
                    pkgName + className
                } else {
                    className
                }
            }
    }

    fun <SourceT> loadSources(
        pkgName: String,
        sourceClassNames: String,
        classLoader: ClassLoader,
        asSource: (Any) -> SourceT?,
        createSourcesFromFactory: (Any) -> List<SourceT>?,
        onUnknownInstance: (String) -> Unit = {},
    ): List<SourceT> {
        return resolveSourceClassNames(pkgName, sourceClassNames).flatMap { fullClassName ->
            val clazz = Class.forName(fullClassName, false, classLoader)
            val instance = clazz.getDeclaredConstructor().newInstance()
            asSource(instance)?.let { source ->
                return@flatMap listOf(source)
            }
            createSourcesFromFactory(instance)?.let { sources ->
                return@flatMap sources
            }
            onUnknownInstance(instance.javaClass.name)
            emptyList()
        }
    }
}
