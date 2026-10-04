package org.skepsun.kototoro.source.host

import org.skepsun.kototoro.core.source.MihonJarIdentity
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider

/** Independently authored, tiny test ABI; production loads the embedding platform's real Mihon API. */
internal class JarFixture(private val root: Path, runtimeApi: Boolean = false) {
    val apiClasses: Path = root.resolve("api-classes")

    init {
        compile(
            root.resolve("api-src"), apiClasses, mapOf(
                "eu/kanade/tachiyomi/source/CatalogueSource.java" to """
                    package eu.kanade.tachiyomi.source;
                    public interface CatalogueSource {
                        long getId(); String getName(); String getLang(); boolean getSupportsLatest();
                    }
                """.trimIndent(),
                "eu/kanade/tachiyomi/source/SourceFactory.java" to """
                    package eu.kanade.tachiyomi.source;
                    public interface SourceFactory { java.util.List<CatalogueSource> createSources(); }
                """.trimIndent(),
            ) + if (runtimeApi) RuntimeJarFixture.apiSources else emptyMap(),
        )
    }

    fun compatibilityLoader() = URLClassLoader(arrayOf(apiClasses.toUri().toURL()), javaClass.classLoader)

    fun sourceJar(
        name: String = "Single",
        id: String = "9007199254740993L",
        packageName: String = "fixture.extension",
        body: String = "",
        metadata: String = manifest(packageName, ".$name"),
    ): Path {
        val classes = root.resolve("$name-classes")
        val source = """
            package $packageName;
            public class $name implements eu.kanade.tachiyomi.source.CatalogueSource {
                $body
                public long getId() { return $id; }
                public String getName() { return "漫画"; }
                public String getLang() { return "zh"; }
                public boolean getSupportsLatest() { return true; }
            }
        """.trimIndent()
        compile(root.resolve("$name-src"), classes, mapOf("${packageName.replace('.', '/')}/$name.java" to source))
        return jar(root.resolve("$name.jar"), metadata, classes)
    }

    fun factoryJar(empty: Boolean = false, duplicate: Boolean = false, throwing: Boolean = false): Path {
        val factoryBody = when {
            throwing -> "throw new IllegalStateException(\"fixture constructor failure\");"
            empty -> "return java.util.Collections.emptyList();"
            else -> "return java.util.Arrays.asList(new First(), new Second());"
        }
        val sources = mapOf(
            "fixture/extension/Factory.java" to """
                package fixture.extension;
                public class Factory implements eu.kanade.tachiyomi.source.SourceFactory {
                    public java.util.List<eu.kanade.tachiyomi.source.CatalogueSource> createSources() {
                        $factoryBody
                    }
                }
            """.trimIndent(),
            "fixture/extension/First.java" to sourceClass("First", "-9223372036854775808L", "en"),
            "fixture/extension/Second.java" to sourceClass(
                "Second", if (duplicate) "-9223372036854775808L" else "9223372036854775807L", "ja",
            ),
        )
        val classes = root.resolve("factory-classes")
        compile(root.resolve("factory-src"), classes, sources)
        return jar(root.resolve("factory.jar"), manifest("fixture.extension", ".Factory"), classes)
    }

    fun jar(path: Path, manifest: String?, classes: Path? = null, extra: Map<String, ByteArray> = emptyMap()): Path {
        JarOutputStream(Files.newOutputStream(path)).use { output ->
            fun entry(name: String, bytes: ByteArray) {
                output.putNextEntry(JarEntry(name))
                output.write(bytes)
                output.closeEntry()
            }
            manifest?.let { entry("AndroidManifest.xml", it.toByteArray(Charsets.UTF_8)) }
            classes?.let { directory ->
                Files.walk(directory).use { paths ->
                    paths.filter(Files::isRegularFile).forEach { file ->
                        entry(directory.relativize(file).toString().replace('\\', '/'), Files.readAllBytes(file))
                    }
                }
            }
            extra.forEach { (name, bytes) -> entry(name, bytes) }
        }
        return path
    }

