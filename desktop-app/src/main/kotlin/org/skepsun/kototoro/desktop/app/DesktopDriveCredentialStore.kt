package org.skepsun.kototoro.desktop.app

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal interface DesktopDriveCredentialStore {
    fun read(): String?
    fun write(value: String)
    fun clear()
}

/** Credentials never enter the preference directory, Room, backups or the source browser's cookie store. */
internal fun desktopDriveCredentialStore(root: Path): DesktopDriveCredentialStore {
    val identity = MessageDigest.getInstance("SHA-256")
        .digest("${root.toAbsolutePath().normalize()}\n$DESKTOP_GOOGLE_CLIENT_ID".toByteArray(UTF_8))
        .joinToString("") { "%02x".format(it) }
    val os = System.getProperty("os.name").lowercase()
    return when {
        os.contains("mac") -> MacDriveCredentialStore(identity)
        os.contains("win") -> WindowsDriveCredentialStore("Kototoro/GoogleDrive/$identity")
        else -> SecretServiceDriveCredentialStore(identity)
    }
}

/** The Security framework is supplied by macOS; JNA is already shipped for desktop playback. */
private class MacDriveCredentialStore(private val account: String) : DesktopDriveCredentialStore {
    private val service = "org.skepsun.kototoro.desktop.google-drive".toByteArray(UTF_8)
    private val accountBytes = account.toByteArray(UTF_8)
    private val security by lazy { Native.load("/System/Library/Frameworks/Security.framework/Security", Security::class.java) }
    private val foundation by lazy { Native.load("/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation", Foundation::class.java) }

    override fun read(): String? = withItem { item, length, data ->
        if (item == null) null else data!!.getByteArray(0, length).toString(UTF_8)
    }

    override fun write(value: String) {
        val bytes = value.toByteArray(UTF_8)
        try {
            withItem { item, _, _ ->
                val status = if (item == null) {
                    security.SecKeychainAddGenericPassword(null, service.size, service, accountBytes.size,
                        accountBytes, bytes.size, bytes, null)
                } else security.SecKeychainItemModifyAttributesAndData(item, null, bytes.size, bytes)
                checkStatus(status)
            }
        } finally { bytes.fill(0) }
    }

    override fun clear() = withItem { item, _, _ ->
        if (item != null) checkStatus(security.SecKeychainItemDelete(item))
    }

    private fun <T> withItem(block: (Pointer?, Int, Pointer?) -> T): T {
        val length = IntByReference()
        val data = PointerByReference()
        val item = PointerByReference()
        val status = security.SecKeychainFindGenericPassword(null, service.size, service, accountBytes.size,
            accountBytes, length, data, item)
        if (status == -25300) return block(null, 0, null) // errSecItemNotFound
        checkStatus(status)
        try {
            return block(item.value, length.value, data.value)
        } finally {
            security.SecKeychainItemFreeContent(null, data.value)
            item.value?.let(foundation::CFRelease)
        }
    }

    private fun checkStatus(status: Int) {
        if (status != 0) throw DesktopDriveException(DesktopDriveFailure.CREDENTIAL_STORE)
    }

    interface Security : Library {
        fun SecKeychainFindGenericPassword(keychains: Pointer?, serviceLength: Int, service: ByteArray,
            accountLength: Int, account: ByteArray, length: IntByReference, data: PointerByReference,
            item: PointerByReference): Int
        fun SecKeychainAddGenericPassword(keychain: Pointer?, serviceLength: Int, service: ByteArray,
            accountLength: Int, account: ByteArray, length: Int, data: ByteArray, item: PointerByReference?): Int
        fun SecKeychainItemModifyAttributesAndData(item: Pointer, attributes: Pointer?, length: Int, data: ByteArray): Int
        fun SecKeychainItemFreeContent(attributes: Pointer?, data: Pointer?): Int
        fun SecKeychainItemDelete(item: Pointer): Int
    }

    interface Foundation : Library { fun CFRelease(value: Pointer) }
}

/** Windows Credential Manager protects the generic credential for the current Windows user. */
private class WindowsDriveCredentialStore(private val target: String) : DesktopDriveCredentialStore {
    private val credentials by lazy { Native.load("Advapi32", Credentials::class.java) }

