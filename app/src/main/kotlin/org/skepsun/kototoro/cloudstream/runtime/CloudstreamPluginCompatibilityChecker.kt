package org.skepsun.kototoro.cloudstream.runtime

import java.io.File
import java.util.zip.ZipFile

internal object CloudstreamPluginCompatibilityChecker {

    fun inspect(
        pluginFile: File,
        hostClassLoader: ClassLoader,
    ): CloudstreamPluginCompatibility {
        return runCatching {
            val referencedHostClasses = ZipFile(pluginFile).use { zip ->
                val dexEntries = zip.entries().asSequence()
                    .filterNot { it.isDirectory }
                    .filter { it.name.endsWith(".dex", ignoreCase = true) }
                    .toList()
                require(dexEntries.isNotEmpty()) { "Plugin archive does not contain DEX bytecode" }
                dexEntries
                    .asSequence()
                    .flatMap { entry ->
                        zip.getInputStream(entry).use { input ->
                            DexExecutableTypeDescriptorReader.read(input.readBytes()).asSequence()
                        }
                    }
                    .mapNotNull(::cloudstreamClassName)
                    .toSortedSet()
            }
            val missingClasses = referencedHostClasses.filterNot { className ->
                runCatching { hostClassLoader.loadClass(className) }.isSuccess
            }
            if (missingClasses.isEmpty()) {
                CloudstreamPluginCompatibility.Compatible
            } else {
                CloudstreamPluginCompatibility.Incompatible(
                    reason = "Requires unsupported Cloudstream host classes: ${missingClasses.joinToString()}",
                    missingHostClasses = missingClasses,
                )
            }
        }.getOrElse { error ->
            CloudstreamPluginCompatibility.Incompatible(
                reason = "Invalid Cloudstream plugin archive: ${error.message ?: error.javaClass.simpleName}",
            )
        }
    }

    private fun cloudstreamClassName(descriptor: String): String? {
        val classDescriptor = descriptor.dropWhile { it == '[' }
        if (!classDescriptor.startsWith(CLOUDSTREAM_DESCRIPTOR_PREFIX) || !classDescriptor.endsWith(';')) {
            return null
        }
        return classDescriptor
            .removePrefix("L")
            .removeSuffix(";")
            .replace('/', '.')
    }

    private const val CLOUDSTREAM_DESCRIPTOR_PREFIX = "Lcom/lagradost/cloudstream3/"
}

internal sealed interface CloudstreamPluginCompatibility {
    data object Compatible : CloudstreamPluginCompatibility

    data class Incompatible(
        val reason: String,
        val missingHostClasses: List<String> = emptyList(),
    ) : CloudstreamPluginCompatibility
}

internal object DexExecutableTypeDescriptorReader {

    fun read(data: ByteArray): List<String> {
        require(data.size >= DEX_HEADER_SIZE && data.startsWith(DEX_MAGIC)) { "Invalid DEX header" }
        val strings = Table(size = data.uint(STRING_IDS_SIZE_OFFSET), offset = data.uint(STRING_IDS_OFFSET), itemSize = 4)
        val types = Table(size = data.uint(TYPE_IDS_SIZE_OFFSET), offset = data.uint(TYPE_IDS_OFFSET), itemSize = 4)
        val fields = Table(size = data.uint(FIELD_IDS_SIZE_OFFSET), offset = data.uint(FIELD_IDS_OFFSET), itemSize = 8)
        val methods = Table(size = data.uint(METHOD_IDS_SIZE_OFFSET), offset = data.uint(METHOD_IDS_OFFSET), itemSize = 8)
        val classes = Table(size = data.uint(CLASS_DEFS_SIZE_OFFSET), offset = data.uint(CLASS_DEFS_OFFSET), itemSize = 32)
        data.validate(strings)
        data.validate(types)
        data.validate(fields)
        data.validate(methods)
        data.validate(classes)
        val typeDescriptors = List(types.size) { index ->
            data.string(strings, data.uint(types.itemOffset(index)))
        }
        return buildSet {
            for (classIndex in 0 until classes.size) {
                val classDataOffset = data.uint(classes.itemOffset(classIndex) + CLASS_DATA_OFFSET)
                if (classDataOffset != 0) {
                    data.readClassData(classDataOffset, fields, methods, typeDescriptors, this)
                }
            }
        }.sorted()
    }

    private fun ByteArray.readClassData(
        offset: Int,
        fields: Table,
        methods: Table,
        typeDescriptors: List<String>,
        destination: MutableSet<String>,
    ) {
        val cursor = Cursor(offset)
        val staticFieldsSize = uleb128(cursor)
        val instanceFieldsSize = uleb128(cursor)
        val directMethodsSize = uleb128(cursor)
        val virtualMethodsSize = uleb128(cursor)
        repeat(staticFieldsSize + instanceFieldsSize) {
            uleb128(cursor)
            uleb128(cursor)
        }
        readMethods(cursor, directMethodsSize, fields, methods, typeDescriptors, destination)
        readMethods(cursor, virtualMethodsSize, fields, methods, typeDescriptors, destination)
    }