    fun identity(path: Path): MihonJarIdentity = MihonJarInspector().inspect(path).let {
        MihonJarIdentity(it.packageName, it.versionCode, it.sha256)
    }

    fun compile(sourceRoot: Path, output: Path, sources: Map<String, String>) {
        Files.createDirectories(output)
        val paths = sources.map { (name, source) ->
            sourceRoot.resolve(name).also { path ->
                Files.createDirectories(path.parent)
                Files.writeString(path, source)
            }
        }
        val compiler = requireNotNull(ToolProvider.getSystemJavaCompiler()) { "Fixture tests require a JDK" }
        val dependencies = listOf(apiClasses.toString(),
            kotlin.coroutines.Continuation::class.java.protectionDomain.codeSource.location.toURI().let(Path::of).toString(),
            kotlinx.serialization.json.JsonObject::class.java.protectionDomain.codeSource.location.toURI().let(Path::of).toString(),
            kotlinx.serialization.KSerializer::class.java.protectionDomain.codeSource.location.toURI().let(Path::of).toString(),
        ).joinToString(java.io.File.pathSeparator)
        val options = listOf("--release", "11", "-encoding", "UTF-8", "-classpath", dependencies,
            "-d", output.toString()) + paths.map(Path::toString)
        check(compiler.run(null, null, null, *options.toTypedArray()) == 0)
    }

    private fun sourceClass(name: String, id: String, language: String) = """
        package fixture.extension;
        public class $name implements eu.kanade.tachiyomi.source.CatalogueSource {
            public long getId() { return $id; }
            public String getName() { return "$name"; }
            public String getLang() { return "$language"; }
            public boolean getSupportsLatest() { return false; }
        }
    """.trimIndent()

    companion object {
        /** A Tsundoku-style manifest: novel keys instead of the manga ones; [libraryMarker] false drops tachiyomix.extensionLib. */
        fun novelManifest(
            packageName: String = "fixture.novel", entry: String = ".NovelSource", versionName: String = "1.6.3",
            libraryMarker: Boolean = true, extra: String = "",
        ) = """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="$packageName"
                android:versionCode="3" android:versionName="$versionName">
                <application android:label="Tsundoku: Fixture 小说">
                    <meta-data android:name="tachiyomix.name" android:value="夹具小说"/>
                    ${if (libraryMarker) """<meta-data android:name="tachiyomix.extensionLib" android:value="1.6"/>""" else ""}
                    <meta-data android:name="tachiyomi.novelextension.class" android:value="$entry"/>
                    <meta-data android:name="tachiyomi.novelextension.nsfw" android:value="0"/>
                    $extra
                </application>
            </manifest>
        """.trimIndent()

        /** An Aniyomi-style manifest: anime keys, and the version name carries the extensions-lib generation. */
        fun animeManifest(
            packageName: String = "fixture.anime", entry: String = ".AnimeSource", versionName: String = "14.1", extra: String = "",
        ) = """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="$packageName"
                android:versionCode="1" android:versionName="$versionName">
                <application android:label="Aniyomi: Fixture 动画">
                    <meta-data android:name="tachiyomi.animeextension.class" android:value="$entry"/>
                    <meta-data android:name="tachiyomi.animeextension.nsfw" android:value="0"/>
                    $extra
                </application>
            </manifest>
        """.trimIndent()
        fun manifest(packageName: String = "fixture.extension", entry: String = ".Single") = """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="$packageName"
                android:versionCode="16" android:versionName="1.6.16">
                <application android:label="Fixture">
                    <meta-data android:name="tachiyomix.name" android:value="漫画 &amp; fixture"/>
                    <meta-data android:name="tachiyomix.extensionLib" android:value="1.6"/>
                    <meta-data android:name="tachiyomi.extension.class" android:value="$entry"/>
                    <meta-data android:name="tachiyomi.extension.nsfw" android:value="0"/>
                </application>
            </manifest>
        """.trimIndent()
    }
}