    override fun read(): String? {
        val pointer = PointerByReference()
        if (!credentials.CredReadW(WString(target), 1, 0, pointer)) {
            if (Native.getLastError() == 1168) return null // ERROR_NOT_FOUND
            throw DesktopDriveException(DesktopDriveFailure.CREDENTIAL_STORE)
        }
        try {
            val stored = Credential(pointer.value)
            if (stored.blobSize !in 1..2560) throw DesktopDriveException(DesktopDriveFailure.CREDENTIAL_STORE)
            return stored.blob!!.getByteArray(0, stored.blobSize).toString(UTF_8)
        } finally { credentials.CredFree(pointer.value) }
    }

    override fun write(value: String) {
        val bytes = value.toByteArray(UTF_8)
        if (bytes.size !in 1..2560) throw DesktopDriveException(DesktopDriveFailure.CREDENTIAL_STORE)
        Memory(bytes.size.toLong()).use { memory ->
            try {
                memory.write(0, bytes, 0, bytes.size)
                val credential = Credential().apply {
                    targetName = WString(target)
                    blobSize = bytes.size
                    blob = memory
                    persist = 2 // CRED_PERSIST_LOCAL_MACHINE, scoped to the current user.
                    userName = WString("Kototoro")
                    write()
                }
                if (!credentials.CredWriteW(credential, 0)) {
                    throw DesktopDriveException(DesktopDriveFailure.CREDENTIAL_STORE)
                }
            } finally { memory.clear(); bytes.fill(0) }
        }
    }

    override fun clear() {
        if (!credentials.CredDeleteW(WString(target), 1, 0) && Native.getLastError() != 1168) {
            throw DesktopDriveException(DesktopDriveFailure.CREDENTIAL_STORE)
        }
    }

    interface Credentials : StdCallLibrary {
        fun CredReadW(target: WString, type: Int, flags: Int, credential: PointerByReference): Boolean
        fun CredWriteW(credential: Credential, flags: Int): Boolean
        fun CredDeleteW(target: WString, type: Int, flags: Int): Boolean
        fun CredFree(value: Pointer)
    }

    @Structure.FieldOrder("flags", "type", "targetName", "comment", "lastWrittenLow", "lastWrittenHigh",
        "blobSize", "blob", "persist", "attributeCount", "attributes", "targetAlias", "userName")
    class Credential : Structure {
        constructor() : super()
        constructor(pointer: Pointer) : super(pointer) { read() }
        @JvmField var flags = 0
        @JvmField var type = 1
        @JvmField var targetName: WString? = null
        @JvmField var comment: WString? = null
        @JvmField var lastWrittenLow = 0
        @JvmField var lastWrittenHigh = 0
        @JvmField var blobSize = 0
        @JvmField var blob: Pointer? = null
        @JvmField var persist = 0
        @JvmField var attributeCount = 0
        @JvmField var attributes: Pointer? = null
        @JvmField var targetAlias: WString? = null
        @JvmField var userName: WString? = null
    }
}

/** Linux uses the desktop keyring, with no plaintext fallback when Secret Service is unavailable. */
private class SecretServiceDriveCredentialStore(private val account: String) : DesktopDriveCredentialStore {
    override fun read(): String? = command(listOf("lookup"), null, allowMissing = true)
    override fun write(value: String) { command(listOf("store", "--label=Kototoro Google Drive"), value) }
    override fun clear() { command(listOf("clear"), null, allowMissing = true) }

    private fun command(arguments: List<String>, input: String?, allowMissing: Boolean = false): String? {
        val process = try {
            ProcessBuilder(listOf("secret-tool") + arguments + listOf("application", "kototoro", "account", account))
                .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        } catch (_: Exception) { throw DesktopDriveException(DesktopDriveFailure.CREDENTIAL_STORE) }
        try {
            process.outputStream.use { if (input != null) it.write(input.toByteArray(UTF_8)) }
            if (!process.waitFor(30, TimeUnit.SECONDS)) throw DesktopDriveException(DesktopDriveFailure.CREDENTIAL_STORE)
            if (allowMissing && process.exitValue() == 1) return null
            if (process.exitValue() != 0) throw DesktopDriveException(DesktopDriveFailure.CREDENTIAL_STORE)
            return process.inputStream.readNBytes(8192).toString(UTF_8).trimEnd('\n').takeIf(String::isNotEmpty)
        } finally { process.destroyForcibly() }
    }
}
