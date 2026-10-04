package org.skepsun.kototoro.dex

import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider

/** The Android SDK's d8 and aapt2, used to build real DEX and binary-manifest fixtures during tests. */
class AndroidTools private constructor(private val d8: Path, val androidJar: Path, private val aapt2: Path?) {

    /** Dexes [classesDirectory] (compiled class files) into `classes.dex` inside [outputDirectory]. */
    fun dex(classesJar: Path, outputDirectory: Path, classpath: List<Path> = emptyList()): Path {
        Files.createDirectories(outputDirectory)
        val java = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
        val command = mutableListOf(
            java.toString(), "-cp", d8.toString(), "com.android.tools.r8.D8", "--release", "--min-api", "26", "--lib", androidJar.toString(),
        )
        classpath.forEach { command += listOf("--classpath", it.toString()) }
        command += listOf("--output", outputDirectory.toString(), classesJar.toString())
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val log = process.inputStream.readAllBytes().decodeToString()
        check(process.waitFor() == 0) { "d8 failed: $log" }
        return outputDirectory.resolve("classes.dex")
    }

    /** Compiles Java [sources] (path to text) into a jar of class files. */
    fun compileJar(workDirectory: Path, sources: Map<String, String>): Path {
        val classes = Files.createDirectories(workDirectory.resolve("classes"))
        val paths = sources.map { (name, text) ->
            workDirectory.resolve("src").resolve(name).also { Files.createDirectories(it.parent); Files.writeString(it, text) }
        }
        val compiler = requireNotNull(ToolProvider.getSystemJavaCompiler()) { "A JDK is required" }
        check(compiler.run(null, null, null, "--release", "11", "-d", classes.toString(), *paths.map(Path::toString).toTypedArray()) == 0)
        val jar = workDirectory.resolve("classes.jar")
        JarOutputStream(Files.newOutputStream(jar)).use { output ->
            Files.walk(classes).use { files ->
                files.filter(Files::isRegularFile).forEach { file ->
                    output.putNextEntry(JarEntry(classes.relativize(file).toString().replace('\\', '/')))
                    output.write(Files.readAllBytes(file))
                    output.closeEntry()
                }
            }
        }
        return jar
    }

    /** Builds an APK whose manifest is binary XML, as real extension APKs are, and adds [dex] as classes.dex. */
    fun apk(workDirectory: Path, manifest: String, dex: Path): Path {
        val tool = requireNotNull(aapt2) { "aapt2 is not available" }
        val manifestFile = workDirectory.resolve("AndroidManifest.xml").also { Files.writeString(it, manifest) }
        val base = workDirectory.resolve("base.apk")
        run(listOf(tool.toString(), "link", "--manifest", manifestFile.toString(), "-I", androidJar.toString(), "-o", base.toString()))
        val apk = workDirectory.resolve("extension.apk")
        java.util.zip.ZipFile(base.toFile()).use { source ->
            java.util.zip.ZipOutputStream(Files.newOutputStream(apk)).use { output ->
                for (entry in source.entries()) {
                    output.putNextEntry(java.util.zip.ZipEntry(entry.name))
                    source.getInputStream(entry).use { it.copyTo(output) }
                    output.closeEntry()
                }
                output.putNextEntry(java.util.zip.ZipEntry("classes.dex"))
                output.write(Files.readAllBytes(dex))
                output.closeEntry()
            }
        }
        return apk
    }

    private fun run(command: List<String>) {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val log = process.inputStream.readAllBytes().decodeToString()
        check(process.waitFor() == 0) { "${command.first()} failed: $log" }
    }

    companion object {
        fun find(): AndroidTools? {
            val sdk = System.getProperty("kototoro.android.sdk")?.let(Path::of)?.takeIf(Files::isDirectory) ?: return null
            val tools = Files.list(sdk.resolve("build-tools")).use { it.sorted().toList() }.lastOrNull { Files.isRegularFile(it.resolve("lib/d8.jar")) } ?: return null
            val platform = Files.list(sdk.resolve("platforms")).use { it.sorted().toList() }.lastOrNull { Files.isRegularFile(it.resolve("android.jar")) } ?: return null
            val aapt2 = listOf("aapt2.exe", "aapt2").map(tools::resolve).firstOrNull(Files::isRegularFile)
            return AndroidTools(tools.resolve("lib/d8.jar"), platform.resolve("android.jar"), aapt2)
        }
    }
}
