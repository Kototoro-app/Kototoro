package org.skepsun.kototoro.cloudstream.desktop

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.SimpleRemapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * What a converted plugin's classes are given on Windows in place of Android behaviour the host cannot provide:
 * - process exits throw ([PluginExits]) instead of closing Kototoro;
 * - `ActivityManager.MemoryInfo` is [DesktopMemoryInfo], filled by [DesktopActivityManager] from the machine's memory,
 *   as the compatibility runtime's class is a stub that throws.
 *
 * Only plugins are rewritten: Android's classes stay as they are for everything else in the process.
 */
object PluginBytecodeRewriter {
	/** Bump when the rewrite changes: rewritten jars are cached under it. */
	const val VERSION = 2

	private val exits = internalName(PluginExits::class.java)
	private val activityManager = internalName(DesktopActivityManager::class.java)
	private val memoryInfo = internalName(DesktopMemoryInfo::class.java)

	/** Android types a plugin's classes see as the host's stand-ins. */
	private val types = mapOf("android/app/ActivityManager\$MemoryInfo" to memoryInfo)

	/**
	 * (owner, name, descriptor after [types], opcode) of a call → the static method replacing it, which takes the
	 * receiver, if any, as its first argument; each leaves the stack as the call did.
	 */
	private val calls = mapOf(
		Call("java/lang/System", "exit", "(I)V", Opcodes.INVOKESTATIC) to Target(exits, "exit", "(I)V"),
		Call("java/lang/Runtime", "exit", "(I)V", Opcodes.INVOKEVIRTUAL) to
			Target(exits, "exit", "(Ljava/lang/Runtime;I)V"),
		Call("java/lang/Runtime", "halt", "(I)V", Opcodes.INVOKEVIRTUAL) to
			Target(exits, "halt", "(Ljava/lang/Runtime;I)V"),
		Call("kotlin/system/ProcessKt", "exitProcess", "(I)Ljava/lang/Void;", Opcodes.INVOKESTATIC) to
			Target(exits, "exitProcess", "(I)Ljava/lang/Void;"),
		Call("android/os/Process", "killProcess", "(I)V", Opcodes.INVOKESTATIC) to Target(exits, "killProcess", "(I)V"),
		Call("android/app/ActivityManager", "getMemoryInfo", "(L$memoryInfo;)V", Opcodes.INVOKEVIRTUAL) to
			Target(activityManager, "getMemoryInfo", "(Landroid/app/ActivityManager;L$memoryInfo;)V"),
	)

	/** Writes [source] to [target] with every class rewritten; other entries are copied as they are. */
	fun rewrite(source: Path, target: Path) {
		val temporary = Files.createTempFile(target.toAbsolutePath().parent ?: Path.of("."), ".rewrite-", ".tmp")
		try {
			ZipInputStream(Files.newInputStream(source)).use { input ->
				ZipOutputStream(Files.newOutputStream(temporary)).use { output ->
					while (true) {
						val entry = input.nextEntry ?: break
						val bytes = input.readBytes()
						output.putNextEntry(ZipEntry(entry.name).apply { time = 0 })
						output.write(if (entry.name.endsWith(".class")) rewriteClass(bytes) else bytes)
						output.closeEntry()
					}
				}
			}
			Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
		} finally {
			Files.deleteIfExists(temporary)
		}
	}

	/** The class rewritten; the same bytes when nothing in it is. */
	fun rewriteClass(bytes: ByteArray): ByteArray {
		val reader = ClassReader(bytes)
		// Replacements take and leave the stack the calls did, and remapped types stay consistent in the frames, so
		// frames and maxima stand.
		val writer = ClassWriter(0)
		var changed = false
		val calls = object : ClassVisitor(Opcodes.ASM9, writer) {
			override fun visitMethod(
				access: Int,
				name: String?,
				descriptor: String?,
				signature: String?,
				exceptions: Array<out String>?,
			): MethodVisitor = object : MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, descriptor, signature, exceptions)) {
				override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) {
					val target = this@PluginBytecodeRewriter.calls[Call(owner, name, descriptor, opcode)]
					if (target == null) {
						super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
					} else {
						changed = true
						super.visitMethodInsn(Opcodes.INVOKESTATIC, target.owner, target.name, target.descriptor, false)
					}
				}
			}
		}
		val remapper = object : SimpleRemapper(types) {
			override fun map(key: String): String? = super.map(key)?.also { changed = true }
		}
		reader.accept(ClassRemapper(calls, remapper), 0)
		return if (changed) writer.toByteArray() else bytes
	}

	private fun internalName(type: Class<*>) = type.name.replace('.', '/')

	private data class Call(val owner: String, val name: String, val descriptor: String, val opcode: Int)

	private data class Target(val owner: String, val name: String, val descriptor: String)
}