    private fun ByteArray.readMethods(
        cursor: Cursor,
        count: Int,
        fields: Table,
        methods: Table,
        typeDescriptors: List<String>,
        destination: MutableSet<String>,
    ) {
        var methodIndex = 0
        repeat(count) {
            methodIndex = Math.addExact(methodIndex, uleb128(cursor))
            require(methodIndex in 0 until methods.size) { "Invalid DEX method index" }
            uleb128(cursor)
            val codeOffset = uleb128(cursor)
            if (codeOffset != 0) {
                readCode(codeOffset, fields, methods, typeDescriptors, destination)
            }
        }
    }

    private fun ByteArray.readCode(
        offset: Int,
        fields: Table,
        methods: Table,
        typeDescriptors: List<String>,
        destination: MutableSet<String>,
    ) {
        require(offset >= 0 && offset + CODE_ITEM_HEADER_SIZE <= size) { "Invalid DEX code offset" }
        val instructionCount = uint(offset + INSNS_SIZE_OFFSET)
        val instructionsOffset = offset + CODE_ITEM_HEADER_SIZE
        val instructionsEnd = Math.addExact(instructionsOffset, Math.multiplyExact(instructionCount, 2))
        require(instructionsEnd <= size) { "DEX instructions exceed file bounds" }
        var instructionIndex = 0
        while (instructionIndex < instructionCount) {
            val instructionOffset = instructionsOffset + instructionIndex * 2
            val firstCodeUnit = ushort(instructionOffset)
            val opcode = firstCodeUnit and 0xff
            val width = instructionWidth(opcode, firstCodeUnit, instructionOffset, instructionCount - instructionIndex)
            require(width > 0 && instructionIndex + width <= instructionCount) { "Invalid DEX instruction width" }
            when {
                opcode in TYPE_REFERENCE_OPCODES -> {
                    addTypeDescriptor(ushort(instructionOffset + 2), typeDescriptors, destination)
                }
                opcode in FIELD_REFERENCE_OPCODES -> {
                    val fieldIndex = ushort(instructionOffset + 2)
                    require(fieldIndex in 0 until fields.size) { "Invalid DEX field index" }
                    addTypeDescriptor(ushort(fields.itemOffset(fieldIndex)), typeDescriptors, destination)
                }
                opcode in METHOD_REFERENCE_OPCODES || opcode in POLYMORPHIC_METHOD_OPCODES -> {
                    val methodIndex = ushort(instructionOffset + 2)
                    require(methodIndex in 0 until methods.size) { "Invalid DEX method reference" }
                    addTypeDescriptor(ushort(methods.itemOffset(methodIndex)), typeDescriptors, destination)
                }
            }
            instructionIndex += width
        }
    }

    private fun ByteArray.addTypeDescriptor(
        typeIndex: Int,
        typeDescriptors: List<String>,
        destination: MutableSet<String>,
    ) {
        require(typeIndex in typeDescriptors.indices) {
            "Invalid DEX type index $typeIndex (type count ${typeDescriptors.size})"
        }
        destination += typeDescriptors[typeIndex]
    }

    private fun ByteArray.instructionWidth(
        opcode: Int,
        firstCodeUnit: Int,
        offset: Int,
        remainingCodeUnits: Int,
    ): Int = when {
        opcode == 0 -> payloadWidth(firstCodeUnit, offset, remainingCodeUnits)
        opcode in WIDTH_TWO_OPCODES -> 2
        opcode in WIDTH_THREE_OPCODES -> 3
        opcode == 0x18 -> 5
        opcode in POLYMORPHIC_METHOD_OPCODES -> 4
        else -> 1
    }

    private fun ByteArray.payloadWidth(firstCodeUnit: Int, offset: Int, remainingCodeUnits: Int): Int = when (firstCodeUnit) {
        PACKED_SWITCH_PAYLOAD -> {
            require(remainingCodeUnits >= 2) { "Invalid packed-switch payload" }
            Math.addExact(4, Math.multiplyExact(ushort(offset + 2), 2))
        }
        SPARSE_SWITCH_PAYLOAD -> {
            require(remainingCodeUnits >= 2) { "Invalid sparse-switch payload" }
            Math.addExact(2, Math.multiplyExact(ushort(offset + 2), 4))
        }
        FILL_ARRAY_DATA_PAYLOAD -> {
            require(remainingCodeUnits >= 4) { "Invalid fill-array-data payload" }
            val elementWidth = ushort(offset + 2)
            val elementCount = uint(offset + 4)
            val byteCount = Math.multiplyExact(elementWidth, elementCount)
            Math.addExact(4, Math.toIntExact((byteCount + 1L) / 2L))
        }
        else -> 1
        }

