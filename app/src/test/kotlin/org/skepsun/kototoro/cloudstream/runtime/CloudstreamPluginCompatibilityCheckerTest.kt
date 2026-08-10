package org.skepsun.kototoro.cloudstream.runtime

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CloudstreamPluginCompatibilityCheckerTest : FunSpec({

	test("plugin referencing unavailable Cloudstream app classes is rejected") {
		val pluginFile = createPluginArchive(
			listOf(
				"Lcom/lagradost/cloudstream3/MainAPI;",
				"Lcom/lagradost/cloudstream3/MainActivity;",
				"Lcom/lagradost/cloudstream3/ui/home/HomeViewModel;",
			),
		)

		val result = CloudstreamPluginCompatibilityChecker.inspect(
			pluginFile = pluginFile,
			hostClassLoader = CloudstreamPluginCompatibilityChecker::class.java.classLoader!!,
		).shouldBeInstanceOf<CloudstreamPluginCompatibility.Incompatible>()

		result.missingHostClasses shouldContainExactly listOf(
			"com.lagradost.cloudstream3.MainActivity",
			"com.lagradost.cloudstream3.ui.home.HomeViewModel",
		)
	}

	test("plugin referencing only available Cloudstream runtime classes is accepted") {
		val pluginFile = createPluginArchive(
			listOf(
				"Lcom/lagradost/cloudstream3/MainAPI;",
				"Lcom/lagradost/cloudstream3/plugins/Plugin;",
			),
		)

		CloudstreamPluginCompatibilityChecker.inspect(
			pluginFile = pluginFile,
			hostClassLoader = CloudstreamPluginCompatibilityChecker::class.java.classLoader!!,
		) shouldBe CloudstreamPluginCompatibility.Compatible
	}

	test("archive without dex bytecode is rejected") {
		val pluginFile = Files.createTempFile("cloudstream-invalid", ".cs3").toFile()
		ZipOutputStream(pluginFile.outputStream()).use { zip ->
			zip.putNextEntry(ZipEntry("manifest.json"))
			zip.write("{}".toByteArray())
			zip.closeEntry()
		}

		val result = CloudstreamPluginCompatibilityChecker.inspect(
			pluginFile = pluginFile,
			hostClassLoader = CloudstreamPluginCompatibilityChecker::class.java.classLoader!!,
		).shouldBeInstanceOf<CloudstreamPluginCompatibility.Incompatible>()

		result.missingHostClasses shouldBe emptyList()
	}
})

private fun createPluginArchive(typeDescriptors: List<String>) =
	Files.createTempFile("cloudstream-compatibility", ".cs3").toFile().apply {
		ZipOutputStream(outputStream()).use { zip ->
			zip.putNextEntry(ZipEntry("classes.dex"))
			zip.write(createDex(typeDescriptors))
			zip.closeEntry()
		}
	}

private fun createDex(typeDescriptors: List<String>): ByteArray {
	val headerSize = 112
	val stringIdsOffset = headerSize
	val typeIdsOffset = stringIdsOffset + typeDescriptors.size * 4
	val stringData = ByteArrayOutputStream()
	val stringOffsets = typeDescriptors.map { descriptor ->
		val offset = typeIdsOffset + typeDescriptors.size * 4 + stringData.size()
		stringData.write(descriptor.length)
		stringData.write(descriptor.toByteArray())
		stringData.write(0)
		offset
	}
	val data = ByteArray(typeIdsOffset + typeDescriptors.size * 4 + stringData.size())
	data[0] = 'd'.code.toByte()
	data[1] = 'e'.code.toByte()
	data[2] = 'x'.code.toByte()
	data.writeInt(0x38, typeDescriptors.size)
	data.writeInt(0x3c, stringIdsOffset)
	data.writeInt(0x40, typeDescriptors.size)
	data.writeInt(0x44, typeIdsOffset)
	stringOffsets.forEachIndexed { index, offset -> data.writeInt(stringIdsOffset + index * 4, offset) }
	typeDescriptors.indices.forEach { index -> data.writeInt(typeIdsOffset + index * 4, index) }
	stringData.toByteArray().copyInto(data, typeIdsOffset + typeDescriptors.size * 4)
	return data
}

private fun ByteArray.writeInt(offset: Int, value: Int) {
	this[offset] = value.toByte()
	this[offset + 1] = (value ushr 8).toByte()
	this[offset + 2] = (value ushr 16).toByte()
	this[offset + 3] = (value ushr 24).toByte()
}
