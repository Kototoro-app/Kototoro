package org.skepsun.kototoro.reader.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.io.path.readText
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * I1 build guardrail (ADR 0002, closure plan CS-6, improvement plan §6.2): `:reader-core`
 * must stay free of Android, Compose, renderer and application-layer types.
 *
 * Since the §8.2 module extraction the compile-time classpath of this pure Kotlin/JVM module
 * is the primary I1 boundary; this TEXT scan is the second line of defense — it flags
 * violations inside method bodies (fully-qualified references without imports) and keeps
 * reviewers honest about the rule itself, fixture-proven. It covers import statements
 * (including aliased imports) and fully-qualified references in code, with comments stripped
 * to avoid false positives. It is NOT a complete dependency-graph proof — a transitive leak
 * that never appears textually would pass; adding an Android/Compose dependency to
 * reader-core/build.gradle is only caught by review.
 *
 * The fixture-based tests deliberately violate every rule and assert the guard flags them,
 * proving the check fails on violations rather than only passing on an already-clean tree.
 */
class ReaderCoreIsolationGuardTest {

    private data class Violation(val file: String, val line: Int, val reason: String, val evidence: String) {
        override fun toString(): String = "$file:$line [$reason] $evidence"
    }

    // ---------------------------------------------------------------------------------------------
    // Scanner
    // ---------------------------------------------------------------------------------------------

    private val allowedImportPrefixes = listOf(
        "kotlin.",
        "kotlinx.",
        "java.",
        "javax.",
        "org.skepsun.kototoro.reader.core.",
    )

    private val importRegex = Regex("""^\s*import\s+([A-Za-z0-9_.]+)(\s+as\s+\w+)?\s*$""")
    private val androidReferenceRegex = Regex("""\bandroidx?\b\.""")
    private val appLayerReferenceRegex = Regex("""\borg\.skepsun\.kototoro\.(?!reader\.core\b)""")

    private fun scanKotlinSource(path: Path): List<Violation> {
        val stripped = stripComments(path.readText())
        val violations = mutableListOf<Violation>()
        stripped.lines().forEachIndexed { index, line ->
            val lineNumber = index + 1
            importRegex.matchEntire(line)?.let { match ->
                val fqn = match.groupValues[1]
                if (allowedImportPrefixes.none { fqn.startsWith(it) }) {
                    violations.add(
                        Violation(
                            file = path.name,
                            line = lineNumber,
                            reason = "forbidden import (alias: ${match.groupValues[2].isNotBlank()})",
                            evidence = line.trim(),
                        ),
                    )
                    return@forEachIndexed
                }
            }
            if (androidReferenceRegex.containsMatchIn(line)) {
                violations.add(Violation(path.name, lineNumber, "Android/Compose reference", line.trim()))
            } else if (appLayerReferenceRegex.containsMatchIn(line)) {
                violations.add(Violation(path.name, lineNumber, "application-layer reference", line.trim()))
            }
        }
        return violations
    }

    /** Strips `//` line comments and `/* */` block comments while preserving line numbers. */
    private fun stripComments(text: String): String {
        val sb = StringBuilder(text.length)
        var inBlockComment = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val next = if (i + 1 < text.length) text[i + 1] else ' '
            when {
                !inBlockComment && c == '/' && next == '/' -> {
                    while (i < text.length && text[i] != '\n') i++
                }
                !inBlockComment && c == '/' && next == '*' -> {
                    inBlockComment = true
                    i += 2
                }
                inBlockComment && c == '*' && next == '/' -> {
                    inBlockComment = false
                    i += 2
                    sb.append(' ')
                }
                else -> {
                    if (!inBlockComment || c == '\n') sb.append(c)
                    i++
                }
            }
        }
        return sb.toString()
    }

    private fun scanDirectory(dir: Path): List<Violation> {
        Files.walk(dir).use { stream ->
            return stream
                .filter { Files.isRegularFile(it) && it.extension == "kt" }
                .sorted()
                .flatMap { scanKotlinSource(it).stream() }
                .toList()
        }
    }

    private fun fixture(name: String): Path {
        var dir: Path? = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        var hops = 0
        while (dir != null && hops < 5) {
            val candidate = dir.resolve("reader-core/src/test/resources/reader-core-isolation-fixtures/$name")
            if (Files.isRegularFile(candidate)) return candidate
            dir = dir.parent
            hops++
        }
        error("fixture not found: $name (searched from ${System.getProperty("user.dir")})")
    }

    // ---------------------------------------------------------------------------------------------
    // Guard: the real core tree must be clean
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `reader core sources stay free of Android, Compose, renderer and app-layer references`() {
        val dir = readerCoreSourceDir()
        val fileCount = Files.walk(dir).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.extension == "kt" }.count()
        }
        assertTrue(fileCount > 20, "expected to scan the real reader/core tree, found $fileCount files in $dir")

        val violations = scanDirectory(dir)
        assertTrue(
            violations.isEmpty(),
            "reader/core violates I1 — ${violations.size} textual violations:\n" +
                violations.joinToString("\n"),
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Guard self-test: deliberately violating fixtures MUST be flagged
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `guard fails on a direct Android import`() {
        val violations = scanKotlinSource(fixture("ViolatingAndroidImport.kt"))
        assertTrue(
            violations.any { it.reason.startsWith("forbidden import") },
            "expected the Android import to be flagged: $violations",
        )
    }

    @Test
    fun `guard fails on an aliased Compose import`() {
        val violations = scanKotlinSource(fixture("ViolatingComposeAlias.kt"))
        assertTrue(
            violations.any { it.reason.startsWith("forbidden import") && it.reason.contains("alias: true") },
            "expected the aliased Compose import to be flagged: $violations",
        )
    }

    @Test
    fun `guard fails on a fully qualified renderer reference without import`() {
        val violations = scanKotlinSource(fixture("ViolatingRendererFqn.kt"))
        assertTrue(
            violations.any { it.reason == "application-layer reference" },
            "expected the fully-qualified renderer reference to be flagged: $violations",
        )
    }

    @Test
    fun `guard fails on an inline Android class reference without import`() {
        val violations = scanKotlinSource(fixture("ViolatingInlineAndroid.kt"))
        assertTrue(
            violations.any { it.reason == "Android/Compose reference" },
            "expected the inline android.graphics reference to be flagged: $violations",
        )
    }

    @Test
    fun `guard passes on a clean fixture that mentions Android only in comments`() {
        val violations = scanKotlinSource(fixture("CleanWithAndroidComments.kt"))
        assertTrue(
            violations.isEmpty(),
            "comments must not trigger the guard (comment stripping broken): $violations",
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private fun readerCoreSourceDir(): Path {
        var dir: Path? = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        var hops = 0
        while (dir != null && hops < 5) {
            val candidate = dir.resolve("reader-core/src/main/kotlin/org/skepsun/kototoro/reader/core")
            if (Files.isDirectory(candidate)) return candidate
            dir = dir.parent
            hops++
        }
        error("reader/core source dir not found from ${System.getProperty("user.dir")}")
    }
}
