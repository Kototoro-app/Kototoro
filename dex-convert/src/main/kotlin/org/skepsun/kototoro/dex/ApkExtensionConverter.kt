package org.skepsun.kototoro.dex

import java.io.IOException
import java.nio.file.Path
import java.util.zip.ZipFile

/** The JVM jar made from an extension APK, with what its manifest declared. */
data class ApkConversion(val manifest: ApkManifest, val report: DexConversionReport)

/**
 * APK -> JVM jar for the Tachiyomi-family hosts. Android repositories only publish APKs for most ecosystems; the
 * hosts expect what Keiyoushi's jars look like: class files plus a text AndroidManifest.xml. The binary manifest is
 * decoded and written back as that text manifest, so the inspectors need no APK-specific code.
 */
object ApkExtensionConverter {
    /** True for an archive with `classes.dex` and a binary (AXML) manifest, i.e. a real Android package. */
    fun isApk(path: Path): Boolean = try {
        ZipFile(path.toFile()).use { zip ->
            val manifest = zip.getEntry("AndroidManifest.xml")
            manifest != null && zip.getEntry("classes.dex") != null &&
                zip.getInputStream(manifest).use { it.read() } != '<'.code
        }
    } catch (_: IOException) {
        false
    }

    fun convert(apk: Path, target: Path): ApkConversion {
        val manifest = try {
            ApkManifestReader.read(apk)
        } catch (error: ApkManifestException) {
            throw DexConversionException(DexConversionFailure.NOT_AN_ARCHIVE, error)
        }
        val report = DexJarConverter.convert(apk, target, mapOf("AndroidManifest.xml" to manifest.withLabel().toText().toByteArray(Charsets.UTF_8)))
        return ApkConversion(manifest, report)
    }

    /** Resource-reference labels cannot be resolved; fall back to the extension's own display name, then its package. */
    private fun ApkManifest.withLabel(): ApkManifest = if (!label.isNullOrBlank()) this else copy(
        label = metaData["tachiyomix.name"]?.takeIf(String::isNotBlank) ?: packageName.substringAfterLast('.'),
    )
}
