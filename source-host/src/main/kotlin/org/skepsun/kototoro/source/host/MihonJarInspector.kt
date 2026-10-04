package org.skepsun.kototoro.source.host

import org.skepsun.kototoro.core.source.MihonJarIdentity
import org.skepsun.kototoro.core.source.MihonJarMetadata
import org.skepsun.kototoro.core.source.SourceEcosystem
import org.w3c.dom.Element
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler
import java.io.DataInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.jar.JarFile
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** Inspects data only; no extension classes are loaded or initialized. */
class MihonJarInspector(
    private val classVersionCeiling: Int = System.getProperty("java.class.version").substringBefore('.').toInt(),
) {
    fun inspect(path: Path): MihonJarMetadata {
        val hash = try {
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path).use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        } catch (error: Exception) {
            throw SourceJarException(SourceJarFailure.INVALID_ARCHIVE, error)
        }
        try {
            return JarFile(path.toFile()).use { jar ->
                val manifestEntry = jar.getJarEntry("AndroidManifest.xml")
                    ?: throw SourceJarException(SourceJarFailure.INVALID_MANIFEST)
                val manifestBytes = jar.getInputStream(manifestEntry).use { it.readNBytes(MAX_MANIFEST_BYTES + 1) }
                if (manifestBytes.size > MAX_MANIFEST_BYTES) {
                    throw SourceJarException(SourceJarFailure.INVALID_MANIFEST)
                }
                val manifest = parseManifest(manifestBytes)
                val maximumClassVersion = classVersion(jar)
                manifest.copy(sha256 = hash, maximumClassVersion = maximumClassVersion)
            }
        } catch (error: SourceJarException) {
            throw error
        } catch (error: Exception) {
            throw SourceJarException(SourceJarFailure.INVALID_ARCHIVE, error)
        }
    }

    fun verify(metadata: MihonJarMetadata, expected: MihonJarIdentity) {
        if (!expected.sha256.matches(Regex("[0-9a-fA-F]{64}")) ||
            !metadata.sha256.equals(expected.sha256, ignoreCase = true)
        ) {
            throw SourceJarException(SourceJarFailure.HASH_MISMATCH)
        }
        if (metadata.packageName != expected.packageName) {
            throw SourceJarException(SourceJarFailure.PACKAGE_MISMATCH)
        }
        if (metadata.versionCode != expected.versionCode) {
            throw SourceJarException(SourceJarFailure.VERSION_MISMATCH)
        }
    }

    private fun parseManifest(bytes: ByteArray): MihonJarMetadata = try {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        }
        val builder = factory.newDocumentBuilder().apply {
            setErrorHandler(object : DefaultHandler() {
                override fun error(error: SAXParseException) = throw error
                override fun fatalError(error: SAXParseException) = throw error
            })
        }
        val root = bytes.inputStream().use { builder.parse(it) }.documentElement
        require(root.tagName == "manifest")
        val packageName = root.getAttribute("package").also {
            require(it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")))
        }
        val application = root.getElementsByTagName("application").item(0) as? Element
            ?: throw IllegalArgumentException("Missing application")
        val elements = application.getElementsByTagName("meta-data")
        val values = linkedMapOf<String, String>()
        for (index in 0 until elements.length) {
            val element = elements.item(index) as Element
            val name = element.android("name")
            require(name.isNotEmpty() && name !in values)
            values[name] = element.android("value")
        }
        // Manga (Mihon), novel (Tsundoku) and anime (Aniyomi) extensions differ in the manifest keys, and in the
        // source ABI the first two share. An extension declaring more than one family cannot be assigned a runtime.
        val families = listOf(
            SourceEcosystem.MIHON to "tachiyomi.extension",
            SourceEcosystem.TSUNDOKU to "tachiyomi.novelextension",
            SourceEcosystem.ANIYOMI to "tachiyomi.animeextension",
        ).filter { (_, prefix) -> "$prefix.class" in values || "$prefix.factory" in values }
        if (families.size > 1) throw SourceJarException(SourceJarFailure.AMBIGUOUS_ECOSYSTEM)
        val (ecosystem, keyPrefix) = families.singleOrNull() ?: (SourceEcosystem.MIHON to "tachiyomi.extension")
        val novel = ecosystem == SourceEcosystem.TSUNDOKU
        val versionName = root.android("versionName").also { require(it.isNotBlank()) }
        // Tsundoku infers a missing library marker from the version name ("1.6.15" -> "1.6"); Aniyomi's version name
        // is the library ("14.1" -> 14, the major number is the extensions-lib generation).
        val library = values["tachiyomix.extensionLib"] ?: when (ecosystem) {
            SourceEcosystem.TSUNDOKU -> versionName.substringBeforeLast('.', "")
            SourceEcosystem.ANIYOMI -> versionName.substringBeforeLast('.', versionName)
            else -> ""
        }
        val supportedLibrary = when (ecosystem) {
            SourceEcosystem.TSUNDOKU -> Regex("1\\.[46](?:\\.[0-9]+)*")
            SourceEcosystem.ANIYOMI -> Regex("1[2-6](?:\\.[0-9]+)*")
            else -> Regex("1\\.[456](?:\\.[0-9]+)*")
        }
        if (!library.matches(supportedLibrary)) {
            throw SourceJarException(SourceJarFailure.UNSUPPORTED_LIBRARY)
        }
        val entries = listOf("$keyPrefix.class", "$keyPrefix.factory").flatMap { key ->
            values[key]?.split(';').orEmpty()
        }.map { entry ->
            val name = entry.trim()
            require(name.isNotEmpty())
            val fullName = when {
                name.startsWith('.') -> packageName + name
                '.' !in name -> "$packageName.$name"
                else -> name
            }
            require(fullName.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+")))
            fullName
        }
        require(entries.isNotEmpty() && entries.distinct().size == entries.size)
        val displayName = values["tachiyomix.name"].orEmpty().ifBlank { application.android("label").removeEcosystemPrefix() }
        require(displayName.isNotBlank() && !displayName.startsWith('@'))
        val versionCode = root.android("versionCode").toLong().also { require(it >= 0) }
        val nsfw = values["$keyPrefix.nsfw"] ?: "0"
        require(nsfw in setOf("0", "1", "false", "true"))
        // Tsundoku gates content on the warning level as well as the explicit flag.
        val warned = novel && (values["tachiyomix.contentWarning"]?.toIntOrNull() ?: 0) > 0
        MihonJarMetadata(
            packageName, displayName, versionCode, versionName, library, entries,
            nsfw == "1" || nsfw == "true" || warned, "", 0,
            ecosystem,
        )
    } catch (error: SourceJarException) {
        throw error
    } catch (error: Exception) {
        throw SourceJarException(SourceJarFailure.INVALID_MANIFEST, error)
    }

    private fun classVersion(jar: JarFile): Int {
        var maximum = 0
        for (entry in jar.entries()) {
            if (entry.isDirectory || !entry.name.endsWith(".class")) continue
            if (entry.name.startsWith("META-INF/versions/")) {
                val release = entry.name.removePrefix("META-INF/versions/").substringBefore('/').toIntOrNull()
                if (!jar.isMultiRelease || release == null || release > classVersionCeiling - 44) continue
            }
            val version = jar.getInputStream(entry).use { stream ->
                DataInputStream(stream).use { input ->
                    require(input.readInt() == 0xCAFEBABE.toInt())
                    val minor = input.readUnsignedShort()
                    require(minor != 65535) // Host does not launch with preview bytecode enabled.
                    input.readUnsignedShort()
                }
            }
            if (version > classVersionCeiling) throw SourceJarException(SourceJarFailure.UNSUPPORTED_BYTECODE)
            maximum = maxOf(maximum, version)
        }
        if (maximum == 0) throw SourceJarException(SourceJarFailure.INVALID_ARCHIVE)
        return maximum
    }

    private fun Element.android(name: String): String = getAttributeNS(ANDROID_NAMESPACE, name)

    /** Repository labels read "Aniyomi: Name"; the prefix says where it came from, not what it is called. */
    private fun String.removeEcosystemPrefix() = replace(Regex("^(Aniyomi|Tachiyomi|Tsundoku|Mihon): "), "")

    private companion object {
        const val MAX_MANIFEST_BYTES = 1024 * 1024
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
