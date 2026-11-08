package com.hippo.unifile

import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

class FakeSafDirUniFile(
    private val treeUri: Uri,
    private val backingDir: File,
    private val stripCbzOnCreateFile: Boolean = false,
) : UniFile(null) {

    private val children = linkedMapOf<String, FakeSafChildUniFile>()

    fun registerCreatedChild(actualName: String): Uri {
        val file = File(backingDir, actualName).apply { if (!exists()) createNewFile() }
        val childUri = Uri.parse("$treeUri%2F${Uri.encode(actualName)}")
        children[actualName] = FakeSafChildUniFile(this, childUri, file, actualName)
        return childUri
    }

    override fun createFile(displayName: String?): UniFile? {
        val rawName = displayName ?: return null
        val actualName = if (stripCbzOnCreateFile && rawName.endsWith(".cbz", ignoreCase = true)) {
            rawName.substringBeforeLast('.')
        } else {
            rawName
        }
        val file = File(backingDir, actualName).apply { if (!exists()) createNewFile() }
        val childUri = Uri.parse("$treeUri%2F${Uri.encode(actualName)}")
        return FakeSafChildUniFile(this, childUri, file, actualName).also {
            children[actualName] = it
        }
    }

    override fun createDirectory(displayName: String?): UniFile? = null
    override fun getUri(): Uri = treeUri
    override fun getName(): String = backingDir.name
    override fun getType(): String? = null
    override fun isDirectory(): Boolean = true
    override fun isFile(): Boolean = false
    override fun lastModified(): Long = backingDir.lastModified()
    override fun length(): Long = 0L
    override fun canRead(): Boolean = true
    override fun canWrite(): Boolean = true
    override fun delete(): Boolean = backingDir.deleteRecursively()
    override fun exists(): Boolean = backingDir.exists()
    override fun listFiles(): Array<UniFile> = children.values.toTypedArray()
    override fun listFiles(filter: FilenameFilter?): Array<UniFile> = listFiles()
    override fun findFile(displayName: String?): UniFile? {
        if (displayName == null) return null
        children[displayName]?.takeIf { it.exists() }?.let { return it }
        val disk = File(backingDir, displayName)
        return if (disk.exists()) {
            val childUri = Uri.parse("$treeUri%2F${Uri.encode(displayName)}")
            FakeSafChildUniFile(this, childUri, disk, displayName).also {
                children[displayName] = it
            }
        } else {
            null
        }
    }

    override fun renameTo(displayName: String?): Boolean = false
    override fun getFilePath(): String? = backingDir.absolutePath
    override fun openOutputStream(): OutputStream = throw UnsupportedOperationException()
    override fun openOutputStream(append: Boolean): OutputStream = throw UnsupportedOperationException()
    override fun openInputStream(): InputStream = throw UnsupportedOperationException()
    override fun createRandomAccessFile(mode: String?): UniRandomAccessFile =
        throw UnsupportedOperationException()
}

class FakeSafChildUniFile(
    parent: UniFile,
    private val childUri: Uri,
    private val file: File,
    private val displayName: String,
) : UniFile(parent) {
    override fun createFile(displayName: String?): UniFile? = null
    override fun createDirectory(displayName: String?): UniFile? = null
    override fun getUri(): Uri = childUri
    override fun getName(): String = displayName
    override fun getType(): String = "application/octet-stream"
    override fun isDirectory(): Boolean = false
    override fun isFile(): Boolean = file.isFile
    override fun lastModified(): Long = file.lastModified()
    override fun length(): Long = file.length()
    override fun canRead(): Boolean = file.canRead()
    override fun canWrite(): Boolean = file.canWrite()
    override fun delete(): Boolean = file.delete()
    override fun exists(): Boolean = file.exists()
    override fun listFiles(): Array<UniFile>? = null
    override fun listFiles(filter: FilenameFilter?): Array<UniFile>? = null
    override fun findFile(displayName: String?): UniFile? = null
    override fun renameTo(displayName: String?): Boolean = false
    override fun getFilePath(): String = file.absolutePath
    override fun openOutputStream(): OutputStream = file.outputStream()
    override fun openOutputStream(append: Boolean): OutputStream = FileOutputStream(file, append)
    override fun openInputStream(): InputStream = file.inputStream()
    override fun createRandomAccessFile(mode: String?): UniRandomAccessFile =
        throw UnsupportedOperationException()
}