    private fun ByteArray.string(strings: Table, index: Int): String {
        require(index in 0 until strings.size) { "Invalid DEX string index" }
        val cursor = Cursor(uint(strings.itemOffset(index)))
        uleb128(cursor)
        val start = cursor.offset
        var end = start
        while (end < size && this[end].toInt() != 0) end++
        require(end < size) { "Unterminated DEX string" }
        return decodeToString(start, end)
    }

    private fun ByteArray.uleb128(cursor: Cursor): Int {
        var result = 0
        var shift = 0
        repeat(5) {
            require(cursor.offset < size) { "Invalid DEX uleb128 value" }
            val value = this[cursor.offset++].toInt() and 0xff
            result = result or ((value and 0x7f) shl shift)
            if (value and 0x80 == 0) return result
            shift += 7
        }
        throw IllegalArgumentException("Invalid DEX uleb128 value")
    }

    private fun ByteArray.validate(table: Table) {
        require(table.size >= 0 && table.offset >= 0) { "Invalid DEX table" }
        val tableEnd = Math.addExact(table.offset, Math.multiplyExact(table.size, table.itemSize))
        require(tableEnd <= size) { "DEX table exceeds file bounds" }
    }

    private fun ByteArray.uint(offset: Int): Int {
        require(offset >= 0 && offset + 4 <= size) { "Invalid DEX offset" }
        return (this[offset].toInt() and 0xff) or
            ((this[offset + 1].toInt() and 0xff) shl 8) or
            ((this[offset + 2].toInt() and 0xff) shl 16) or
            ((this[offset + 3].toInt() and 0xff) shl 24)
    }

    private fun ByteArray.ushort(offset: Int): Int {
        require(offset >= 0 && offset + 2 <= size) { "Invalid DEX offset" }
        return (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        return size >= prefix.size && prefix.indices.all { index -> this[index] == prefix[index] }
    }

    private data class Table(
        val size: Int,
        val offset: Int,
        val itemSize: Int,
    ) {
        fun itemOffset(index: Int): Int = Math.addExact(offset, Math.multiplyExact(index, itemSize))
    }

    private data class Cursor(var offset: Int)

    private const val DEX_HEADER_SIZE = 112
    private const val STRING_IDS_SIZE_OFFSET = 0x38
    private const val STRING_IDS_OFFSET = 0x3c
    private const val TYPE_IDS_SIZE_OFFSET = 0x40
    private const val TYPE_IDS_OFFSET = 0x44
    private const val FIELD_IDS_SIZE_OFFSET = 0x50
    private const val FIELD_IDS_OFFSET = 0x54
    private const val METHOD_IDS_SIZE_OFFSET = 0x58
    private const val METHOD_IDS_OFFSET = 0x5c
    private const val CLASS_DEFS_SIZE_OFFSET = 0x60
    private const val CLASS_DEFS_OFFSET = 0x64
    private const val CLASS_DATA_OFFSET = 24
    private const val CODE_ITEM_HEADER_SIZE = 16
    private const val INSNS_SIZE_OFFSET = 12
    private const val PACKED_SWITCH_PAYLOAD = 0x0100
    private const val SPARSE_SWITCH_PAYLOAD = 0x0200
    private const val FILL_ARRAY_DATA_PAYLOAD = 0x0300
    private val TYPE_REFERENCE_OPCODES = setOf(0x1c, 0x1f, 0x20, 0x22, 0x23, 0x24, 0x25)
    private val FIELD_REFERENCE_OPCODES = 0x52..0x6d
    private val METHOD_REFERENCE_OPCODES = (0x6e..0x72) + (0x74..0x78)
    private val POLYMORPHIC_METHOD_OPCODES = 0xfa..0xfb
    private val WIDTH_TWO_OPCODES = setOf(0x02, 0x05, 0x08, 0x13, 0x15, 0x16, 0x19, 0x1a, 0x1c, 0x1f, 0x20, 0x22, 0x23, 0x29, 0xfe, 0xff) +
        (0x2d..0x3d) + (0x44..0x6d) + (0x90..0xaf) + (0xd0..0xe2)
    private val WIDTH_THREE_OPCODES = setOf(0x03, 0x06, 0x09, 0x14, 0x17, 0x1b, 0x24, 0x25, 0x26, 0x2a, 0x2b, 0x2c, 0xfc, 0xfd) +
        METHOD_REFERENCE_OPCODES
    private val DEX_MAGIC = byteArrayOf('d'.code.toByte(), 'e'.code.toByte(), 'x'.code.toByte())
}
