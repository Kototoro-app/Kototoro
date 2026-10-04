package org.skepsun.kototoro.desktop.app

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.skepsun.kototoro.core.source.SourceDetailsFetchMode
import org.skepsun.kototoro.core.source.SourceEcosystem
import java.nio.file.Path

/**
 * A real Tsundoku APK (opt-in, `-PrealApkDirectory`) through the session: converted, installed, listed and read over
 * the live network. A site being down is reported, not hidden: the probe prints each stage before it can fail.
 */
internal object DesktopRealNovelProbe {
    fun run(args: Array<String>) {
        val directory = Path.of(requireNotNull(System.getProperty("kototoro.dex.real.apks")) { "no real APK directory" })
        // The sites behind these extensions come and go: take the requested one, else the first APK that is present.
        val apk = listOfNotNull(System.getProperty("kototoro.real.novel"), "allnovelfull.apk", "novelfull.apk")
            .map(directory::resolve).first { java.nio.file.Files.isRegularFile(it) }
        verifyAllClasses(apk, Path.of(args[1]))
        runBlocking {
            val session = DesktopSession.open(Path.of(args[1]))
            try {
                val extension = session.importJar(apk)
                println("INSTALLED ${extension.label}: ${extension.sources.map { it.source.name }}")
                val listing = session.sourceListings().single { it.ecosystem == SourceEcosystem.TSUNDOKU }
                check(listing.source.contentType == "NOVEL") { "unexpected type ${listing.source.contentType}" }
                val client = session.sources
                val describe = client.describe(listing.source.name)
                check(describe.isChapterContentSupported) { "chapter content not advertised" }
                val page = withTimeout(90_000) { client.getList(listing.source.name, 0, null, null) }
                println("LIST ${page.size}: ${page.take(3).map { it.title }}")
                check(page.isNotEmpty()) { "empty listing" }
                val details = withTimeout(90_000) { client.getDetails(page.first(), SourceDetailsFetchMode.FORCE_REFRESH) }
                val chapters = requireNotNull(details.chapters) { "no chapters" }
                println("DETAILS ${details.title}: ${chapters.size} chapters, first=${chapters.first().title}")
                check(chapters.isNotEmpty())
                val content = withTimeout(90_000) { client.getChapterContent(chapters.first(), null) }
                println("CONTENT ${content?.html?.length} chars: ${content?.html?.take(160)}")
                check(content != null && content.html.length > 200) { "chapter text too short" }
                println("DESKTOP_UI_OK=${args[0]}")
            } catch (error: Throwable) {
                // The wire protocol hides causes by design; the local diagnostics log has class names and top frames.
                java.nio.file.Files.walk(Path.of(args[1])).use { files ->
                    files.filter { it.fileName.toString() == "source-errors.log" }.forEach { println("DIAGNOSTICS ${it}\n" + java.nio.file.Files.readString(it)) }
                }
                throw error
            } finally {
                session.close()
            }
        }
    }

    /** Links every class of the converted jar against the real host ABI: any VerifyError is a conversion defect. */
    fun verifyAllClasses(apk: Path, root: Path) {
        java.nio.file.Files.createDirectories(root)
        val jar = root.resolve("verify.jar")
        val report = org.skepsun.kototoro.dex.ApkExtensionConverter.convert(apk, jar).report
        val failures = mutableListOf<String>()
        var linked = 0
        java.net.URLClassLoader(arrayOf(jar.toUri().toURL()), DesktopRealNovelProbe::class.java.classLoader).use { loader ->
            java.util.zip.ZipFile(jar.toFile()).use { zip ->
                for (entry in zip.entries()) {
                    if (!entry.name.endsWith(".class")) continue
                    val name = entry.name.removeSuffix(".class").replace('/', '.')
                    try {
                        Class.forName(name, false, loader).declaredMethods
                        linked++
                    } catch (error: Throwable) {
                        failures += "$name: ${error.javaClass.simpleName} ${error.message?.lineSequence()?.take(2)?.joinToString(" | ")}"
                    }
                }
            }
        }
        println("VERIFY classes=${report.classes} linked=$linked repaired=${report.repairedInstantiations} failures=${failures.size}")
        failures.forEach { println("  $it") }
        check(failures.isEmpty()) { "converted classes failed verification" }
        java.nio.file.Files.delete(jar)
    }
}