package org.skepsun.kototoro.cloudstream.runtime

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CloudstreamPluginCompatibilityCheckerTest : FunSpec({

	test("plugin with unavailable Cloudstream classes only in type metadata is accepted") {
		val pluginFile = createPluginArchive(
			listOf(
				"Lcom/lagradost/cloudstream3/MainAPI;",
				"Lcom/lagradost/cloudstream3/MainActivity;",
				"Lcom/lagradost/cloudstream3/ui/home/HomeViewModel;",
			),
		)

		CloudstreamPluginCompatibilityChecker.inspect(
			pluginFile = pluginFile,
			hostClassLoader = CloudstreamPluginCompatibilityChecker::class.java.classLoader!!,
		) shouldBe CloudstreamPluginCompatibility.Compatible
	}

	test("plugin executing an instruction against an unavailable Cloudstream class is rejected") {
		val pluginFile = createPluginArchive(
			createExecutableReferenceDex("Lcom/lagradost/cloudstream3/MainActivity;"),
		)

		val result = CloudstreamPluginCompatibilityChecker.inspect(
			pluginFile = pluginFile,
			hostClassLoader = CloudstreamPluginCompatibilityChecker::class.java.classLoader!!,
		).shouldBeInstanceOf<CloudstreamPluginCompatibility.Incompatible>()

		result.missingHostClasses shouldContainExactly listOf("com.lagradost.cloudstream3.MainActivity")
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

    test("existing host class with a missing method is rejected before plugin activation") {
        val result = CloudstreamPluginCompatibilityChecker.inspect(
            createPluginArchive(createExecutableMethodDex(
                "Lcom/lagradost/cloudstream3/CommonActivity;", "showToastWithDuration",
                listOf("Ljava/lang/String;", "Ljava/lang/Integer;"),
            )),
            CloudstreamPluginCompatibilityChecker::class.java.classLoader!!,
        ).shouldBeInstanceOf<CloudstreamPluginCompatibility.Incompatible>()

        result.missingHostClasses shouldBe emptyList()
        result.reason.contains("CommonActivity.showToastWithDuration") shouldBe true
    }

    test("the Kotlin default argument bridge plugins call for showToast is available") {
        // Plugins compiled against Cloudstream call showToast("text") through this synthetic bridge.
        CloudstreamPluginCompatibilityChecker.inspect(
            createPluginArchive(createExecutableMethodDex(
                "Lcom/lagradost/cloudstream3/CommonActivity;", "showToast\$default",
                listOf(
                    "Lcom/lagradost/cloudstream3/CommonActivity;",
                    "Ljava/lang/String;",
                    "Ljava/lang/Integer;",
                    "I",
                    "Ljava/lang/Object;",
                ),
            )),
            CloudstreamPluginCompatibilityChecker::class.java.classLoader!!,
        ) shouldBe CloudstreamPluginCompatibility.Compatible
    }

    test("an executable call to an available host method is accepted") {
        CloudstreamPluginCompatibilityChecker.inspect(
            createPluginArchive(createExecutableMethodDex(
                "Lcom/lagradost/cloudstream3/MainAPI;", "setMainUrl", listOf("Ljava/lang/String;"),
            )),
            CloudstreamPluginCompatibilityChecker::class.java.classLoader!!,
        ) shouldBe CloudstreamPluginCompatibility.Compatible
    }

    test("wrong overload of an existing host method is rejected") {
        CloudstreamPluginCompatibilityChecker.inspect(
            createPluginArchive(createExecutableMethodDex(
                "Lcom/lagradost/cloudstream3/MainAPI;", "setMainUrl", listOf("Ljava/lang/Integer;"),
            )),
            CloudstreamPluginCompatibilityChecker::class.java.classLoader!!,
        ).shouldBeInstanceOf<CloudstreamPluginCompatibility.Incompatible>()
    }

    test("bundled real video plugin stays compatible") {
        CloudstreamPluginCompatibilityChecker.inspect(
            File("src/androidTest/assets/IdlixProvider.cs3"),
            CloudstreamPluginCompatibilityChecker::class.java.classLoader!!,
        ) shouldBe CloudstreamPluginCompatibility.Compatible
    }
})

private fun createExecutableMethodDex(owner: String, method: String, parameters: List<String>): ByteArray {
    val descriptors = listOf("Ltest/Plugin;", owner, "V") + parameters
    val strings = descriptors + listOf("run", method)
    val stringIdsOffset = 112
    val typeIdsOffset = stringIdsOffset + strings.size * 4
    val prototypeOffset = typeIdsOffset + descriptors.size * 4
    val methodIdsOffset = prototypeOffset + 12
    val classDefsOffset = methodIdsOffset + 16
    val classDataOffset = classDefsOffset + 32
    val parameterOffset = classDataOffset + 8
    val codeOffset = 320
    val stringDataOffset = codeOffset + 24
    val stringData = ByteArrayOutputStream()
    val stringOffsets = strings.map { value ->
        val offset = stringDataOffset + stringData.size()
        stringData.write(value.length)
        stringData.write(value.toByteArray())
        stringData.write(0)
        offset
    }
    return ByteArray(stringDataOffset + stringData.size()).apply {
        "dex".toByteArray().copyInto(this)
        writeInt(0x38, strings.size)
        writeInt(0x3c, stringIdsOffset)
        writeInt(0x40, descriptors.size)
        writeInt(0x44, typeIdsOffset)
        writeInt(0x48, 1)
        writeInt(0x4c, prototypeOffset)
        writeInt(0x58, 2)
        writeInt(0x5c, methodIdsOffset)
        writeInt(0x60, 1)
        writeInt(0x64, classDefsOffset)
        stringOffsets.forEachIndexed { index, offset -> writeInt(stringIdsOffset + index * 4, offset) }
        descriptors.indices.forEach { writeInt(typeIdsOffset + it * 4, it) }
        writeInt(prototypeOffset + 4, 2)
        writeInt(prototypeOffset + 8, parameterOffset)
        writeInt(parameterOffset, parameters.size)
        parameters.indices.forEach { writeShort(parameterOffset + 4 + it * 2, 3 + it) }
        writeShort(methodIdsOffset, 0)
        writeInt(methodIdsOffset + 4, descriptors.size)
        writeShort(methodIdsOffset + 8, 1)
        writeInt(methodIdsOffset + 12, descriptors.size + 1)
        writeInt(classDefsOffset, 0)
        writeInt(classDefsOffset + 24, classDataOffset)
        byteArrayOf(0, 0, 1, 0, 0, 1, 0xc0.toByte(), 2).copyInto(this, classDataOffset)
        writeShort(codeOffset, 3)
        writeInt(codeOffset + 12, 4)
        writeShort(codeOffset + 16, 0x306e)
        writeShort(codeOffset + 18, 1)
        writeShort(codeOffset + 20, 0x210)
        writeShort(codeOffset + 22, 0x0e)
        stringData.toByteArray().copyInto(this, stringDataOffset)
    }
}

private fun createPluginArchive(typeDescriptors: List<String>) =
	createPluginArchive(createDex(typeDescriptors))

private fun createPluginArchive(dex: ByteArray) =
	Files.createTempFile("cloudstream-compatibility", ".cs3").toFile().apply {
		ZipOutputStream(outputStream()).use { zip ->
			zip.putNextEntry(ZipEntry("classes.dex"))
			zip.write(dex)
			zip.closeEntry()
		}
	}

private fun createExecutableReferenceDex(targetDescriptor: String): ByteArray {
	val strings = listOf("Ltest/Plugin;", targetDescriptor, "execute")
	val headerSize = 112
	val stringIdsOffset = headerSize
	val typeIdsOffset = stringIdsOffset + strings.size * 4
	val methodIdsOffset = typeIdsOffset + 2 * 4
	val classDefsOffset = methodIdsOffset + 8
	val classDataOffset = classDefsOffset + 32
	val codeOffset = 192
	val stringDataOffset = codeOffset + 22
	val stringData = ByteArrayOutputStream()
	val stringOffsets = strings.map { value ->
		val offset = stringDataOffset + stringData.size()
		stringData.write(value.length)
		stringData.write(value.toByteArray())
		stringData.write(0)
		offset
	}
	val data = ByteArray(stringDataOffset + stringData.size())
	data[0] = 'd'.code.toByte()
	data[1] = 'e'.code.toByte()
	data[2] = 'x'.code.toByte()
	data.writeInt(0x38, strings.size)
	data.writeInt(0x3c, stringIdsOffset)
	data.writeInt(0x40, 2)
	data.writeInt(0x44, typeIdsOffset)
	data.writeInt(0x58, 1)
	data.writeInt(0x5c, methodIdsOffset)
	data.writeInt(0x60, 1)
	data.writeInt(0x64, classDefsOffset)
	stringOffsets.forEachIndexed { index, offset -> data.writeInt(stringIdsOffset + index * 4, offset) }
	data.writeInt(typeIdsOffset, 0)
	data.writeInt(typeIdsOffset + 4, 1)
	data.writeShort(methodIdsOffset, 0)
	data.writeShort(methodIdsOffset + 2, 0)
	data.writeInt(methodIdsOffset + 4, 2)
	data.writeInt(classDefsOffset, 0)
	data.writeInt(classDefsOffset + 24, classDataOffset)
	byteArrayOf(0, 0, 1, 0, 0, 1, 0xc0.toByte(), 1).copyInto(data, classDataOffset)
	data.writeShort(codeOffset, 1)
	data.writeInt(codeOffset + 12, 3)
	data.writeShort(codeOffset + 16, 0x22)
	data.writeShort(codeOffset + 18, 1)
	data.writeShort(codeOffset + 20, 0x0e)
	stringData.toByteArray().copyInto(data, stringDataOffset)
	return data
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

private fun ByteArray.writeShort(offset: Int, value: Int) {
	this[offset] = value.toByte()
	this[offset + 1] = (value ushr 8).toByte()
}
